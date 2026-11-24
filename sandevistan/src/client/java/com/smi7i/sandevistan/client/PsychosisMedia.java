package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.smi7i.sandevistan.ModSounds;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Киберпсихоз: перед глазами идёт видео, все звуки кроме спокойной музыки полностью заглушаются.
 * Всё начинается с приступа и заканчивается вместе с ним (музыка и видео по 30 секунд, как и сам приступ).
 *
 * Видео лежит в ресурсах как набор JPEG-кадров 1920x1080, 30 к/с (assets/sandevistan/psychosis/video.bin): игра не умеет
 * проигрывать видео, поэтому кадры по очереди кладутся в одну динамическую текстуру. Кадры декодируются
 * заранее в нескольких потоках (иначе Full HD не успевает на 30 к/с), а на экране видео растягивается на всё окно.
 */
public final class PsychosisMedia {
    // ===== Настройки =====
    /** Уровень звука для плавных переходов до/после приступа; во время приступа миксин полностью глушит остальные звуки. */
    private static final float WORLD_VOLUME = 0.03F;
    /** Насколько видео непрозрачно (1 = полностью закрывает мир). */
    private static final float VIDEO_ALPHA = 1.0F;
    /** За сколько тиков видео и приглушение звуков плавно включаются и выключаются. */
    private static final int FADE_TICKS = 10;
    private static final float MUSIC_VOLUME = 0.9F;

    private static final Identifier VIDEO_DATA = Identifier.fromNamespaceAndPath("sandevistan", "psychosis/video.bin");
    private static final Identifier VIDEO_TEXTURE = Identifier.fromNamespaceAndPath("sandevistan", "psychosis_video");

    private static byte[][] frames; // JPEG-кадры; пустой массив — видео не нашлось
    private static int fps = 15, vw, vh;
    private static DynamicTexture texture;
    private static int shownFrame = -1;
    /** Декодирование кадров идёт заранее в нескольких потоках: в основном потоке остаётся только копирование в текстуру. */
    private static final int DECODE_THREADS = 3;
    /** На сколько кадров вперёд заказывать декодирование. */
    private static final int LOOKAHEAD = 5;
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(DECODE_THREADS, r -> {
        Thread t = new Thread(r, "sandevistan-psychosis-video");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<Integer, int[]> decodedFrames = new ConcurrentHashMap<>(); // кадр -> ABGR
    private static final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    private static volatile int generation; // меняется при каждом новом приступе: устаревшие кадры отбрасываются
    private static long videoStartNanos;
    /** С какого кадра начинать: у приступа киберпсихоза с первого, у малого приступа — с середины слайдшоу. */
    private static int videoStartFrame;
    /** Был ли уже показан хоть один кадр в этом приступе (на повторе по кругу последний кадр держится на экране). */
    private static boolean frameReady;

    private static boolean prevPsychosis, prevShow;
    private static int videoTick;
    private static int fade;
    private static MusicSound music;

    private PsychosisMedia() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PsychosisMedia::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "psychosis_video"), PsychosisMedia::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            prevPsychosis = false;
            prevShow = false;
            fade = 0;
            music = null;
        });
    }

    /** Множитель громкости обычных звуков для плавного перехода при начале и окончании приступа. */
    public static float soundFactor() {
        return 1.0F - (1.0F - WORLD_VOLUME) * fade / FADE_TICKS;
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) {
            prevPsychosis = false;
            prevShow = false;
            fade = 0;
            return;
        }
        boolean psychosis = ClientSandevistanState.psychosis();
        // Видео идёт и во время приступа киберпсихоза, и во время малого приступа (без музыки).
        boolean show = psychosis || ClientSandevistanState.episode();
        if ((psychosis && !prevPsychosis) || (show && !prevShow)) {
            videoTick = 0;
            shownFrame = -1;
            frameReady = false;
            generation++;
            decodedFrames.clear();
            inFlight.clear();
            videoStartNanos = System.nanoTime();
            ensureLoaded(mc);
            // Малый приступ (5 секунд) показывает середину ролика: 2.5 с до середины и 2.5 с после.
            videoStartFrame = psychosis || frames == null ? 0 : Math.max(0, frames.length / 2 - (int) (2.5 * fps));
        }
        if (psychosis && !prevPsychosis) {
            music = new MusicSound();
            mc.getSoundManager().play(music);
        }
        prevPsychosis = psychosis;
        prevShow = show;

        if (show) {
            fade = Math.min(FADE_TICKS, fade + 1);
            videoTick++;
        } else {
            fade = Math.max(0, fade - 1);
        }
    }

    // ---------------------------------------------------------------- видео

    private static void ensureLoaded(Minecraft mc) {
        if (frames != null) return;
        frames = new byte[0][];
        try (InputStream raw = mc.getResourceManager().getResource(VIDEO_DATA).orElseThrow().open();
             DataInputStream in = new DataInputStream(raw)) {
            int count = in.readInt();
            fps = Math.max(1, in.readInt());
            vw = in.readInt();
            vh = in.readInt();
            byte[][] loaded = new byte[count][];
            for (int i = 0; i < count; i++) {
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                loaded[i] = data;
            }
            NativeImage image = new NativeImage(vw, vh, false);
            texture = new DynamicTexture(() -> "sandevistan psychosis video", image);
            mc.getTextureManager().register(VIDEO_TEXTURE, texture);
            frames = loaded;
        } catch (Exception e) {
            frames = new byte[0][]; // нет видео — остальное (звук, музыка) всё равно работает
        }
    }

    /** Номер кадра, который сейчас должен быть на экране: по реальному времени с начала приступа, чтобы шли все 30 к/с. */
    private static int targetFrame() {
        long elapsed = System.nanoTime() - videoStartNanos;
        int index = (int) (elapsed * fps / 1_000_000_000L) + videoStartFrame;
        if (index >= frames.length && ClientSandevistanState.psychosis()
                && (ClientSandevistanState.permanentPsychosis() || ClientSandevistanState.psychosisTicks > 20)) {
            // Видео кончилось, а приступ ещё идёт: начинаем заново (в конце обычного приступа не перезапускаем).
            videoStartNanos = System.nanoTime();
            videoStartFrame = 0;
            generation++;
            decodedFrames.clear();
            inFlight.clear();
            shownFrame = -1;
            index = 0;
        }
        return Math.max(0, Math.min(frames.length - 1, index));
    }

    /** Выложить на экран самый свежий готовый кадр (не новее нужного) и заказать декодирование кадров вперёд. */
    private static void showFrame(int index) {
        if (frames == null || frames.length == 0 || texture == null || index < 0) return;
        int best = -1;
        for (int k : decodedFrames.keySet()) {
            if (k <= index && k > best) best = k;
        }
        if (best > shownFrame) {
            int[] pixels = decodedFrames.get(best);
            if (pixels != null) upload(pixels, best);
        }
        decodedFrames.keySet().removeIf(k -> k <= shownFrame);
        int last = Math.min(frames.length - 1, index + LOOKAHEAD);
        for (int i = Math.max(index, shownFrame + 1); i <= last; i++) {
            if (decodedFrames.containsKey(i) || !inFlight.add(i)) continue;
            decode(i);
        }
    }

    private static void decode(int frame) {
        byte[] data = frames[frame];
        int gen = generation;
        DECODER.execute(() -> {
            try {
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
                if (decoded == null || decoded.getWidth() != vw || decoded.getHeight() != vh) return;
                int[] argb = decoded.getRGB(0, 0, vw, vh, null, 0, vw);
                for (int i = 0; i < argb.length; i++) {
                    int c = argb[i];
                    // NativeImage хранит ABGR.
                    argb[i] = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
                }
                if (gen == generation) decodedFrames.put(frame, argb);
            } catch (IOException | RuntimeException ignored) {
                // битый кадр пропускаем
            } finally {
                if (gen == generation) inFlight.remove(frame);
            }
        });
    }

    private static void upload(int[] abgr, int index) {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        for (int y = 0; y < vh; y++) {
            int row = y * vw;
            for (int x = 0; x < vw; x++) pixels.setPixel(x, y, abgr[row + x]);
        }
        texture.upload();
        shownFrame = index;
        frameReady = true;
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (fade <= 0 || texture == null || frames == null || frames.length == 0) return;
        // Кадр выбирается здесь, на каждой отрисовке (а не раз в тик): так видео идёт на полных 30 к/с.
        showFrame(targetFrame());
        if (!frameReady) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int alpha = Math.round(255 * VIDEO_ALPHA * fade / FADE_TICKS);
        if (alpha <= 2) return;
        // Кадр растягивается на весь экран (Full HD 1920x1080 — один в один, другие размеры окна — с растяжением).
        graphics.blit(RenderPipelines.GUI_TEXTURED, VIDEO_TEXTURE, 0, 0, 0.0F, 0.0F, w, h,
                vw, vh, vw, vh, (alpha << 24) | 0xFFFFFF);
    }

    // ---------------------------------------------------------------- музыка

    /** Спокойная музыка «в голове»: играет, пока идёт приступ, потом плавно стихает. */
    private static final class MusicSound extends AbstractTickableSoundInstance {
        private boolean fadingOut;

        MusicSound() {
            super(ModSounds.PSYCHOSIS_CALM, SoundSource.MASTER, RandomSource.create());
            this.looping = true; // музыка играет по кругу, пока идёт приступ; в конце приступа плавно затихает (tick)
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.volume = 0.01F;
            this.pitch = 1.0F;
        }

        @Override
        public void tick() {
            float step = MUSIC_VOLUME / FADE_TICKS;
            if (ClientSandevistanState.psychosis() && !fadingOut) {
                volume = Math.min(MUSIC_VOLUME, volume + step);
            } else {
                fadingOut = true;
                volume -= step;
                if (volume <= 0.01F) stop();
            }
        }
    }
}
