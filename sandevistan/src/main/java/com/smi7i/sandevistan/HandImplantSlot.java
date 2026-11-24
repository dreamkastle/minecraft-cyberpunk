package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Слот импланта руки (левый или правый): принимает «Имплант руки» (тег sandevistan:hand_implants) и клинок богомола
 * только своей стороны, по одному.
 */
public class HandImplantSlot extends Slot {
    /** Номера двух слотов в меню инвентаря игрока (после слота Сандевистана 46). */
    public static final int MENU_INDEX_1 = 47; // левая рука
    public static final int MENU_INDEX_2 = 48; // правая рука
    /** Места слотов на вкладке инвентаря в творческом режиме (в столбик под слотом Сандевистана). */
    public static final int CREATIVE_X = 127;
    public static final int CREATIVE_Y_1 = 38;
    public static final int CREATIVE_Y_2 = 56;

    public static final TagKey<Item> HAND_IMPLANTS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "hand_implants"));

    private final boolean left;

    public HandImplantSlot(Container container, int x, int y, boolean left) {
        super(container, 0, x, y);
        this.left = left;
    }

    public static boolean isHandImplant(ItemStack stack) {
        return stack.is(HAND_IMPLANTS);
    }

    /** Любой предмет, который вообще можно поставить в слот руки (имплант руки или клинок любой стороны). */
    public static boolean isAnyHandItem(ItemStack stack) {
        return isHandImplant(stack) || stack.is(ModItems.BLADE_LEFT) || stack.is(ModItems.BLADE_RIGHT);
    }

    /** Можно ли поставить предмет в слот меню с таким номером (47 — левый, 48 — правый). */
    public static boolean accepts(int menuIndex, ItemStack stack) {
        if (isHandImplant(stack)) return true;
        return menuIndex == MENU_INDEX_1 ? stack.is(ModItems.BLADE_LEFT) : stack.is(ModItems.BLADE_RIGHT);
    }

    @Override public boolean mayPlace(ItemStack stack) {
        return isHandImplant(stack) || (left ? stack.is(ModItems.BLADE_LEFT) : stack.is(ModItems.BLADE_RIGHT));
    }
    @Override public int getMaxStackSize() { return 1; }
}
