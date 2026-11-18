package com.smi7i.sandevistan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    public static final int PSYCHOSIS_TICKS = 400;   // 20 секунд приступа
    private static final double RADIUS = 24.0;

    /**
     * Бонусы игроку, пока работает Сандевистан (невидимые модификаторы, без иконок зелий; мир при этом
     * ещё и замедлен в 20 раз — TimeDilation). Множители: 0.5 = +50%, 1.0 = +100% (вдвое быстрее).
     */
    public static final Identifier SPEED_BOOST_ID = Identifier.fromNamespaceAndPath("sandevistan", "speed_boost");
    public static final double SPEED_BOOST = 0.5;
    public static final Identifier ATTACK_SPEED_BOOST_ID = Identifier.fromNamespaceAndPath("sandevistan", "attack_speed_boost");
    public static final double ATTACK_SPEED_BOOST = 1.0;
    public static final Identifier MINING_BOOST_ID = Identifier.fromNamespaceAndPath("sandevistan", "mining_boost");
    public static final double MINING_BOOST = 1.0;

    /** Штрафы перегрева — тоже невидимые модификаторы, без иконок зелий. -0.35 = на 35% медленнее. */
    public static final Identifier OVERHEAT_SLOW_ID = Identifier.fromNamespaceAndPath("sandevistan", "overheat_slow");
    public static final double OVERHEAT_SLOW = -0.35;
    /** -0.5 = урон вдвое меньше. */
    public static final Identifier OVERHEAT_WEAK_ID = Identifier.fromNamespaceAndPath("sandevistan", "overheat_weak");
    public static final double OVERHEAT_WEAK = -0.5;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static class State {
        boolean active;
        int ticksLeft;
        int cooldown;
        int streak;
        /** Сколько ещё тиков длится перегрев (штрафы и эффекты на экране). */
        int overheatTicks;
        /** Сколько ещё тиков длится приступ киберпсихоза (управление у автопилота на клиенте). */
        int psychosisTicks;
        /** Сколько ещё ждать, чтобы очистить одну ячейку киберпсихоза мини-игрой «Дефрагментация памяти». */
        int defragTicks;
        /** Включён способностью костюма («Последний бой»): без импланта, без перегрева, выключить вручную нельзя. */
        boolean forced;
    }

    /**
     * Включить Сандевистан на ticks тиков «извне» (способность костюма-меха): всё как обычно — зелёный мир, послеобразы,
     * замедление вокруг, — но не нужен имплант, нельзя выключить вручную и в конце нет перегрева.
     */
    public static void forceActive(ServerPlayer player, int ticks) {
        State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        s.active = true;
        s.forced = true;
        s.ticksLeft = ticks;
        setSpeedBoost(player, true);
        sync(player, s);
    }

    /** Сколько ждать после перегрева (или после прошлой очистки), чтобы очистить одну ячейку: 3 минуты. */
    public static final int DEFRAG_WAIT = 3600;
    /** Сколько тиков таймера очистки можно «простить» (если часы клиента чуть убежали вперёд). */
    private static final int DEFRAG_SLACK = 100;

    public static void init() {
        TimeDilation.init();
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
                // Без текста над хотбаром: клиент сам показывает голограмму «ИМПЛАНТ ОСТЫВАЕТ».
                sync(player, s);
                return;
            }
            s.active = true;
            s.ticksLeft = DURATION;
            setSpeedBoost(player, true); // + мир замедляется в 20 раз (TimeDilation)
            sync(player, s);
        } else if (action == 1) {
            if (!s.active || s.psychosisTicks > 0 || s.forced) return;
            s.active = false;
            // Ячейки киберпсихоза ручное выключение НЕ сбрасывает — только мини-игра (action 2).
            s.cooldown = MANUAL_COOLDOWN;
            setSpeedBoost(player, false);
            sync(player, s);
        } else if (action == 2) {
            // Мини-игра «Дефрагментация памяти» пройдена: очищаем одну ячейку, если время подошло.
            // DEFRAG_SLACK — запас на небольшое расхождение часов клиента и сервера.
            if (s.streak <= 0 || s.defragTicks > DEFRAG_SLACK || s.active || s.psychosisTicks > 0) {
                sync(player, s); // отказ: клиент узнает настоящий остаток времени
                return;
            }
            s.streak--;
            s.defragTicks = s.streak > 0 ? DEFRAG_WAIT : 0;
            sync(player, s);
        }
    }

    /** Включает или снимает бонусы Сандевистана: ходьба +50%, удары +100%, копание +100%. */
    private static void setSpeedBoost(ServerPlayer player, boolean on) {
        setModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_BOOST_ID, SPEED_BOOST, on);
        setModifier(player.getAttribute(Attributes.ATTACK_SPEED), ATTACK_SPEED_BOOST_ID, ATTACK_SPEED_BOOST, on);
        setModifier(player.getAttribute(Attributes.BLOCK_BREAK_SPEED), MINING_BOOST_ID, MINING_BOOST, on);
    }

    /** Включает или снимает штрафы перегрева (замедление и слабость). */
    private static void setOverheatPenalty(ServerPlayer player, boolean on) {
        setModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), OVERHEAT_SLOW_ID, OVERHEAT_SLOW, on);
        setModifier(player.getAttribute(Attributes.ATTACK_DAMAGE), OVERHEAT_WEAK_ID, OVERHEAT_WEAK, on);
    }

    private static void setModifier(AttributeInstance attribute, Identifier id, double amount, boolean on) {
        if (attribute == null) return;
        attribute.removeModifier(id);
        if (on) {
            attribute.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Во сколько раз уменьшается входящий урон, пока работает Сандевистан (и во время приступа). */
    public static final float DAMAGE_DIVISOR = 2.5F;

    /** Работает ли сейчас у игрока Сандевистан (включая приступ) — тогда урон по нему уменьшен. */
    public static boolean isDamageReduced(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s != null && (s.active || s.psychosisTicks > 0);
    }

    private static boolean hasImplant(ServerPlayer player) {
        return ImplantSlot.isImplant(player.getAttachedOrCreate(ModAttachments.IMPLANT));
    }

    private static void forceOff(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        s.active = false;
        s.forced = false;
        s.cooldown = 0;
        s.streak = 0;
        s.defragTicks = 0;
        s.overheatTicks = 0;
        s.psychosisTicks = 0;
        setSpeedBoost(player, false);
        setOverheatPenalty(player, false);
        sync(player, s);
    }

    private static void sync(ServerPlayer player, State s) {
        ServerPlayNetworking.send(player, new StatePayload(s.active, s.ticksLeft, s.streak, s.overheatTicks, s.psychosisTicks,
                s.defragTicks));
    }

    private static void tick(MinecraftServer server) {
        tickPlayers(server);
        List<ServerPlayer> active = new ArrayList<>();
        List<ServerPlayer> psychosis = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State s = STATES.get(player.getUUID());
            if (s == null) continue;
            if (s.active || s.psychosisTicks > 0) active.add(player);
            if (s.psychosisTicks > 0) psychosis.add(player);
        }
        TimeDilation.tick(server, active, psychosis); // зоны замедления 30 блоков вокруг каждого
    }

    private static void tickPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State s = STATES.get(player.getUUID());
            if (s == null) continue;

            if (s.psychosisTicks > 0) {
                tickPsychosis(player, s);
                continue;
            }

            if (!s.active) {
                if (s.cooldown > 0) s.cooldown--;
                // Отсчёт до очистки ячейки киберпсихоза (3 минуты).
                if (s.streak > 0 && s.defragTicks > 0) {
                    s.defragTicks--;
                    // В конце — «можно проходить», и раз в 10 с сверяем часы с клиентом.
                    if (s.defragTicks == 0 || s.defragTicks % 200 == 0) sync(player, s);
                }
                if (s.overheatTicks > 0) {
                    s.overheatTicks--;
                    if (s.overheatTicks == 0) {
                        setOverheatPenalty(player, false);
                        sync(player, s);
                    }
                }
                continue;
            }

            if (!s.forced && !hasImplant(player)) {
                s.active = false;
                s.cooldown = MANUAL_COOLDOWN;
                setSpeedBoost(player, false);
                sync(player, s);
                continue;
            }

            int age = DURATION - s.ticksLeft;
            if (age % 5 == 0) {
                applyWorldEffects(player, age);
            }

            s.ticksLeft--;
            if (s.ticksLeft <= 0 && s.forced) {
                // Конец «Последнего боя»: просто выключается, без перегрева и паузы.
                s.active = false;
                s.forced = false;
                setSpeedBoost(player, false);
                sync(player, s);
            } else if (s.ticksLeft <= 0) {
                overheat(player, s);
            } else if (s.ticksLeft % 20 == 0) {
                sync(player, s);
            }
        }
    }

    /** Раньше здесь мобов замедляли эффектом; теперь мир в зоне 30 блоков замедляет TimeDilation. */
    private static void applyWorldEffects(ServerPlayer player, int age) {
    }

    /**
     * Приступ киберпсихоза: Сандевистан работает сам (ускорение, все вокруг замедляются),
     * управление у автопилота на клиенте. После приступа — обычный перегрев.
     */
    private static void startPsychosis(ServerPlayer player, State s) {
        s.active = false;
        s.streak = 0; // приступ «сжигает» все ячейки
        s.defragTicks = 0;
        s.psychosisTicks = PSYCHOSIS_TICKS;
        s.cooldown = PSYCHOSIS_TICKS + OVERHEAT_TICKS;
        s.overheatTicks = 0;
        setOverheatPenalty(player, false);
        setSpeedBoost(player, true); // Сандевистан во время приступа работает сам
        sync(player, s);
    }

    private static void tickPsychosis(ServerPlayer player, State s) {
        int age = PSYCHOSIS_TICKS - s.psychosisTicks;
        if (age % 5 == 0) {
            applyWorldEffects(player, age);
        }
        s.psychosisTicks--;
        if (s.psychosisTicks <= 0) {
            // Приступ кончился — откат: обычный перегрев.
            setSpeedBoost(player, false);
            s.overheatTicks = OVERHEAT_TICKS;
            s.cooldown = OVERHEAT_TICKS;
            setOverheatPenalty(player, true);
            sync(player, s);
        } else if (s.psychosisTicks % 20 == 0) {
            sync(player, s);
        }
    }

    private static void overheat(ServerPlayer player, State s) {
        if (s.streak + 1 >= PSYCHOSIS_STREAK) {
            startPsychosis(player, s);
            return;
        }
        s.active = false;
        s.streak++;
        s.defragTicks = DEFRAG_WAIT; // очистить ячейку можно через 3 минуты после перегрева
        s.cooldown = OVERHEAT_TICKS;
        s.overheatTicks = OVERHEAT_TICKS;
        setSpeedBoost(player, false);
        // Без зелий: замедление и слабость — невидимые модификаторы,
        // а качание экрана, красный оттенок и глитчи рисует клиент по overheatTicks.
        setOverheatPenalty(player, true);
        // Без текста над хотбаром: перегрев показывают голограммы, а счёт — шкала КИБЕРПСИХОЗ.
        sync(player, s);
    }
}
