package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Способность костюма «Магнит» (по видео пользователя): костюм выставляет обе руки вперёд, красная аура притягивает
 * всех существ и предметы в конусе перед ним в шар у рук (куски земли тоже — их копии рисует клиент), потом мощный
 * выброс: всех в конусе — урон и отброс далеко вперёд. Тайминг — под звук из видео (удар через 2.75 с).
 */
public final class MechPush {
    // ===== Настройки (тики) =====
    public static final int RAISE = 9, BLAST = 55, END = 90, COOLDOWN = 20 * 20;
    /** Дальность конуса, его половинный угол (косинус) и где шар: впереди и на высоте рук. */
    public static final double RANGE = 18, CONE_COS = Math.cos(Math.toRadians(62)), BALL_FORWARD = 4.2, BALL_UP = 2.5;

    private static final Map<UUID, Integer> CD = new HashMap<>();
    private static final Random RNG = new Random();

    private MechPush() {}

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(PushPayload.TYPE, PushPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PushPayload.TYPE, (payload, context) -> start(context.player()));
    }

    public static long startTime(net.minecraft.world.entity.player.Player p) {
        return p.getAttachedOrElse(ModAttachments.MECH_PUSH_START, 0L);
    }

    public static boolean isActive(net.minecraft.world.entity.player.Player p) {
        return startTime(p) > 0;
    }

    /** Куда смотрит костюм (по горизонтали). */
    public static Vec3 forward(net.minecraft.world.entity.player.Player p) {
        return Vec3.directionFromRotation(0, p.getYRot());
    }

    /** Точка шара перед руками. */
    public static Vec3 ball(net.minecraft.world.entity.player.Player p) {
        return p.position().add(forward(p).scale(BALL_FORWARD)).add(0, BALL_UP, 0);
    }

    static void start(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.isAlive() || isActive(player) || MechManager.isAttacking(player)
                || MechManager.isEquipping(player) || MechVortex.isActive(player) || MechMeteor.isActive(player)
                || MechSingularity.isActive(player) || MechShowdown.isActive(player) || CD.containsKey(player.getUUID())) {
            MechManager.sync(player);
            return;
        }
        player.setAttached(ModAttachments.MECH_PUSH_START, player.level().getGameTime());
        ServerLevel level = player.level();
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_PUSH, 3.0F, 1.0F);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_THRUSTER, 1.6F, 0.7F);
    }

    static void tick(ServerPlayer player) {
        Integer cd = CD.get(player.getUUID());
        if (cd != null) {
            if (cd <= 1) CD.remove(player.getUUID()); else CD.put(player.getUUID(), cd - 1);
            if (cd <= 1 || cd % 20 == 0) MechManager.sync(player);
        }
        long s = startTime(player);
        if (s <= 0) return;
        ServerLevel level = player.level();
        long age = level.getGameTime() - s;
        if (!ModAttachments.hasMech(player) || !player.isAlive() || age >= END) {
            player.setAttached(ModAttachments.MECH_PUSH_START, 0L);
            if (ModAttachments.hasMech(player)) CD.put(player.getUUID(), COOLDOWN);
            MechManager.sync(player);
            return;
        }
        Vec3 b = ball(player);
        if (age >= RAISE && age < BLAST) {
            // Притяжение: всех в конусе тянет к шару, у шара — держит (кружит).
            for (Entity e : inCone(player, level)) {
                Vec3 to = b.subtract(e.getBoundingBox().getCenter());
                double d = to.length();
                Vec3 vel = d > 1.6 ? to.normalize().scale(Math.min(0.9, 0.12 + d * 0.06)) : to.scale(0.3).add(0, 0.02, 0);
                e.setDeltaMovement(e.getDeltaMovement().scale(0.4).add(vel.scale(0.6)));
                e.hurtMarked = true;
                e.fallDistance = 0;
            }
            // Куски земли перед костюмом — копии летят в шар (блоки остаются на месте).
            if ((age - RAISE) % 5 == 0 && age < BLAST - 8) {
                List<MechManager.Broken> lifted = new ArrayList<>();
                Vec3 f = forward(player);
                for (int i = 0; i < 20 && lifted.size() < 5; i++) {
                    double along = 3 + RNG.nextDouble() * (RANGE - 4), across = (RNG.nextDouble() * 2 - 1) * along * 0.75;
                    Vec3 at = player.position().add(f.scale(along)).add(-f.z * across, 0, f.x * across);
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(at.x), (int) Math.floor(at.z)) - 1;
                    BlockPos pos = BlockPos.containing(at.x, top, at.z);
                    var st = level.getBlockState(pos);
                    if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                    lifted.add(new MechManager.Broken(pos, st));
                }
                if (!lifted.isEmpty()) MechManager.fx(level, MechFxPayload.PULL, b, player.getYRot(), player.getId(), lifted);
            }
        }
        if (age == BLAST) blast(player, level, b);
    }

    /** Мощный выброс: урон и отброс всех в конусе далеко вперёд. */
    private static void blast(ServerPlayer player, ServerLevel level, Vec3 b) {
        MechManager.sound(level, b, com.smi7i.sandevistan.ModSounds.BOOM_ORBITAL, 3.5F, 1.0F);
        MechManager.sound(level, b, com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 3.0F, 0.8F);
        MechManager.sound(level, b, com.smi7i.sandevistan.ModSounds.BOOM_SONIC, 2.6F, 1.0F);
        Vec3 f = forward(player);
        // Выброс вспахивает землю веером вперёд.
        GroundScar.strip(level, player.position(), new Vec3(f.x, 0, f.z).normalize(), 2.5, RANGE * 0.8, 2.5, RANGE * 0.9, 0.85);
        for (Entity e : inCone(player, level)) {
            if (e instanceof LivingEntity le) le.hurtServer(level, player.damageSources().playerAttack(player), MechManager.quarter(le) * 1.6F);
            Vec3 side = e.position().subtract(player.position());
            side = new Vec3(side.x, 0, side.z).normalize();
            e.setDeltaMovement(f.scale(2.6).add(side.scale(0.6)).add(0, 0.7 + RNG.nextDouble() * 0.3, 0));
            e.hurtMarked = true;
        }
    }

    /** Все существа и предметы в конусе перед костюмом (кроме него самого и творческих игроков). */
    private static List<Entity> inCone(ServerPlayer player, ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        Vec3 f = forward(player), c = player.position();
        for (Entity e : level.getEntities((Entity) null, new AABB(c, c).inflate(RANGE + 2, 6, RANGE + 2),
                e -> e != player && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand))) {
            if (e instanceof ServerPlayer sp && sp.isCreative()) continue;
            Vec3 to = e.position().subtract(c);
            Vec3 flat = new Vec3(to.x, 0, to.z);
            double d = flat.length();
            if (d > RANGE || d < 0.5 || flat.normalize().dot(f) < CONE_COS) continue;
            out.add(e);
        }
        return out;
    }

    public static int cooldown(ServerPlayer player) {
        return CD.getOrDefault(player.getUUID(), 0);
    }
}
