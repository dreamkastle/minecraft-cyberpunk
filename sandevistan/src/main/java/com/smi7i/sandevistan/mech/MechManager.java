package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Костюм-мех: вызов клавишей (падает с неба в точку, куда смотрит игрок, не дальше {@link #SUMMON_RANGE} блоков)
 * и посадка по ПКМ (игрок сам становится мехом, вылезти нельзя).
 */
public final class MechManager {
    /** Как далеко (в блоках) может упасть костюм от игрока — туда, куда он смотрит. */
    public static final double SUMMON_RANGE = 5.0;

    public static final ResourceKey<EntityType<?>> MECH_SUIT_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("sandevistan", "mech_suit"));
    public static final EntityType<MechSuitEntity> MECH_SUIT = Registry.register(BuiltInRegistries.ENTITY_TYPE, MECH_SUIT_KEY,
            EntityType.Builder.<MechSuitEntity>of(MechSuitEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.7F)
                    .clientTrackingRange(10)
                    .build(MECH_SUIT_KEY));

    /** Чей костюм где стоит: у игрока только один вызванный костюм. */
    private static final Map<UUID, UUID> SUMMONED = new HashMap<>();

    private MechManager() {}

    // ===== Скорость ходьбы под анимацию =====
    /**
     * Путь корпуса за один цикл ходьбы (блоки): опорная стопа уезжает назад на 8 + 9 = 17 пикселей за 55% цикла
     * → 17 / 0.55 ≈ 30.9 пикселя за цикл; × 15/16 (масштаб игрока); × cos 10° (носки развёрнуты наружу).
     * Должно совпадать с STRIDE / STRIDE_BACK / STANCE в models_src/Texturer.java.
     */
    public static final float STRIDE_BLOCKS = (float) ((8.0 + 9.0) / 0.55 * 0.9375 / 16.0 * Math.cos(Math.toRadians(10)));
    /** Длина цикла анимации ходьбы (секунды) — её не меняем. */
    public static final float WALK_CYCLE = 1.6F;
    /** Обычная скорость ходьбы игрока, блоков в секунду. */
    private static final double PLAYER_WALK_SPEED = 4.317;
    /** Скорость в костюме ровно под шаг анимации (≈ 1.1 блока/с): опорная стопа не скользит, а отталкивается. */
    public static final double SPEED_MODIFIER = STRIDE_BLOCKS / WALK_CYCLE / PLAYER_WALK_SPEED - 1.0;
    public static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath("sandevistan", "mech_walk_speed");

    /** Замедление ходьбы в костюме: держим, пока игрок в костюме, снимаем, когда нет. */
    /** Ниже этой доли здоровья костюм хромает (медленнее, неровный шаг) и дымит сильнее. */
    public static final float LIMP_HP = 0.3F;
    /** Какую долю урона получает пилот в костюме (броня): ≈ в 4.5 раза меньше. Иссякшее жизнеобеспечение (иссушение) — полностью. */
    public static final float MECH_DAMAGE_TAKEN = 0.22F;

    private static void keepSpeed(ServerPlayer player) {
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        boolean want = ModAttachments.hasMech(player);
        var current = attr.getModifier(SPEED_ID);
        if (!want) {
            if (current != null) attr.removeModifier(SPEED_ID);
            return;
        }
        // Во время атаки стоит на месте; с Сандевистаном — скорость бега игрока; иначе — тяжёлый шаг.
        // -0.99, а не -1: при нулевой скорости игра сужает обзор (FOV), а наш модификатор из расчёта FOV исключается делением.
        double amount = isAttacking(player) || isEquipping(player) || MechVortex.isActive(player) || MechMeteor.isActive(player) || MechSingularity.isActive(player) || MechPush.isActive(player) || MechDeath.isDying(player) || MechShowdown.inCutscene(player) || MechShowdown.inDeath(player) ? -0.99
                : com.smi7i.sandevistan.SandevistanManager.isDamageReduced(player) ? SANDEVISTAN_MODIFIER : SPEED_MODIFIER;
        // Сильно повреждённый костюм хромает — идёт медленнее.
        if (amount > -0.99 && player.getHealth() / player.getMaxHealth() < LIMP_HP) amount = (1 + amount) * 0.82 - 1;
        if (current != null && Math.abs(current.amount() - amount) < 1e-6) return;
        if (current != null) attr.removeModifier(SPEED_ID);
        attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SPEED_ID, amount,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** Скорость бега обычного игрока, блоков в секунду. */
    private static final double PLAYER_SPRINT_SPEED = 5.612;
    /**
     * Множитель костюма при работающем Сандевистане: вместе с его ускорением (+50%) получается ровно скорость бега игрока.
     * (1 + x) · (1 + SPEED_BOOST) = бег / ходьба.
     */
    public static final double SANDEVISTAN_MODIFIER =
            PLAYER_SPRINT_SPEED / PLAYER_WALK_SPEED / (1.0 + com.smi7i.sandevistan.SandevistanManager.SPEED_BOOST) - 1.0;

    // ===== Жизнь в костюме =====
    /** Сколько живёт игрок в костюме без уколов (тики, 20 = 1 с): 1 минута. */
    public static final int LIFE_TICKS = 60 * 20;
    /** Сколько добавляет один укол из шприцов: 30 секунд. */
    public static final int DOSE_TICKS = 30 * 20;
    /** Уколов в шприцах при посадке. */
    public static final int DOSES = 5;
    /** Когда время вышло: урон раз в DYING_INTERVAL тиков (1.0 = полсердца, броня не спасает). */
    public static final float DYING_DAMAGE = 1.0F;
    public static final int DYING_INTERVAL = 20;

    // ===== Атака: комбо из трёх ударов (анимация attack) =====
    /** Длина атаки — как у анимации attack (7.0 с, по видео «атака.mp4» из Roblox). */
    public static final int ATTACK_TICKS = 140;
    /** Перезарядка после атаки (тики): 12 секунд. */
    public static final int ATTACK_COOLDOWN = 12 * 20;
    /** Моменты попадания (тики от начала): хук правой, хук левой, удар об землю — совпадают с анимацией. */
    public static final int HIT_RIGHT = 32, HIT_LEFT = 63, HIT_SLAM = 103;
    /** Хуки: урон, дальность (блоки) и отбрасывание. */
    public static final float HOOK_DAMAGE = 10.0F, HOOK_RANGE = 5.5F, HOOK_KNOCKBACK = 1.2F;
    /** Удар об землю: урон, радиус вокруг точки удара, подброс вверх. */
    public static final float SLAM_DAMAGE = 16.0F, SLAM_RADIUS = 7.5F, SLAM_LAUNCH = 0.7F;

    // ===== Вызов костюма =====
    /** Заряд вызова (тики): после вызова и после гибели в костюме — 30 секунд до следующего вызова. */
    public static final int SUMMON_COOLDOWN = 30 * 20;

    private static final Map<UUID, Integer> ATTACK_CD = new HashMap<>();
    private static final Map<UUID, Integer> SUMMON_CD = new HashMap<>();

    /** Посадка в костюм (анимация): тики. Во время посадки костюм стоит и не атакует. */
    public static final int EQUIP_TICKS = 50;

    public static boolean isEquipping(net.minecraft.world.entity.player.Player player) {
        long start = player.getAttachedOrElse(ModAttachments.MECH_EQUIP_START, 0L);
        return start > 0 && player.level().getGameTime() - start < EQUIP_TICKS;
    }

    public static boolean isAttacking(net.minecraft.world.entity.player.Player player) {
        long start = player.getAttachedOrElse(ModAttachments.MECH_ATTACK_START, 0L);
        return start > 0 && attackAge(player, 0) < ATTACK_TICKS;
    }

    /** Скорость атаки: 2 — если при начале атаки работал Сандевистан. */
    public static int attackSpeed(net.minecraft.world.entity.player.Player player) {
        return Math.max(1, player.getAttachedOrElse(ModAttachments.MECH_ATTACK_SPEED, 1));
    }

    /** Время атаки «по анимации» в тиках (с Сандевистаном идёт в 2 раза быстрее). partial — доля тика для отрисовки. */
    public static float attackAge(net.minecraft.world.entity.player.Player player, float partial) {
        long start = player.getAttachedOrElse(ModAttachments.MECH_ATTACK_START, 0L);
        if (start <= 0) return -1;
        return (player.level().getGameTime() - start + partial) * attackSpeed(player);
    }

    /** Сколько блоков поднимает третий удар атаки. */
    public static final int LEVITATE_COUNT = 14;
    private static final java.util.Random RNG = new java.util.Random();

    /** Урон любой атаки костюма: четверть МАКСИМАЛЬНОГО здоровья цели (сколько бы у неё ни осталось). */
    public static float quarter(net.minecraft.world.entity.LivingEntity e) {
        return e.getMaxHealth() * 0.25F;
    }

    /** Костюм тяжёлый: его не отбрасывают и не подкидывают (голем, равагер, взрывы) — сопротивление отбросу 100%. */
    public static final Identifier KNOCKBACK_ID = Identifier.fromNamespaceAndPath("sandevistan", "mech_knockback");

    /** Здоровье в костюме: 200 (игроку +180 к максимуму, пока он в костюме); при посадке — полное. */
    public static final double MECH_MAX_HEALTH = 200.0;
    public static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath("sandevistan", "mech_health");

    private static void keepHealth(ServerPlayer player) {
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attr == null) return;
        boolean want = ModAttachments.hasMech(player) && player.isAlive();
        var kb = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) {
            boolean hasKb = kb.getModifier(KNOCKBACK_ID) != null;
            if (want && !hasKb) kb.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(KNOCKBACK_ID, 1.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            else if (!want && hasKb) kb.removeModifier(KNOCKBACK_ID);
        }
        boolean has = attr.getModifier(HEALTH_ID) != null;
        if (want && !has) {
            attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(HEALTH_ID,
                    MECH_MAX_HEALTH - 20.0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            player.setHealth(player.getMaxHealth()); // сел в костюм — здоровье полное
        } else if (!want && has) {
            attr.removeModifier(HEALTH_ID);
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        // Повреждения: чем меньше здоровья, тем больше кусков отвалилось (назад не прирастают до новой посадки).
        if (want) {
            int lost = player.getAttachedOrElse(ModAttachments.MECH_LOST, 0);
            int need = lostStageFor(player.getHealth() / Math.max(1.0F, player.getMaxHealth()));
            if (need > lost) {
                player.setAttached(ModAttachments.MECH_LOST, need);
                ServerLevel level = player.level();
                Vec3 at = player.position().add(0, 1.8, 0);
                sound(level, at, com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 1.6F, 0.75F + 0.1F * need);
                sound(level, at, net.minecraft.sounds.SoundEvents.ANVIL_LAND, 0.7F, 0.6F);
                sound(level, at, net.minecraft.sounds.SoundEvents.CHAIN_BREAK, 1.2F, 0.7F);
            }
        }
    }

    /** Пороги здоровья (доля), ниже которых отваливается следующая часть костюма. */
    public static final float[] LOST_THRESHOLDS = {0.85F, 0.70F, 0.55F, 0.40F, 0.25F, 0.12F};

    public static int lostStageFor(float hp) {
        int s = 0;
        for (float t : LOST_THRESHOLDS) if (hp < t) s++;
        return s;
    }

    /** Костюм не прыгает, зато сам перешагивает на блок вверх. */
    public static final Identifier STEP_ID = Identifier.fromNamespaceAndPath("sandevistan", "mech_step_height");
    public static final double STEP_BONUS = 0.45; // 0.6 + 0.45 = 1.05 блока

    /** Костюм приминает траву, цветы, папоротники и снег под ногами (как будто проходит тяжёлая машина). */
    private static void trample(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.onGround() || player.isSpectator()) return;
        double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
        if (dx * dx + dz * dz < 1.0E-4 && player.tickCount % 10 != 0) return;
        ServerLevel level = player.level();
        var box = player.getBoundingBox();
        int y = (int) Math.floor(box.minY + 0.01);
        int broken = 0;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX) && broken < 6; x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ) && broken < 6; z++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    var st = level.getBlockState(pos);
                    if (st.isAir() || !st.canBeReplaced() || !st.getFluidState().isEmpty()) continue;
                    level.destroyBlock(pos, false);
                    broken++;
                }
            }
        }
    }

    private static void keepStep(ServerPlayer player) {
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (attr == null) return;
        boolean want = ModAttachments.hasMech(player);
        boolean has = attr.getModifier(STEP_ID) != null;
        if (want && !has) {
            attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(STEP_ID, STEP_BONUS,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        } else if (!want && has) {
            attr.removeModifier(STEP_ID);
        }
    }

    /** Звук костюма для всех рядом. */
    static void sound(ServerLevel level, Vec3 at, net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, event, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch);
    }

    /**
     * Приземление вызванного костюма (момент касания в анимации spawn): пыль, обломки, кратер, взрывная волна.
     * Вызывается из MechSuitEntity.tick.
     */
    static void land(ServerLevel level, Vec3 at) {
        // Блоки НЕ ломаются — только свои эффекты (купол пыли, кольцо, летящие куски земли) у всех рядом.
        fx(level, MechFxPayload.LAND, at, 0, 3, java.util.List.of());
        GroundScar.circle(level, at, 3.6, 1.1);
        // Отбрасывает всех рядом.
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(at, at).inflate(4.0))) {
            Vec3 d = e.position().subtract(at);
            double len = Math.max(0.5, Math.sqrt(d.x * d.x + d.z * d.z));
            e.push(d.x / len * 0.9, 0.5, d.z / len * 0.9);
            e.hurtMarked = true;
        }
    }

    /**
     * Эффект у всех игроков рядом (рисуется на клиенте своим кодом — MechFx): тип, точка, направление удара,
     * сила и выбитые блоки (их куски разлетаются и крутятся).
     */
    static void fx(ServerLevel level, int type, Vec3 at, float yaw, int power, java.util.List<Broken> broken) {
        long[] pos = new long[broken.size()];
        int[] states = new int[broken.size()];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = broken.get(i).pos().asLong();
            states[i] = net.minecraft.world.level.block.Block.getId(broken.get(i).state());
        }
        MechFxPayload payload = new MechFxPayload(type, at.x, at.y, at.z, yaw, power,
                net.minecraft.world.level.block.Block.getId(groundAt(level, at)), pos, states);
        for (ServerPlayer p : net.fabricmc.fabric.api.networking.v1.PlayerLookup.around(level, at, 128)) {
            ServerPlayNetworking.send(p, payload);
        }
    }

    /** Выбитый блок: где был и какой. */
    record Broken(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {}

    /** Кратер: убирает блоки в круге радиуса r вокруг точки — только 1 слой земли (без выпадения предметов). */
    private static java.util.List<Broken> crater(ServerLevel level, Vec3 at, double r) {
        java.util.List<Broken> out = new java.util.ArrayList<>();
        java.util.Set<BlockPos> done = new java.util.HashSet<>();
        int baseY = BlockPos.containing(at).getY();
        for (double x = -r; x <= r; x += 0.5) {
            for (double z = -r; z <= r; z += 0.5) {
                if (x * x + z * z > r * r) continue;
                breakColumn(level, at.x + x, at.z + z, baseY, done, out);
            }
        }
        return out;
    }

    /**
     * Борозда вперёд от костюма: от from до to блоков по направлению dir, шириной width — тоже 1 слой.
     * Так удар ломает «на дальность», а не вглубь.
     */
    private static java.util.List<Broken> trench(ServerLevel level, Vec3 origin, Vec3 dir, double from, double to, double width) {
        java.util.List<Broken> out = new java.util.ArrayList<>();
        java.util.Set<BlockPos> done = new java.util.HashSet<>();
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        int baseY = BlockPos.containing(origin).getY();
        for (double d = from; d <= to; d += 0.5) {
            double w = width * (0.6 + 0.4 * (d - from) / Math.max(0.1, to - from)); // к концу чуть шире
            for (double s = -w / 2; s <= w / 2; s += 0.5) {
                Vec3 p = origin.add(dir.scale(d)).add(side.scale(s));
                breakColumn(level, p.x, p.z, baseY, done, out);
            }
        }
        return out;
    }

    /**
     * Столбик в точке (x, z): ломается блок на уровне ног (препятствие) и ОДИН слой земли под ногами — не глубже.
     */
    private static void breakColumn(ServerLevel level, double x, double z, int baseY, java.util.Set<BlockPos> done,
                                    java.util.List<Broken> out) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos pos = BlockPos.containing(x, baseY + dy, z);
            if (!done.add(pos)) continue;
            var st = level.getBlockState(pos);
            if (st.isAir()) continue;
            Broken b = breakBlock(level, pos);
            if (b != null) out.add(b);
        }
    }

    // ===== Рывок (dash) =====
    /** Рывков подряд (заряды) и перезарядка КАЖДОГО использованного заряда (тики): 3 секунды. */
    public static final int DASH_CHARGES = 3;
    public static final int DASH_COOLDOWN = 60;
    /** Кого задел по пути: урон и сила отброса. */
    public static final float DASH_DAMAGE = 4.0F;
    public static final double DASH_KNOCKBACK = 1.1;
    /** Перезарядка использованных зарядов рывка: у каждого свой таймер. */
    private static final Map<UUID, java.util.List<Integer>> DASH_RECHARGE = new HashMap<>();

    public static int dashCharges(ServerPlayer player) {
        return DASH_CHARGES - DASH_RECHARGE.getOrDefault(player.getUUID(), java.util.List.of()).size();
    }

    /** Для клиента: заряды * 10000 + тиков до ближайшего восстановления. */
    private static int dashState(ServerPlayer player) {
        java.util.List<Integer> list = DASH_RECHARGE.getOrDefault(player.getUUID(), java.util.List.of());
        int next = 0;
        for (int v : list) next = next == 0 ? v : Math.min(next, v);
        return (DASH_CHARGES - list.size()) * 10000 + next;
    }

    private static void tickDashCharges(ServerPlayer player) {
        java.util.List<Integer> list = DASH_RECHARGE.get(player.getUUID());
        if (list == null || list.isEmpty()) return;
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            int v = list.get(i) - 1;
            if (v <= 0) {
                list.remove(i);
                changed = true;
            } else {
                list.set(i, v);
            }
        }
        if (changed || player.tickCount % 20 == 0) sync(player);
    }
    public static final double DASH_DISTANCE = 5.0;
    public static final int DASH_TICKS = 5;

    /** Рывок: проверка и перезарядка, звук и пыль для всех. yaw — направление рывка. */
    private static void dash(ServerPlayer player, float yaw) {
        if (!ModAttachments.hasMech(player) || !player.isAlive() || isAttacking(player) || isEquipping(player)
                || MechVortex.isActive(player) || MechMeteor.isActive(player) || MechSingularity.isActive(player) || MechPush.isActive(player) || dashCharges(player) <= 0) {
            sync(player);
            return;
        }
        ServerLevel level = player.level();
        Vec3 from = player.position();
        Vec3 to = dashTarget(player, Vec3.directionFromRotation(0, yaw));
        if (to.distanceToSqr(from) < 0.25) { // некуда — стена вплотную
            sync(player);
            return;
        }
        DASH_RECHARGE.computeIfAbsent(player.getUUID(), k -> new java.util.ArrayList<>()).add(DASH_COOLDOWN);
        sync(player);
        dashHit(player, from, to);
        // Телепорт: исчез в одной точке — появился в другой (взгляд не меняется).
        sound(level, from, com.smi7i.sandevistan.ModSounds.MECH_DASH, 1.6F, 1.0F);
        sound(level, from, com.smi7i.sandevistan.ModSounds.BOOM_WHOOSH, 1.5F, 1.15F);
        sound(level, from, com.smi7i.sandevistan.ModSounds.BOOM_SONIC, 1.6F, 1.1F);
        fx(level, MechFxPayload.DASH, from, yaw, 1, java.util.List.of());
        player.connection.teleport(to.x, to.y, to.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        fx(level, MechFxPayload.DASH_END, to, yaw, 1, java.util.List.of());
        // Следы рывка: сорванная земля в точке ухода, борозда по пути, вмятина в точке появления.
        Vec3 dashDir = to.subtract(from);
        double dashLen = Math.sqrt(dashDir.x * dashDir.x + dashDir.z * dashDir.z);
        if (dashLen > 0.5) GroundScar.strip(level, from, new Vec3(dashDir.x / dashLen, 0, dashDir.z / dashLen), 0, dashLen, 1.0, 1.6, 0.55);
        GroundScar.circle(level, from, 1.6, 0.7);
        GroundScar.circle(level, to, 2.0, 0.8);
    }

    /** Всех, кто оказался на пути рывка (полоса шириной ~2.4 блока), отбрасывает в стороны и вперёд, небольшой урон. */
    private static void dashHit(ServerPlayer player, Vec3 from, Vec3 to) {
        ServerLevel level = player.level();
        Vec3 path = to.subtract(from);
        double len = path.length();
        if (len < 0.1) return;
        Vec3 dir = path.scale(1 / len);
        boolean hit = false;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(from, to).inflate(1.4, 2.0, 1.4);
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                e -> e != player && e.isAlive())) {
            Vec3 rel = e.position().subtract(from);
            double along = rel.dot(dir);
            if (along < -0.5 || along > len + 0.8) continue;
            Vec3 side = rel.subtract(dir.scale(along));
            double dist = Math.sqrt(side.x * side.x + side.z * side.z);
            if (dist > 1.2 + e.getBbWidth() / 2) continue;
            // В сторону от линии рывка и немного вперёд.
            Vec3 away = dist > 0.05 ? new Vec3(side.x / dist, 0, side.z / dist) : new Vec3(-dir.z, 0, dir.x);
            Vec3 push = away.scale(DASH_KNOCKBACK).add(dir.scale(0.5));
            e.hurtServer(level, player.damageSources().playerAttack(player), quarter(e));
            e.push(push.x, 0.35, push.z);
            e.hurtMarked = true;
            hit = true;
        }
        if (hit) {
            sound(level, to, com.smi7i.sandevistan.ModSounds.MECH_HIT1, 1.3F, 1.35F);
            sound(level, to, com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 1.3F, 1.1F);
        }
    }

    /**
     * Куда телепортироваться: идём от игрока по направлению мелкими шагами (0.25 блока) до DASH_DISTANCE.
     * На каждом шаге хитбокс костюма должен помещаться: на той же высоте, на 1 блок выше (ступенька)
     * или ниже (спуск до 3 блоков — встаём на землю). Упёрлись в стену — останавливаемся на последнем
     * свободном месте. Так нельзя пройти сквозь стену и застрять в блоках.
     */
    private static Vec3 dashTarget(ServerPlayer player, Vec3 dir) {
        ServerLevel level = player.level();
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        Vec3 start = player.position();
        Vec3 last = start;
        double y = start.y;
        for (double d = 0.25; d <= DASH_DISTANCE + 1e-6; d += 0.25) {
            double x = start.x + dir.x * d, z = start.z + dir.z * d;
            Double found = null;
            // Сначала та же высота, потом ступенька вверх (до 1 блока).
            for (double up : new double[]{0, 0.5, 1.0}) {
                if (fits(level, player, box, start, x, y + up, z)) {
                    found = y + up;
                    break;
                }
            }
            if (found == null) break; // стена
            // Под ногами пусто — опускаемся на землю (не дальше 3 блоков, иначе висим в воздухе — тоже можно).
            double ny = found;
            for (int i = 0; i < 12 && fits(level, player, box, start, x, ny - 0.25, z); i++) ny -= 0.25;
            y = ny;
            last = new Vec3(x, y, z);
        }
        // Точно на поверхность: опускаем до касания.
        double settle = last.y;
        for (int i = 0; i < 8 && fits(level, player, box, start, last.x, settle - 0.03125, last.z); i++) settle -= 0.03125;
        return new Vec3(last.x, settle, last.z);
    }

    private static boolean fits(ServerLevel level, ServerPlayer player, net.minecraft.world.phys.AABB box, Vec3 start,
                                double x, double y, double z) {
        return level.noCollision(player, box.move(x - start.x, y - start.y, z - start.z));
    }

    /** Снятие костюма: пилот погибает (как /kill — не спасают ни броня, ни тотем, ни творческий режим). */
    private static void eject(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.isAlive()) return;
        ServerLevel level = player.level();
        fx(level, MechFxPayload.EJECT, player.position(), player.getYRot(), 2, java.util.List.of());
        // Звуки из видео пользователя: тяжёлый удар и скрежет костюма (без голоса голема).
        sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_LAND, 2.0F, 0.7F);
        sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_HIT2, 1.5F, 0.6F);
        sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 1.8F, 0.8F);
        player.kill(level);
        // На всякий случай (если смерть чем-то отменили) костюм всё равно снимается.
        if (ModAttachments.hasMech(player)) {
            player.removeAttached(ModAttachments.MECH);
            player.refreshDimensions();
        }
    }

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(SummonMechPayload.TYPE, SummonMechPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MechAttackPayload.TYPE, MechAttackPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MechAttackPayload.TYPE, (payload, context) -> startAttack(context.player()));
        PayloadTypeRegistry.serverboundPlay().register(EjectMechPayload.TYPE, EjectMechPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MechFxPayload.TYPE, MechFxPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BoardPayload.TYPE, BoardPayload.CODEC);
        MechVortex.init();
        MechMeteor.init();
        MechSingularity.init();
        MechPush.init();
        MechDeath.init();
        MechShowdown.init();
        ProjectileParry.init();
        PayloadTypeRegistry.serverboundPlay().register(DashPayload.TYPE, DashPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DashPayload.TYPE, (payload, context) -> dash(context.player(), payload.yaw()));
        // В костюме руками блоки не ломаются — только способностями.
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                ModAttachments.hasMech(player) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !ModAttachments.hasMech(player));
        ServerPlayNetworking.registerGlobalReceiver(EjectMechPayload.TYPE, (payload, context) -> eject(context.player()));
        // Гибель в костюме: костюм потерян, вызов снова заряжается.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p && ModAttachments.hasMech(p)) SUMMON_CD.put(p.getUUID(), SUMMON_COOLDOWN);
        });
        PayloadTypeRegistry.serverboundPlay().register(InjectPayload.TYPE, InjectPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MechStatePayload.TYPE, MechStatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SummonMechPayload.TYPE, (payload, context) -> summon(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(InjectPayload.TYPE, (payload, context) -> inject(context.player()));
        // Вошедшему игроку в костюме — сразу правильный размер (хитбокс и высота глаз) и таймер.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            handler.player.refreshDimensions();
            sync(handler.player);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                keepSpeed(p);
                keepStep(p);
                trample(p);
                keepHealth(p);
                tickBoarding(p);
                MechVortex.tick(p);
                MechMeteor.tick(p);
                MechSingularity.tick(p);
                MechPush.tick(p);
                MechDeath.tick(p);
                MechShowdown.tick(p);
                tickDashCharges(p);
                tickLife(p);
                tickAttack(p);
                tickCooldowns(p);
            }
        });
    }

    /** Таймер жизни в костюме: идёт вниз; когда кончился — игрок медленно умирает. */
    private static void tickLife(ServerPlayer player) {
        // Таймер идёт и в творческом режиме (там умирание просто не наносит урона).
        if (!ModAttachments.hasMech(player) || !player.isAlive() || player.isSpectator()) return;
        int ticks = player.getAttachedOrElse(ModAttachments.MECH_TICKS, 0);
        if (ticks > 0) {
            ticks--;
            player.setAttached(ModAttachments.MECH_TICKS, ticks);
            if (ticks % 20 == 0) sync(player); // раз в секунду сверяем таймер с клиентом
        } else if (player.tickCount % DYING_INTERVAL == 0) {
            player.hurtServer(player.level(), player.damageSources().wither(), DYING_DAMAGE);
        }
    }

    /** Укол из шприцов: +30 секунд, всего 5 уколов. Можно и когда время уже вышло — это останавливает умирание. */
    /** Сколько жизни в костюме добавляет один укол (тики): 45 секунд. */
    public static final int DOSE_ADD = 45 * 20;

    public static void inject(ServerPlayer player) {
        if (!ModAttachments.hasMech(player)) return;
        int doses = player.getAttachedOrElse(ModAttachments.MECH_DOSES, 0);
        if (doses <= 0) {
            sync(player);
            return;
        }
        player.setAttached(ModAttachments.MECH_DOSES, doses - 1);
        // Укол добавляет DOSE_ADD тиков (45 с), но не больше минуты.
        player.setAttached(ModAttachments.MECH_TICKS, Math.min(LIFE_TICKS, player.getAttachedOrElse(ModAttachments.MECH_TICKS, 0) + DOSE_ADD));
        player.level().playSound(null, player.getX(), player.getY() + 2.2, player.getZ(),
                com.smi7i.sandevistan.ModSounds.MECH_INJECT, net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 1.0F);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        ServerPlayNetworking.send(player, new MechStatePayload(player.getAttachedOrElse(ModAttachments.MECH_TICKS, 0),
                player.getAttachedOrElse(ModAttachments.MECH_DOSES, 0),
                ATTACK_CD.getOrDefault(player.getUUID(), 0), SUMMON_CD.getOrDefault(player.getUUID(), 0),
                dashState(player), MechVortex.cooldown(player), MechMeteor.cooldown(player), MechSingularity.cooldown(player), MechPush.cooldown(player)));
    }

    /** Перезарядки атаки и вызова идут вниз; раз в секунду и в момент готовности — сверяем с клиентом. */
    private static void tickCooldowns(ServerPlayer player) {
        boolean changed = false;
        for (Map<UUID, Integer> map : java.util.List.of(ATTACK_CD, SUMMON_CD)) {
            Integer cd = map.get(player.getUUID());
            if (cd == null) continue;
            cd--;
            if (cd <= 0) map.remove(player.getUUID()); else map.put(player.getUUID(), cd);
            if (cd <= 0 || cd % 20 == 0) changed = true;
        }
        if (changed) sync(player);
    }

    /** Способность «атака»: в костюме, не во время атаки и после перезарядки. */
    public static void startAttack(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.isAlive() || isAttacking(player) || isEquipping(player)
                || MechVortex.isActive(player) || MechMeteor.isActive(player) || MechSingularity.isActive(player) || MechPush.isActive(player) || ATTACK_CD.containsKey(player.getUUID())) {
            sync(player);
            return;
        }
        player.setAttached(ModAttachments.MECH_ATTACK_SPEED,
                com.smi7i.sandevistan.SandevistanManager.isDamageReduced(player) ? 2 : 1);
        player.setAttached(ModAttachments.MECH_ATTACK_START, player.level().getGameTime());
    }

    /** Удары атаки — в те же моменты, что и в анимации; в конце — перезарядка. */
    private static void tickAttack(ServerPlayer player) {
        long start = player.getAttachedOrElse(ModAttachments.MECH_ATTACK_START, 0L);
        if (start <= 0) return;
        int speed = attackSpeed(player);
        long age = (player.level().getGameTime() - start) * speed;
        long prev = age - speed; // за этот тик анимация прошла (prev, age]
        if (!ModAttachments.hasMech(player) || !player.isAlive() || age >= ATTACK_TICKS) {
            player.setAttached(ModAttachments.MECH_ATTACK_START, 0L);
            if (ModAttachments.hasMech(player)) ATTACK_CD.put(player.getUUID(), ATTACK_COOLDOWN);
            sync(player);
            return;
        }
        ServerLevel level = player.level();
        float pitch = speed > 1 ? 1.25F : 1.0F;
        // Звуки начинаются раньше удара: самый громкий момент звука совпадает с попаданием.
        if (crossed(prev, age, HIT_RIGHT - HIT1_SOUND_LEAD * speed)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_HIT1, 2.0F, pitch);
        // Как в видео: второй удар — тяжёлый грохот, третий — второй хук.
        if (crossed(prev, age, HIT_LEFT - SLAM_SOUND_LEAD * speed)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_SLAM, 3.0F, pitch);
        if (crossed(prev, age, HIT_SLAM - HIT2_SOUND_LEAD * speed)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_HIT2, 2.6F, pitch);
        // Слои из пака: свист замаха перед каждым ударом, удар и столкновение в момент попадания.
        if (crossed(prev, age, HIT_RIGHT - 14 * speed)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_WHOOSH, 1.4F, pitch);
        if (crossed(prev, age, HIT_LEFT - 12 * speed)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_WHOOSH, 1.4F, 0.9F * pitch);
        if (crossed(prev, age, HIT_SLAM - 16 * speed)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_MOTION, 1.6F, 0.85F * pitch);
        if (crossed(prev, age, HIT_RIGHT)) sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 1.8F, pitch);
        if (crossed(prev, age, HIT_LEFT)) {
            sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 2.0F, 0.9F * pitch);
            sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 1.6F, 0.85F * pitch);
        }
        if (crossed(prev, age, HIT_SLAM)) {
            sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 2.6F, 0.72F * pitch);
            sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_LAND, 2.2F, 0.9F * pitch);
            sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 2.2F, 0.8F * pitch);
        }
        if (crossed(prev, age, HIT_RIGHT)) hook(player, 1);
        if (crossed(prev, age, HIT_LEFT)) hook(player, 2);
        if (crossed(prev, age, HIT_SLAM)) slam(player);
        // Ударная волна идёт в реальном времени (её не ускоряем — так её видно).
        long realSince = (player.level().getGameTime() - start) - (HIT_SLAM + speed - 1) / speed;
        if (realSince >= 1 && realSince <= WAVE_TICKS) shockwave(player, (int) realSince);
    }

    /** На сколько тиков звук удара начинается раньше самого удара (где у звука самый громкий момент). */
    private static final int HIT1_SOUND_LEAD = 23, HIT2_SOUND_LEAD = 9, SLAM_SOUND_LEAD = 12;

    private static boolean crossed(long prev, long now, long mark) {
        return prev < mark && mark <= now;
    }

    // ===== Эффекты ударов и ударная волна =====
    /** Ударная волна третьего удара: длится тиков, уходит вперёд на блоков, ширина в конце. */
    public static final int WAVE_TICKS = 28;
    /** Кого уже задела ударная волна этого удара. */
    private static final Map<UUID, java.util.Set<Integer>> WAVE_HIT = new HashMap<>();
    public static final double WAVE_LENGTH = 22.0, WAVE_WIDTH = 4.5;

    /** Блок под точкой (для обломков и пыли того же цвета, что земля). */
    private static net.minecraft.world.level.block.state.BlockState groundAt(ServerLevel level, Vec3 p) {
        BlockPos pos = BlockPos.containing(p.x, p.y - 0.2, p.z);
        for (int i = 0; i < 3; i++, pos = pos.below()) {
            var st = level.getBlockState(pos);
            if (!st.isAir()) return st;
        }
        return net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
    }

    /** Хук: бьёт всех перед костюмом в пределах HOOK_RANGE и отбрасывает. index 1/2 — второй мощнее (больше обломков). */
    private static void hook(ServerPlayer player, int index) {
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 p = player.position().add(look.scale(2.2));
        ServerLevel level = player.level();
        // Клешня задевает землю: маленькая яма, куски летят вперёд и в стороны, дым.
        fx(level, MechFxPayload.HIT, p, player.getYRot(), index,
                trench(level, player.position(), look, 1.5, 4.0 + 2.0 * index, 1.2 + 0.6 * index));
        GroundScar.strip(level, player.position(), look, 1.2, 5.0 + 2.5 * index, 1.4 + 0.6 * index, 2.6 + 1.0 * index, 0.9 + 0.2 * index);
        hookHit(player);
    }

    /** Хук бьёт полосой вперёд: длина, полуширина у костюма и насколько она расширяется на каждый блок вдаль. */
    public static final double HOOK_LENGTH = 9.0, HOOK_HALF_WIDTH = 1.8, HOOK_SPREAD = 0.3;
    /** Удар об землю — полоса шире и дальше. */
    public static final double SLAM_LENGTH = 14.0, SLAM_HALF_WIDTH = 3.0, SLAM_SPREAD = 0.32;

    /** Точка (относительно костюма) в полосе вперёд по look: длиной len, полушириной half + spread × расстояние. */
    static boolean inStrip(Vec3 rel, Vec3 look, double len, double half, double spread, double pad) {
        double along = rel.x * look.x + rel.z * look.z;
        double across = Math.abs(rel.x * -look.z + rel.z * look.x);
        return along >= -1.0 - pad && along <= len + pad && across <= half + spread * Math.max(0, along) + pad
                && Math.abs(rel.y) < 4.0;
    }

    private static void hookHit(ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 c = player.position();
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                player.getBoundingBox().inflate(HOOK_LENGTH + 1.0, 3.0, HOOK_LENGTH + 1.0), e -> e != player && e.isAlive())) {
            Vec3 to = e.position().subtract(c);
            Vec3 flat = new Vec3(to.x, 0, to.z);
            // Удар направленный: широкая полоса вперёд, расширяется вдаль (а не круг вокруг).
            if (!inStrip(to, look, HOOK_LENGTH, HOOK_HALF_WIDTH, HOOK_SPREAD, e.getBbWidth() / 2)) continue;
            e.hurtServer(level, player.damageSources().playerAttack(player), quarter(e));
            Vec3 dir = flat.normalize().scale(HOOK_KNOCKBACK);
            e.push(dir.x, 0.25, dir.z); // отбрасывает от костюма
            e.hurtMarked = true;
        }
    }

    /** Удар об землю: всех вокруг точки удара — урон и подброс, грохот и пыль. */
    private static void slam(ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 point = player.position().add(look.scale(2.0));
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                player.getBoundingBox().inflate(SLAM_LENGTH + 1.0, 3.0, SLAM_LENGTH + 1.0), e -> e != player && e.isAlive())) {
            Vec3 to = e.position().subtract(point);
            double dist = Math.sqrt(to.x * to.x + to.z * to.z);
            if (!inStrip(e.position().subtract(player.position()), look, SLAM_LENGTH, SLAM_HALF_WIDTH, SLAM_SPREAD, e.getBbWidth() / 2)) continue;
            float k = (float) Math.max(0.5, 1.0 - dist / SLAM_LENGTH * 0.5); // ближе — сильнее
            e.hurtServer(level, player.damageSources().playerAttack(player), quarter(e));
            Vec3 push = dist > 0.01 ? new Vec3(to.x / dist, 0, to.z / dist).scale(0.8 * k) : Vec3.ZERO;
            e.push(push.x, SLAM_LAUNCH * k, push.z);
            e.hurtMarked = true;
        }
        WAVE_HIT.put(player.getUUID(), new java.util.HashSet<>());
        fx(level, MechFxPayload.SLAM, point, player.getYRot(), 3, crater(level, point, 1.7)); // не достаёт до ног костюма
        GroundScar.circle(level, point, 3.4, 1.3);
        GroundScar.strip(level, player.position(), look, 2.0, SLAM_LENGTH * 0.7, 3.0, 5.5, 0.8);
        // Третий удар: часть блоков вокруг поднимается и зависает в воздухе, синеет и резко падает (рисует клиент).
        java.util.List<Broken> lifted = new java.util.ArrayList<>();
        for (int i = 0; i < 40 && lifted.size() < LEVITATE_COUNT; i++) {
            // Блоки поднимаются впереди, веером вдаль (как бьёт сам удар).
            double along = 1.0 + RNG.nextDouble() * 9.0;
            double across = (RNG.nextDouble() * 2 - 1) * (SLAM_HALF_WIDTH + SLAM_SPREAD * along);
            Vec3 at = point.add(look.scale(along)).add(-look.z * across, 0, look.x * across);
            BlockPos top = surface(level, BlockPos.containing(at.x, point.y + 1.5, at.z), 3);
            if (top == null) continue;
            Broken b = breakBlock(level, top);
            if (b != null) lifted.add(b);
        }
        if (!lifted.isEmpty()) fx(level, MechFxPayload.LEVITATE, point, player.getYRot(), 3, lifted);
    }

    /**
     * Ударная волна после удара об землю: за WAVE_TICKS уходит вперёд на WAVE_LENGTH, расширяясь до WAVE_WIDTH;
     * ломает верхний слой блоков, часть из них подбрасывает в воздух (падая, они не ставятся обратно).
     * Не трогает неразрушимые и очень твёрдые блоки (бедрок, обсидиан) и блоки с содержимым (сундуки и т.п.).
     */
    private static void shockwave(ServerPlayer player, int tick) {
        ServerLevel level = player.level();
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 side = new Vec3(-look.z, 0, look.x);
        double t0 = (tick - 1) / (double) WAVE_TICKS, t1 = tick / (double) WAVE_TICKS;
        double d0 = 2.0 + WAVE_LENGTH * t0, d1 = 2.0 + WAVE_LENGTH * t1;
        double width = 1.0 + (WAVE_WIDTH - 1.0) * t1;
        Vec3 origin = player.position();
        java.util.Set<BlockPos> done = new java.util.HashSet<>();
        java.util.List<Broken> broken = new java.util.ArrayList<>();
        int baseY = BlockPos.containing(origin).getY();
        for (double d = d0; d < d1; d += 0.5) {
            for (double w = -width / 2; w <= width / 2; w += 0.5) {
                Vec3 p = origin.add(look.scale(d)).add(side.scale(w));
                breakColumn(level, p.x, p.z, baseY, done, broken); // 1 слой земли + препятствия, не глубже
            }
        }
        // За волной остаётся вспаханная полоса.
        GroundScar.strip(level, origin, look, d0, d1, width, width, 0.6);
        // Волна задевает всех на своём пути (каждого один раз за удар).
        java.util.Set<Integer> hitSet = WAVE_HIT.computeIfAbsent(player.getUUID(), k -> new java.util.HashSet<>());
        Vec3 mid = origin.add(look.scale((d0 + d1) / 2));
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(mid, mid).inflate(width / 2 + 1.5, 2.0, width / 2 + 1.5), e -> e != player && e.isAlive())) {
            Vec3 rel = e.position().subtract(origin);
            double along = rel.x * look.x + rel.z * look.z, across = Math.abs(rel.x * side.x + rel.z * side.z);
            if (along < d0 - 1.0 || along > d1 + 1.0 || across > width / 2 + 1.0 || !hitSet.add(e.getId())) continue;
            e.hurtServer(level, player.damageSources().playerAttack(player), quarter(e) * 0.5F);
            e.push(look.x * 0.6, 0.55, look.z * 0.6);
            e.hurtMarked = true;
        }
        // Дым и куски по фронту волны.
        Vec3 front = origin.add(look.scale(d1));
        fx(level, MechFxPayload.WAVE, front, player.getYRot(), Math.max(1, (int) Math.round(width)), broken);
        if (tick % 5 == 0) {
            sound(level, front, com.smi7i.sandevistan.ModSounds.MECH_LAND, 1.2F, 0.7F + 0.3F * (float) t1);
        }
    }

    /** Верхний твёрдый блок в столбце (ищем вниз от start не дальше depth). */
    private static BlockPos surface(ServerLevel level, BlockPos start, int depth) {
        BlockPos pos = start;
        for (int i = 0; i < depth + 4; i++, pos = pos.below()) {
            var st = level.getBlockState(pos);
            if (!st.isAir() && !st.getCollisionShape(level, pos).isEmpty()) return pos;
        }
        return null;
    }

    /**
     * Тихо убирает блок (без выпадения и без ванильных частиц — куски рисует MechFx).
     * Не трогает неразрушимые и очень твёрдые (бедрок, обсидиан), блоки с содержимым и жидкости.
     */
    private static Broken breakBlock(ServerLevel level, BlockPos pos) {
        var st = level.getBlockState(pos);
        float hardness = st.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 30 || st.hasBlockEntity() || !st.getFluidState().isEmpty()) return null;
        // Блок НЕ ломается (по просьбе пользователя) — клиент только рисует, будто он разлетается.
        return new Broken(pos.immutable(), st);
    }

    /** Костюм падает с неба туда, куда смотрит игрок (не дальше SUMMON_RANGE). */
    public static void summon(ServerPlayer player) {
        if (ModAttachments.hasMech(player)) return; // уже в костюме
        // Вызвать костюм можно только с надетым Сандевистаном (имплант в слоте).
        if (!com.smi7i.sandevistan.ImplantSlot.isImplant(player.getAttachedOrCreate(ModAttachments.IMPLANT))) {
            sync(player);
            return;
        }
        if (SUMMON_CD.containsKey(player.getUUID())) { // вызов ещё заряжается
            sync(player);
            return;
        }
        ServerLevel level = player.level();
        HitResult hit = player.pick(SUMMON_RANGE, 1.0F, false);
        Vec3 target = hit.getType() == HitResult.Type.MISS
                ? player.getEyePosition().add(player.getLookAngle().scale(SUMMON_RANGE))
                : hit.getLocation();
        double groundY = findGround(level, target);

        // У игрока только один костюм: старый (где бы ни стоял в загруженном мире) взрывается.
        UUID old = SUMMONED.remove(player.getUUID());
        for (ServerLevel l : player.level().getServer().getAllLevels()) {
            for (MechSuitEntity s : l.getEntities(MECH_SUIT, e -> player.getUUID().equals(e.getOwner()) || e.getUUID().equals(old))) {
                explodeSuit(l, s);
            }
        }

        MechSuitEntity suit = new MechSuitEntity(MECH_SUIT, level);
        suit.setPos(target.x, groundY, target.z);
        suit.setYRot(player.getYRot() + 180.0F); // лицом к игроку
        suit.setSpawnTime(level.getGameTime());
        suit.setOwner(player.getUUID());
        level.addFreshEntity(suit);
        SUMMONED.put(player.getUUID(), suit.getUUID());
        SUMMON_CD.put(player.getUUID(), SUMMON_COOLDOWN);
        sync(player);
    }

    /** Верх твёрдого блока под точкой (ищем вниз до 16 блоков). */
    private static double findGround(ServerLevel level, Vec3 at) {
        BlockPos start = BlockPos.containing(at.x, at.y + 0.5, at.z);
        for (int i = 0; i < 16; i++) {
            BlockPos pos = start.below(i);
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return at.y;
    }

    /** Старый костюм взрывается: эффекты у всех рядом (MechFx), грохот, отброс, без разрушения блоков. */
    private static void explodeSuit(ServerLevel level, MechSuitEntity suit) {
        Vec3 at = suit.position();
        fx(level, MechFxPayload.SUIT_EXPLODE, at, suit.getYRot(), 3, java.util.List.of());
        GroundScar.circle(level, at, 3.0, 1.2);
        level.playSound(null, at.x, at.y + 1, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 3.0F, 0.75F);
        sound(level, at, com.smi7i.sandevistan.ModSounds.MECH_LAND, 3.0F, 0.8F);
        sound(level, at, com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 3.0F, 0.7F);
        sound(level, at, com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 3.0F, 0.6F);
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(at, at).inflate(5.0))) {
            Vec3 d = e.position().subtract(at);
            double len = Math.max(0.5, d.length());
            double k = Math.max(0, 1 - len / 5.0);
            e.push(d.x / len * 1.2 * k, 0.5 * k, d.z / len * 1.2 * k);
            e.hurtMarked = true;
        }
        suit.discard();
    }

    /** ПКМ по костюму: игрок встаёт на его место и сам становится мехом. */
    // ===== Кат-сцена посадки =====
    /**
     * Тайминг кат-сцены (тики с ПКМ), общий для сервера и клиента (client/mech/MechCutscene):
     * до BOARD_WALK игрок подходит к костюму, стучит в BOARD_KNOCK1 и BOARD_KNOCK2, в BOARD_PULL костюм
     * затягивает его (экран уже чёрный), в BOARD_REVEAL чёрный экран раскрывается, BOARD_END — конец.
     */
    public static final int BOARD_WALK = 40, BOARD_KNOCK1 = 46, BOARD_KNOCK2 = 55, BOARD_PULL = 68,
            BOARD_REVEAL = 96, BOARD_END = 110;
    /** Костюм хватает: анимация grab начинается за столько тиков до PULL (руки смыкаются ровно в PULL — экран чернеет). */
    public static final int BOARD_STRIKE = 11;

    private record Board(UUID suit, long start) {}

    private static final Map<UUID, Board> BOARDING = new HashMap<>();

    public static boolean isBoarding(net.minecraft.world.entity.player.Player player) {
        return BOARDING.containsKey(player.getUUID());
    }

    /** ПКМ по костюму: начинается кат-сцена (подход, стук, затягивание), сама посадка — в BOARD_PULL. */
    public static void startBoarding(ServerPlayer player, MechSuitEntity suit) {
        if (ModAttachments.hasMech(player) || BOARDING.containsKey(player.getUUID()) || !player.isAlive()) return;
        if (player.level().getGameTime() - suit.getSpawnTime() < MechSuitEntity.LAND_TICK + 10) return; // ещё падает
        for (Board b : BOARDING.values()) if (b.suit().equals(suit.getUUID())) return; // в него уже садятся
        long now = player.level().getGameTime();
        BOARDING.put(player.getUUID(), new Board(suit.getUUID(), now));
        player.setAttached(ModAttachments.MECH_BOARD_START, now);
        suit.setBoardTime(now);
        ServerPlayNetworking.send(player, new BoardPayload(suit.getId(), now));
    }

    private static void tickBoarding(ServerPlayer player) {
        Board b = BOARDING.get(player.getUUID());
        if (b == null) return;
        ServerLevel level = player.level();
        long age = level.getGameTime() - b.start();
        net.minecraft.world.entity.Entity e = level.getEntity(b.suit());
        if (!(e instanceof MechSuitEntity suit) || !player.isAlive() || ModAttachments.hasMech(player)) {
            BOARDING.remove(player.getUUID());
            player.setAttached(ModAttachments.MECH_BOARD_START, 0L);
            if (e instanceof MechSuitEntity s) s.setBoardTime(0);
            return;
        }
        if (age == BOARD_KNOCK1 || age == BOARD_KNOCK2) {
            // Аккуратный стук костяшками по броне (саму руку анимирует клиент — видят все).
            sound(level, suit.position().add(0, 1.3, 0),
                    age == BOARD_KNOCK1 ? com.smi7i.sandevistan.ModSounds.MECH_KNOCK : com.smi7i.sandevistan.ModSounds.MECH_KNOCK2, 1.2F, 1.0F);
        }
        if (age == BOARD_PULL - BOARD_STRIKE) {
            // Костюм оживает: завод перед захватом.
            sound(level, suit.position(), com.smi7i.sandevistan.ModSounds.MECH_GRAB_WINDUP, 1.6F, 1.0F);
        }
        if (age == BOARD_PULL - 1) {
            // Лязг захвата — в момент, когда руки смыкаются и экран чернеет.
            sound(level, suit.position(), com.smi7i.sandevistan.ModSounds.MECH_GRAB, 2.2F, 1.0F);
        }
        if (age >= BOARD_PULL) {
            BOARDING.remove(player.getUUID());
            player.setAttached(ModAttachments.MECH_BOARD_START, 0L);
            if (player.distanceTo(suit) < 8) equip(player, suit);
        }
    }

    public static void equip(ServerPlayer player, MechSuitEntity suit) {
        if (ModAttachments.hasMech(player)) return;
        // Пока костюм падает — залезть нельзя.
        if (player.level().getGameTime() - suit.getSpawnTime() < MechSuitEntity.LAND_TICK + 10) return;
        player.connection.teleport(suit.getX(), suit.getY(), suit.getZ(), suit.getYRot() + 180.0F, player.getXRot());
        player.setAttached(ModAttachments.MECH_EQUIP_START, player.level().getGameTime());
        // Звук сборки — у остальных; сам пилот в это время в «гулкой тишине» (свой звук он услышит при раскрытии экрана).
        for (ServerPlayer p : net.fabricmc.fabric.api.networking.v1.PlayerLookup.around(player.level(), suit.position(), 48)) {
            if (p != player) p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(com.smi7i.sandevistan.ModSounds.MECH_EQUIP),
                    net.minecraft.sounds.SoundSource.PLAYERS, suit.getX(), suit.getY(), suit.getZ(), 1.6F, 1.0F, p.getRandom().nextLong()));
        }
        player.setAttached(ModAttachments.MECH, true);
        player.setAttached(ModAttachments.MECH_TICKS, LIFE_TICKS);
        player.setAttached(ModAttachments.MECH_DOSES, DOSES);
        player.setAttached(ModAttachments.MECH_SHOWDOWN_DONE, false);
        player.setAttached(ModAttachments.MECH_LOST, 0);
        player.refreshDimensions();
        sync(player);
        SUMMONED.values().remove(suit.getUUID());
        suit.discard();
    }
}
