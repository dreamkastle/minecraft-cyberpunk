package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.SandevistanManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Способность «Последний бой» (по видео пользователя, финал Edgerunners): доступна, когда шкала уколов полная
 * (все 5 шприцов введены), один раз на костюм. Сначала кат-сцена CUTSCENE тиков (пилот неуязвим и стоит —
 * со стороны видно, как у него трясётся голова и он «глитчит»), потом RUN тиков: Сандевистан включается сам
 * (зелёный мир, послеобразы, всё вокруг замедлено — другие игроки видят крайне медленно), пилот летит над землёй
 * на 1 блок вместе с двумя клонами, а всех, кого задевает хитбоксом, бьёт.
 * Длины — под музыку из видео (кат-сцена 2.0–17.0 с, полёт 17.0–29.3 с).
 */
public final class MechShowdown {
    // ===== Настройки (тики, 20 = 1 с) =====
    /** Кат-сцена 15 с, «ульта» 15 с, потом гибель (анимация death 3.5 с) — пилот погибает в костюме. */
    public static final int CUTSCENE = 300, RUN = 300, DEATH = 125, DEATH_START = CUTSCENE + RUN, END = DEATH_START + DEATH;
    /** Гибель (анимация death v3, 6 с): правое колено о землю, левое, руки падают (тики от начала гибели). */
    public static final int DEATH_HIT = 44, DEATH_KNEE2 = 68, DEATH_ARMS = 88;
    /** Урон касанием и как часто одного и того же можно задеть снова. */
    public static final float CONTACT_DAMAGE = 3.0F;
    public static final int CONTACT_COOLDOWN = 10;

    private static final Map<UUID, Map<Integer, Integer>> HIT = new HashMap<>();

    // ===== Клоны: летают сами вокруг пилота; когда он кого-то бьёт — по разу пролетают сквозь цель =====
    /** Радиус, в котором носятся клоны, скорость (блоки за тик), насколько резко поворачивают, высота над землёй. */
    /** Скорость — как у костюма с Сандевистаном (≈ бег, 0.3 блока за тик); скользят по земле. */
    public static final double CLONE_RANGE = 30.0, CLONE_SPEED = 0.3, CLONE_TURN = 0.2, CLONE_HOVER = 0.0;
    /** Клоны вылетают к цели по очереди (задержка второго), пролетают дальше цели столько тиков. */
    private static final int CLONE_DELAY = 6, CLONE_PASS = 9;

    private static final class Clone {
        Vec3 pos, vel = Vec3.ZERO, goal;
        int strikeId = -1, delay, pass;
        boolean struck;
    }

    private static final Map<UUID, Clone[]> CLONES = new HashMap<>();
    private static final java.util.Random RNG = new java.util.Random();

    private MechShowdown() {}

    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ShowdownPayload.TYPE, ShowdownPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShowdownClonesPayload.TYPE, ShowdownClonesPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ShowdownPayload.TYPE, (payload, context) -> start(context.player()));
    }

    public static long age(net.minecraft.world.entity.player.Player player) {
        long s = player.getAttachedOrElse(ModAttachments.MECH_SHOWDOWN_START, 0L);
        return s > 0 ? player.level().getGameTime() - s : -1;
    }

    /** Идёт кат-сцена (пилот неуязвим, стоит). */
    public static boolean inCutscene(net.minecraft.world.entity.player.Player player) {
        long a = age(player);
        return a >= 0 && a < CUTSCENE;
    }

    /** Идёт полёт после кат-сцены. */
    public static boolean inRun(net.minecraft.world.entity.player.Player player) {
        long a = age(player);
        return a >= CUTSCENE && a < DEATH_START;
    }

    /** Идёт анимация гибели (стоит, управление заблокировано). */
    public static boolean inDeath(net.minecraft.world.entity.player.Player player) {
        long a = age(player);
        return a >= DEATH_START && a < END;
    }

    public static boolean isActive(net.minecraft.world.entity.player.Player player) {
        long a = age(player);
        return a >= 0 && a < END;
    }

    /** Доступна: в костюме, все 5 уколов сделаны, ещё не использована в этом костюме. */
    public static boolean available(net.minecraft.world.entity.player.Player player) {
        return ModAttachments.hasMech(player) && player.getAttachedOrElse(ModAttachments.MECH_DOSES, 0) <= 0
                && !Boolean.TRUE.equals(player.getAttached(ModAttachments.MECH_SHOWDOWN_DONE));
    }

    static void start(ServerPlayer player) {
        if (!available(player) || !player.isAlive() || isActive(player) || MechManager.isAttacking(player)
                || MechManager.isEquipping(player) || MechVortex.isActive(player) || MechMeteor.isActive(player) || MechSingularity.isActive(player) || MechPush.isActive(player)) {
            MechManager.sync(player);
            return;
        }
        player.setAttached(ModAttachments.MECH_SHOWDOWN_START, player.level().getGameTime());
        player.setAttached(ModAttachments.MECH_SHOWDOWN_DONE, true);
        HIT.remove(player.getUUID());
    }

    static void tick(ServerPlayer player) {
        long a = age(player);
        if (a < 0) return;
        player.fallDistance = 0;
        if (a == CUTSCENE) {
            // Конец кат-сцены — Сандевистан включается сам на всё время полёта (без перегрева в конце).
            SandevistanManager.forceActive(player, RUN);
            Vec3 side = Vec3.directionFromRotation(0, player.getYRot() + 90);
            Clone[] cl = new Clone[2];
            for (int i = 0; i < 2; i++) {
                cl[i] = new Clone();
                cl[i].pos = player.position().add(side.scale(i == 0 ? 2.5 : -2.5));
            }
            CLONES.put(player.getUUID(), cl);
        }
        if (a >= CUTSCENE && a < DEATH_START) {
            contactHits(player);
            // Клоны с уроном убраны по просьбе пользователя — теперь это только картинка на клиенте.
        } else {
            CLONES.remove(player.getUUID());
        }
        if (a == DEATH_START + DEATH_HIT || a == DEATH_START + DEATH_KNEE2) {
            // Колено костюма тяжело ударяет о землю.
            MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_SLAM, 2.2F, a == DEATH_START + DEATH_HIT ? 0.62F : 0.55F);
            MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_LAND, 1.8F, 0.75F);
        }
        if (a == DEATH_START + DEATH_ARMS) {
            // Руки падают, клешни ударяют о землю.
            MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_LAND, 1.4F, 1.1F);
        }
        if (a >= END) {
            // Конец: пилот погибает в костюме. Время начала НЕ сбрасываем — пока тело на экране, костюм лежит
            // в позе гибели (после возрождения игрок — новый объект, данных уже нет, костюм снят).
            HIT.remove(player.getUUID());
            CLONES.remove(player.getUUID());
            if (player.isAlive()) player.kill(player.level());
            return;
        }
        if (!ModAttachments.hasMech(player) || !player.isAlive()) {
            player.setAttached(ModAttachments.MECH_SHOWDOWN_START, 0L);
            HIT.remove(player.getUUID());
        }
    }

    /** Всех, кого задел хитбоксом костюма в полёте, — урон и отброс (одного и того же — не чаще CONTACT_COOLDOWN). */
    private static void contactHits(ServerPlayer player) {
        ServerLevel level = player.level();
        Map<Integer, Integer> hit = HIT.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        hit.replaceAll((id, t) -> t - 1);
        hit.values().removeIf(t -> t <= 0);
        Vec3 vel = player.getDeltaMovement();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(0.45),
                e -> e != player && e.isAlive() && !(e instanceof ArmorStand) && !e.isSpectator())) {
            if (hit.containsKey(e.getId())) continue;
            hit.put(e.getId(), CONTACT_COOLDOWN);
            e.hurtServer(level, player.damageSources().playerAttack(player), MechManager.quarter(e));
            Vec3 away = e.position().subtract(player.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() > 1.0E-4 ? away.normalize() : new Vec3(vel.x, 0, vel.z).normalize();
            e.push(away.x * 0.9 + vel.x * 0.6, 0.35, away.z * 0.9 + vel.z * 0.6);
            e.hurtMarked = true;
            MechManager.sound(level, e.position(), com.smi7i.sandevistan.ModSounds.MECH_HIT1, 1.0F, 1.4F);
            MechManager.sound(level, e.position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 1.1F, 1.3F);
            // Пилот кого-то задел — клоны по очереди пролетают сквозь эту цель.
            Clone[] cl = CLONES.get(player.getUUID());
            if (cl != null) {
                for (int i = 0; i < cl.length; i++) {
                    if (cl[i].strikeId >= 0 && !cl[i].struck) continue; // уже летит к другой цели
                    cl[i].strikeId = e.getId();
                    cl[i].delay = 2 + CLONE_DELAY * i;
                    cl[i].struck = false;
                }
            }
        }
    }

    /**
     * Клоны: сами носятся по дугам вокруг пилота (не дальше CLONE_RANGE), в 1 блоке над землёй.
     * Есть цель — летят к ней, задевают ОДИН раз (урон и отброс) и пролетают дальше по инерции, потом снова свободны.
     */
    private static void tickClones(ServerPlayer player) {
        Clone[] cl = CLONES.get(player.getUUID());
        if (cl == null) return;
        ServerLevel level = player.level();
        Vec3 home = player.position();
        double[] data = new double[cl.length * 4];
        for (int i = 0; i < cl.length; i++) {
            Clone c = cl[i];
            Vec3 desired;
            net.minecraft.world.entity.Entity target = c.strikeId >= 0 ? level.getEntity(c.strikeId) : null;
            if (c.strikeId >= 0 && (target == null || !target.isAlive())) {
                c.strikeId = -1;
                target = null;
            }
            if (target != null && c.delay > 0) {
                c.delay--;
                target = null; // ещё ждёт своей очереди — летает как обычно
            }
            boolean passing = false;
            if (target != null && !c.struck) {
                Vec3 aim = target.position().add(0, target.getBbHeight() * 0.5, 0);
                if (c.pos.distanceTo(aim) < 1.9) {
                    // Пролетает сквозь — один удар.
                    c.struck = true;
                    c.pass = CLONE_PASS;
                    if (target instanceof LivingEntity le) {
                        le.hurtServer(level, player.damageSources().playerAttack(player), MechManager.quarter(le));
                        Vec3 push = c.vel.lengthSqr() > 1.0E-4 ? c.vel.normalize() : Vec3.ZERO;
                        le.push(push.x * 0.9, 0.35, push.z * 0.9);
                        le.hurtMarked = true;
                    }
                    MechManager.sound(level, target.position(), com.smi7i.sandevistan.ModSounds.MECH_HIT1, 1.0F, 1.5F);
                }
                desired = aim.subtract(c.pos);
            } else if (c.struck && c.pass > 0) {
                c.pass--;
                passing = true;
                desired = c.vel;
                if (c.pass == 0) c.strikeId = -1;
            } else {
                // Свободный полёт: новая точка на кольце вокруг пилота, когда долетел (или ушёл слишком далеко).
                if (c.goal == null || c.pos.distanceTo(c.goal) < 3.0 || c.goal.distanceTo(home) > CLONE_RANGE) {
                    double ang = RNG.nextDouble() * Math.PI * 2, r = 6 + RNG.nextDouble() * (CLONE_RANGE - 8);
                    c.goal = home.add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                }
                if (c.pos.distanceTo(home) > CLONE_RANGE) c.goal = home;
                desired = c.goal.subtract(c.pos);
            }
            desired = new Vec3(desired.x, 0, desired.z);
            if (desired.lengthSqr() > 1.0E-6) desired = desired.normalize().scale(CLONE_SPEED);
            c.vel = passing ? c.vel : c.vel.lerp(desired, CLONE_TURN);
            Vec3 next = c.pos.add(c.vel.x, 0, c.vel.z);
            int gy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    net.minecraft.util.Mth.floor(next.x), net.minecraft.util.Mth.floor(next.z));
            double y = c.pos.y + (gy + CLONE_HOVER - c.pos.y) * 0.5;
            c.pos = new Vec3(next.x, y, next.z);
            data[i * 4] = c.pos.x;
            data[i * 4 + 1] = c.pos.y;
            data[i * 4 + 2] = c.pos.z;
            data[i * 4 + 3] = c.vel.lengthSqr() > 1.0E-4 ? Math.toDegrees(Math.atan2(-c.vel.x, c.vel.z)) : player.getYRot();
        }
        ShowdownClonesPayload payload = new ShowdownClonesPayload(player.getId(), data);
        for (ServerPlayer p : net.fabricmc.fabric.api.networking.v1.PlayerLookup.around(level, home, 160)) {
            ServerPlayNetworking.send(p, payload);
        }
    }
}
