package com.smi7i.sandevistan.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * «Душа покидает тело» во время киберпсихоза: камера отделяется от тела.
 * Вы управляете невидимой душой — ходите (WASD, прыжок, бег) и смотрите мышью,
 * а тело на автопилоте носится и убивает, и вы видите это со стороны. После приступа душа возвращается.
 * Душа — клиентский «игрок», которого нет в мире: его никто не видит и он ни с кем не взаимодействует.
 */
public final class PsychosisSoul {
    // ===== Настройки =====
    /** Включена ли «душа» (false — во время приступа вид остаётся из глаз тела). */
    public static final boolean ENABLED = true;
    /** Скорость шага и бега души, блоков за тик (обычный игрок: ~0.22 шаг, ~0.28 бег). */
    private static final double WALK_SPEED = 0.2;
    private static final double SPRINT_SPEED = 0.28;
    /** Сила прыжка и гравитация. */
    private static final double JUMP = 0.42;
    private static final double GRAVITY = 0.08;
    /** На сколько блоков душа «выходит» из тела назад и вверх при начале приступа. */
    private static final double LEAVE_BACK = 1.5;
    private static final double LEAVE_UP = 0.6;

    /** Номер сущности для души: отрицательный, чтобы не совпасть с настоящими сущностями мира. */
    private static final int SOUL_ENTITY_ID = -0x5A4D;

    private static RemotePlayer soul;

    private PsychosisSoul() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PsychosisSoul::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> soul = null);
    }

    /** Активна ли душа (камера отделена от тела). */
    public static boolean active() {
        return soul != null;
    }

    public static RemotePlayer soul() {
        return soul;
    }

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        boolean want = ENABLED && SandevistanConfig.get().soulMode &&ClientSandevistanState.psychosis() && player != null && mc.level != null && player.isAlive();

        if (want && soul == null) {
            leaveBody(mc, player);
        } else if (!want && soul != null) {
            soul = null;
            if (player != null) mc.setCameraEntity(player); // душа возвращается
            return;
        }
        if (soul == null) return;
        if (soul.level() != mc.level) { // сменился мир
            soul = null;
            mc.setCameraEntity(player);
            return;
        }
        if (mc.getCameraEntity() != soul) mc.setCameraEntity(soul);
        prevPos = soul.position();
        move(mc.options);
        // Страховка: если душа провалилась ниже мира — возвращаем её к телу.
        if (soul.getY() < mc.level.getMinY() - 5) {
            soul.setPos(player.getX(), player.getY(), player.getZ());
            soul.setDeltaMovement(Vec3.ZERO);
            soul.setOldPosAndRot();
            prevPos = soul.position();
        }
        curPos = soul.position();
    }

    private static void leaveBody(Minecraft mc, LocalPlayer player) {
        RemotePlayer s = new RemotePlayer(mc.level, player.getGameProfile());
        // Свой номер сущности: душа не добавляется в мир, а Iris спрашивает номер у сущности-камеры (без него — вылет).
        s.setId(SOUL_ENTITY_ID);
        // RemotePlayer по умолчанию проходит сквозь блоки (его положение обычно приходит с сервера) — включаем столкновения.
        s.noPhysics = false;
        float yaw = player.getYRot();
        Vec3 back = Vec3.directionFromRotation(0.0F, yaw).scale(-LEAVE_BACK);
        // Душа выходит из тела назад и чуть вверх; стены её останавливают.
        s.setPos(player.getX(), player.getY(), player.getZ());
        s.move(MoverType.SELF, new Vec3(back.x, LEAVE_UP, back.z));
        s.setYRot(yaw);
        s.setXRot(player.getXRot() + 10.0F);
        s.setYHeadRot(yaw);
        s.setOldPosAndRot();
        soul = s;
        prevPos = curPos = s.position();
        mc.setCameraEntity(s);
    }

    /** Простая ходьба по клавишам: WASD, пробел — прыжок, бег — клавиша бега. */
    private static void move(Options options) {
        // Игра душу не «тикает» (её нет в мире) — делаем сами то, что обычно делается каждый тик.
        soul.setOldPosAndRot();
        soul.avatarState().tick(soul.position(), soul.getDeltaMovement());

        float forward = (options.keyUp.isDown() ? 1 : 0) - (options.keyDown.isDown() ? 1 : 0);
        float strafe = (options.keyLeft.isDown() ? 1 : 0) - (options.keyRight.isDown() ? 1 : 0);
        double speed = options.keySprint.isDown() && forward > 0 ? SPRINT_SPEED : WALK_SPEED;

        double len = Math.sqrt(forward * forward + strafe * strafe);
        double tx = 0, tz = 0;
        if (len > 0) {
            float yawRad = soul.getYRot() * Mth.DEG_TO_RAD;
            double f = forward / len * speed;
            double s = strafe / len * speed;
            tx = s * Mth.cos(yawRad) - f * Mth.sin(yawRad);
            tz = f * Mth.cos(yawRad) + s * Mth.sin(yawRad);
        }

        Vec3 v = soul.getDeltaMovement();
        double vx = v.x + (tx - v.x) * 0.5;
        double vz = v.z + (tz - v.z) * 0.5;
        // Гравитация всегда (как у обычного игрока): иначе душа «теряет» землю через тик и подпрыгивает.
        double vy = soul.onGround() && options.keyJump.isDown() ? JUMP : (v.y - GRAVITY) * 0.98;
        soul.setDeltaMovement(vx, vy, vz);
        soul.move(MoverType.SELF, soul.getDeltaMovement());
        if (soul.onGround() && soul.getDeltaMovement().y < 0) {
            soul.setDeltaMovement(soul.getDeltaMovement().x, 0.0, soul.getDeltaMovement().z);
        }
        // Покачивание камеры при ходьбе — как у обычного игрока.
        float bob = soul.onGround() ? (float) Math.min(0.1, soul.getDeltaMovement().horizontalDistance()) : 0.0F;
        soul.avatarState().updateBob(bob);
    }

    /** Высота глаз души над ногами. */
    private static final double EYE_HEIGHT = 1.62;
    /** Положение души на прошлом и текущем тике — для плавной камеры. */
    private static Vec3 prevPos = Vec3.ZERO, curPos = Vec3.ZERO;

    public static float yaw() {
        return soul != null ? soul.getYRot() : 0.0F;
    }

    public static float pitch() {
        return soul != null ? soul.getXRot() : 0.0F;
    }

    /** Глаза души в этом кадре: плавно между прошлым и текущим тиком. */
    public static Vec3 eyePosition(float partialTick) {
        return prevPos.lerp(curPos, partialTick).add(0.0, EYE_HEIGHT, 0.0);
    }

    /** Поворот головы души мышью (вызывается каждый кадр из MouseHandlerMixin). */
    public static void turn(double dx, double dy) {
        if (soul != null) {
            soul.turn(dx, dy);
            soul.setYHeadRot(soul.getYRot());
        }
    }
}
