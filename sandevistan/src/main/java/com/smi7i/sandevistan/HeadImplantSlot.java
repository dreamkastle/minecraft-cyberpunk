package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Слот импланта головы: принимает только предметы из тега sandevistan:head_implants (нейро интерфейс), по одному. */
public class HeadImplantSlot extends Slot {
    /** Номер слота в меню инвентаря игрока (после слотов Сандевистана 46 и рук 47, 48). */
    public static final int MENU_INDEX = 49;
    /** Место слота на вкладке инвентаря в творческом режиме (в столбике под слотами рук). */
    public static final int CREATIVE_X = 127;
    public static final int CREATIVE_Y = 74;

    public static final TagKey<Item> HEAD_IMPLANTS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "head_implants"));

    public HeadImplantSlot(Container container, int x, int y) {
        super(container, 0, x, y);
    }

    public static boolean isHeadImplant(ItemStack stack) {
        return stack.is(HEAD_IMPLANTS);
    }

    /** Работает ли у игрока нейро интерфейс: надет в слоте головы и игрок не взломан. */
    public static boolean hasNeuroInterface(Player player) {
        return hasNeuroInterfaceRaw(player) && !HackState.isHacked(player);
    }

    /** Надет ли нейро интерфейс в слоте головы (даже если он сейчас отключён взломом). */
    public static boolean hasNeuroInterfaceRaw(Player player) {
        return isHeadImplant(player.getAttachedOrCreate(ModAttachments.HEAD_IMPLANT));
    }

    @Override public boolean mayPlace(ItemStack stack) { return isHeadImplant(stack); }
    @Override public int getMaxStackSize() { return 1; }
}
