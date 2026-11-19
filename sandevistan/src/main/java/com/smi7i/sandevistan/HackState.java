package com.smi7i.sandevistan;

import net.minecraft.world.entity.player.Player;

/** Взломан ли игрок: пока идёт время взлома, все его импланты (Сандевистан, руки, нейро интерфейс, чипы) отключены. */
public final class HackState {
    private HackState() {}

    public static boolean isHacked(Player player) {
        return player.level().getGameTime() < player.getAttachedOrCreate(ModAttachments.HACKED_UNTIL);
    }

    /** Сколько тиков ещё отключены импланты (0, если не взломан). */
    public static int remainingTicks(Player player) {
        long left = player.getAttachedOrCreate(ModAttachments.HACKED_UNTIL) - player.level().getGameTime();
        return left > 0 ? (int) left : 0;
    }
}
