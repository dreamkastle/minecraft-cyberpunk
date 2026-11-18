package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.SandevistanManager;

/** Состояние импланта на стороне клиента (для цвета экрана, FOV, послеобразов, эффектов перегрева). */
public class ClientSandevistanState {
    public static final int HOLD_TICKS = 40;
    public static final float FOV_BOOST = 5.0F;

    public static boolean active;
    public static int ticksLeft;
    public static int streak;
    /** Сколько тиков ещё длится перегрев (приходит с сервера, дальше считается здесь). 0 = нет перегрева. */
    public static int overheatTicks;
    /** Сколько тиков ещё длится приступ киберпсихоза. 0 = нет. */
    public static int psychosisTicks;
    /** Сколько тиков игрок уже держит V (0..HOLD_TICKS). */
    public static int holdTicks;

    /** Плавная сила зелёного оттенка 0..1 (растёт при зажатии V, максимум при работе). */
    public static float tint;
    /** Сила зелёного на прошлом тике (для плавности между кадрами). */
    private static float prevTint;
    /** Плавная сила перегрева 0..1: красный экран, качание, глитчи. */
    public static float overheat;
    private static float prevOverheat;
    /** Плавный множитель FOV 0..1 (1 пока имплант работает). */
    public static float fovFade;

    /** Заряд для шкалы 0..1: убывает за 10 секунд работы, потом восстанавливается за время остывания. */
    public static float energy = 1.0F;
    private static float prevEnergy = 1.0F;
    private static float refillStart;
    private static int refillLeft, refillTotal;
    private static boolean prevActive, prevPsychosis;

    /** Свечение импланта в обычном состоянии (слабое), 0..1. */
    public static final float IDLE_GLOW = 0.18F;
    /** Сила свечения импланта на спине 0..1. */
    public static float glow = IDLE_GLOW;
    private static float prevGlow = IDLE_GLOW;

    /** Скорость исчезания зелёного после выключения или если V отпустили раньше времени (доля за тик). */
    public static final float TINT_OUT_SPEED = 0.06F;
    /** Как быстро накатывает перегрев (доля за тик). */
    public static final float OVERHEAT_IN_SPEED = 0.25F;
    /** За сколько тиков до конца перегрева эффекты начинают плавно уходить (60 = 3 секунды). */
    public static final int OVERHEAT_FADE_TICKS = 60;

    /** Сколько тиков ещё ждать до «Дефрагментации памяти» (очистки одной ячейки киберпсихоза). */
    public static int defragTicks;

    public static void update(boolean active, int ticksLeft, int streak, int overheatTicks, int psychosisTicks,
                              int defragTicks) {
        ClientSandevistanState.active = active;
        ClientSandevistanState.ticksLeft = ticksLeft;
        ClientSandevistanState.streak = streak;
        ClientSandevistanState.overheatTicks = overheatTicks;
        ClientSandevistanState.psychosisTicks = psychosisTicks;
        ClientSandevistanState.defragTicks = defragTicks;
    }

    /** Можно ли сейчас пройти «Дефрагментацию памяти» и очистить ячейку. */
    public static boolean defragReady() {
        return streak > 0 && defragTicks <= 0 && !active && !psychosis();
    }

    /** Идёт ли сейчас приступ киберпсихоза. */
    public static boolean psychosis() {
        return psychosisTicks > 0;
    }

    public static void reset() {
        update(false, 0, 0, 0, 0, 0);
        holdTicks = 0;
        tint = 0.0F;
        prevTint = 0.0F;
        overheat = 0.0F;
        prevOverheat = 0.0F;
        fovFade = 0.0F;
        energy = prevEnergy = 1.0F;
        refillLeft = refillTotal = 0;
        prevActive = prevPsychosis = false;
        glow = prevGlow = IDLE_GLOW;
    }

    /** 0.0 .. 1.0, прогресс зажатия клавиши. */
    public static float chargeProgress() {
        return Math.min(1.0F, holdTicks / (float) HOLD_TICKS);
    }

    /** Вызывается каждый клиентский тик. */
    public static void tickVisuals() {
        prevTint = tint;
        if (active) {
            tint = 1.0F;
        } else if (holdTicks > 0) {
            // Зелёный растёт вместе с зажатием V: к моменту включения уже полный.
            tint = Math.max(tint, chargeProgress());
        } else {
            tint -= tint * TINT_OUT_SPEED;
            if (tint < 0.002F) tint = 0.0F;
        }

        prevOverheat = overheat;
        if (overheatTicks > 0) overheatTicks--;
        if (psychosisTicks > 0) psychosisTicks--;
        // На паузе (Esc в одиночной игре) сервер стоит — и наш отсчёт тоже стоит, иначе они разойдутся.
        if (!active && streak > 0 && defragTicks > 0 && !net.minecraft.client.Minecraft.getInstance().isPaused()) defragTicks--;
        // Быстро накатывает, держится, в последние OVERHEAT_FADE_TICKS плавно уходит. Во время приступа — полный красный.
        float overheatTarget = psychosis() ? 1.0F : Math.min(1.0F, overheatTicks / (float) OVERHEAT_FADE_TICKS);
        overheat += (overheatTarget - overheat) * (overheatTarget > overheat ? OVERHEAT_IN_SPEED : 0.1F);
        if (overheat < 0.002F) overheat = 0.0F;

        float fovTarget = active ? 1.0F : 0.0F;
        fovFade += (fovTarget - fovFade) * 0.2F;
        if (fovFade < 0.001F) fovFade = 0.0F;

        tickEnergy();

        prevGlow = glow;
        float glowTarget = (active || psychosis()) ? 1.0F
                : holdTicks > 0 ? IDLE_GLOW + (1.0F - IDLE_GLOW) * chargeProgress()
                : IDLE_GLOW;
        glow += (glowTarget - glow) * (glowTarget > glow ? 0.3F : 0.08F);
    }

    private static void tickEnergy() {
        prevEnergy = energy;
        if (active && ticksLeft > 0) ticksLeft--;

        boolean psy = psychosis();
        if (psy && !prevPsychosis) {
            // Приступ: заряд выжжен до конца приступа и перегрева после него.
            refillStart = 0.0F;
            refillTotal = refillLeft = psychosisTicks + SandevistanManager.OVERHEAT_TICKS;
        } else if (prevActive && !active && !psy) {
            // Выключился: восстанавливается за время остывания (перегрев — дольше).
            refillStart = energy;
            refillTotal = refillLeft = overheatTicks > 0 ? overheatTicks : SandevistanManager.MANUAL_COOLDOWN;
        }
        prevActive = active;
        prevPsychosis = psy;

        if (active) {
            energy = Math.max(0.0F, ticksLeft / (float) SandevistanManager.DURATION);
        } else if (psy) {
            energy = 0.0F;
            if (refillLeft > 0) refillLeft--;
        } else if (refillLeft > 0) {
            refillLeft--;
            energy = refillStart + (1.0F - refillStart) * (1.0F - refillLeft / (float) Math.max(1, refillTotal));
        } else {
            energy = 1.0F;
        }
    }

    /** Заряд для шкалы в этом кадре 0..1. */
    public static float energyForFrame(float partialTick) {
        return prevEnergy + (energy - prevEnergy) * partialTick;
    }

    /** Сколько секунд ещё остывать. */
    public static float cooldownSecondsLeft() {
        return refillLeft / 20.0F;
    }

    /** Насколько остыл 0..1 (1 — готов). */
    public static float cooldownProgress() {
        return refillTotal <= 0 ? 1.0F : 1.0F - refillLeft / (float) refillTotal;
    }

    /** Остывает ли имплант (шкала восстанавливается). */
    public static boolean recharging() {
        return !active && (refillLeft > 0 || psychosis());
    }

    /** Сила свечения импланта в этом кадре 0..1. */
    public static float glowForFrame(float partialTick) {
        return prevGlow + (glow - prevGlow) * partialTick;
    }

    /** Сила зелёного для текущего кадра 0..1, плавно между тиками. partialTick — доля между тиками. */
    public static float tintForFrame(float partialTick) {
        // Сила из настроек: 0 — без фильтра, 1.5 — сильнее обычного.
        return smooth(prevTint + (tint - prevTint) * partialTick) * SandevistanConfig.get().filterStrength;
    }

    /** Сила перегрева для текущего кадра 0..1. */
    public static float overheatForFrame(float partialTick) {
        return smooth(prevOverheat + (overheat - prevOverheat) * partialTick);
    }

    /** Насколько «свежий» перегрев: 1 сразу после него, 0 к концу. Чем свежее, тем сильнее качает и глючит. */
    public static float overheatFreshness() {
        return Math.min(1.0F, overheatTicks / (float) SandevistanManager.OVERHEAT_TICKS);
    }

    /** Есть ли сейчас хоть какой-то цветной фильтр (зелёный или красный). */
    public static boolean anyFilter(float partialTick) {
        return tintForFrame(partialTick) > 0.002F || overheatForFrame(partialTick) > 0.002F;
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t); // мягкий старт и мягкое окончание
    }

    public static float fovBoost() {
        return FOV_BOOST * fovFade;
    }
}
