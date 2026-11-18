package com.smi7i.sandevistan.client;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Эффекты ударов, пока работает Сандевистан: искры и вспышка разлетаются от точки удара и, раз мир в слоумо,
 * медленно расходятся и висят в воздухе; дуга взмаха.
 */
public final class SlowMotionHitEffects {
    // ===== Настройки =====
    /** Сколько искр за удар. */
    private static final int SPARKS = 6;
    /** Скорость разлёта искр (в слоумо они всё равно летят медленно). */
    private static final double SPARK_SPEED = 0.45;

    private static final Random R = new Random();

    private SlowMotionHitEffects() {}

    public static void onAttack(Player player, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || player != mc.player) return;
        if (!ClientSandevistanState.active && !ClientSandevistanState.psychosis()) return;

        AABB box = target.getBoundingBox();
        // Точка удара: на поверхности цели со стороны игрока, на уровне груди.
        Vec3 center = box.getCenter();
        Vec3 toPlayer = player.getEyePosition().subtract(center).normalize();
        Vec3 hit = center.add(toPlayer.scale(Math.min(box.getXsize(), box.getZsize()) * 0.5))
                .add(0, box.getYsize() * 0.15, 0);
        Vec3 away = toPlayer.scale(-1);

        // Пиксельная звезда, трещины-разряды и щепки (как на образцах пользователя).
        HitBurst.spawn(hit, away);
        // Оранжевые крестики — ванильные частицы крита.
        for (int i = 0; i < SPARKS; i++) {
            Vec3 dir = away.add(R.nextGaussian() * 0.7, R.nextGaussian() * 0.5 + 0.2, R.nextGaussian() * 0.7).normalize();
            double speed = SPARK_SPEED * (0.4 + R.nextDouble() * 0.6);
            mc.level.addParticle(ParticleTypes.CRIT, hit.x, hit.y, hit.z, dir.x * speed, dir.y * speed, dir.z * speed);
        }
    }
}
