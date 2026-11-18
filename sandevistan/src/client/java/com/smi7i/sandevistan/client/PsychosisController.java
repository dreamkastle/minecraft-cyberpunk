package com.smi7i.sandevistan.client;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Автопилот киберпсихоза (только клиент). Пока идёт приступ, управление у него:
 *  - видит живое существо — бежит к нему и бьёт;
 *  - никого нет — стоит, озирается, видит вдалеке своего красного двойника и бежит к нему; двойник рассыпается.
 * Чтобы не было похоже на бота: дрожание взгляда, резкие подёргивания головы, спотыкания,
 * случайные прыжки, кружение вокруг цели, промахи.
 */
public final class PsychosisController {
    // ===== Настройки =====
    /** Как далеко ищет жертв (в блоках). */
    public static final double SEARCH_RANGE = 28.0;
    /** Игроки — главная цель: насколько дальше за ними гонится и сколько тиков помнит, если потерял из виду. */
    public static final double PLAYER_EXTRA_RANGE = 20.0;
    public static final int PLAYER_MEMORY_TICKS = 120;
    /** Дистанция удара (до края хитбокса цели). */
    public static final double ATTACK_REACH = 2.8;
    /** Как резко голова доворачивает к цели: больше — резче. */
    public static final float TURN_RATE = 9.0F;
    /** Максимальная скорость поворота головы, градусов в секунду. */
    public static final float MAX_TURN_SPEED = 720.0F;
    /** Расстояние, на котором появляется двойник-галлюцинация (от и до). */
    public static final double HALLUCINATION_MIN = 9.0;
    public static final double HALLUCINATION_MAX = 16.0;
    /** Шансы «человеческих» сбоев за тик. */
    public static final float TWITCH_CHANCE = 0.03F;
    public static final float STUMBLE_CHANCE = 0.025F;
    public static final float RANDOM_JUMP_CHANCE = 0.03F;
    public static final float AIR_SWING_CHANCE = 0.04F;

    /** Застревание: меньше этого сдвига за тик (в блоках) при беге считается «упёрся». */
    public static final double STUCK_MOVE = 0.04;
    /** Через сколько тиков упора начинает обходить препятствие боком. */
    public static final int STUCK_TICKS = 8;
    /** Через сколько тиков упора бросает цель совсем. */
    public static final int GIVE_UP_TICKS = 80;

    private enum Mode { HUNT, STARE, WATCH, CHASE }

    private static Vec3 lastPos;
    private static int stuckTicks, detourTicks, detourDir;
    private static float detourYaw;

    private static final Random R = new Random();

    private static boolean wasControlling;
    private static Mode mode = Mode.STARE;
    private static int modeTicks;
    private static LivingEntity target;
    private static int lostSightTicks;

    private static float desiredYaw, desiredPitch;
    private static float noiseYaw, noisePitch;
    private static float twitchYaw, twitchPitch;
    private static int twitchTicks;
    private static int lookAroundTicks;

    private static boolean forward, left, right, jump, sprint, shift;
    private static int stumbleTicks, strafeDir, strafeTicks, crouchTicks;
    private static float attackThreshold = 0.9F;

    /** Двойник-галлюцинация (рисует SandevistanAfterimages). null — нет. */
    static AfterimageRenderState hallucination;
    private static Vec3 hallucinationPos;

    private PsychosisController() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PsychosisController::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop());
    }

    /** Управляет ли сейчас автопилот игроком. */
    public static boolean isControlling() {
        return ClientSandevistanState.psychosis() && Minecraft.getInstance().player != null;
    }

    /** «Нажатия» клавиш движения от автопилота, или null, если управляет человек. */
    public static Input forcedInput() {
        if (PlayerFreeze.frozen()) return Input.EMPTY; // заморожен чужим Сандевистаном — стоит
        if (!isControlling()) return null;
        return new Input(forward, false, left, right, jump, shift, sprint);
    }

    /** Нажатия клавиш во время приступа пропадают, чтобы не сработать все разом после него. */
    public static void swallowKeyPresses() {
        for (KeyMapping key : Minecraft.getInstance().options.keyMappings) {
            while (key.consumeClick()) {
                // выбрасываем
            }
        }
    }

    private static void stop() {
        wasControlling = false;
        target = null;
        hallucination = null;
        hallucinationPos = null;
        forward = left = right = jump = sprint = shift = false;
        mode = Mode.STARE;
        lastPos = null;
        stuckTicks = detourTicks = 0;
        detourYaw = 0.0F;
    }

    // ===== Каждый тик: решения =====

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (!isControlling() || mc.level == null || player == null) {
            if (wasControlling) {
                stop();
                KeyMapping.releaseAll();
            }
            return;
        }
        if (!wasControlling) {
            wasControlling = true;
            desiredYaw = player.getYRot();
            desiredPitch = player.getXRot();
            mode = Mode.STARE;
            modeTicks = 10;
        }

        jump = false;
        left = right = false;
        updateTarget(player);
        if (target != null) {
            hunt(mc, player);
        } else {
            idle(mc, player);
        }
        handleStuck(player);
        chaos(player);
    }

    private static void updateTarget(LocalPlayer player) {
        if (target != null) {
            boolean isPlayer = target instanceof Player;
            boolean valid = target.isAlive() && !target.isRemoved()
                    && player.distanceTo(target) < SEARCH_RANGE + (isPlayer ? PLAYER_EXTRA_RANGE : 6);
            if (valid && player.hasLineOfSight(target)) {
                lostSightTicks = 0;
            } else if (valid && lostSightTicks++ < (isPlayer ? PLAYER_MEMORY_TICKS : 30)) {
                return; // ещё помнит, куда побежала жертва (игрока — гораздо дольше)
            } else {
                target = null;
            }
        }
        // Гонится за мобом, а в поле зрения появился игрок — сразу бросает моба.
        boolean lookForPlayer = target != null && !(target instanceof Player) && player.tickCount % 5 == 0;
        if (target == null || lookForPlayer || R.nextFloat() < 0.01F) {
            Level level = player.level();
            AABB area = player.getBoundingBox().inflate(SEARCH_RANGE);
            List<LivingEntity> seen = level.getEntitiesOfClass(LivingEntity.class, area, e -> isVictim(player, e));
            // Игроки — главная цель: если видит хоть одного, мобов не трогает.
            List<LivingEntity> players = seen.stream().filter(e -> e instanceof Player).toList();
            if (!players.isEmpty()) seen = new java.util.ArrayList<>(players);
            else if (lookForPlayer) return; // игроков нет — продолжает бить моба
            if (!seen.isEmpty()) {
                seen.sort(Comparator.comparingDouble(player::distanceToSqr));
                // Не всегда самый близкий — иногда второй по счёту.
                LivingEntity next = seen.get(seen.size() > 1 && R.nextFloat() < 0.25F ? 1 : 0);
                if (next == target) return;
                target = next;
                lostSightTicks = 0;
                hallucination = null;
                hallucinationPos = null;
            }
        }
    }

    private static boolean isVictim(LocalPlayer player, LivingEntity e) {
        if (e == player || !e.isAlive() || e instanceof ArmorStand || e.isSpectator()) return false;
        if (e instanceof Player other && other.isCreative()) return false;
        return player.hasLineOfSight(e);
    }

    private static void hunt(Minecraft mc, LocalPlayer player) {
        mode = Mode.HUNT;
        AABB box = target.getBoundingBox();
        Vec3 aim = new Vec3(box.getCenter().x, box.minY + box.getYsize() * 0.7, box.getCenter().z);
        lookAt(player, aim);

        Vec3 eye = player.getEyePosition();
        double dist = Math.sqrt(box.distanceToSqr(eye));
        forward = dist > 1.4;
        sprint = dist > 3.0;

        // Вблизи кружит вокруг жертвы, меняя сторону.
        if (dist < 4.5) {
            if (strafeTicks-- <= 0) {
                strafeDir = R.nextInt(3) - 1;
                strafeTicks = 8 + R.nextInt(18);
            }
            left = strafeDir < 0;
            right = strafeDir > 0;
        } else if (R.nextFloat() < 0.04F) {
            // Вдалеке иногда «рыскает» зигзагом.
            if (R.nextBoolean()) left = true; else right = true;
        }

        float facing = Math.abs(Mth.wrapDegrees(desiredYaw - player.getYRot()));
        if (dist <= ATTACK_REACH && facing < 40.0F && player.getAttackStrengthScale(0.5F) >= attackThreshold) {
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
            attackThreshold = 0.7F + R.nextFloat() * 0.3F;
        } else if (dist < 5.0 && R.nextFloat() < AIR_SWING_CHANCE) {
            player.swing(InteractionHand.MAIN_HAND); // удар по воздуху
        }
    }

    private static void idle(Minecraft mc, LocalPlayer player) {
        if (mode == Mode.HUNT) {
            mode = Mode.STARE;
            modeTicks = 15 + R.nextInt(30);
        }
        modeTicks--;
        switch (mode) {
            case STARE -> {
                // Стоит и озирается рывками.
                forward = false;
                sprint = false;
                if (lookAroundTicks-- <= 0) {
                    desiredYaw = player.getYRot() + (R.nextBoolean() ? 1 : -1) * (40 + R.nextFloat() * 110);
                    desiredPitch = -10 + R.nextFloat() * 25;
                    lookAroundTicks = 10 + R.nextInt(18);
                }
                if (modeTicks <= 0 && spawnHallucination(mc, player)) {
                    mode = Mode.WATCH;
                    modeTicks = 12 + R.nextInt(18);
                }
            }
            case WATCH -> {
                // Заметил двойника и смотрит на него.
                forward = false;
                sprint = false;
                lookAtHallucination(player);
                if (modeTicks <= 0) {
                    mode = Mode.CHASE;
                    modeTicks = 220;
                }
            }
            case CHASE -> {
                lookAtHallucination(player);
                forward = true;
                sprint = true;
                double dist = hallucinationPos == null ? 0 : player.position().distanceTo(hallucinationPos);
                if (hallucinationPos == null || dist < 2.2 || modeTicks <= 0) {
                    hallucination = null; // рассыпался
                    hallucinationPos = null;
                    mode = Mode.STARE;
                    modeTicks = 15 + R.nextInt(35);
                }
            }
            default -> mode = Mode.STARE;
        }
        // Двойник всегда смотрит на игрока.
        if (hallucination != null && hallucinationPos != null) {
            Vec3 d = player.position().subtract(hallucinationPos);
            hallucination.bodyRot = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        }
    }

    private static void lookAtHallucination(LocalPlayer player) {
        if (hallucinationPos != null) {
            lookAt(player, hallucinationPos.add(0, 1.5, 0));
        }
    }

    private static boolean spawnHallucination(Minecraft mc, LocalPlayer player) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = R.nextDouble() * Math.PI * 2;
            double d = HALLUCINATION_MIN + R.nextDouble() * (HALLUCINATION_MAX - HALLUCINATION_MIN);
            Vec3 ground = findGround(player.level(), player.getX() + Math.cos(angle) * d,
                    player.getY(), player.getZ() + Math.sin(angle) * d);
            if (ground == null) continue;

            AfterimageRenderState state = SandevistanAfterimages.snapshot(mc, player);
            state.x = ground.x;
            state.y = ground.y;
            state.z = ground.z;
            state.yRot = 0.0F;
            state.xRot = 0.0F;
            state.walkAnimationSpeed = 0.0F;
            state.walkAnimationPos = 0.0F;
            state.psychosis = true;
            hallucination = state;
            hallucinationPos = ground;
            return true;
        }
        return false;
    }

    /** Ищет место, где можно стоять: внизу твёрдый блок, на уровне ног и головы — пусто. */
    private static Vec3 findGround(Level level, double x, double y, double z) {
        BlockPos start = BlockPos.containing(x, y + 4, z);
        for (int dy = 0; dy < 12; dy++) {
            BlockPos feet = start.below(dy);
            if (isFree(level, feet) && isFree(level, feet.above()) && !isFree(level, feet.below())) {
                return new Vec3(x, feet.getY(), z);
            }
        }
        return null;
    }

    private static boolean isFree(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** «Человеческие» сбои: дрожание взгляда, подёргивания, спотыкания, прыжки, приседания. */
    private static void chaos(LocalPlayer player) {
        noiseYaw = Mth.clamp((noiseYaw + (float) R.nextGaussian() * 3.0F) * 0.85F, -12.0F, 12.0F);
        noisePitch = Mth.clamp((noisePitch + (float) R.nextGaussian() * 1.5F) * 0.85F, -6.0F, 6.0F);

        if (twitchTicks > 0) {
            if (--twitchTicks == 0) {
                twitchYaw = 0.0F;
                twitchPitch = 0.0F;
            }
        } else if (R.nextFloat() < TWITCH_CHANCE) {
            twitchYaw = (R.nextBoolean() ? 1 : -1) * (20 + R.nextFloat() * 25);
            twitchPitch = (R.nextFloat() * 2 - 1) * 12;
            twitchTicks = 3 + R.nextInt(4);
        }

        boolean moving = forward || left || right;
        if (stumbleTicks > 0) {
            stumbleTicks--;
            forward = false;
            sprint = false;
        } else if (moving && R.nextFloat() < STUMBLE_CHANCE) {
            stumbleTicks = 3 + R.nextInt(6);
        }

        if (moving && player.onGround()) {
            int obstacle = obstacleAhead(player);
            // Ступенька в 1 блок — перепрыгивает; стену в 2+ блока — не прыгает зря (её обходит handleStuck).
            if (obstacle == 1 || (obstacle == 0 && R.nextFloat() < RANDOM_JUMP_CHANCE)) {
                jump = true;
            }
        }

        if (crouchTicks > 0) {
            crouchTicks--;
            shift = true;
        } else {
            shift = false;
            if (R.nextFloat() < 0.008F) crouchTicks = 2 + R.nextInt(4);
        }
    }

    /** Что впереди на уровне ног: 0 — свободно, 1 — ступенька в 1 блок (можно запрыгнуть), 2 — стена выше. */
    private static int obstacleAhead(LocalPlayer player) {
        Level level = player.level();
        Vec3 dir = Vec3.directionFromRotation(0.0F, player.getYRot());
        BlockPos feet = BlockPos.containing(player.getX() + dir.x * 0.7, player.getY() + 0.01, player.getZ() + dir.z * 0.7);
        if (isFree(level, feet)) return 0;
        boolean canClimb = isFree(level, feet.above()) && isFree(level, feet.above(2))
                && isFree(level, player.blockPosition().above(2)); // и над головой есть место для прыжка
        return canClimb ? 1 : 2;
    }

    /**
     * Застревание: если хочет бежать, а почти не двигается — обходит препятствие боком (то влево, то вправо),
     * а если застрял совсем надолго — бросает эту цель.
     */
    private static void handleStuck(LocalPlayer player) {
        Vec3 pos = player.position();
        double moved = lastPos == null ? 1.0 : Math.sqrt(Math.pow(pos.x - lastPos.x, 2) + Math.pow(pos.z - lastPos.z, 2));
        lastPos = pos;

        if (forward && moved < STUCK_MOVE) {
            stuckTicks++;
        } else {
            stuckTicks = Math.max(0, stuckTicks - 2);
        }

        if (stuckTicks > STUCK_TICKS && detourTicks == 0) {
            detourTicks = 15 + R.nextInt(20);
            detourDir = R.nextBoolean() ? 1 : -1;
        }
        if (detourTicks > 0) {
            detourTicks--;
            // Отворачивает от стены и идёт вдоль неё боком.
            detourYaw = detourDir * 55.0F;
            forward = true;
            left = detourDir < 0;
            right = detourDir > 0;
        } else {
            detourYaw = 0.0F;
        }

        if (stuckTicks > GIVE_UP_TICKS) {
            // Совсем застрял — бросает цель и ищет что-то другое.
            target = null;
            hallucination = null;
            hallucinationPos = null;
            mode = Mode.STARE;
            modeTicks = 10;
            stuckTicks = 0;
            detourTicks = 0;
            detourYaw = 0.0F;
        }
    }

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        desiredYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        desiredPitch = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
    }

    // ===== Каждый кадр: плавный поворот головы =====

    public static void turnFrame(double frameSeconds) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        float dt = (float) Math.min(frameSeconds, 0.1);
        float yawDiff = Mth.wrapDegrees(desiredYaw + noiseYaw + twitchYaw + detourYaw - player.getYRot());
        float pitchTarget = Mth.clamp(desiredPitch + noisePitch + twitchPitch, -85.0F, 85.0F);
        float pitchDiff = pitchTarget - player.getXRot();

        float k = Math.min(1.0F, dt * TURN_RATE);
        float maxStep = MAX_TURN_SPEED * dt;
        float yawStep = Mth.clamp(yawDiff * k, -maxStep, maxStep);
        float pitchStep = Mth.clamp(pitchDiff * k, -maxStep, maxStep);
        // Entity.turn умножает на 0.15 (как движение мыши).
        player.turn(yawStep / 0.15, pitchStep / 0.15);
    }
}
