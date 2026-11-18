package com.smi7i.sandevistan;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * Импланты рук. Хотя бы один в слоте: удар рукой (ближний бой) получает +2 сердца урона (+4), и этот бонус
 * добавляется ДО брони цели — броня его уменьшает. Оба слота заняты: +10% к скорости атаки и добычи.
 * Бонус урона от двух имплантов не складывается.
 */
public final class HandImplants {
    /** Бонус урона удара: 2 сердца = 4 здоровья. */
    public static final float BONUS_DAMAGE = 4.0F;
    /** Прибавка скорости атаки и добычи при двух имплантах: +10%. */
    public static final double BOTH_SPEED_BONUS = 0.10;

    private static final Identifier ATTACK_SPEED_ID = Identifier.fromNamespaceAndPath("sandevistan", "hand_implants_attack_speed");
    private static final Identifier MINING_ID = Identifier.fromNamespaceAndPath("sandevistan", "hand_implants_mining");

    private HandImplants() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                update(player);
            }
        });
    }

    /** Сколько слотов рук занято имплантами (0..2). */
    public static int count(Player player) {
        int n = 0;
        if (HandImplantSlot.isHandImplant(player.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_1))) n++;
        if (HandImplantSlot.isHandImplant(player.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_2))) n++;
        return n;
    }

    public static boolean hasAny(Player player) {
        return count(player) > 0;
    }

    private static void update(ServerPlayer player) {
        boolean both = count(player) >= 2;
        setModifier(player.getAttribute(Attributes.ATTACK_SPEED), ATTACK_SPEED_ID, both);
        setModifier(player.getAttribute(Attributes.BLOCK_BREAK_SPEED), MINING_ID, both);
    }

    private static void setModifier(AttributeInstance attribute, Identifier id, boolean on) {
        if (attribute == null) return;
        boolean present = attribute.getModifier(id) != null;
        if (on && !present) {
            attribute.addTransientModifier(new AttributeModifier(id, BOTH_SPEED_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!on && present) {
            attribute.removeModifier(id);
        }
    }
}
