package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Слот чипа нейро интерфейса: принимает только предметы из тега sandevistan:neuro_chips, по одному. */
public class NeuroChipSlot extends Slot {
    /** Номер слота в меню инвентаря игрока (после слота головы 49). */
    public static final int MENU_INDEX = 50;
    /** Место слота на вкладке инвентаря в творческом режиме (в столбике под слотом головы). */
    public static final int CREATIVE_X = 127;
    public static final int CREATIVE_Y = 92;

    public static final TagKey<Item> NEURO_CHIPS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "neuro_chips"));

    public NeuroChipSlot(Container container, int x, int y) {
        super(container, 0, x, y);
    }

    public static boolean isChip(ItemStack stack) {
        return stack.is(NEURO_CHIPS);
    }

    @Override public boolean mayPlace(ItemStack stack) { return isChip(stack); }
    @Override public int getMaxStackSize() { return 1; }
}
