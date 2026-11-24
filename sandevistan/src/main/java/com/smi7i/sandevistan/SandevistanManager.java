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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

/** Серверная логика Сандевистана: включение, 10 секунд работы, выключение, перегрев. */
public class SandevistanManager {
    public static final int DURATION = 200;          // 10 секунд
    public static final int OVERHEAT_TICKS = 260;    // 13 секунд штрафа после перегрева (было 10, +3 с)
    public static final int MANUAL_COOLDOWN = 100;   // 5 секунд после ручного выключения
    /**
     * Шкала киберпсихоза: 0..100 единиц, хранится в десятых долях (LOAD_MAX = 1000 = 100.0). Заполнилась — приступ.
     * Сейчас шкалу меняют только: ношение Сандевистана, его включение и иммуносупрессор. Остальные источники
     * (клинки, чипы, взлом, перегревы) пока отключены — новые добавляются через addLoadUnits.
     */
    public static final int LOAD_MAX = 1000;
    /** Ношение Сандевистана: +0.1 единицы каждую секунду. */
    public static final int LOAD_PER_SECOND = 1;
    /** Включение Сандевистана: +5 единиц. */
    public static final int LOAD_ON_ACTIVATE = 50;
    /** Иммуносупрессор: -10 единиц. */
    public static final int LOAD_IMMUNOSUPPRESSANT = 100;

    /**
     * Малый приступ: пока на шкале есть хотя бы 40 единиц, раз в секунду бросается кубик. Шанс не плавный, а по ступеням:
     * от 40 единиц — 0.2%, от 50 — 0.5%, от 60 — 1.2%, на 100 — 2.2%. Приступ длится 5 секунд: игрок не может
     * двигаться, звуки вокруг тише до 3%, перед глазами середина слайдшоу киберпсихоза.
     */
    public static final int EPISODE_MIN_LOAD = 400;        // 40 единиц (в десятых долях)
    public static final int EPISODE_TICKS = 100;           // 5 секунд

    /** От 75 единиц каждый малый приступ перерастает в приступ киберпсихоза с потерей контроля (30 секунд). */
    public static final int LOAD_ESCALATE = 750;
    /** Шкала дошла до 100 — потеря контроля насовсем: пока не умрёт или пока чипом «Взлом» не отключат его импланты. */
    public static final int PERMANENT_PSYCHOSIS_TICKS = 1_000_000;
    /** Чип «Взлом» сбивает постоянный приступ: шкала временно падает до 80, есть время снять импланты. */
    public static final int LOAD_AFTER_HACK = 800;
    /** Окно после снятия всех имплантов (3 минуты): наденешь военный имплант — шкала сразу 60. */
    public static final int GRACE_TICKS = 20 * 60 * 3;
    public static final int LOAD_AFTER_GRACE = 600;

    /** Сколько шкалы (в десятых долях) дают разовые действия имплантов. */
    public static final int LOAD_NEURO_EQUIP = 20;      // надел нейро интерфейс: +2
    public static final int LOAD_BLADE_EXTEND = 30;     // достал клинок (каждый): +3
    public static final int LOAD_CHIP_OVERTIME = 70;    // использовал чип овертайма: +7
    public static final int LOAD_CHIP_HACK = 10;        // использовал чип взлома: +1

    // Импланты, которые считаются отдельно: сколько шкалы каждый успел добавить (вычитается при его снятии).
    private static final int K_SAND = 0, K_NEURO = 1, K_CHIP = 2, K_HAND_L = 3, K_HAND_R = 4, K_ARMOR = 5, K_COUNT = 6;
    public static final int PSYCHOSIS_TICKS = 600;   // 30 секунд приступа (было 20, +10 с)
    public static final int PSYCHOSIS_OVERHEAT_TICKS = 400; // 20 секунд перегрева ПОСЛЕ приступа (вдвое дольше обычного)
    /** Во сколько раз длиннее Сандевистан, пока идёт приступ киберпсихоза (после приступа снова обычный). */
    public static final int PSYCHOSIS_DURATION_MULT = 2;
    /** Чип «Овертайм»: работает 6 секунд, потом перезаряжается 2 минуты (отсчёт с конца работы). */
    public static final int CHIP_ACTIVE_TICKS = 120;
    public static final int CHIP_COOLDOWN_TICKS = 2400;
    /** Штраф в конце овертайма: столько тиков без Сандевистана (30 секунд). */
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
        /** Шкала киберпсихоза в десятых долях единицы: 0..LOAD_MAX. */
        int load;
        /** Тики до следующей прибавки за ношение Сандевистана (раз в секунду). */
        int loadTimer;
        /** Сколько ещё тиков длится малый приступ (0 = нет) и тики до следующего броска кубика (раз в секунду). */
        int episodeTicks;
        int episodeTimer;
        /** Потеря контроля насовсем (шкала дошла до 100): психоз не кончается сам. */
        boolean permanent;
        /** Сколько шкалы успел добавить каждый имплант (в десятых долях): при его снятии столько вычитается. */
        final int[] given = new int[K_COUNT];
        /** Что лежало в слотах имплантов в прошлый тик (чтобы заметить «надел» и «снял»). */
        final Item[] lastItem = new Item[K_COUNT];
        boolean tracked;
        boolean hadImplants;
        /** Сколько ещё тиков действует окно «надел военный имплант — шкала 60» после снятия всех имплантов. */
        int graceTicks;
        /** Отсрочка от чипа «Взлом» (в десятых долях): на сколько шкала временно сброшена со 100; вернётся, когда взлом кончится. */
        int reprieve;
        /** Дробный остаток шкалы (в десятых): продвинутый нейро интерфейс замедляет рост на 15%, остаток копится. */
        double loadCarry;
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

    /** Надет ли у игрока (и работает ли) админский бесконечный Сандевистан. */
    public static boolean isAdminSandevistan(ServerPlayer player) {
        return player.getAttachedOrCreate(ModAttachments.IMPLANT).is(ModItems.ADMIN_SANDEVISTAN)
                && !HackState.implantsOff(player);
    }

    /** Шкала киберпсихоза игрока в десятых долях единицы (0..LOAD_MAX). */
    public static int getLoad(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s == null ? 0 : s.load;
    }

    /**
     * Иммуносупрессор: убавляет шкалу киберпсихоза на 10 единиц (не ниже нуля). Не работает во время самого приступа.
     * @return true, если шкала была не пуста и убавилась (тогда предмет можно тратить)
     */
    public static boolean removeLoad(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null || s.psychosisTicks > 0 || s.load <= 0) return false;
        s.load = Math.max(0, s.load - LOAD_IMMUNOSUPPRESSANT);
        sync(player, s);
        return true;
    }

    /** Прибавить к шкале tenths десятых долей единицы; дошла до максимума — потеря контроля насовсем. @return true, если уже отправили состояние клиенту. */
    private static boolean addLoad(ServerPlayer player, State s, int tenths) {
        if (s.psychosisTicks > 0 || tenths <= 0) return false;
        s.load = Math.min(LOAD_MAX, s.load + tenths);
        if (s.load >= LOAD_MAX) {
            startPsychosis(player, s, true); // сам отправляет состояние
            return true;
        }
        sync(player, s);
        return true;
    }

    /** Продвинутый нейро интерфейс: любая прибавка к шкале на 15% меньше (дробные части копятся и не теряются). */
    private static final double ADVANCED_NEURO_LOAD_FACTOR = 0.85;

    private static int resist(ServerPlayer player, State s, int tenths) {
        if (tenths <= 0 || !HeadImplantSlot.hasAdvancedNeuro(player)) return tenths;
        double scaled = tenths * ADVANCED_NEURO_LOAD_FACTOR + s.loadCarry;
        int whole = (int) scaled;
        s.loadCarry = scaled - whole;
        return whole;
    }

    /** То же, но запоминаем, что эту часть шкалы добавил имплант key: снимут его — вычтется. */
    private static boolean addLoadTracked(ServerPlayer player, State s, int key, int tenths) {
        if (key == K_SAND && isAdminSandevistan(player)) return false; // админский Сандевистан не даёт нагрузки
        if (s.psychosisTicks > 0 || tenths <= 0) return false;
        int added = resist(player, s, tenths);
        if (added <= 0) return false;
        s.given[key] += added;
        return addLoad(player, s, added);
    }

    // ---------------------------------------------------------------- слежение за имплантами

    private static ItemStack slotStack(ServerPlayer p, int key) {
        return switch (key) {
            case K_SAND -> p.getAttachedOrCreate(ModAttachments.IMPLANT);
            case K_NEURO -> p.getAttachedOrCreate(ModAttachments.HEAD_IMPLANT);
            case K_CHIP -> p.getAttachedOrCreate(ModAttachments.CHIP);
            case K_HAND_L -> p.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_1);
            case K_HAND_R -> p.getAttachedOrCreate(ModAttachments.HAND_IMPLANT_2);
            default -> p.getAttachedOrCreate(ModAttachments.BODY_IMPLANT);
        };
    }

    /** Военные импланты: Сандевистан, клинки богомола, подкожная броня. */
    private static boolean isMilitary(int key, Item item) {
        return key == K_SAND || key == K_ARMOR
                || (key == K_HAND_L && item == ModItems.BLADE_LEFT)
                || (key == K_HAND_R && item == ModItems.BLADE_RIGHT);
    }

    /** Заметить, какой имплант надели или сняли, и применить правила шкалы. */
    private static void trackImplants(ServerPlayer player, State s) {
        boolean any = false;
        for (int key = 0; key < K_COUNT; key++) {
            ItemStack stack = slotStack(player, key);
            Item now = stack.isEmpty() ? null : stack.getItem();
            if (now != null) any = true;
            if (!s.tracked) {
                s.lastItem[key] = now; // что надето до начала слежения, ничего не даёт
                continue;
            }
            if (now == s.lastItem[key]) continue;
            if (s.lastItem[key] != null) {
                // Сняли (или заменили): шкала убавляется на столько, сколько имплант успел добавить.
                s.load = Math.max(0, s.load - s.given[key]);
                s.given[key] = 0;
                sync(player, s);
            }
            s.lastItem[key] = now;
            if (now != null) {
                // +2 даёт обычный нейро интерфейс; продвинутый при надевании шкалу не трогает.
                if (key == K_NEURO && now == ModItems.NEURO_INTERFACE) addLoadTracked(player, s, K_NEURO, LOAD_NEURO_EQUIP);
                if (isMilitary(key, now) && s.graceTicks > 0) {
                    // Надел военный имплант в течение 3 минут после снятия всех: шкала сразу поднимается до 60.
                    s.graceTicks = 0;
                    if (s.load < LOAD_AFTER_GRACE) {
                        s.load = LOAD_AFTER_GRACE;
                        sync(player, s);
                    }
                }
            }
        }
        s.tracked = true;
        if (s.graceTicks > 0) s.graceTicks--;
        if (!any) {
            // Имплантов нет совсем: шкала падает до нуля, а 3 минуты действует окно «вернёшь импланты — 60».
            if (s.hadImplants) s.graceTicks = GRACE_TICKS; // сняли последний имплант: пошли 3 минуты
            if (s.load > 0 || s.reprieve > 0 || s.permanent) {
                s.load = 0;
                s.reprieve = 0;
                java.util.Arrays.fill(s.given, 0);
                if (s.permanent) endPermanent(player, s);
                sync(player, s);
            }
        }
        s.hadImplants = any;
    }

    private static boolean anyImplant(ServerPlayer p) {
        for (int key = 0; key < K_COUNT; key++) {
            if (!slotStack(p, key).isEmpty()) return true;
        }
        return false;
    }

    /** Закончить постоянную потерю контроля: клинки убираются, Сандевистан не работает сам. */
    private static void endPermanent(ServerPlayer player, State s) {
        s.permanent = false;
        s.psychosisTicks = 0;
        s.cooldown = 0;
        player.setAttached(ModAttachments.BLADES_OUT, false);
        setSpeedBoost(player, false);
    }

    /**
     * Игрока взломали чипом «Взлом» (HackManager). Если он в постоянной потере контроля — контроль возвращается,
     * шкала временно падает до 80, и у него есть время снять импланты, пока они отключены. Не снял — когда взлом
     * кончится, шкале возвращается отсрочка (+20, не выше 100) поверх того, что убавилось при снятии имплантов.
     */
    public static void onHacked(ServerPlayer target) {
        State s = STATES.get(target.getUUID());
        if (s == null || !s.permanent) return;
        endPermanent(target, s);
        s.reprieve = Math.max(0, LOAD_MAX - LOAD_AFTER_HACK);
        s.load = LOAD_AFTER_HACK;
        s.overheatTicks = 0;
        setOverheatPenalty(target, false);
        sync(target, s);
    }

    /** Шанс малого приступа за одну секунду при данной шкале (в десятых долях единицы), 0..1. */
    public static double episodeChance(int load) {
        if (load >= LOAD_MAX) return 0.022;
        if (load >= 600) return 0.012;
        if (load >= 500) return 0.005;
        if (load >= EPISODE_MIN_LOAD) return 0.002;
        return 0.0;
    }

    private static void startEpisode(ServerPlayer player, State s) {
        s.episodeTicks = EPISODE_TICKS;
        sync(player, s);
    }

    /** Для будущих источников нагрузки (другие импланты): прибавить units единиц шкалы киберпсихоза (0..100). */
    public static void addLoadUnits(ServerPlayer player, double units) {
        State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        addLoad(player, s, resist(player, s, (int) Math.round(units * 10.0)));
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
            if (!addLoadTracked(player, s, K_SAND, LOAD_ON_ACTIVATE)) sync(player, s); // включение: +5 единиц шкалы
        } else if (action == 3) {
            activateChip(player, s);
        } else if (action == 4) {
            toggleBlades(player, s);
        } else if (action == 1) {
            if (!s.active || s.psychosisTicks > 0 || s.forced) return;
            if (s.overtime) { // выключили в овертайме — штраф берётся сразу
                overtimePenalty(player, s);
                return;
            }
            s.active = false;
            // Шкалу киберпсихоза ручное выключение не меняет.
            s.cooldown = MANUAL_COOLDOWN;
            setSpeedBoost(player, false);
            sync(player, s);
        } else if (action == 2) {
            // Мини-игра «Дефрагментация памяти» пока отключена: шкалу она не меняет.
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

    /** Включён ли у игрока Сандевистан прямо сейчас (сам приступ киберпсихоза сюда не входит). */
    public static boolean isSandevistanRunning(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s != null && s.active;
    }

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
        return ImplantSlot.isImplant(player.getAttachedOrCreate(ModAttachments.IMPLANT)) && !HackState.implantsOff(player);
    }

    /** Достать клинки богомола или убрать (клавиша M). Каждый доставленный клинок даёт +3 к шкале киберпсихоза (носить — бесплатно). */
    private static void toggleBlades(ServerPlayer player, State s) {
        if (s.psychosisTicks > 0) return; // в приступе клинки управляются не тобой: достаны до его конца
        boolean out = player.getAttachedOrCreate(ModAttachments.BLADES_OUT);
        if (out) {
            player.setAttached(ModAttachments.BLADES_OUT, false);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.BLADE_EXTEND, SoundSource.PLAYERS, 0.8F, 0.75F);
            return;
        }
        int blades = MantisBlades.count(player);
        if (blades == 0 || s.psychosisTicks > 0 || ModAttachments.hasMech(player)) return;
        player.setAttached(ModAttachments.BLADES_OUT, true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                ModSounds.BLADE_EXTEND, SoundSource.PLAYERS, 1.0F, 1.0F);
        if (MantisBlades.hasLeft(player)) addLoadTracked(player, s, K_HAND_L, LOAD_BLADE_EXTEND);
        if (MantisBlades.hasRight(player)) addLoadTracked(player, s, K_HAND_R, LOAD_BLADE_EXTEND);
        sync(player, s);
    }

    private static void forceOff(ServerPlayer player) {
        player.setAttached(ModAttachments.BLADES_OUT, false);
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        s.active = false;
        s.forced = false;
        s.overtime = false;
        s.chipTicks = 0;
        s.cooldown = 0;
        s.load = 0;
        s.loadTimer = 0;
        s.episodeTicks = 0;
        s.episodeTimer = 0;
        s.defragTicks = 0;
        s.overheatTicks = 0;
        s.psychosisTicks = 0;
        s.permanent = false; // умер — контроль вернулся
        s.reprieve = 0;
        s.graceTicks = 0;
        java.util.Arrays.fill(s.given, 0);
        setSpeedBoost(player, false);
        setOverheatPenalty(player, false);
        sync(player, s);
    }

    private static void sync(ServerPlayer player, State s) {
        ServerPlayNetworking.send(player, new StatePayload(s.active, s.ticksLeft, s.load, s.overheatTicks, s.psychosisTicks,
                s.episodeTicks));
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
            if (s == null) {
                // Игрок с имплантами накапливает шкалу, даже если ни разу ничего не включал.
                if (!anyImplant(player)) continue;
                s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
            }

            tickChip(player, s);
            trackImplants(player, s);

            // Отсрочка от чипа «Взлом» кончилась (импланты снова работают): шкале возвращается то, что с неё сняли.
            if (s.reprieve > 0 && !HackState.isHacked(player)) {
                s.load = Math.min(LOAD_MAX, s.load + s.reprieve);
                s.reprieve = 0;
                if (s.load >= LOAD_MAX) startPsychosis(player, s, true);
                else sync(player, s);
            }

            // Ношение: Сандевистан и подкожная броня дают по +0.1 к шкале каждую секунду (в приступе шкала стоит).
            if (s.psychosisTicks <= 0 && ++s.loadTimer >= 20) {
                s.loadTimer = 0;
                if (hasImplant(player)) addLoadTracked(player, s, K_SAND, LOAD_PER_SECOND);
                if (BodyImplants.armorOf(player) > 0) addLoadTracked(player, s, K_ARMOR, LOAD_PER_SECOND);
            }

            // Малый приступ: идёт — отсчитываем; не идёт, а шкала от 40 единиц — раз в секунду бросаем кубик.
            // От 75 единиц любой малый приступ перерастает в приступ киберпсихоза. Пока длится «отсрочка» после
            // взлома, приступов нет: у игрока есть время снять импланты.
            if (s.episodeTicks > 0) {
                if (s.load >= LOAD_ESCALATE && s.psychosisTicks <= 0) startPsychosis(player, s, false);
                else if (--s.episodeTicks == 0) sync(player, s);
            } else if (s.psychosisTicks <= 0 && s.reprieve <= 0 && s.load >= EPISODE_MIN_LOAD) {
                if (++s.episodeTimer >= 20) {
                    s.episodeTimer = 0;
                    if (player.getRandom().nextDouble() < episodeChance(s.load)) {
                        if (s.load >= LOAD_ESCALATE) startPsychosis(player, s, false);
                        else startEpisode(player, s);
                    }
                }
            } else {
                s.episodeTimer = 0;
            }

            if (s.psychosisTicks > 0) {
                tickPsychosis(player, s);
                continue;
            }

            if (!s.active) {
                if (s.cooldown > 0) s.cooldown--;
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

            // Админский Сандевистан бесконечный: время не убывает, перегрева и конца нет.
            if (isAdminSandevistan(player)) s.ticksLeft = durationFor(s); else s.ticksLeft--;
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
    private static void startPsychosis(ServerPlayer player, State s, boolean permanent) {
        s.active = false;
        s.overtime = false;
        // В приступе клинки достаются сами и остаются до конца приступа.
        boolean autoBlades = MantisBlades.count(player) > 0;
        if (autoBlades && !player.getAttachedOrCreate(ModAttachments.BLADES_OUT)) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.BLADE_EXTEND, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        player.setAttached(ModAttachments.BLADES_OUT, autoBlades);
        // Голос в начале приступа: его слышат все игроки в 15 блоках вокруг (дальность задана в ModSounds и sounds.json).
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                ModSounds.PSYCHOSIS_VOICE, SoundSource.PLAYERS, 1.0F, 1.0F);
        s.permanent = permanent;
        if (permanent) s.load = LOAD_MAX; // шкала на 100: контроль потерян насовсем
        s.loadTimer = 0;
        s.episodeTicks = 0; // большой приступ заменяет малый
        s.defragTicks = 0;
        s.psychosisTicks = permanent ? PERMANENT_PSYCHOSIS_TICKS : PSYCHOSIS_TICKS;
        s.cooldown = (permanent ? 0 : PSYCHOSIS_TICKS) + PSYCHOSIS_OVERHEAT_TICKS;
        s.overheatTicks = 0;
        setOverheatPenalty(player, false);
        setSpeedBoost(player, true); // Сандевистан во время приступа работает сам
        sync(player, s);
    }

    private static void tickPsychosis(ServerPlayer player, State s) {
        if (s.permanent) return; // постоянная потеря контроля сама не кончается
        int age = PSYCHOSIS_TICKS - s.psychosisTicks;
        if (age % 5 == 0) {
            applyWorldEffects(player, age);
        }
        s.psychosisTicks--;
        if (s.psychosisTicks <= 0) {
            // Приступ кончился — клинки сами убираются, дальше откат: обычный перегрев.
            player.setAttached(ModAttachments.BLADES_OUT, false);
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
        addLoadTracked(player, s, K_CHIP, LOAD_CHIP_OVERTIME); // овертайм: +7 к шкале
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
     * Конец овертайма (чип кончился или игрок выключил Сандевистан): 30 секунд без Сандевистана
     * (откат со штрафами перегрева). Шкалу киберпсихоза овертайм пока не меняет.
     */
    private static void overtimePenalty(ServerPlayer player, State s) {
        s.overtime = false;
        s.active = false;
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
        addLoadTracked(player, s, K_CHIP, LOAD_CHIP_HACK); // взлом: +1 к шкале
        syncChip(player, s);
    }

    private static void overheat(ServerPlayer player, State s) {
        s.active = false;
        s.cooldown = OVERHEAT_TICKS;
        s.overheatTicks = OVERHEAT_TICKS;
        setSpeedBoost(player, false);
        // Без зелий: замедление и слабость — невидимые модификаторы,
        // а качание экрана, красный оттенок и глитчи рисует клиент по overheatTicks.
        setOverheatPenalty(player, true);
        // Без текста над хотбаром: перегрев показывают голограммы, а шкалу КИБЕРПСИХОЗ перегрев пока не меняет.
        sync(player, s);
    }
}
