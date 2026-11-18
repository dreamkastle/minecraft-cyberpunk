package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Способность костюма «вихрь» (по видео пользователя): красная зона радиусом RADIUS, все живые в ней начинают
 * кружиться по орбите вокруг костюма, костюм взлетает и парит (клиент двигает пилота, анимация skill),
 * в SLAM все резко падают вниз и получают урон, костюм плавно спускается. Перезарядка COOLDOWN.
 * Эффекты рисует клиент (client/mech/MechFx) по синхронизированному времени начала MECH_SKILL_START.
 */
public final class MechVortex {
    // ===== Настройки (тики, 20 = 1 с) =====
    public static final double RADIUS = 9.0;
    /** Зона заряжается, с ORBIT_START все кружатся, в SLAM падают (как взрыв в звуке), в END — костюм на земле. */
    public static final int ORBIT_START = 12, SLAM = 72, END = 100;
    public static final int COOLDOWN = 20 * 20;
    public static final float DAMAGE = 16.0F;
    /** Высота орбиты над землёй и скорость кружения (радиан за тик: от медленной к быстрой). */
    private static final double ORBIT_HEIGHT = 2.2, SPIN_FROM = 0.06, SPIN_TO = 0.32;

    private record Victim(UUID id, double radius, double height, double angle0) {}

    private static final Map<UUID, List<Victim>> VICTIMS = new HashMap<>();
    private static final Map<UUID, Double> GROUND = new HashMap<>();
    static final Map<UUID, Integer> CD = new HashMap<>();

    private MechVortex() {}

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(VortexPayload.TYPE, VortexPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(VortexPayload.TYPE, (payload, context) -> start(context.player()));
    }

    public static boolean isActive(net.minecraft.world.entity.player.Player player) {
        long s = player.getAttachedOrElse(ModAttachments.MECH_SKILL_START, 0L);
        return s > 0 && player.level().getGameTime() - s < END;
    }

    static void start(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.isAlive() || isActive(player) || MechManager.isAttacking(player)
                || MechManager.isEquipping(player) || MechMeteor.isActive(player) || MechSingularity.isActive(player) || MechPush.isActive(player) || CD.containsKey(player.getUUID())) {
            MechManager.sync(player);
            return;
        }
        ServerLevel level = player.level();
        player.setAttached(ModAttachments.MECH_SKILL_START, level.getGameTime());
        GROUND.put(player.getUUID(), player.getY());
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_VORTEX, 3.0F, 1.0F);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_MOTION, 2.0F, 0.9F);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_THRUSTER, 1.6F, 0.8F);
        // Кто в зоне — тот и закрутится (каждому своя орбита: радиус, высота, начальный угол).
        List<Victim> list = new ArrayList<>();
        Vec3 c = player.position();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RADIUS, 4, RADIUS),
                e -> e != player && e.isAlive() && !(e instanceof ArmorStand) && !e.isSpectator())) {
            double dx = e.getX() - c.x, dz = e.getZ() - c.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r > RADIUS) continue;
            if (e instanceof ServerPlayer sp && sp.isCreative()) continue;
            list.add(new Victim(e.getUUID(), Math.max(2.8, Math.min(r, RADIUS - 1.5)), ORBIT_HEIGHT + Math.random() * 1.6,
                    Math.atan2(dz, dx)));
        }
        VICTIMS.put(player.getUUID(), list);
    }

    static void tick(ServerPlayer player) {
        Integer cd = CD.get(player.getUUID());
        if (cd != null) {
            if (cd <= 1) CD.remove(player.getUUID()); else CD.put(player.getUUID(), cd - 1);
            if (cd <= 1 || cd % 20 == 0) MechManager.sync(player);
        }
        long s = player.getAttachedOrElse(ModAttachments.MECH_SKILL_START, 0L);
        if (s <= 0) return;
        ServerLevel level = player.level();
        long age = level.getGameTime() - s;
        player.fallDistance = 0; // взлёт и спуск — без урона от падения
        List<Victim> list = VICTIMS.getOrDefault(player.getUUID(), List.of());
        double groundY = GROUND.getOrDefault(player.getUUID(), player.getY());
        if (age >= ORBIT_START && age < SLAM) {
            // Орбита: все кружатся вокруг костюма всё быстрее, понемногу поднимаясь.
            double k = (age - ORBIT_START) / (double) (SLAM - ORBIT_START);
            double angle = SPIN_FROM * (age - ORBIT_START) + 0.5 * (SPIN_TO - SPIN_FROM) * (age - ORBIT_START) * k;
            for (Victim v : list) {
                if (!(level.getEntity(v.id()) instanceof LivingEntity e) || !e.isAlive()) continue;
                double a = v.angle0() + angle;
                double r = v.radius() * (1.0 - 0.15 * k);
                Vec3 target = new Vec3(player.getX() + Math.cos(a) * r,
                        groundY + v.height() * Math.min(1, k * 3) + 0.4 * Math.sin(age * 0.3 + v.angle0()),
                        player.getZ() + Math.sin(a) * r);
                Vec3 vel = target.subtract(e.position()).scale(0.45);
                if (vel.length() > 1.6) vel = vel.normalize().scale(1.6);
                e.setDeltaMovement(vel);
                e.hurtMarked = true;
                e.fallDistance = 0;
            }
        }
        if (age == SLAM) {
            // Под взрыв из дорожки — ещё тяжёлый удар об землю (из видео с атакой), ниже тоном: гул.
            MechManager.sound(level, new Vec3(player.getX(), groundY, player.getZ()), com.smi7i.sandevistan.ModSounds.MECH_SLAM, 3.0F, 0.75F);
            MechManager.sound(level, new Vec3(player.getX(), groundY, player.getZ()), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 3.0F, 0.7F);
            MechManager.sound(level, new Vec3(player.getX(), groundY, player.getZ()), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 2.6F, 0.8F);
            GroundScar.circle(level, new Vec3(player.getX(), groundY, player.getZ()), RADIUS * 0.75, 1.2);
            // Резко вниз и урон всем, кто кружился.
            for (Victim v : list) {
                if (!(level.getEntity(v.id()) instanceof LivingEntity e) || !e.isAlive()) continue;
                e.setDeltaMovement(0, -3.0, 0);
                e.hurtMarked = true;
                e.fallDistance = 0;
                e.hurtServer(level, player.damageSources().playerAttack(player), MechManager.quarter(e));
            }
            VICTIMS.remove(player.getUUID());
        }
        if (age >= END || !ModAttachments.hasMech(player) || !player.isAlive()) {
            player.setAttached(ModAttachments.MECH_SKILL_START, 0L);
            VICTIMS.remove(player.getUUID());
            GROUND.remove(player.getUUID());
            if (ModAttachments.hasMech(player)) CD.put(player.getUUID(), COOLDOWN);
            MechManager.sync(player);
        }
    }

    public static int cooldown(ServerPlayer player) {
        return CD.getOrDefault(player.getUUID(), 0);
    }
}
