package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ImplantSlot extends Slot {
    /** Номер слота импланта в меню инвентаря игрока (после 46 стандартных: 0..45). */
    public static final int MENU_INDEX = 46;
    /** Место слота на вкладке инвентаря в творческом режиме (справа от брони). */
    public static final int CREATIVE_X = 127;
    public static final int CREATIVE_Y = 20;

    public static final TagKey<Item> IMPLANTS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "implants"));

    public ImplantSlot(Container container, int x, int y) {
        super(container, 0, x, y);
    }

    public static boolean isImplant(ItemStack stack) {
        return stack.is(IMPLANTS);
    }

    @Override public boolean mayPlace(ItemStack stack) { return isImplant(stack); }
    @Override public int getMaxStackSize() { return 1; }
}
