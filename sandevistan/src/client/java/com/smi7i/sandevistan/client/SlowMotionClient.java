package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.TimeDilation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Плавное слоумо в зоне Сандевистана. Сущности в зоне делают один тик раз в 20 (см. ClientZones),
 * а их движение за этот тик растягивается на всё время до следующего шага.
 * Время меряется по настоящим часам, длина промежутка — средняя по последним шагам (без рывков).
 */
public final class SlowMotionClient {
    /** Во сколько раз ниже звучат звуки мира в слоумо (движок не опускает ниже 0.5 от обычного). */
    public static final float WORLD_SOUND_PITCH = 0.5F;
    /** Насколько быстро подстраиваться под настоящий ритм шагов (0..1): меньше — ровнее. */
    private static final double INTERVAL_SMOOTHING = 0.25;

    private static long lastStepNanos = System.nanoTime();
    private static double intervalNanos = TimeDilation.SLOWDOWN * 50_000_000.0;

    private SlowMotionClient() {}

    /** ClientZones сообщает о шаге мира в зонах. */
    static void onStep() {
        long now = System.nanoTime();
        long measured = now - lastStepNanos;
        if (measured > 200_000_000L && measured < 3_000_000_000L) {
            intervalNanos += (measured - intervalNanos) * INTERVAL_SMOOTHING;
        }
        lastStepNanos = now;
    }

    /** Плавная доля между шагами (0..1), или null, если зон нет. */
    public static Float worldPartial() {
        if (!ClientZones.anyZone()) return null;
        double elapsed = System.nanoTime() - lastStepNanos;
        return (float) Mth.clamp(elapsed / intervalNanos, 0.0, 1.0);
    }

    /** Идёт ли слоумо вокруг самого игрока (он в зоне) — для звуков и частиц. */
    public static boolean isSlowMotion() {
        return ClientZones.localInZone();
    }

    /** Доля для отрисовки сущности: в зоне — плавное слоумо, остальное (и игроки) — как обычно. */
    public static float partialFor(Entity entity, float vanillaPartial) {
        if (entity instanceof Player || !ClientZones.inZone(entity)) return vanillaPartial;
        Float slow = worldPartial();
        return slow != null ? slow : vanillaPartial;
    }

    public static void init() {
        // Состояние ведёт ClientZones; оставлено для совместимости вызова из SandevistanInputClient.
    }
}
