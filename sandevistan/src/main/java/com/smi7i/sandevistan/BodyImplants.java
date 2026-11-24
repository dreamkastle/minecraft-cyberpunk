package com.smi7i.sandevistan;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Подкожная броня (имплант тела): три уровня, Mk.I / Mk.II / Mk.III дают 3 / 4 / 5 единиц брони.
 * Бонус добавляется к броне игрока, пока имплант надет в слот тела; взломанный игрок остаётся без него.
 */
public final class BodyImplants {
    private static final Identifier ARMOR_ID = Identifier.fromNamespaceAndPath("sandevistan", "body_implant_armor");

    private BodyImplants() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) update(player);
        });
    }

    /** Сколько единиц брони даёт предмет (0 — это не имплант брони). */
    public static int armorFor(ItemStack stack) {
        if (stack.is(ModItems.ARMOR_IMPLANT_1)) return 3;
        if (stack.is(ModItems.ARMOR_IMPLANT_2)) return 4;
        if (stack.is(ModItems.ARMOR_IMPLANT_3)) return 5;
        return 0;
    }

    /** Сколько брони даёт игроку надетый имплант сейчас. */
    public static int armorOf(Player player) {
        if (HackState.isHacked(player)) return 0;
        return armorFor(player.getAttachedOrCreate(ModAttachments.BODY_IMPLANT));
    }

    private static void update(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor == null) return;
        int want = armorOf(player);
        AttributeModifier current = armor.getModifier(ARMOR_ID);
        if (want == 0) {
            if (current != null) armor.removeModifier(ARMOR_ID);
            return;
        }
        if (current != null && current.amount() == want) return;
        armor.removeModifier(ARMOR_ID);
        armor.addTransientModifier(new AttributeModifier(ARMOR_ID, want, AttributeModifier.Operation.ADD_VALUE));
    }
}
