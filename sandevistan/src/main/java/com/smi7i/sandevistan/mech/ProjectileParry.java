package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.SandevistanManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * Отбивание снарядов (стрелы, снежки, яйца, файерболы, трезубцы…) в костюме или с работающим Сандевистаном:
 * ЛКМ — отбить туда, куда смотришь; ПКМ — отразить строго назад, в того, кто стрелял (или ровно обратно по пути).
 * Снаряд становится «твоим» — бьёт того, в кого летит.
 */
public final class ProjectileParry {
    /** Как далеко от глаз можно достать снаряд (сервер проверяет с запасом на задержку сети). */
    public static final double REACH = 8.0;
    /** Отбитый ЛКМ летит быстрее, отражённый ПКМ — тоже чуть быстрее, чем прилетел. */
    public static final double BAT_SPEED = 1.6, REFLECT_SPEED = 1.15;

    private ProjectileParry() {}

    public static boolean canParry(ServerPlayer p) {
        return p.isAlive() && (ModAttachments.hasMech(p) || SandevistanManager.isDamageReduced(p));
    }

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ParryPayload.TYPE, ParryPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ParryPayload.TYPE, (payload, context) ->
                parry(context.player(), payload.entityId(), payload.reflect()));
    }

    private static void parry(ServerPlayer player, int id, boolean reflect) {
        if (!canParry(player)) return;
        ServerLevel level = player.level();
        if (!(level.getEntity(id) instanceof Projectile proj) || !proj.isAlive()) return;
        Vec3 eye = player.getEyePosition();
        Vec3 pos = proj.position();
        if (pos.distanceTo(eye) > REACH + 4.0) return;
        Vec3 v = proj.getDeltaMovement();
        double speed = v.length();
        if (speed < 0.05) return; // воткнулась/лежит
        Entity shooter = proj.getOwner();

        Vec3 out;
        if (reflect) {
            double s = Math.max(speed, 0.6) * REFLECT_SPEED;
            if (shooter != null && shooter != player && shooter.isAlive() && shooter.level() == level
                    && shooter.position().distanceTo(pos) < 96) {
                // Точно в стрелка: в середину его тела, с поправкой на падение снаряда по дуге.
                Vec3 target = shooter.getBoundingBox().getCenter();
                Vec3 to = target.subtract(pos);
                double ticks = to.length() / s;
                double g = proj.getGravity();
                out = to.normalize().scale(s).add(0, 0.5 * g * ticks, 0);
            } else {
                out = v.normalize().scale(-s); // ровно обратно по пути
            }
        } else {
            // Отбить туда, куда смотришь (чуть вверх, как отбитый мяч).
            out = player.getLookAngle().scale(Math.max(speed, 1.0) * BAT_SPEED).add(0, 0.08, 0);
        }

        proj.setOwner(player);
        proj.setDeltaMovement(out);
        double h = Math.sqrt(out.x * out.x + out.z * out.z);
        proj.setYRot((float) (Math.atan2(out.x, out.z) * 180.0 / Math.PI));
        proj.setXRot((float) (Math.atan2(out.y, h) * 180.0 / Math.PI));
        proj.yRotO = proj.getYRot();
        proj.xRotO = proj.getXRot();
        if (proj instanceof AbstractArrow arrow) arrow.setCritArrow(true);
        proj.hurtMarked = true;
        proj.needsSync = true;

        player.swing(reflect ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        MechManager.sound(level, pos, com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 1.1F, reflect ? 1.5F : 1.25F);
        MechManager.sound(level, pos, net.minecraft.sounds.SoundEvents.ANVIL_LAND, 0.35F, 1.9F);
        level.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 14, 0.15, 0.15, 0.15, 0.4);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, 10, 0.1, 0.1, 0.1, 0.3);
    }
}
