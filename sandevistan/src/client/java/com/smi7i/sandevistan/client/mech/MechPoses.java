package com.smi7i.sandevistan.client.mech;

import net.minecraft.util.Mth;

/** Какую позу принимает костюм: стоящий (падение → стойка) и надетый на игрока (стойка ↔ ходьба по движению). */
public final class MechPoses {
    /** Масштаб модели: такой же, как у игрока (игра рисует игрока в 15/16 размера). */
    public static final float SCALE = 0.9375F / 16.0F;
    /**
     * Сколько блоков проходит костюм за один цикл ходьбы: опорная стопа уезжает назад на 4.5 + 6.5 = 11 пикселей
     * за 60% цикла, значит корпус за весь цикл проходит 11 / 0.6 ≈ 18.3 пикселя (× 15/16 — масштаб игрока).
     */
    public static final float STRIDE_BLOCKS = com.smi7i.sandevistan.mech.MechManager.STRIDE_BLOCKS;

    private MechPoses() {}

    /** Стоящий костюм: сначала падение с неба (spawn), потом стойка (idle). age — секунды с вызова. */
    public static MechModel.Pose standing(float age) {
        MechModel m = MechModel.get();
        MechModel.Anim spawn = m.anim("spawn");
        // Падение в анимации — 1 с; растягиваем его до FALL_SECONDS (медленнее, эпичнее), остальное — как есть.
        float animT = age < FALL_SECONDS ? age / FALL_SECONDS * SPAWN_TOUCH : age - FALL_SECONDS + SPAWN_TOUCH;
        if (spawn != null && animT < spawn.length()) return m.newPose().blend(spawn, animT, 1);
        float idleT = animT - (spawn != null ? spawn.length() : 0);
        return m.newPose().blend(m.anim("idle"), idleT, 1);
    }

    // ===== Посадка в костюм =====
    /** Длина посадки (секунды) — как MechManager.EQUIP_TICKS. */
    public static final float EQUIP_SECONDS = com.smi7i.sandevistan.mech.MechManager.EQUIP_TICKS / 20.0F;
    /** Момент глубокого приседа в анимации spawn и конец подъёма. */
    private static final float SPAWN_CROUCH = 1.6F, SPAWN_RISE_END = 3.7F;
    /** Пилот забирается с CLIMB_START до CLIMB_END секунд; костюм начинает вставать в RISE_START. */
    private static final float CLIMB_START = 0.25F, CLIMB_END = 1.25F, RISE_START = 1.1F;

    /**
     * Посадка: костюм приседает (как после приземления), пилот запрыгивает на своё место, костюм закрывается
     * и тяжело встаёт в стойку. t — секунды с ПКМ.
     */
    public static MechModel.Pose equip(float t, float ageTicks) {
        MechModel m = MechModel.get();
        MechModel.Anim spawn = m.anim("spawn");
        MechModel.Pose p = m.newPose().blend(m.anim("idle"), ageTicks / 20.0F, 1);
        if (spawn == null) return p;
        float spawnT;
        float w;
        if (t < RISE_START) {
            spawnT = SPAWN_CROUCH;
            w = smooth(t / 0.45F); // плавно приседает из стойки
        } else {
            float k = (t - RISE_START) / (EQUIP_SECONDS - RISE_START);
            spawnT = SPAWN_CROUCH + (SPAWN_RISE_END - SPAWN_CROUCH) * k;
            w = 1.0F;
        }
        return p.blend(spawn, spawnT, w);
    }

    /** Насколько пилот уже на месте (0..1) в момент посадки t. */
    public static float climb(float t) {
        return smooth((t - CLIMB_START) / (CLIMB_END - CLIMB_START));
    }

    /** Подскок пилота (пиксели) при забирании. */
    public static float climbHop(float k) {
        return k <= 0 || k >= 1 ? 0 : 5.0F * Mth.sin((float) Math.PI * k);
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Касание земли в анимации spawn (секунды) и сколько длится падение в игре. */
    public static final float SPAWN_TOUCH = 1.0F;
    public static final float FALL_SECONDS = com.smi7i.sandevistan.mech.MechSuitEntity.LAND_TICK / 20.0F;

    /** Костюм на игроке: стойка, а при движении — ходьба в родном темпе анимации (время шага — из MechMotion). */
    public static MechModel.Pose worn(float walkTime, float weight, float ageTicks, float attackTime) {
        return worn(walkTime, weight, 0, 0, ageTicks, attackTime);
    }

    /** То же + шаг вбок (strafe) со своим весом. */
    public static MechModel.Pose worn(float walkTime, float weight, float strafeTime, float strafeWeight, float ageTicks, float attackTime) {
        MechModel m = MechModel.get();
        MechModel.Pose p = m.newPose().blend(m.anim("idle"), ageTicks / 20.0F, 1);
        MechModel.Anim walk = m.anim("walk");
        float w = Mth.clamp(weight, 0, 1);
        if (walk != null && w > 0.001F) {
            p.blend(walk, walkTime, w);
        }
        // Диагональный шаг: вес > 0 — вперёд-вправо, < 0 — вперёд-влево.
        MechModel.Anim strafe = m.anim(strafeWeight >= 0 ? "strafe_r" : "strafe_l");
        float sw = Mth.clamp(Math.abs(strafeWeight), 0, 1);
        if (strafe != null && sw > 0.001F) p.blend(strafe, strafeTime, sw);
        // Атака: поверх всего; плавно входит и выходит (начинается и кончается позой стойки).
        MechModel.Anim attack = m.anim("attack");
        if (attack != null && attackTime >= 0 && attackTime < attack.length()) {
            float aw = Mth.clamp(Math.min(attackTime / 0.2F, (attack.length() - attackTime) / 0.3F), 0, 1);
            p.blend(attack, attackTime, aw);
        }
        return p;
    }
}
