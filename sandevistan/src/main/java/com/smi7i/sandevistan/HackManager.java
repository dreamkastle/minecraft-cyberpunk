package com.smi7i.sandevistan;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Чип «Взлом»: список игроков с нейро интерфейсом в радиусе 500 блоков, попытка взлома мини-игрой на клиенте
 * и результат на сервере. Успех отключает импланты цели на 40 секунд, провал присылает цели предупреждение.
 */
public final class HackManager {
    public static final int BIT_SANDEVISTAN = 1;
    public static final int BIT_HAND_LEFT = 2;
    public static final int BIT_HAND_RIGHT = 4;
    public static final int BIT_NEURO = 8;
    public static final int BIT_CHIP_OVERTIME = 16;
    public static final int BIT_CHIP_HACK = 32;

    public static final double RADIUS = 500.0;
    /** Сколько тиков отключены импланты после успешного взлома: 40 секунд. */
    public static final int DISABLE_TICKS = 800;
    /** Сколько у игрока есть на мини-игру, прежде чем попытка считается брошенной (3 минуты). */
    private static final long PENDING_TICKS = 20L * 180;

    private record Pending(String target, long expires) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private HackManager() {}

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(HackRequestPayload.TYPE, (payload, context) -> request(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HackStartPayload.TYPE, (payload, context) -> start(context.player(), payload.target()));
        ServerPlayNetworking.registerGlobalReceiver(HackResultPayload.TYPE, (payload, context) -> result(context.player(), payload.success()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
    }

    /** Установленные импланты игрока в виде маски битов (для списка целей). */
    public static int implantMask(ServerPlayer p) {
        int mask = 0;
        if (ImplantSlot.isImplant(p.getAttachedOrCreate(ModAttachments.IMPLANT))) mask |= BIT_SANDEVISTAN;
        if (HandImplantSlot.isHandImplant(p.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_1))) mask |= BIT_HAND_LEFT;
        if (HandImplantSlot.isHandImplant(p.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_2))) mask |= BIT_HAND_RIGHT;
        if (HeadImplantSlot.hasNeuroInterfaceRaw(p)) mask |= BIT_NEURO;
        ItemStack chip = p.getAttachedOrCreate(ModAttachments.CHIP);
        if (chip.is(ModItems.CHIP_OVERTIME)) mask |= BIT_CHIP_OVERTIME;
        if (chip.is(ModItems.CHIP_HACK)) mask |= BIT_CHIP_HACK;
        return mask;
    }

    private static boolean isValidTarget(ServerPlayer hacker, ServerPlayer target) {
        return target != null && target != hacker && target.isAlive()
                && target.level() == hacker.level()
                && target.distanceTo(hacker) <= RADIUS
                && HeadImplantSlot.hasNeuroInterfaceRaw(target);
    }

    private static void request(ServerPlayer player) {
        if (!SandevistanManager.canHack(player)) return;
        MinecraftServer server = player.level().getServer();
        StringBuilder data = new StringBuilder();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (!isValidTarget(player, other)) continue;
            if (data.length() > 0) data.append('\n');
            data.append(other.getName().getString()).append('\t').append(implantMask(other));
        }
        ServerPlayNetworking.send(player, new HackListPayload(data.toString()));
    }

    /** Игрок выбрал цель: с этого момента идёт кулдаун чипа, а мини-игра ждёт результата. */
    private static void start(ServerPlayer player, String targetName) {
        if (!SandevistanManager.canHack(player)) return;
        ServerPlayer target = player.level().getServer().getPlayerList().getPlayerByName(targetName);
        if (!isValidTarget(player, target)) return;
        SandevistanManager.startHackCooldown(player);
        PENDING.put(player.getUUID(), new Pending(targetName, player.level().getGameTime() + PENDING_TICKS));
    }

    private static void result(ServerPlayer player, boolean success) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending == null || !player.isAlive()) return;
        if (player.level().getGameTime() > pending.expires()) return;
        ServerPlayer target = player.level().getServer().getPlayerList().getPlayerByName(pending.target());
        if (target == null) return;
        if (success) {
            target.setAttached(ModAttachments.HACKED_UNTIL, target.level().getGameTime() + DISABLE_TICKS);
        } else {
            ServerPlayNetworking.send(target, new HackWarningPayload()); // только предупреждение, без ника взломщика
        }
    }
}
