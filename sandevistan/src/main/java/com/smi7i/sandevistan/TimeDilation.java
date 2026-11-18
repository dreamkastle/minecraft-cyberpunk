package com.smi7i.sandevistan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Зона Сандевистана: всё, что ближе ZONE_RADIUS блоков к игроку с работающим имплантом (мобы, стрелы,
 * TNT, предметы), живёт в SLOWDOWN раз медленнее — делает один «шаг» раз в SLOWDOWN тиков.
 * Дальше зоны мир живёт как обычно. Игроков это не касается (их «замирание» — PlayerFreeze).
 * Работает и в одиночной игре, и на сервере. Само «пропускание тиков» — TickRateManagerMixin
 * (подменяет TickRateManager.isEntityFrozen); на клиенте те же правила считает ClientZones.
 */
public final class TimeDilation {
    /** Во сколько раз замедляется мир в зоне. */
    public static final int SLOWDOWN = 20;
    /** Радиус зоны в блоках. */
    public static final double ZONE_RADIUS = 30.0;

    /** Клиентская проверка (заполняет клиент): заморожена ли сущность прямо сейчас. */
    public static Predicate<Entity> clientFrozen = e -> false;
    /** Клиентская проверка: в зоне ли сущность (без учёта шага). */
    public static Predicate<Entity> clientInZone = e -> false;

    private static final List<ServerPlayer> USERS = new ArrayList<>();
    private static List<Integer> lastActive = List.of(), lastPsychosis = List.of();
    private static long counter;
    private static boolean stepTick = true;

    private TimeDilation() {}

    public static void init() {
        // В замедленной зоне неуязвимость моба после удара растянулась бы в 20 раз —
        // сбрасываем её, чтобы каждый удар игрока проходил.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (source.getEntity() instanceof Player && inServerZone(entity)) {
                entity.invulnerableTime = 0;
            }
            return true;
        });
        // Новому игроку — кто сейчас в Сандевистане.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sender.sendPacket(new ActiveUsersPayload(lastActive, lastPsychosis)));
    }

    /**
     * Вызывается каждый тик сервера со списком игроков, у которых работает Сандевистан (и с приступом).
     */
    public static void tick(MinecraftServer server, List<ServerPlayer> active, List<ServerPlayer> psychosis) {
        USERS.clear();
        // Приступ киберпсихоза зону НЕ создаёт: мир вокруг идёт обычно, другие игроки не замирают.
        for (ServerPlayer p : active) if (!psychosis.contains(p)) USERS.add(p);
        counter++;
        stepTick = USERS.isEmpty() || counter % SLOWDOWN == 0;

        updateFrozenPlayers(server);

        List<Integer> activeIds = active.stream().map(Entity::getId).toList();
        List<Integer> psychosisIds = psychosis.stream().map(Entity::getId).toList();
        if (!activeIds.equals(lastActive) || !psychosisIds.equals(lastPsychosis)) {
            lastActive = activeIds;
            lastPsychosis = psychosisIds;
            ActiveUsersPayload payload = new ActiveUsersPayload(activeIds, psychosisIds);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** Игроки, которые сейчас «замерли» (без Сандевистана в чужой зоне). */
    private static final java.util.Set<java.util.UUID> FROZEN_PLAYERS = new java.util.HashSet<>();

    /**
     * Игрок без Сандевистана ближе ZONE_RADIUS к игроку, у которого он работает, «замирает»: его экран на паузе,
     * а когда Сандевистан у всех рядом выключится — он увидит всё произошедшее ускоренно (×10). Если Сандевистан
     * работает у обоих — оба видят всё как есть.
     */
    private static void updateFrozenPlayers(MinecraftServer server) {
        double r2 = ZONE_RADIUS * ZONE_RADIUS;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean frozen = false;
            if (!USERS.contains(p)) {
                for (ServerPlayer user : USERS) {
                    if (user.level() == p.level() && user.distanceToSqr(p) <= r2) {
                        frozen = true;
                        break;
                    }
                }
            }
            boolean was = FROZEN_PLAYERS.contains(p.getUUID());
            if (frozen != was) {
                if (frozen) FROZEN_PLAYERS.add(p.getUUID()); else FROZEN_PLAYERS.remove(p.getUUID());
                ServerPlayNetworking.send(p, new FreezePayload(frozen));
            }
        }
        FROZEN_PLAYERS.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** Заморожена ли сущность прямо сейчас (зона и не шаг). Игроков не касается. */
    public static boolean isZoneFrozen(Entity entity) {
        if (entity instanceof Player || entity.countPlayerPassengers() > 0) return false;
        if (entity.level().isClientSide()) return clientFrozen.test(entity);
        return !stepTick && inServerZone(entity);
    }

    /** В зоне ли сущность (на сервере). */
    public static boolean inServerZone(Entity entity) {
        if (USERS.isEmpty() || entity.level().isClientSide()) return false;
        double r2 = ZONE_RADIUS * ZONE_RADIUS;
        for (ServerPlayer user : USERS) {
            if (user.level() == entity.level() && user.distanceToSqr(entity) <= r2) return true;
        }
        return false;
    }

    /** Список игроков с работающим Сандевистаном (на сервере). */
    public static List<ServerPlayer> users() {
        return USERS;
    }
}
