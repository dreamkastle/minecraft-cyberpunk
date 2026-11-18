package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Самая мощная способность костюма — «Сингулярность».
 * 1) Кат-сцена (RISE): костюм поднимается в воздух, вокруг бьют красные и синие молнии.
 * 2) Наведение (AIM, 5 с): куда смотришь — туда и ударит (клавиша ещё раз — раньше).
 * 3) Выстрел: красная и синяя молнии летят в точку и сливаются (BEAM + MERGE) — рождается чёрная дыра.
 * 4) Дыра (GROW): всё вокруг кружится по её орбите и затягивается, она поглощает блоки и растёт до MAX_RADIUS.
 * 5) Коллапс и сверхновая: ударная волна радиусом NOVA_RADIUS, урон максимальный в центре и слабеет к краю.
 * Время — от синхронизированных MECH_SING_START / MECH_SING_FIRE; центр дыры клиенты получают SingularityFxPayload.
 */
public final class MechSingularity {
    // ===== Настройки (тики, 20 = 1 с) =====
    public static final int RISE = 50, AIM = 100;
    /** От выстрела: лучи летят BEAM, слияние до BIRTH, рост GROW, коллапс COLLAPSE, затем сверхновая; конец через TAIL. */
    public static final int BEAM = 10, BIRTH = 44, GROW = 240, COLLAPSE = 14, NOVA = BIRTH + GROW + COLLAPSE, TAIL = 160;
    public static final double MAX_RADIUS = 10.0, NOVA_RADIUS = 50.0, PUSH_RADIUS = 100.0, AIM_RANGE = 70.0;
    /** Высота дыры над точкой прицела и высота парения костюма. */
    public static final double HOLE_LIFT = 3.0, HOVER = 7.0;
    public static final int COOLDOWN = 90 * 20;

    private static final class State {
        Vec3 center;
        long fire;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Integer> CD = new HashMap<>();
    private static final Random RNG = new Random();

    private MechSingularity() {}

    // ===== Кратер сверхновой =====
    /** Чаша: радиус по горизонтали, глубина, высота «сноса» над землёй; раскрывается от центра за CRATER_TICKS. */
    public static final double CRATER_R = 22, CRATER_DEPTH = 12, CRATER_UP = 14, SCORCH_R = 32;
    public static final int CRATER_TICKS = 15;

    private record Crater(ServerLevel level, Vec3 c, long start) {}

    private static final List<Crater> CRATERS = new ArrayList<>();

    private static final net.minecraft.world.level.block.state.BlockState[] RIM = {
            Blocks.BLACKSTONE.defaultBlockState(), Blocks.BASALT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState()};

    /** Слой кратера между радиусами: выбивает чашу (и всё над ней), дно раскалено, края обуглены. */
    private static void craterStep(Crater cr, int step) {
        ServerLevel level = cr.level();
        double r0 = CRATER_R * step / CRATER_TICKS, r1 = CRATER_R * (step + 1) / CRATER_TICKS;
        int cx = (int) Math.floor(cr.c().x), cz = (int) Math.floor(cr.c().z), gy = (int) Math.floor(cr.c().y);
        int R = (int) Math.ceil(r1) + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < r0 || d >= r1) continue;
                double k = d / CRATER_R;
                int depth = (int) Math.floor(CRATER_DEPTH * Math.sqrt(Math.max(0, 1 - k * k)));
                int up = (int) Math.ceil(CRATER_UP * Math.sqrt(Math.max(0, 1 - k * k)));
                for (int dy = -depth; dy <= up; dy++) {
                    pos.set(cx + dx, gy + dy, cz + dz);
                    var st = level.getBlockState(pos);
                    if (st.isAir()) continue;
                    float hardness = st.getDestroySpeed(level, pos);
                    if (hardness < 0 || hardness > 50 || st.hasBlockEntity()) continue;
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
                // Дно чаши — раскалённый шлак: магма, обсидиан, изредка плачущий обсидиан (светится).
                pos.set(cx + dx, gy - depth - 1, cz + dz);
                var floor = level.getBlockState(pos);
                if (!floor.isAir() && floor.getDestroySpeed(level, pos) >= 0 && !floor.hasBlockEntity()) {
                    double roll = RNG.nextDouble();
                    var lava = roll < 0.35 ? Blocks.MAGMA_BLOCK : roll < 0.55 ? Blocks.OBSIDIAN : roll < 0.62 ? Blocks.CRYING_OBSIDIAN : Blocks.BLACKSTONE;
                    level.setBlock(pos, lava.defaultBlockState(), 2);
                }
            }
        }
        // Последний шаг — обугленное кольцо вокруг кратера.
        if (step == CRATER_TICKS - 1) {
            int S = (int) SCORCH_R;
            for (int dx = -S; dx <= S; dx++) {
                for (int dz = -S; dz <= S; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d < CRATER_R - 1 || d > SCORCH_R) continue;
                    double chance = 1 - (d - CRATER_R) / (SCORCH_R - CRATER_R);
                    if (RNG.nextDouble() > 0.25 + 0.7 * chance) continue;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dx, cz + dz) - 1;
                    pos.set(cx + dx, top, cz + dz);
                    var st = level.getBlockState(pos);
                    if (st.isAir() || st.hasBlockEntity() || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, pos) < 0) continue;
                    level.setBlock(pos, RIM[RNG.nextInt(RIM.length)], 2);
                    // Над обугленной землёй — ни травы, ни цветов.
                    BlockPos above = pos.above();
                    if (level.getBlockState(above).canBeReplaced()) level.setBlock(above, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    static void init() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (java.util.Iterator<Crater> it = CRATERS.iterator(); it.hasNext(); ) {
                Crater cr = it.next();
                long step = cr.level().getGameTime() - cr.start();
                if (step >= 0 && step < CRATER_TICKS) craterStep(cr, (int) step);
                if (step >= CRATER_TICKS - 1) it.remove();
            }
        });
        PayloadTypeRegistry.serverboundPlay().register(SingularityPayload.TYPE, SingularityPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SingularityFxPayload.TYPE, SingularityFxPayload.CODEC);
        SkyShatter.init();
        ServerPlayNetworking.registerGlobalReceiver(SingularityPayload.TYPE, (payload, context) -> {
            if (payload.action() == 0) start(context.player());
            else if (isActive(context.player()) && fireTime(context.player()) <= 0) fire(context.player());
        });
    }

    public static long startTime(net.minecraft.world.entity.player.Player p) {
        return p.getAttachedOrElse(ModAttachments.MECH_SING_START, 0L);
    }

    public static long fireTime(net.minecraft.world.entity.player.Player p) {
        return p.getAttachedOrElse(ModAttachments.MECH_SING_FIRE, 0L);
    }

    public static boolean isActive(net.minecraft.world.entity.player.Player p) {
        return startTime(p) > 0;
    }

    /** Радиус чёрной дыры через t тиков после выстрела (0 — ещё не родилась / уже взорвалась). */
    public static double radius(double t) {
        if (t < BIRTH || t >= NOVA) return 0;
        if (t < BIRTH + GROW) {
            double g = (t - BIRTH) / GROW;
            return 1.0 + (MAX_RADIUS - 1.0) * Math.pow(g, 1.35);
        }
        double k = (t - BIRTH - GROW) / COLLAPSE;   // коллапс: резко сжимается перед взрывом
        return MAX_RADIUS * (1 - 0.85 * k * k);
    }

    static void start(ServerPlayer player) {
        if (!ModAttachments.hasMech(player) || !player.isAlive() || isActive(player) || MechManager.isAttacking(player)
                || MechManager.isEquipping(player) || MechVortex.isActive(player) || MechMeteor.isActive(player)
                || MechShowdown.isActive(player) || MechPush.isActive(player) || CD.containsKey(player.getUUID())) {
            MechManager.sync(player);
            return;
        }
        player.setAttached(ModAttachments.MECH_SING_START, player.level().getGameTime());
        player.setAttached(ModAttachments.MECH_SING_FIRE, 0L);
        STATES.put(player.getUUID(), new State());
        MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_SING_CHARGE, 4.0F, 1.0F);
        MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.BOOM_TAKEOFF, 3.0F, 0.85F);
    }

    private static void fire(ServerPlayer player) {
        State st = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        ServerLevel level = player.level();
        HitResult hit = player.pick(AIM_RANGE, 1.0F, false);
        Vec3 target = hit.getType() == HitResult.Type.MISS
                ? player.getEyePosition().add(player.getLookAngle().scale(AIM_RANGE * 0.6))
                : hit.getLocation();
        if (hit.getType() == HitResult.Type.MISS) {
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(target.x), (int) Math.floor(target.z));
            target = new Vec3(target.x, Math.min(target.y, gy), target.z);
        }
        st.center = target.add(0, HOLE_LIFT, 0);
        st.fire = level.getGameTime();
        player.setAttached(ModAttachments.MECH_SING_FIRE, st.fire);
        SingularityFxPayload fx = new SingularityFxPayload(player.getId(), st.center.x, st.center.y, st.center.z, st.fire);
        for (ServerPlayer p : PlayerLookup.around(level, st.center, 300)) ServerPlayNetworking.send(p, fx);
        if (!PlayerLookup.around(level, st.center, 300).contains(player)) ServerPlayNetworking.send(player, fx);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.MECH_SING_FIRE, 4.0F, 1.0F);
        MechManager.sound(level, player.position(), com.smi7i.sandevistan.ModSounds.BOOM_WHOOSH, 3.0F, 0.85F);
    }

    static void tick(ServerPlayer player) {
        Integer cd = CD.get(player.getUUID());
        if (cd != null) {
            if (cd <= 1) CD.remove(player.getUUID()); else CD.put(player.getUUID(), cd - 1);
            if (cd <= 1 || cd % 20 == 0) MechManager.sync(player);
        }
        long s = startTime(player);
        if (s <= 0) return;
        ServerLevel level = player.level();
        long now = level.getGameTime();
        player.fallDistance = 0;
        if (!player.getAbilities().mayfly) { // парит — чтобы игра не выкинула «за полёт»
            player.getAbilities().mayfly = true;
            player.onUpdateAbilities();
        }
        State st = STATES.get(player.getUUID());
        if (st == null || !ModAttachments.hasMech(player) || !player.isAlive()) {
            finish(player, st != null);
            return;
        }
        long f = fireTime(player);
        if (f <= 0) {
            if (now - s >= RISE + AIM) fire(player);
            return;
        }
        long t = now - f;
        if (t == BIRTH) MechManager.sound(level, st.center, com.smi7i.sandevistan.ModSounds.MECH_HOLE, 6.0F, 1.0F);
        double r = radius(t);
        if (r > 0) {
            pull(player, level, st.center, r, t);
            if (t < BIRTH + GROW) absorb(player, level, st.center, r);
        }
        if (t == NOVA) {
            supernova(player, level, st.center);
            CRATERS.add(new Crater(level, st.center.subtract(0, HOLE_LIFT, 0), level.getGameTime()));
        }
        // Небо раскалывается, когда кратер уже выбит (иначе куски считали бы старую высоту земли и висели над ямой).
        if (t == NOVA + CRATER_TICKS + 1) {
            SkyShatter.start(player, level);
        }
        if (t >= NOVA + TAIL) finish(player, true);
    }

    /** Всё вокруг кружится по орбите дыры и затягивается внутрь; у горизонта — урон, предметы исчезают. */
    private static void pull(ServerPlayer owner, ServerLevel level, Vec3 c, double r, long t) {
        double range = 8 + r * 2.6;
        for (Entity e : level.getEntities((Entity) null, new AABB(c, c).inflate(range), e -> e != owner && e.isAlive() && !e.isSpectator())) {
            if (e instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator())) continue;
            if (e instanceof ArmorStand) continue;
            Vec3 at = e.getBoundingBox().getCenter();
            Vec3 v = c.subtract(at);
            double d = v.length();
            if (d > range || d < 1.0E-3) continue;
            Vec3 dir = v.scale(1 / d);
            Vec3 tangent = new Vec3(-dir.z, 0, dir.x);
            if (tangent.lengthSqr() < 1.0E-4) tangent = new Vec3(1, 0, 0);
            tangent = tangent.normalize();
            double strength = Math.min(1.3, 0.05 + 0.55 * Math.pow(r / d, 1.2));
            Vec3 vel = e.getDeltaMovement().scale(0.55).add(dir.scale(strength * 0.45)).add(tangent.scale(strength * 0.85));
            e.setDeltaMovement(vel);
            e.hurtMarked = true;
            e.fallDistance = 0;
            if (d < r * 0.95) {
                if (e instanceof LivingEntity le) {
                    if (t % 10 == 0) le.hurtServer(level, owner.damageSources().playerAttack(owner), MechManager.quarter(le) * 0.35F);
                } else {
                    e.discard(); // предметы, стрелы и т.п. исчезают в дыре
                }
            }
        }
    }

    /** Дыра понемногу поглощает блоки вокруг себя (тем больше, чем она крупнее); клиенты рисуют, как они летят по спирали. */
    private static void absorb(ServerPlayer owner, ServerLevel level, Vec3 c, double r) {
        // Дыра прожорливая: много блоков за тик и в шаре заметно больше горизонта.
        int budget = 20 + (int) (r * 8);
        double reach = r * 1.7 + 2;
        List<MechManager.Broken> eaten = new ArrayList<>();
        for (int i = 0; i < budget * 5 && eaten.size() < budget; i++) {
            double u = RNG.nextDouble() * 2 - 1, phi = RNG.nextDouble() * Math.PI * 2;
            double sq = Math.sqrt(1 - u * u), rr = reach * Math.cbrt(RNG.nextDouble());
            BlockPos pos = BlockPos.containing(c.x + sq * Math.cos(phi) * rr, c.y + u * rr, c.z + sq * Math.sin(phi) * rr);
            var state = level.getBlockState(pos);
            if (state.isAir()) continue;
            float hardness = state.getDestroySpeed(level, pos);
            if (hardness < 0 || hardness > 50 || state.hasBlockEntity()) continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            if (!state.getFluidState().isEmpty() && state.getFluidState().isSource() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
            eaten.add(new MechManager.Broken(pos.immutable(), state));
        }
        // Клиентам — не больше 40 кусков за тик (рисовать сотни незачем).
        if (eaten.size() > 40) eaten = new ArrayList<>(eaten.subList(0, 40));
        if (!eaten.isEmpty()) MechManager.fx(level, MechFxPayload.ABSORB, c, 0, owner.getId(), eaten);
    }

    /** Сверхновая: урон всем в NOVA_RADIUS — максимум в центре, к краю слабее; отброс от центра. */
    private static void supernova(ServerPlayer owner, ServerLevel level, Vec3 c) {
        MechManager.sound(level, c, com.smi7i.sandevistan.ModSounds.MECH_SUPERNOVA, 10.0F, 1.0F);
        MechManager.sound(level, c, com.smi7i.sandevistan.ModSounds.MECH_LAND, 8.0F, 0.5F);
        MechManager.sound(level, c, com.smi7i.sandevistan.ModSounds.MECH_SLAM, 8.0F, 0.45F);
        MechManager.sound(level, c, com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 10.0F, 0.5F);
        MechManager.sound(level, c, com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 10.0F, 0.55F);
        MechManager.sound(level, c, com.smi7i.sandevistan.ModSounds.BOOM_ORBITAL, 10.0F, 0.7F);
        // Ударная волна дальше урона: до 100 блоков всех отбрасывает (без урона за NOVA_RADIUS).
        for (Entity e : level.getEntities((Entity) null, new AABB(c, c).inflate(PUSH_RADIUS), e -> e != owner && e.isAlive() && !e.isSpectator())) {
            if (e instanceof ServerPlayer sp && sp.isCreative()) continue;
            double d = e.position().distanceTo(c);
            if (d <= NOVA_RADIUS || d > PUSH_RADIUS) continue;
            double f = 1 - (d - NOVA_RADIUS) / (PUSH_RADIUS - NOVA_RADIUS);
            Vec3 out = e.position().subtract(c).normalize();
            e.push(out.x * 1.4 * f, 0.3 + 0.4 * f, out.z * 1.4 * f);
            e.hurtMarked = true;
        }
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(NOVA_RADIUS),
                e -> e != owner && e.isAlive() && !e.isSpectator())) {
            if (e instanceof ServerPlayer sp && sp.isCreative()) continue;
            double d = e.getBoundingBox().getCenter().distanceTo(c);
            if (d > NOVA_RADIUS) continue;
            double f = 1 - d / NOVA_RADIUS;
            float dmg = (float) ((MechManager.quarter(e) * 3.2 + 24) * Math.pow(f, 1.6));
            e.hurtServer(level, owner.damageSources().playerAttack(owner), dmg);
            Vec3 out = e.position().subtract(c);
            out = out.lengthSqr() > 1.0E-4 ? out.normalize() : new Vec3(0, 1, 0);
            e.push(out.x * 2.6 * f, 0.4 + 0.9 * f, out.z * 2.6 * f);
            e.hurtMarked = true;
        }
    }

    private static void finish(ServerPlayer player, boolean cooldown) {
        player.setAttached(ModAttachments.MECH_SING_START, 0L);
        player.setAttached(ModAttachments.MECH_SING_FIRE, 0L);
        STATES.remove(player.getUUID());
        if (!player.isCreative() && !player.isSpectator() && player.getAbilities().mayfly) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        if (cooldown && ModAttachments.hasMech(player)) CD.put(player.getUUID(), COOLDOWN);
        MechManager.sync(player);
    }

    public static int cooldown(ServerPlayer player) {
        return CD.getOrDefault(player.getUUID(), 0);
    }
}
