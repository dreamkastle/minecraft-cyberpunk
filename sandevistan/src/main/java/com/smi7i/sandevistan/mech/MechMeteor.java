package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Способность костюма «метеор» (по видео пользователя «новый скилл»): зажал клавишу — костюм приседает, прыгает
 * и исчезает (очень быстрый); пока клавиша зажата — летишь невидимым на высоте HOVER блоков над землёй
 * (над любым блоком, высота повторяет рельеф), камера свободная. Отпустил (или кончилось время) — костюм
 * появляется в воздухе, под ним красная зона радиусом RADIUS, через AIM тиков резкий удар об землю:
 * урон всем в зоне, ударная волна и синие молнии. Двигает пилота клиент (MechClient.meteorTick),
 * эффекты рисует MechFx по синхронизированным MECH_METEOR_START / MECH_METEOR_DROP.
 */
public final class MechMeteor {
    // ===== Настройки (тики, 20 = 1 с) =====
    /** Высота полёта над землёй и радиус зоны удара (блоки). */
    public static final double HOVER = 5.0, RADIUS = 10.0;
    /** Присед и прыжок до исчезновения; самое долгое время полёта (потом падает сам). */
    public static final int LAUNCH = 14, MAX_FLY = 240;
    /** После появления: зона висит AIM тиков, потом удар; в END (от появления) способность закончена. */
    public static final int AIM = 22, END = 50;
    public static final int COOLDOWN = 18 * 20;

    private static final Map<UUID, Integer> CD = new HashMap<>();

    private MechMeteor() {}

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(MeteorPayload.TYPE, MeteorPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MeteorPayload.TYPE, (payload, context) -> {
            if (payload.action() == 0) start(context.player());
            else drop(context.player());
        });
    }

    public static long startTime(net.minecraft.world.entity.player.Player p) {
        return p.getAttachedOrElse(ModAttachments.MECH_METEOR_START, 0L);
    }

    public static long dropTime(net.minecraft.world.entity.player.Player p) {
        return p.getAttachedOrElse(ModAttachments.MECH_METEOR_DROP, 0L);
    }

    public static boolean isActive(net.minecraft.world.entity.player.Player p) {
        return startTime(p) > 0;
    }

    /** Невидим и летит (между прыжком и появлением). */
    public static boolean isHidden(net.minecraft.world.entity.player.Player p) {
        long s = startTime(p);
        return s > 0 && dropTime(p) <= 0 && p.level().getGameTime() - s >= LAUNCH;
    }

    static void start(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.isAlive() || isActive(player) || MechManager.isAttacking(player)
                || MechManager.isEquipping(player) || MechVortex.isActive(player) || MechShowdown.isActive(player) || MechSingularity.isActive(player) || MechPush.isActive(player)
                || CD.containsKey(player.getUUID())) {
            MechManager.sync(player);
            return;
        }
        player.setAttached(ModAttachments.MECH_METEOR_START, player.level().getGameTime());
        player.setAttached(ModAttachments.MECH_METEOR_DROP, 0L);
        MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_METEOR_LAUNCH, 2.5F, 1.0F);
        MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.BOOM_TAKEOFF, 2.4F, 1.0F);
    }

    /** Клавишу отпустили: если ещё в прыжке — дождёмся исчезновения (tick сам вызовет), иначе появляемся. */
    static void drop(ServerPlayer player) {
        long s = startTime(player);
        if (s <= 0 || dropTime(player) > 0) return;
        long age = player.level().getGameTime() - s;
        if (age < LAUNCH) {
            RELEASED.put(player.getUUID(), true);
            return;
        }
        appear(player);
    }

    private static final Map<UUID, Boolean> RELEASED = new HashMap<>();

    private static void appear(ServerPlayer player) {
        RELEASED.remove(player.getUUID());
        player.setAttached(ModAttachments.MECH_METEOR_DROP, player.level().getGameTime());
        player.setInvisible(false);
        MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_METEOR_SLAM, 3.5F, 1.0F);
        MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.BOOM_MOTION, 2.4F, 1.0F);
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
        long now = level.getGameTime();
        long age = now - s;
        long d = dropTime(player);
        player.fallDistance = 0;
        if (!ModAttachments.hasMech(player) || !player.isAlive()) {
            finish(player, false);
            return;
        }
        // Чтобы игра не выкинула «за полёт», пока костюм в воздухе.
        if (!player.getAbilities().mayfly) {
            player.getAbilities().mayfly = true;
            player.onUpdateAbilities();
        }
        if (d <= 0) {
            if (age == LAUNCH) {
                // Исчезновение: свист и порыв ветра.
                MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_WHOOSH, 2.4F, 1.1F);
                MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_WIND, 1.6F, 1.0F);
                MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_SONIC, 2.2F, 1.0F);
                player.setInvisible(true);
                if (RELEASED.containsKey(player.getUUID())) appear(player);
            } else if (age > LAUNCH) {
                player.setInvisible(true);
                if (age >= LAUNCH + MAX_FLY) appear(player);
            }
            return;
        }
        long since = now - d;
        if (since == AIM) slam(player);
        if (since >= END) finish(player, true);
    }

    private static void slam(ServerPlayer player) {
        ServerLevel level = player.level();
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 3.4F, 0.75F);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 3.0F, 0.7F);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_LAND, 3.4F, 0.8F);
        double gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
        Vec3 c = new Vec3(player.getX(), gy, player.getZ());
        GroundScar.circle(level, c, 6.5, 1.4);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RADIUS, 6, RADIUS),
                e -> e != player && e.isAlive() && !(e instanceof ArmorStand) && !e.isSpectator())) {
            double dx = e.getX() - c.x, dz = e.getZ() - c.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r > RADIUS + e.getBbWidth() / 2) continue;
            if (e instanceof ServerPlayer sp && sp.isCreative()) continue;
            float k = (float) (1.0 - 0.5 * r / RADIUS);
            e.hurtServer(level, player.damageSources().playerAttack(player), MechManager.quarter(e) * 1.4F);
            Vec3 out = r > 0.01 ? new Vec3(dx / r, 0, dz / r) : Vec3.ZERO;
            e.push(out.x * 1.3 * k, 0.8 * k, out.z * 1.3 * k);
            e.hurtMarked = true;
        }
    }

    private static void finish(ServerPlayer player, boolean cooldown) {
        player.setAttached(ModAttachments.MECH_METEOR_START, 0L);
        player.setAttached(ModAttachments.MECH_METEOR_DROP, 0L);
        player.setInvisible(false);
        RELEASED.remove(player.getUUID());
        if (!player.isCreative() && !player.isSpectator() && player.getAbilities().mayfly) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        if (cooldown && ModAttachments.hasMech(player)) CD.put(player.getUUID(), COOLDOWN);
        MechManager.sync(player);
    }

    public static int cooldown(ServerPlayer player) {
        return CD.getOrDefault(player.getUUID(), 0);
    }
}
