package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.smi7i.sandevistan.ModSounds;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * Киберпсихоз: перед глазами идёт видео, звуки игры глушатся до 5%, на фоне играет спокойная музыка.
 * Всё начинается с приступа и заканчивается вместе с ним (музыка и видео по 30 секунд, как и сам приступ).
 *
 * Видео лежит в ресурсах как набор JPEG-кадров (assets/sandevistan/psychosis/video.bin): игра не умеет
 * проигрывать видео, поэтому кадры по очереди кладутся в одну динамическую текстуру.
 */
public final class PsychosisMedia {
    // ===== Настройки =====
    /** Громкость остальных звуков игры во время приступа (0.05 = 5%). */
    private static final float WORLD_VOLUME = 0.05F;
    /** Насколько видео непрозрачно (1 = полностью закрывает мир). */
    private static final float VIDEO_ALPHA = 0.85F;
    /** За сколько тиков видео и приглушение звуков плавно включаются и выключаются. */
    private static final int FADE_TICKS = 10;
    private static final float MUSIC_VOLUME = 0.9F;

    private static final Identifier VIDEO_DATA = Identifier.fromNamespaceAndPath("sandevistan", "psychosis/video.bin");
    private static final Identifier VIDEO_TEXTURE = Identifier.fromNamespaceAndPath("sandevistan", "psychosis_video");

    private static byte[][] frames; // JPEG-кадры; пустой массив — видео не нашлось
    private static int fps = 15, vw, vh;
    private static DynamicTexture texture;
    private static int shownFrame = -1;

    private static boolean prevPsychosis;
    private static int videoTick;
    private static int fade;
    private static MusicSound music;

    private PsychosisMedia() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PsychosisMedia::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "psychosis_video"), PsychosisMedia::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            prevPsychosis = false;
            fade = 0;
            music = null;
        });
    }

    /** Множитель громкости обычных звуков игры: 1 в обычное время, WORLD_VOLUME во время приступа (плавно). */
    public static float soundFactor() {
        return 1.0F - (1.0F - WORLD_VOLUME) * fade / FADE_TICKS;
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) {
            prevPsychosis = false;
            fade = 0;
            return;
        }
        boolean psychosis = ClientSandevistanState.psychosis();
        if (psychosis && !prevPsychosis) {
            videoTick = 0;
            shownFrame = -1;
            ensureLoaded(mc);
            music = new MusicSound();
            mc.getSoundManager().play(music);
        }
        prevPsychosis = psychosis;

        if (psychosis) {
            fade = Math.min(FADE_TICKS, fade + 1);
            videoTick++;
            showFrame(Math.min(frames == null ? 0 : frames.length - 1, videoTick * fps / 20));
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

    private static void showFrame(int index) {
        if (frames == null || frames.length == 0 || texture == null || index == shownFrame || index < 0) return;
        shownFrame = index;
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frames[index]));
            if (decoded == null || decoded.getWidth() != vw || decoded.getHeight() != vh) return;
            int[] argb = decoded.getRGB(0, 0, vw, vh, null, 0, vw);
            NativeImage pixels = texture.getPixels();
            if (pixels == null) return;
            for (int y = 0; y < vh; y++) {
                for (int x = 0; x < vw; x++) {
                    int c = argb[y * vw + x];
                    // NativeImage хранит ABGR.
                    pixels.setPixel(x, y, 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF));
                }
            }
            texture.upload();
        } catch (IOException ignored) {
            // битый кадр пропускаем
        }
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (fade <= 0 || texture == null || frames == null || frames.length == 0 || shownFrame < 0) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int alpha = Math.round(255 * VIDEO_ALPHA * fade / FADE_TICKS);
        if (alpha <= 2) return;
        float cover = Math.max(w / (float) vw, h / (float) vh); // заполнить экран целиком
        int dw = Math.round(vw * cover), dh = Math.round(vh * cover);
        graphics.blit(RenderPipelines.GUI_TEXTURED, VIDEO_TEXTURE, (w - dw) / 2, (h - dh) / 2, 0, 0, dw, dh,
                vw, vh, vw, vh, (alpha << 24) | 0xFFFFFF);
    }

    // ---------------------------------------------------------------- музыка

    /** Спокойная музыка «в голове»: играет, пока идёт приступ, потом плавно стихает. */
    private static final class MusicSound extends AbstractTickableSoundInstance {
        private boolean fadingOut;

        MusicSound() {
            super(ModSounds.PSYCHOSIS_CALM, SoundSource.MASTER, RandomSource.create());
            this.looping = false;
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
