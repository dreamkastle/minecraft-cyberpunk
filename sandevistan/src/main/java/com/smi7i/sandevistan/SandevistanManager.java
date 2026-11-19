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
    public static final int OVERHEAT_TICKS = 260;    // 13 секунд штрафа после перегрева (было 10, +3 с)
    public static final int MANUAL_COOLDOWN = 100;   // 5 секунд после ручного выключения
    public static final int PSYCHOSIS_STREAK = 3;    // перегревов подряд до киберпсихоза
    public static final int PSYCHOSIS_TICKS = 600;   // 30 секунд приступа (было 20, +10 с)
    public static final int PSYCHOSIS_OVERHEAT_TICKS = 400; // 20 секунд перегрева ПОСЛЕ приступа (вдвое дольше обычного)
    /** Во сколько раз длиннее Сандевистан, пока идёт приступ киберпсихоза (после приступа снова обычный). */
    public static final int PSYCHOSIS_DURATION_MULT = 2;
    /** Чип «Овертайм»: работает 6 секунд, потом перезаряжается 2 минуты (отсчёт с конца работы). */
    public static final int CHIP_ACTIVE_TICKS = 120;
    public static final int CHIP_COOLDOWN_TICKS = 2400;
    /** Штраф в конце овертайма: столько полосок киберпсихоза и столько тиков без Сандевистана (30 секунд). */
    public static final int OVERTIME_STREAK = 2;
    public static final int OVERTIME_LOCK_TICKS = 600;
    /** Чип «Взлом»: перезарядка 1 минута 30 секунд, отсчёт с начала попытки взлома. */
    public static final int HACK_COOLDOWN_TICKS = 1800;
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
        /** Сколько ещё тиков работает чип нейро интерфейса и сколько он перезаряжается. */
        int chipTicks;
        int chipCooldown;
        /** Перезарядка чипа «Взлом» и последний замеченный чип (чтобы послать клиенту его перезарядку при смене). */
        int hackCooldown;
        int lastChip;
        /** Овертайм: срок Сандевистана вышел, но он продолжает работать, пока работает чип. */
        boolean overtime;
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

    /** Идёт ли у игрока приступ киберпсихоза прямо сейчас (иммуносупрессор в это время не работает). */
    public static boolean isInPsychosis(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s != null && s.psychosisTicks > 0;
    }

    /** Сколько ячеек киберпсихоза сейчас занято (0..PSYCHOSIS_STREAK-1). */
    public static int getStreak(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s == null ? 0 : s.streak;
    }

    /**
     * Иммуносупрессор: снимает одну ячейку киберпсихоза СРАЗУ, не дожидаясь 3 минут (DEFRAG_WAIT).
     * Таймер очистки мини-игрой не трогаем. Не работает во время самого приступа.
     * @return true, если ячейка снята (тогда предмет можно тратить)
     */
    public static boolean removeOneCell(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null || s.psychosisTicks > 0 || s.streak <= 0) return false;
        s.streak--;
        if (s.streak == 0) s.defragTicks = 0;
        sync(player, s);
        return true;
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
            s.ticksLeft = durationFor(s);
            setSpeedBoost(player, true); // + мир замедляется в 20 раз (TimeDilation)
            sync(player, s);
        } else if (action == 3) {
            activateChip(player, s);
        } else if (action == 1) {
            if (!s.active || s.psychosisTicks > 0 || s.forced) return;
            if (s.overtime) { // выключили в овертайме — штраф берётся сразу
                overtimePenalty(player, s);
                return;
            }
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

    /**
     * Длительность Сандевистана для игрока: обычная (DURATION, 10 с) или вдвое длиннее, пока идёт приступ
     * киберпсихоза. Удвоение действует только внутри приступа и пропадает, когда он кончается.
     */
    private static int durationFor(State s) {
        return s.psychosisTicks > 0 ? DURATION * PSYCHOSIS_DURATION_MULT : DURATION;
    }

    private static boolean hasImplant(ServerPlayer player) {
        // Взломанный игрок (чип «Взлом» у другого) временно остаётся без Сандевистана.
        return ImplantSlot.isImplant(player.getAttachedOrCreate(ModAttachments.IMPLANT)) && !HackState.isHacked(player);
    }

    private static void forceOff(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        s.active = false;
        s.forced = false;
        s.overtime = false;
        s.chipTicks = 0;
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

            tickChip(player, s);

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
                s.overtime = false;
                s.cooldown = MANUAL_COOLDOWN;
                setSpeedBoost(player, false);
                sync(player, s);
                continue;
            }

            int age = durationFor(s) - s.ticksLeft;
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
                if (s.chipTicks > 0) {
                    // Овертайм: чип удерживает Сандевистан включённым, перегрева пока нет.
                    s.ticksLeft = 0;
                    if (!s.overtime) {
                        s.overtime = true;
                        sync(player, s);
                    }
                } else {
                    overheat(player, s);
                }
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
        s.overtime = false;
        s.streak = 0; // приступ «сжигает» все ячейки
        s.defragTicks = 0;
        s.psychosisTicks = PSYCHOSIS_TICKS;
        s.cooldown = PSYCHOSIS_TICKS + PSYCHOSIS_OVERHEAT_TICKS;
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
            s.overheatTicks = PSYCHOSIS_OVERHEAT_TICKS;
            s.cooldown = PSYCHOSIS_OVERHEAT_TICKS;
            setOverheatPenalty(player, true);
            sync(player, s);
        } else if (s.psychosisTicks % 20 == 0) {
            sync(player, s);
        }
    }

    /** Нажали клавишу чипа: включаем его, если надет нейро интерфейс с чипом и он не перезаряжается. */
    private static void activateChip(ServerPlayer player, State s) {
        if (s.chipTicks > 0 || s.chipCooldown > 0 || s.psychosisTicks > 0) {
            syncChip(player, s);
            return;
        }
        if (NeuroChips.activeChip(player) != NeuroChips.OVERTIME) return;
        s.chipTicks = CHIP_ACTIVE_TICKS;
        syncChip(player, s);
    }

    private static void tickChip(ServerPlayer player, State s) {
        int current = NeuroChips.activeChip(player);
        boolean resync = current != s.lastChip; // сменили чип — клиенту нужна перезарядка нового
        s.lastChip = current;
        if (s.chipCooldown > 0) {
            s.chipCooldown--;
            if (current == NeuroChips.OVERTIME && (s.chipCooldown == 0 || s.chipCooldown % 20 == 0)) resync = true;
        }
        if (s.hackCooldown > 0) {
            s.hackCooldown--;
            if (current == NeuroChips.HACK && (s.hackCooldown == 0 || s.hackCooldown % 20 == 0)) resync = true;
        }
        if (s.chipTicks > 0) {
            s.chipTicks--;
            boolean valid = current == NeuroChips.OVERTIME; // сняли интерфейс или чип — чип гаснет
            if (!valid || s.chipTicks <= 0) {
                // Взлом чужим чипом штрафа не даёт, а снять чип, чтобы избежать штрафа, нельзя.
                endChip(player, s, !HackState.isHacked(player));
                resync = false;
            } else if (s.chipTicks % 20 == 0) {
                resync = true;
            }
        }
        if (resync) syncChip(player, s);
    }

    /** Чип кончился: перезарядка, а если шёл овертайм — штраф (penalize = false, если нас отключил взлом). */
    private static void endChip(ServerPlayer player, State s, boolean penalize) {
        s.chipTicks = 0;
        s.chipCooldown = CHIP_COOLDOWN_TICKS;
        if (s.overtime) {
            if (penalize) overtimePenalty(player, s);
            else s.overtime = false;
        }
        syncChip(player, s);
    }

    /**
     * Конец овертайма (чип кончился или игрок выключил Сандевистан): +2 полоски киберпсихоза (три — приступ)
     * и 30 секунд без Сандевистана (откат со штрафами перегрева).
     */
    private static void overtimePenalty(ServerPlayer player, State s) {
        s.overtime = false;
        s.streak += OVERTIME_STREAK;
        if (s.streak >= PSYCHOSIS_STREAK) {
            startPsychosis(player, s);
            return;
        }
        s.active = false;
        s.defragTicks = DEFRAG_WAIT;
        s.cooldown = OVERTIME_LOCK_TICKS;
        s.overheatTicks = OVERTIME_LOCK_TICKS;
        setSpeedBoost(player, false);
        setOverheatPenalty(player, true);
        sync(player, s);
    }

    private static void syncChip(ServerPlayer player, State s) {
        int chip = NeuroChips.activeChip(player);
        int active = chip == NeuroChips.OVERTIME ? s.chipTicks : 0;
        int cooldown = chip == NeuroChips.OVERTIME ? s.chipCooldown : chip == NeuroChips.HACK ? s.hackCooldown : 0;
        ServerPlayNetworking.send(player, new ChipStatePayload(active, cooldown));
    }

    /** Можно ли сейчас пользоваться чипом «Взлом» (чип надет, не перезаряжается, нет приступа). */
    public static boolean canHack(ServerPlayer player) {
        State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        return NeuroChips.activeChip(player) == NeuroChips.HACK && s.hackCooldown <= 0 && s.psychosisTicks <= 0;
    }

    /** Игрок начал взлом: запускаем перезарядку чипа (она идёт и при успехе, и при провале). */
    public static void startHackCooldown(ServerPlayer player) {
        State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        s.hackCooldown = HACK_COOLDOWN_TICKS;
        syncChip(player, s);
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
