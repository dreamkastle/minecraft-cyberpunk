package com.smi7i.sandevistan;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Клинки богомола: левый и правый предметы в слотах рук. По клавише M их достают (сколько клинков надето — столько
 * и достаётся). Пока клинки достаны, удар наносит минимум 6.5 урона; убранные клинки бонусов не дают. Каждое
 * доставание добавляет 0.1 к шкале киберпсихоза за каждый клинок.
 */
public final class MantisBlades {
    /** Урон удара достанными клинками (броня цели учитывается). */
    public static final float DAMAGE = 6.5F;
    /** Прибавка к шкале киберпсихоза за каждый клинок при доставании: 1 десятая (десять десятых — одна полоска). */
    public static final int LOAD_TENTHS_PER_BLADE = 1;

    /** Звук взмаха не чаще, чем раз в 5 тиков (защита от спама пакетами). */
    private static final long MISS_SOUND_GAP = 5;
    private static final Map<UUID, Long> LAST_MISS = new HashMap<>();

    private MantisBlades() {}

    public static void init() {
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

    /** Достаны ли клинки сейчас (есть хотя бы один надетый и включён флаг). */
    public static boolean isExtended(Player p) {
        return p.getAttachedOrCreate(ModAttachments.BLADES_OUT) && count(p) > 0;
    }
}
