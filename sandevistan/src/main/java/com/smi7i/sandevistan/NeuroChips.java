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
        if (isHackChip(chip)) return HACK;
        return NONE;
    }

    /** Любой из чипов «Взлом»: базовый, Мк 1 или Мк 2. */
    public static boolean isHackChip(ItemStack chip) {
        return chip.is(ModItems.CHIP_HACK) || chip.is(ModItems.CHIP_HACK_1) || chip.is(ModItems.CHIP_HACK_2);
    }

    /** Уровень чипа «Взлом» у игрока: 0 — базовый, 1 — Мк 1, 2 — Мк 2; -1 — чипа взлома нет. */
    public static int hackTier(Player player) {
        ItemStack chip = player.getAttachedOrCreate(ModAttachments.CHIP);
        if (chip.is(ModItems.CHIP_HACK_2)) return 2;
        if (chip.is(ModItems.CHIP_HACK_1)) return 1;
        return chip.is(ModItems.CHIP_HACK) ? 0 : -1;
    }
}
