package com.smi7i.sandevistan.client.mech;

import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.mech.MechManager;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Как костюм-мех стоит, шагает и поворачивается (только отрисовка, у каждого игрока своё).
 * Считается ОДИН раз за игровой тик (а при отрисовке только плавно подставляется) — поэтому лишние «снимки» позы
 * (послеобразы Сандевистана, отдельный проход поверх фильтра) ничего не сбивают.
 * - шаг по пройденному пути: за цикл анимации костюм проходит ровно длину шага — опорная стопа стоит и отталкивается;
 * - корпус тяжело разворачивается в сторону движения, голова пилота смотрит, куда смотрит игрок;
 * - назад — пятится (шаг задом наперёд); разворот на месте — переступая ногами.
 */
public final class MechMotion {
    /** Скорость разворота корпуса (градусы в секунду) — тяжёлый. */
    public static final float TURN_SPEED = 160.0F;
    /** Если движение отличается от взгляда больше чем на столько — пятиться, а не разворачиваться. */
    public static final float BACKWARD_ANGLE = 120.0F;
    /** Стоя на месте: корпус доворачивается за взглядом, если голова отвернулась больше чем на столько. */
    public static final float HEAD_FREE_ANGLE = 45.0F;
    /** Насколько голова пилота может повернуться относительно корпуса. */
    public static final float HEAD_LIMIT = 80.0F;
    /** Разворот на месте: один цикл переступания на столько градусов поворота. */
    public static final float TURN_DEGREES_PER_CYCLE = 100.0F;
    private static final float TICK = 0.05F;

    private static final class State {
        float bodyYaw, bodyYawO, walkTime, walkTimeO, weight, weightO, strafeTime, strafeTimeO, strafeWeight, strafeWeightO;
        /** Крен в повороте (градусы): в ульте костюм заваливается внутрь поворота. */
        float bank, bankO;
        boolean init;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    /** Для отрисовки: время анимации ходьбы и её вес (0 — стоит, 1 — идёт). */
    public record Gait(float walkTime, float weight, float strafeTime, float strafeWeight) {}

    /**
     * Диагональный шаг (W+A / W+D): если движение отличается от взгляда на угол между этими — костюм смотрит
     * вперёд, а ноги идут по диагонали. Чисто вбок (A или D) — обычная ходьба с разворотом.
     */
    public static final float STRAFE_MIN = 20.0F, STRAFE_MAX = 75.0F;
    /** Анимации strafe_r / strafe_l: цикл 1.3 с, за цикл корпус проходит 4 × 5.5 = 22 px (× 15/16 масштаба игрока). */
    public static final float STRAFE_CYCLE = 1.3F, STRAFE_BLOCKS = 22.0F * 0.9375F / 16.0F;

    private MechMotion() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MechMotion::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.isPaused()) {
            if (mc.level == null) STATES.clear();
            return;
        }
        for (Player p : mc.level.players()) {
            if (!ModAttachments.hasMech(p)) {
                STATES.remove(p.getId());
                continue;
            }
            tickPlayer(p, STATES.computeIfAbsent(p.getId(), id -> new State()));
        }
    }

    private static void tickPlayer(Player p, State s) {
        float headYaw = p.getYHeadRot();
        if (!s.init) {
            s.bodyYaw = s.bodyYawO = headYaw;
            s.init = true;
        }
        s.bodyYawO = s.bodyYaw;
        s.walkTimeO = s.walkTime;
        s.weightO = s.weight;
        s.strafeTimeO = s.strafeTime;
        s.strafeWeightO = s.strafeWeight;
        s.bankO = s.bank;
        // В воздухе («метеор», «вихрь») ноги не шагают: ни звука шагов, ни тряски — корпус просто смотрит по взгляду.
        if (com.smi7i.sandevistan.mech.MechMeteor.isActive(p) || com.smi7i.sandevistan.mech.MechVortex.isActive(p)
                || com.smi7i.sandevistan.mech.MechSingularity.isActive(p)) {
            float step = TURN_SPEED * TICK;
            s.bodyYaw += Mth.clamp(Mth.wrapDegrees(headYaw - s.bodyYaw), -step, step);
            s.weight *= 0.7F;
            s.strafeWeight *= 0.7F;
            return;
        }

        double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 2.0) dist = 0; // телепорт — не шаг
        boolean moving = dist > 0.008;

        float target = s.bodyYaw;
        int dir = 1;
        boolean strafing = false;
        if (moving) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float off = Math.abs(Mth.wrapDegrees(moveYaw - headYaw));
            if (off > STRAFE_MIN && off < STRAFE_MAX) {
                target = headYaw; // боком: корпус смотрит, куда смотрит игрок
                strafing = true;
            } else if (off > BACKWARD_ANGLE) {
                target = moveYaw + 180.0F; // пятится: смотрит вперёд, шагает назад
                dir = -1;
            } else {
                target = moveYaw;
            }
        } else if (Math.abs(Mth.wrapDegrees(headYaw - s.bodyYaw)) > HEAD_FREE_ANGLE) {
            target = headYaw; // стоит — доворачивается за взглядом
        }
        float step = TURN_SPEED * TICK;
        float turned = Mth.clamp(Mth.wrapDegrees(target - s.bodyYaw), -step, step);
        s.bodyYaw += turned;
        // Крен: в ульте (скольжение) — сильно внутрь поворота, при обычной ходьбе — чуть-чуть.
        boolean glide = MechShowdownClient.running(p);
        float bankTarget = Mth.clamp(-turned * (glide ? 2.6F : 0.6F), glide ? -22 : -5, glide ? 22 : 5);
        s.bank += (bankTarget - s.bank) * 0.22F;

        float targetWeight;
        float targetStrafe = 0.0F;
        if (moving && strafing) {
            // Вправо от корпуса (+X модели) — strafe_r (вес +), влево — strafe_l (вес −). Правый вектор: (-cos yaw, -sin yaw).
            double yaw = Math.toRadians(s.bodyYaw);
            double lateral = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
            s.strafeTime += (float) (dist / STRAFE_BLOCKS * STRAFE_CYCLE);
            targetStrafe = lateral >= 0 ? 1.0F : -1.0F;
            targetWeight = 0.0F;
        } else if (moving) {
            // Шаг по пройденному пути: длина шага анимации = STRIDE_BLOCKS за цикл → стопа не скользит.
            float stride = (float) (dist / MechManager.STRIDE_BLOCKS * MechManager.WALK_CYCLE) * dir;
            // Повреждённый костюм хромает: на одну ногу ступает быстро, на другую — медленно и тяжело.
            if (p.getHealth() / Math.max(1, p.getMaxHealth()) < MechManager.LIMP_HP) {
                boolean firstHalf = Math.floorMod((int) Math.floor(s.walkTime / (MechManager.WALK_CYCLE / 2)), 2) == 0;
                stride *= firstHalf ? 1.4F : 0.72F;
            }
            s.walkTime += stride;
            targetWeight = 1.0F;
        } else if (Math.abs(turned) > 0.01F) {
            // Разворот на месте — переступает ногами.
            s.walkTime += Math.abs(turned) / TURN_DEGREES_PER_CYCLE * MechManager.WALK_CYCLE;
            targetWeight = Mth.clamp(Math.abs(turned) / TICK / 60.0F, 0.0F, 0.85F);
        } else {
            targetWeight = 0.0F;
        }
        s.weight += (targetWeight - s.weight) * 0.3F; // плавно входит в шаг и выходит
        s.strafeWeight += (targetStrafe - s.strafeWeight) * 0.3F;
        float sh = STRAFE_CYCLE / 2;
        if (Math.abs(s.strafeWeight) > 0.35F && Math.floor(s.strafeTime / sh) != Math.floor(s.strafeTimeO / sh)) {
            float w = s.weight;
            s.weight = Math.abs(s.strafeWeight); // громкость шага
            footstep(p, s);
            s.weight = w;
        }
        // Стопа встаёт на землю дважды за цикл (в начале и середине) — тяжёлый шаг и немного пыли.
        float half = MechManager.WALK_CYCLE / 2;
        if (s.weight > 0.35F && Math.floor(s.walkTime / half) != Math.floor(s.walkTimeO / half)) footstep(p, s);
    }

    private static void footstep(Player p, State s) {
        var level = p.level();
        MechShake.onStep(p, s.weight); // у пилота и у всех рядом экран немного трясёт
        level.playLocalSound(p.getX(), p.getY(), p.getZ(), com.smi7i.sandevistan.ModSounds.MECH_STEP,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.9F * s.weight, 0.95F + p.getRandom().nextFloat() * 0.1F, false);
        var ground = level.getBlockState(p.blockPosition().below());
        if (ground.isAir()) return;
        var particle = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, ground);
        // Какая нога встала: правая на целом цикле, левая на половине. Нога ~0.37 блока от центра.
        boolean right = Math.floorMod((int) Math.floor(s.walkTime / (MechManager.WALK_CYCLE / 2)), 2) == 0;
        double yaw = Math.toRadians(s.bodyYaw);
        double side = right ? -0.37 : 0.37;
        double fx = p.getX() + Math.cos(yaw) * side, fz = p.getZ() + Math.sin(yaw) * side;
        // Вмятина-след под ставящейся стопой: она в этот момент впереди корпуса (~0.6 блока). Тает за 30 с.
        double fwd = s.walkTime >= s.walkTimeO ? 0.6 : -0.3; // пятится — стопа сзади
        MechFx.footprint(new Vec3(fx - Math.sin(yaw) * fwd, p.getY(), fz + Math.cos(yaw) * fwd), yaw);
        for (int i = 0; i < 10; i++) {
            double a = p.getRandom().nextDouble() * Math.PI * 2;
            level.addParticle(particle, fx, p.getY() + 0.05, fz, Math.cos(a) * 0.15, 0.1, Math.sin(a) * 0.15);
        }
        for (int i = 0; i < 4; i++) {
            double a = p.getRandom().nextDouble() * Math.PI * 2;
            level.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, fx, p.getY() + 0.1, fz,
                    Math.cos(a) * 0.04, 0.01, Math.sin(a) * 0.04);
        }
    }

    /** Крен корпуса в повороте (градусы), плавно между тиками. */
    public static float bank(net.minecraft.world.entity.Entity e, float partial) {
        State s = STATES.get(e.getId());
        return s == null ? 0 : Mth.lerp(partial, s.bankO, s.bank);
    }

    /** Куда смотрит корпус костюма (градусы), плавно между тиками. */
    public static float bodyYaw(net.minecraft.world.entity.Entity e, float partial) {
        State s = STATES.get(e.getId());
        if (s == null || !s.init) return e instanceof net.minecraft.world.entity.LivingEntity le ? le.yBodyRot : e.getYRot();
        return Mth.rotLerp(partial, s.bodyYawO, s.bodyYaw);
    }

    /** При отрисовке: подставляет поворот корпуса и головы (плавно между тиками) и возвращает походку. */
    public static Gait apply(Avatar entity, AvatarRenderState state, float partialTicks) {
        State s = STATES.get(entity.getId());
        if (s == null || !s.init) return new Gait(0, 0, 0, 0);
        float headYaw = Mth.rotLerp(partialTicks, entity.yHeadRotO, entity.getYHeadRot());
        float body = Mth.rotLerp(partialTicks, s.bodyYawO, s.bodyYaw);
        state.bodyRot = body;
        state.yRot = Mth.clamp(Mth.wrapDegrees(headYaw - body), -HEAD_LIMIT, HEAD_LIMIT);
        return new Gait(Mth.lerp(partialTicks, s.walkTimeO, s.walkTime), Mth.lerp(partialTicks, s.weightO, s.weight),
                Mth.lerp(partialTicks, s.strafeTimeO, s.strafeTime), Mth.lerp(partialTicks, s.strafeWeightO, s.strafeWeight));
    }
}
