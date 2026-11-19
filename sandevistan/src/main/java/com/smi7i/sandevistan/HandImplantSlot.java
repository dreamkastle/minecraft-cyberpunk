package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Слот импланта руки: принимает только предметы из тега sandevistan:hand_implants, по одному. */
public class HandImplantSlot extends Slot {
    /** Номера двух слотов в меню инвентаря игрока (после слота Сандевистана 46). */
    public static final int MENU_INDEX_1 = 47;
    public static final int MENU_INDEX_2 = 48;
    /** Места слотов на вкладке инвентаря в творческом режиме (в столбик под слотом Сандевистана). */
    public static final int CREATIVE_X = 127;
    public static final int CREATIVE_Y_1 = 38;
    public static final int CREATIVE_Y_2 = 56;

    public static final TagKey<Item> HAND_IMPLANTS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "hand_implants"));

    public HandImplantSlot(Container container, int x, int y) {
        super(container, 0, x, y);
    }

    public static boolean isHandImplant(ItemStack stack) {
        return stack.is(HAND_IMPLANTS);
    }

    @Override public boolean mayPlace(ItemStack stack) { return isHandImplant(stack); }
    @Override public int getMaxStackSize() { return 1; }
}
