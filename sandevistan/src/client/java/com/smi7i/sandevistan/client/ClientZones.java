package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.ActiveUsersPayload;
import com.smi7i.sandevistan.TimeDilation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Зоны Сандевистана на клиенте: у кого из игроков сейчас работает имплант (приходит с сервера),
 * где их зоны (30 блоков) и когда «шаг» мира. Сущности в зоне клиент тикает раз в 20 тиков, как сервер.
 */
public final class ClientZones {
    private static final Set<Integer> ACTIVE = new HashSet<>();
    private static final Set<Integer> PSYCHOSIS = new HashSet<>();
    private static final List<Vec3> CENTERS = new ArrayList<>();
    private static long counter;
    private static boolean stepTick = true;

    private ClientZones() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ActiveUsersPayload.TYPE, (payload, context) -> {
            ACTIVE.clear();
            ACTIVE.addAll(payload.activeIds());
            PSYCHOSIS.clear();
            PSYCHOSIS.addAll(payload.psychosisIds());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ACTIVE.clear();
            PSYCHOSIS.clear();
            CENTERS.clear();
        });
        // START: центры зон и шаг считаем ДО тика сущностей, чтобы правила совпадали весь тик.
        ClientTickEvents.START_CLIENT_TICK.register(ClientZones::tick);
        TimeDilation.clientFrozen = e -> !stepTick && inZone(e);
        TimeDilation.clientInZone = ClientZones::inZone;
    }

    private static void tick(Minecraft mc) {
        CENTERS.clear();
        if (mc.level == null || mc.player == null) return;
        // Свой Сандевистан — сразу, не дожидаясь ответа сервера.
        // Приступ киберпсихоза зону не создаёт: мир вокруг идёт обычно.
        if (ClientSandevistanState.active && !ClientSandevistanState.psychosis()) CENTERS.add(mc.player.position());
        for (int id : ACTIVE) {
            if (id == mc.player.getId() || PSYCHOSIS.contains(id)) continue;
            Entity e = mc.level.getEntity(id);
            if (e != null) CENTERS.add(e.position());
        }
        boolean was = stepTick;
        counter++;
        stepTick = CENTERS.isEmpty() || counter % TimeDilation.SLOWDOWN == 0;
        if (!CENTERS.isEmpty() && stepTick) SlowMotionClient.onStep();
        if (CENTERS.isEmpty() && !was) SlowMotionClient.onStep();
    }

    /** Работает ли Сандевистан у игрока с этим номером сущности. */
    public static boolean isActive(int entityId) {
        return ACTIVE.contains(entityId);
    }

    public static boolean isPsychosis(int entityId) {
        return PSYCHOSIS.contains(entityId);
    }

    public static boolean anyZone() {
        return !CENTERS.isEmpty();
    }

    /** Этот тик — «шаг» мира в зонах (сущности в зоне делают свой тик). */
    public static boolean isStepTick() {
        return stepTick;
    }

    /** Попадает ли точка в какую-нибудь зону. */
    public static boolean inZone(Vec3 pos) {
        double r2 = TimeDilation.ZONE_RADIUS * TimeDilation.ZONE_RADIUS;
        for (Vec3 c : CENTERS) {
            if (c.distanceToSqr(pos) <= r2) return true;
        }
        return false;
    }

    /** Сущность в зоне (игроков зона не касается). */
    public static boolean inZone(Entity e) {
        if (CENTERS.isEmpty() || e instanceof Player || e.countPlayerPassengers() > 0) return false;
        return inZone(e.position());
    }

    /** Находится ли сам игрок (камера) в зоне — тогда частицы и звуки вокруг тоже замедлены. */
    public static boolean localInZone() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !CENTERS.isEmpty() && inZone(mc.player.position());
    }
}
