package com.smi7i.sandevistan;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Клинки богомола: левый и правый предметы в слотах рук. По клавише M их достают (сколько клинков надето — столько
 * и достаётся). Пока клинки достаны, удар наносит минимум 6.5 урона (с одним надетым клинком — 4); убранные клинки бонусов не дают. Каждое
 * доставание добавляет 0.1 к шкале киберпсихоза за каждый клинок.
 *
 * Пока клинки достаны: выбранный слот хотбара заблокирован (его нельзя сменить, положить в него или забрать из него
 * предмет, предмет в нём нельзя использовать — см. InventoryBladeLockMixin, AbstractContainerMenuBladeLockMixin,
 * ItemStackBladeUseMixin), а скорость атаки игрока равна ATTACK_SPEED.
 */
public final class MantisBlades {
    /** Урон удара достанными клинками (броня цели учитывается). */
    /** Урон удара, когда надеты оба клинка. */
    public static final float DAMAGE = 6.5F;
    /** Урон удара, когда надет только один клинок. */
    public static final float SINGLE_BLADE_DAMAGE = 4.0F;
    /** Прибавка к шкале киберпсихоза за каждый клинок при доставании: 1 десятая (десять десятых — одна полоска). */
    public static final int LOAD_TENTHS_PER_BLADE = 1;
    /** Скорость атаки игрока, пока клинки достаны (у пустой руки обычно 4.0). */
    public static final double ATTACK_SPEED = 2.2;
    private static final Identifier ATTACK_SPEED_ID = Identifier.fromNamespaceAndPath("sandevistan", "mantis_blade_attack_speed");

    /** Звук взмаха не чаще, чем раз в 5 тиков (защита от спама пакетами). */
    private static final long MISS_SOUND_GAP = 5;
    private static final Map<UUID, Long> LAST_MISS = new HashMap<>();

    private MantisBlades() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) tickAttackSpeed(player);
        });
        ServerPlayNetworking.registerGlobalReceiver(BladeSwingPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isExtended(player)) return;
            long now = player.level().getGameTime();
            Long last = LAST_MISS.get(player.getUUID());
            if (last != null && now - last < MISS_SOUND_GAP) return;
            LAST_MISS.put(player.getUUID(), now);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.BLADE_MISS, SoundSource.PLAYERS, 1.0F, 0.95F + player.getRandom().nextFloat() * 0.1F);
        });
    }

    public static boolean hasLeft(Player p) {
        return p.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_1).is(ModItems.BLADE_LEFT);
    }

    public static boolean hasRight(Player p) {
        return p.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_2).is(ModItems.BLADE_RIGHT);
    }

    /** Сколько клинков надето и работает (0..2); взломанный игрок остаётся без клинков. */
    public static int count(Player p) {
        if (HackState.isHacked(p)) return 0;
        return (hasLeft(p) ? 1 : 0) + (hasRight(p) ? 1 : 0);
    }

    /** Минимальный урон удара: оба клинка — полный, один — 4. */
    public static float damageFor(Player p) {
        return count(p) >= 2 ? DAMAGE : SINGLE_BLADE_DAMAGE;
    }

    /** Достаны ли клинки сейчас (есть хотя бы один надетый и включён флаг). */
    public static boolean isExtended(Player p) {
        return p.getAttachedOrCreate(ModAttachments.BLADES_OUT) && count(p) > 0;
    }

    /**
     * Пока клинки достаны, итоговая скорость атаки (до множителей) ровно ATTACK_SPEED, чем бы ни была занята рука:
     * у предмета в руке свои поправки скорости — модификатор подстраивается под них. Убрали клинки — модификатор снят.
     */
    private static void tickAttackSpeed(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_SPEED);
        if (attr == null) return;
        AttributeModifier current = attr.getModifier(ATTACK_SPEED_ID);
        if (!isExtended(player)) {
            if (current != null) attr.removeModifier(ATTACK_SPEED_ID);
            return;
        }
        double additive = attr.getBaseValue();
        for (AttributeModifier m : attr.getModifiers()) {
            if (m.operation() == AttributeModifier.Operation.ADD_VALUE && !m.id().equals(ATTACK_SPEED_ID)) {
                additive += m.amount();
            }
        }
        double wanted = ATTACK_SPEED - additive;
        if (current != null && Math.abs(current.amount() - wanted) < 1.0E-6) return;
        attr.removeModifier(ATTACK_SPEED_ID);
        attr.addTransientModifier(new AttributeModifier(ATTACK_SPEED_ID, wanted, AttributeModifier.Operation.ADD_VALUE));
    }
}
