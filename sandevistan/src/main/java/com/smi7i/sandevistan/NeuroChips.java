package com.smi7i.sandevistan;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Какой чип сейчас работает у игрока. Чип действует, только если в слоте головы надет нейро интерфейс. */
public final class NeuroChips {
    public static final int NONE = 0;
    /** Чип 1 «Овертайм». */
    public static final int OVERTIME = 1;
    /** Чип 2 «Взлом». */
    public static final int HACK = 2;

    private NeuroChips() {}

    public static int activeChip(Player player) {
        if (!HeadImplantSlot.hasNeuroInterface(player)) return NONE;
        ItemStack chip = player.getAttachedOrCreate(ModAttachments.CHIP);
        if (chip.is(ModItems.CHIP_OVERTIME)) return OVERTIME;
        if (chip.is(ModItems.CHIP_HACK)) return HACK;
        return NONE;
    }
}
