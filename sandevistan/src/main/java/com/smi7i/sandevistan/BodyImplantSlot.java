package com.smi7i.sandevistan;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Слот импланта тела (подкожная броня): принимает только предметы из тега sandevistan:body_implants, по одному. */
public class BodyImplantSlot extends Slot {
    /** Номер слота в меню инвентаря игрока (после слота чипа 50). */
    public static final int MENU_INDEX = 51;
    /** Место слота на вкладке инвентаря в творческом режиме (справа от слота Сандевистана). */
    public static final int CREATIVE_X = 145;
    public static final int CREATIVE_Y = 20;

    public static final TagKey<Item> BODY_IMPLANTS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("sandevistan", "body_implants"));

    public BodyImplantSlot(Container container, int x, int y) {
        super(container, 0, x, y);
    }

    public static boolean isBodyImplant(ItemStack stack) {
        return stack.is(BODY_IMPLANTS);
    }

    /** Надет ли имплант тела (даже если он сейчас отключён взломом). */
    public static boolean hasBodyImplantRaw(Player player) {
        return isBodyImplant(player.getAttachedOrCreate(ModAttachments.BODY_IMPLANT));
    }

    @Override public boolean mayPlace(ItemStack stack) { return isBodyImplant(stack); }
    @Override public int getMaxStackSize() { return 1; }
}
