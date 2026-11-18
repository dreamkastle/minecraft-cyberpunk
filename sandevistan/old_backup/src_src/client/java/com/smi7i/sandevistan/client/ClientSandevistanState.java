package com.smi7i.sandevistan.client;

/** Состояние импланта на стороне клиента (для HUD, цвета экрана, FOV, послеобразов). */
public class ClientSandevistanState {
    public static final int HOLD_TICKS = 40;

    public static boolean active;
    public static int ticksLeft;
    public static int streak;
    /** Сколько тиков игрок уже держит V (0..HOLD_TICKS). */
    public static int holdTicks;

    public static void update(boolean active, int ticksLeft, int streak) {
        ClientSandevistanState.active = active;
        ClientSandevistanState.ticksLeft = ticksLeft;
        ClientSandevistanState.streak = streak;
    }

    public static void reset() {
        update(false, 0, 0);
        holdTicks = 0;
    }

    /** 0.0 .. 1.0, прогресс зажатия клавиши. */
    public static float chargeProgress() {
        return Math.min(1.0F, holdTicks / (float) HOLD_TICKS);
    }
}
