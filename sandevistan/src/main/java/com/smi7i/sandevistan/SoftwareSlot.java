package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Слот программного обеспечения («П.О», связан с головой): принимает предметы из тега sandevistan:software, по одному. */
public class SoftwareSlot extends Slot {
    /** Номер слота в меню инвентаря игрока (после слота тела 51). */
    public static final int MENU_INDEX = 52;
    /** Место слота на вкладке инвентаря в творческом режиме (под слотом тела). */
    public static final int CREATIVE_X = 145;
    public static final int CREATIVE_Y = 38;

    public static final TagKey<Item> SOFTWARE =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "software"));

    public SoftwareSlot(Container container, int x, int y) {
        super(container, 0, x, y);
    }

    public static boolean isSoftware(ItemStack stack) {
        return stack.is(SOFTWARE);
    }

    /** Уровень Л.Е.Д в стопке: 1..3, 0 — это не Л.Е.Д. */
    public static int ledLevel(ItemStack stack) {
        if (stack.is(ModItems.LED_1)) return 1;
        if (stack.is(ModItems.LED_2)) return 2;
        if (stack.is(ModItems.LED_3)) return 3;
        return 0;
    }

    /** Уровень Л.Е.Д, установленного у игрока в слоте П.О (0 — нет). */
    public static int ledLevel(Player player) {
        return ledLevel(player.getAttachedOrCreate(ModAttachments.SOFTWARE));
    }

    @Override public boolean mayPlace(ItemStack stack) { return isSoftware(stack); }
    @Override public int getMaxStackSize() { return 1; }
}
