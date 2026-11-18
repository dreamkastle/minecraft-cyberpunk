package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModBlocks;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * После сверхновой небо над игроком (радиус RADIUS) трескается и осыпается кусками. Каждый кусок — четырёхугольник
 * «неба» на высоте SKY_HEIGHT; он падает и ложится на землю наклонной плитой из блоков «осколок неба» (сверху ночь,
 * снизу день). Падение и дыры в небе с глитчевым космосом рисует клиент (MechSingularityClient) по SkyShatterPayload;
 * сервер в момент приземления выкладывает блоки (по ним можно ходить и ломать).
 */
public final class SkyShatter {
    // ===== Настройки =====
    public static final double RADIUS = 50, SKY_HEIGHT = 60, CELL = 18;
    /** Сколько тиков после взрыва трещины расползаются, прежде чем падает первый кусок. */
    public static final int FIRST_FALL = 70;
    /** Чисел на кусок: 8 — углы на небе (x, z ×4), центр на земле (x, y, z), ширина, длина, рыскание, наклон, начало падения, длительность. */
    public static final int FLOATS = 18; // + перевёрнут ли (1 — небом вверх)
    /** Через сколько тиков упавший кусок исчезает и за сколько тиков рассыпается. */
    public static final int LIFETIME = 30 * 20, DISSOLVE = 30;

    private record Pending(ServerLevel level, long landTime, Vec3 center, double w, double l, double yaw, double tilt, boolean flip) {}

    /** Упавший кусок: через LIFETIME рассыпается за DISSOLVE тиков (блоки убираются в случайном порядке). */
    private record Decay(ServerLevel level, long start, List<BlockPos> blocks) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final List<Decay> DECAY = new ArrayList<>();

    private static final Random RNG = new Random();

    private SkyShatter() {}

    static void init() {
        PayloadTypeRegistry.clientboundPlay().register(SkyShatterPayload.TYPE, SkyShatterPayload.CODEC);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (p.level().getGameTime() < p.landTime()) continue;
                land(p);
                it.remove();
            }
            for (Iterator<Decay> it = DECAY.iterator(); it.hasNext(); ) {
                Decay d = it.next();
                long age = d.level().getGameTime() - d.start();
                if (age < 0) continue;
                int target = (int) Math.min(d.blocks().size(), Math.ceil(d.blocks().size() * (age + 1) / (double) DISSOLVE));
                int removed = d.blocks().size() - target;
                while (d.blocks().size() > removed && !d.blocks().isEmpty()) {
                    BlockPos pos = d.blocks().remove(d.blocks().size() - 1);
                    var st = d.level().getBlockState(pos);
                    if (!st.is(ModBlocks.SKY_SHARD) && !st.is(ModBlocks.SKY_SHARD_FLIP)) continue;
                    if (RNG.nextInt(4) == 0) d.level().levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(st)); // осколки и звон
                    d.level().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
                if (d.blocks().isEmpty()) it.remove();
            }
        });
    }

    /** Сверхновая взорвалась над owner: раскалываем небо над ним. */
    static void start(ServerPlayer owner, ServerLevel level) {
        Random rnd = new Random(level.getGameTime() * 31 + owner.getId());
        double cx = owner.getX(), cz = owner.getZ();
        double groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(cx), (int) Math.floor(cz));
        double skyY = groundY + SKY_HEIGHT;
        int n = (int) Math.ceil(RADIUS / CELL) + 1;
        // Сетка с «дрожащими» узлами — куски неровные, как осколки стекла.
        double[][][] node = new double[2 * n + 1][2 * n + 1][];
        for (int i = 0; i <= 2 * n; i++) {
            for (int j = 0; j <= 2 * n; j++) {
                node[i][j] = new double[]{cx + (i - n) * CELL + (rnd.nextDouble() - 0.5) * CELL * 0.5,
                        cz + (j - n) * CELL + (rnd.nextDouble() - 0.5) * CELL * 0.5};
            }
        }
        List<double[]> chosen = new ArrayList<>();
        for (int i = 0; i < 2 * n; i++) {
            for (int j = 0; j < 2 * n; j++) {
                double mx = (node[i][j][0] + node[i + 1][j + 1][0]) / 2, mz = (node[i][j][1] + node[i + 1][j + 1][1]) / 2;
                double d = Math.hypot(mx - cx, mz - cz);
                if (d > RADIUS || rnd.nextDouble() > 0.62) continue;
                chosen.add(new double[]{i, j, d});
            }
        }
        chosen.sort((x, y) -> Double.compare(x[2], y[2])); // сначала падают ближние к центру
        List<Float> data = new ArrayList<>();
        long now = level.getGameTime();
        int order = 0;
        for (double[] c : chosen) {
            int i = (int) c[0], j = (int) c[1];
            double[][] corners = {node[i][j], node[i + 1][j], node[i + 1][j + 1], node[i][j + 1]};
            double mx = 0, mz = 0;
            for (double[] k : corners) {
                mx += k[0] / 4;
                mz += k[1] / 4;
            }
            double gx = mx + (rnd.nextDouble() - 0.5) * 8, gz = mz + (rnd.nextDouble() - 0.5) * 8;
            double gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(gx), (int) Math.floor(gz));
            double w = 6 + rnd.nextDouble() * 4, l = 7 + rnd.nextDouble() * 5;
            double yaw = rnd.nextDouble() * Math.PI * 2;
            // Разные углы: почти плашмя, наклонно, круто, иногда стоит на ребре; почти половина — небом вверх.
            double roll = rnd.nextDouble();
            double tilt = Math.toRadians(roll < 0.2 ? 6 + rnd.nextDouble() * 12 : roll < 0.65 ? 20 + rnd.nextDouble() * 30 : roll < 0.9 ? 50 + rnd.nextDouble() * 22 : 74 + rnd.nextDouble() * 10);
            boolean flip = rnd.nextDouble() < 0.45;
            // Нижний край чуть зарыт в землю, верхний задран.
            double cy = gy + Math.sin(tilt) * l / 2 * 0.85;
            int fallStart = FIRST_FALL + order * 7 + rnd.nextInt(10);
            int fallDur = 34 + rnd.nextInt(12);
            order++;
            for (double[] k : corners) {
                data.add((float) k[0]);
                data.add((float) k[1]);
            }
            data.add((float) gx);
            data.add((float) cy);
            data.add((float) gz);
            data.add((float) w);
            data.add((float) l);
            data.add((float) yaw);
            data.add((float) tilt);
            data.add((float) fallStart);
            data.add((float) fallDur);
            data.add(flip ? 1.0F : 0.0F);
            PENDING.add(new Pending(level, now + fallStart + fallDur, new Vec3(gx, cy, gz), w, l, yaw, tilt, flip));
        }
        SkyShatterPayload payload = new SkyShatterPayload(now, cx, cz, skyY, groundY, data);
        for (ServerPlayer p : PlayerLookup.around(level, new Vec3(cx, groundY, cz), 320)) ServerPlayNetworking.send(p, payload);
    }

    /** Оси плиты: u — ширина, v — длина (наклонена вверх на tilt), n — нормаль вверх. */
    public static Vec3[] axes(double yaw, double tilt) {
        Vec3 u = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 v0 = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 v = v0.scale(Math.cos(tilt)).add(0, Math.sin(tilt), 0);
        Vec3 n = u.cross(v).normalize();
        if (n.y < 0) n = n.scale(-1);
        return new Vec3[]{u, v, n};
    }

    /** Кусок приземлился: выкладываем наклонную плиту из блоков, бьём и отбрасываем тех, кто под ней. */
    private static void land(Pending p) {
        ServerLevel level = p.level();
        Vec3[] ax = axes(p.yaw(), p.tilt());
        double half = Math.max(p.w(), p.l()) / 2 + 2;
        BlockPos min = BlockPos.containing(p.center().subtract(half, half, half)), max = BlockPos.containing(p.center().add(half, half, half));
        BlockState shard = (p.flip() ? ModBlocks.SKY_SHARD_FLIP : ModBlocks.SKY_SHARD).defaultBlockState();
        List<BlockPos> placed = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            Vec3 d = Vec3.atCenterOf(pos).subtract(p.center());
            if (Math.abs(d.dot(ax[0])) > p.w() / 2 || Math.abs(d.dot(ax[1])) > p.l() / 2 || Math.abs(d.dot(ax[2])) > 0.62) continue;
            BlockState st = level.getBlockState(pos);
            if (!st.isAir() && !st.canBeReplaced()) continue;
            level.setBlock(pos, shard, 3);
            placed.add(pos.immutable());
        }
        java.util.Collections.shuffle(placed, RNG);
        if (!placed.isEmpty()) DECAY.add(new Decay(level, level.getGameTime() + LIFETIME, placed));
        MechManager.sound(level, p.center(), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 3.0F, 0.6F);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p.center(), p.center()).inflate(half, 4, half), LivingEntity::isAlive)) {
            Vec3 out = e.position().subtract(p.center());
            out = new Vec3(out.x, 0, out.z);
            out = out.lengthSqr() > 1.0E-4 ? out.normalize() : new Vec3(1, 0, 0);
            e.push(out.x * 0.9, 0.5, out.z * 0.9);
            e.hurtMarked = true;
        }
    }
}
