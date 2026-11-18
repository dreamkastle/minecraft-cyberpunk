package com.smi7i.sandevistan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/** Серверная логика Сандевистана: включение, 10 секунд работы, выключение, перегрев. */
public class SandevistanManager {
    public static final int DURATION = 200;          // 10 секунд
    public static final int OVERHEAT_TICKS = 200;    // 10 секунд штрафа после перегрева
    public static final int MANUAL_COOLDOWN = 100;   // 5 секунд после ручного выключения
    public static final int PSYCHOSIS_STREAK = 3;    // перегревов подряд до киберпсихоза
    private static final double RADIUS = 24.0;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static class State {
        boolean active;
        int ticksLeft;
        int cooldown;
        int streak;
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SandevistanManager::tick);
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> STATES.remove(handler.getPlayer().getUUID()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                forceOff(player);
            }
        });
    }

    public static void onAction(ServerPlayer player, int action) {
        State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        if (action == 0) {
            if (s.active) return;
            if (!hasImplant(player)) return;
            if (s.cooldown > 0) {
                player.displayClientMessage(Component.literal("Сандевистан остывает: " + (s.cooldown / 20 + 1) + " с"), true);
                return;
            }
            s.active = true;
            s.ticksLeft = DURATION;
            sync(player, s);
        } else if (action == 1) {
            if (!s.active) return;
            s.active = false;
            s.streak = 0;
            s.cooldown = MANUAL_COOLDOWN;
            sync(player, s);
        }
    }

    private static boolean hasImplant(ServerPlayer player) {
        return ImplantSlot.isImplant(player.getAttachedOrCreate(ModAttachments.IMPLANT));
    }

    private static void forceOff(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        s.active = false;
        s.cooldown = 0;
        s.streak = 0;
        sync(player, s);
    }

    private static void sync(ServerPlayer player, State s) {
        ServerPlayNetworking.send(player, new StatePayload(s.active, s.ticksLeft, s.streak));
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State s = STATES.get(player.getUUID());
            if (s == null) continue;

            if (!s.active) {
                if (s.cooldown > 0) s.cooldown--;
                continue;
            }

            if (!hasImplant(player)) {
                s.active = false;
                s.streak = 0;
                s.cooldown = MANUAL_COOLDOWN;
                sync(player, s);
                continue;
            }

            int age = DURATION - s.ticksLeft;
            if (age % 5 == 0) {
                applyWorldEffects(player, age);
            }

            s.ticksLeft--;
            if (s.ticksLeft <= 0) {
                overheat(player, s);
            } else if (s.ticksLeft % 20 == 0) {
                sync(player, s);
            }
        }
    }

    /** Игрок ускоряется, все вокруг постепенно замедляются. */
    private static void applyWorldEffects(ServerPlayer player, int age) {
        int amp = Math.min(4, age / 10);
        List<LivingEntity> nearby = player.level().getEntitiesOfClass(
                LivingEntity.class, player.getBoundingBox().inflate(RADIUS),
                e -> e != player && e.isAlive());
        for (LivingEntity e : nearby) {
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, amp, true, false));
        }
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 20, 3, true, false));
    }

    private static void overheat(ServerPlayer player, State s) {
        s.active = false;
        s.streak++;
        s.cooldown = OVERHEAT_TICKS;

        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, OVERHEAT_TICKS, 1));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, OVERHEAT_TICKS, 1));
        player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 100, 0));
        player.displayClientMessage(Component.literal("Перегрев Сандевистана!"), true);

        if (s.streak >= PSYCHOSIS_STREAK) {
            // Заглушка: сам киберпсихоз (красный мир, неконтролируемые атаки) делаем отдельным этапом.
            player.displayClientMessage(Component.literal("КИБЕРПСИХОЗ (эффекты будут добавлены позже)"), true);
        }
        sync(player, s);
    }
}
