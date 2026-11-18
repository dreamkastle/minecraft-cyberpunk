package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.ModSounds;
import java.util.Random;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Где играют звуки (все — «в голове» игрока, без затухания по расстоянию, слышит только он сам):
 *  - зажатие V — START (звук входа) начинается сразу; отпустили раньше — замедляется и стихает,
 *    дотерпели — продолжается; при включении начинается гул HUM (по кругу, пока работает; плавно стихает);
 *  - Сандевистан выключился (сам, вручную клавишей, конец приступа) — EXIT;
 *  - перегрев — EXIT, сразу за ним PSYCHOSIS_START («заглючил процессор»), пока длится перегрев —
 *    PSYCHOSIS_DISTORTION («искажение кадра») через случайные промежутки;
 *  - начало киберпсихоза — PSYCHOSIS_START, во время приступа гул + PSYCHOSIS_DISTORTION через случайные промежутки.
 */
public final class SandevistanSounds {
    // ===== Громкость (0..1) =====
    private static final float START_VOLUME = 1.0F;
    private static final float EXIT_VOLUME = 1.0F;
    private static final float HUM_VOLUME = 0.7F;
    private static final float PSYCHOSIS_START_VOLUME = 1.0F;
    private static final float DISTORTION_VOLUME = 0.8F;
    /** За сколько тиков гул нарастает в начале и стихает в конце. */
    private static final int HUM_FADE_TICKS = 15;
    /** Пауза между «искажениями кадра» во время приступа, в тиках: от и до (звук длится ~6 секунд). */
    private static final int DISTORTION_MIN_GAP = 130;
    private static final int DISTORTION_MAX_GAP = 220;

    private static final Random R = new Random();
    private static boolean prevActive, prevPsychosis;
    private static ImplantSound hum;
    private static ChargeSound charge;
    private static int distortionTimer;
    private static boolean prevOverheating;
    private static int glitchDelay;
    /** Через сколько тиков после звука выключения при перегреве звучит «заглючил процессор». */
    private static final int OVERHEAT_GLITCH_DELAY = 4;

    /** Если V отпустили до активации: насколько за тик падает высота (скорость) звука и за сколько тиков он стихает. */
    private static final float WIND_DOWN_PITCH_STEP = 0.05F;
    private static final int WIND_DOWN_TICKS = 14;
    /** Ниже этой высоты звук не опускается (так устроен звуковой движок игры). */
    private static final float MIN_PITCH = 0.5F;

    private SandevistanSounds() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SandevistanSounds::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            prevActive = prevPsychosis = false;
            hum = null;
            charge = null;
        });
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        boolean active = ClientSandevistanState.active;
        boolean psychosis = ClientSandevistanState.psychosis();

        // Звук входа начинается с зажатия V (если отпустить раньше — «тянется вниз» и стихает, см. ChargeSound).
        boolean charging = ClientSandevistanState.holdTicks > 0 && !ClientSandevistanState.recharging();
        // Важно: звук, доигравший до конца сам, не помечается «остановленным» — проверяем, звучит ли он сейчас.
        boolean chargePlaying = charge != null && mc.getSoundManager().isActive(charge) && !charge.windingDown();
        if (charging && !active && !chargePlaying) {
            charge = new ChargeSound();
            mc.getSoundManager().play(charge);
        }
        if (active && !prevActive && !chargePlaying) {
            play(mc, ModSounds.START, START_VOLUME); // включился без зажатия — играем вход как обычно
        }
        if (!active && prevActive && !psychosis) {
            play(mc, ModSounds.EXIT, EXIT_VOLUME); // выключился: сам (кончился заряд) или вручную клавишей — звук выхода
        }
        if (psychosis && !prevPsychosis) {
            play(mc, ModSounds.PSYCHOSIS_START, PSYCHOSIS_START_VOLUME);
            distortionTimer = 60 + R.nextInt(60);
        }
        if (!psychosis && prevPsychosis) {
            play(mc, ModSounds.EXIT, EXIT_VOLUME); // приступ закончился — Сандевистан отключился сам
        }
        // Перегрев: сразу после звука выключения — «заглючил процессор», дальше «искажения кадра».
        boolean overheating = ClientSandevistanState.overheatTicks > 0;
        if (overheating && !prevOverheating && !psychosis) {
            glitchDelay = OVERHEAT_GLITCH_DELAY;
            distortionTimer = 50 + R.nextInt(50);
        }
        if (glitchDelay > 0 && --glitchDelay == 0) {
            play(mc, ModSounds.PSYCHOSIS_START, PSYCHOSIS_START_VOLUME);
        }
        if ((psychosis || overheating) && --distortionTimer <= 0) {
            play(mc, ModSounds.PSYCHOSIS_DISTORTION, DISTORTION_VOLUME);
            distortionTimer = DISTORTION_MIN_GAP + R.nextInt(DISTORTION_MAX_GAP - DISTORTION_MIN_GAP + 1);
        }
        prevOverheating = overheating;

        // Гул: пока работает Сандевистан или идёт приступ.
        boolean wantHum = active || psychosis;
        if (wantHum && (hum == null || !mc.getSoundManager().isActive(hum) || hum.fadingOut())) {
            hum = new ImplantSound(ModSounds.HUM, HUM_VOLUME, true,
                    () -> ClientSandevistanState.active || ClientSandevistanState.psychosis());
            mc.getSoundManager().play(hum);
        }

        prevActive = active;
        prevPsychosis = psychosis;

        tickOtherPlayers(mc);
    }

    // ===== Звуки чужого Сандевистана (слышны рядом с игроком, затихают с расстоянием) =====
    private static final java.util.Set<Integer> REMOTE_ON = new java.util.HashSet<>();

    private static void tickOtherPlayers(Minecraft mc) {
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
            if (p == mc.player || !ClientZones.isActive(p.getId())) continue;
            now.add(p.getId());
            if (!REMOTE_ON.contains(p.getId())) {
                mc.getSoundManager().play(new RemoteSound(ModSounds.START, p, START_VOLUME, false));
                mc.getSoundManager().play(new RemoteSound(ModSounds.HUM, p, HUM_VOLUME, true));
            }
        }
        for (int id : REMOTE_ON) {
            if (!now.contains(id) && mc.level.getEntity(id) instanceof net.minecraft.world.entity.player.Player p) {
                mc.getSoundManager().play(new RemoteSound(ModSounds.EXIT, p, EXIT_VOLUME, false));
            }
        }
        REMOTE_ON.clear();
        REMOTE_ON.addAll(now);
    }

    /** Звук, привязанный к другому игроку: идёт за ним; постоянный — пока у него работает Сандевистан. */
    private static final class RemoteSound extends AbstractTickableSoundInstance {
        private final net.minecraft.world.entity.Entity target;
        private final float targetVolume;
        private boolean fading;

        RemoteSound(SoundEvent event, net.minecraft.world.entity.Entity target, float volume, boolean loop) {
            super(event, SoundSource.PLAYERS, RandomSource.create());
            this.target = target;
            this.targetVolume = volume;
            this.looping = loop;
            this.delay = 0;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.volume = loop ? 0.01F : volume;
            this.x = target.getX();
            this.y = target.getY();
            this.z = target.getZ();
        }

        @Override
        public void tick() {
            if (target.isRemoved()) {
                stop();
                return;
            }
            x = target.getX();
            y = target.getY();
            z = target.getZ();
            if (!looping) return;
            float step = targetVolume / HUM_FADE_TICKS;
            if (!fading && ClientZones.isActive(target.getId())) {
                volume = Math.min(targetVolume, volume + step);
            } else {
                fading = true;
                volume -= step;
                if (volume <= 0.01F) stop();
            }
        }
    }

    /** Звук «в голове» (не зависит от того, где стоишь). */
    public static void play(Minecraft mc, SoundEvent event, float volume) {
        mc.getSoundManager().play(new ImplantSound(event, volume, false, null));
    }

    /**
     * Звук входа, который начинается с зажатия V. Дотерпели до активации — играет дальше до конца.
     * Отпустили раньше — замедляется (ниже и медленнее), стихает и обрывается.
     */
    private static final class ChargeSound extends AbstractTickableSoundInstance {
        private boolean committed;
        private boolean windingDown;

        ChargeSound() {
            super(ModSounds.START, SoundSource.PLAYERS, RandomSource.create());
            this.looping = false;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.volume = START_VOLUME;
            this.pitch = 1.0F;
        }

        boolean windingDown() {
            return windingDown;
        }

        @Override
        public void tick() {
            if (committed) return;
            if (ClientSandevistanState.active) {
                committed = true; // активировался — звук продолжается как есть
                return;
            }
            if (!windingDown && ClientSandevistanState.holdTicks > 0) return; // ещё держат V
            windingDown = true;
            pitch = Math.max(MIN_PITCH, pitch - WIND_DOWN_PITCH_STEP);
            volume -= START_VOLUME / WIND_DOWN_TICKS;
            if (volume <= 0.01F) stop();
        }
    }

    /** Звук «в голове»: без положения в мире. Если задано keepPlaying — играет, пока условие верно, потом плавно стихает. */
    private static final class ImplantSound extends AbstractTickableSoundInstance {
        private final float targetVolume;
        private final BooleanSupplier keepPlaying;
        private boolean fadingOut;

        ImplantSound(SoundEvent event, float volume, boolean loop, BooleanSupplier keepPlaying) {
            super(event, SoundSource.PLAYERS, RandomSource.create());
            this.targetVolume = volume;
            this.keepPlaying = keepPlaying;
            this.looping = loop;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.volume = keepPlaying != null ? 0.01F : volume; // постоянные звуки плавно нарастают
        }

        boolean fadingOut() {
            return fadingOut;
        }

        @Override
        public void tick() {
            if (keepPlaying == null) return;
            float step = targetVolume / HUM_FADE_TICKS;
            if (keepPlaying.getAsBoolean() && !fadingOut) {
                volume = Math.min(targetVolume, volume + step);
            } else {
                fadingOut = true;
                volume -= step;
                if (volume <= 0.01F) stop();
            }
        }
    }
}
