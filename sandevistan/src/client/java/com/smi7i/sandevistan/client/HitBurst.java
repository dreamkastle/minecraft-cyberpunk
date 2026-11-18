package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Вспышки при ударах (по образцам пользователя), рисуются кодом из «пикселей», без текстур:
 *  - STAR — пиксельная звезда-вспышка в точке удара (кремовая с шахматной серединой);
 *  - CRACK — белые ломаные «трещины-разряды» вокруг, вырастают и рассыпаются по пикселю;
 *  - щепки — тонкие светлые палочки, разлетаются в стороны.
 * В слоумо всё живёт медленнее (SLOW_AGE) и висит в воздухе. Без шейдеров рисуются в SandevistanWorldPass
 * поверх зелёного фильтра (не зеленеют), с Iris — через событие Fabric.
 */
public final class HitBurst {
    // ===== Настройки =====
    /** Во сколько раз медленнее живут вспышки в слоумо (0.3 = в ~3 раза дольше). */
    private static final float SLOW_AGE = 0.3F;
    /** Размер «пикселя» звезды и трещин, в блоках. */
    private static final float STAR_PIXEL = 0.045F;
    private static final float CRACK_PIXEL = 0.032F;
    /** Жизнь в тиках (без слоумо). */
    private static final float STAR_LIFE = 7.0F;
    private static final float CRACK_LIFE = 11.0F;
    private static final float SHARD_LIFE = 18.0F;
    /** Сколько трещин и щепок за удар. */
    private static final int CRACKS = 5;
    private static final int SHARDS = 10;
    /** Цвета. */
    private static final int STAR_LIGHT = 0xFFF6DF;
    private static final int STAR_DARK = 0x9C7F55;
    private static final int CRACK_COLOR = 0xFFFFFF;
    private static final int SHARD_COLOR = 0xF6DDA6;
    /** Сила мягкого свечения вокруг пикселей (сложение света). */
    private static final float HALO = 0.22F;
    private static final int MAX_OBJECTS = 300;
    private static final int FULL_BRIGHT = 15728880;

    private static final class Burst {
        Vec3 pos;
        float age, prevAge, life, pixel, roll;
        boolean star;
        int[] cells; // x, y, цвет (0 светлый, 1 тёмный) — тройками
        int seed;
    }

    private static final class Shard {
        Vec3 pos, prevPos, vel;
        float age, prevAge, life, len;
    }

    private static final List<Burst> BURSTS = new ArrayList<>();
    private static final List<Shard> SHARDS_LIST = new ArrayList<>();
    private static final Random R = new Random();

    private HitBurst() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            BURSTS.clear();
            SHARDS_LIST.clear();
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!IrisCompat.shadersActive() || !hasAny()) return; // с шейдерами — только когда есть что рисовать
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submit(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
    }

    public static boolean hasAny() {
        return !BURSTS.isEmpty() || !SHARDS_LIST.isEmpty();
    }

    /** Удар: hit — точка удара, away — направление «от игрока». */
    public static void spawn(Vec3 hit, Vec3 away) {
        if (BURSTS.size() + SHARDS_LIST.size() > MAX_OBJECTS) return;
        BURSTS.add(star(hit));
        for (int i = 0; i < CRACKS; i++) {
            Vec3 off = away.scale(0.15 + R.nextDouble() * 0.5)
                    .add(R.nextGaussian() * 0.45, R.nextGaussian() * 0.4, R.nextGaussian() * 0.45);
            BURSTS.add(crack(hit.add(off), i * 1.2F));
        }
        for (int i = 0; i < SHARDS; i++) {
            Shard s = new Shard();
            s.pos = s.prevPos = hit;
            Vec3 dir = away.add(R.nextGaussian() * 0.8, R.nextGaussian() * 0.6 + 0.25, R.nextGaussian() * 0.8).normalize();
            s.vel = dir.scale(0.25 + R.nextDouble() * 0.35);
            s.life = SHARD_LIFE * (0.7F + R.nextFloat() * 0.6F);
            s.len = 0.12F + R.nextFloat() * 0.16F;
            SHARDS_LIST.add(s);
        }
    }

    // ---------- Формы из пикселей ----------

    private static Burst star(Vec3 pos) {
        Burst b = new Burst();
        b.pos = pos;
        b.star = true;
        b.life = STAR_LIFE;
        b.pixel = STAR_PIXEL;
        b.roll = R.nextFloat() * 30.0F - 15.0F;
        List<Integer> cells = new ArrayList<>();
        // Шахматная середина 3x3.
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++) add(cells, x, y, (x + y) & 1);
        // Лучи: прямые длиннее, диагональные короче; у основания — тоже «шахматка».
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && R.nextFloat() < 0.25F) continue;
            int len = diagonal ? 2 + R.nextInt(3) : 3 + R.nextInt(4);
            for (int i = 2; i <= len + 1; i++) add(cells, d[0] * i, d[1] * i, i <= 2 ? i & 1 : 0);
        }
        b.cells = toArray(cells);
        b.seed = R.nextInt();
        return b;
    }

    private static Burst crack(Vec3 pos, float delay) {
        Burst b = new Burst();
        b.pos = pos;
        b.life = CRACK_LIFE;
        b.pixel = CRACK_PIXEL * (0.8F + R.nextFloat() * 0.5F);
        b.roll = R.nextFloat() * 360.0F;
        b.age = b.prevAge = -delay; // трещины вспыхивают чуть по очереди
        List<Integer> cells = new ArrayList<>();
        // Ломаные ветки «лесенкой» из центра, иногда с отростками.
        int branches = 2 + R.nextInt(3);
        for (int i = 0; i < branches; i++) {
            walk(cells, 0, 0, R.nextInt(8), 5 + R.nextInt(7), 2);
        }
        b.cells = toArray(cells);
        b.seed = R.nextInt();
        return b;
    }

    private static final int[][] DIR8 = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private static void walk(List<Integer> cells, int x, int y, int dir, int steps, int depth) {
        for (int i = 0; i < steps; i++) {
            float r = R.nextFloat();
            if (r < 0.3F) dir = (dir + 1) & 7;       // лёгкий поворот
            else if (r < 0.6F) dir = (dir + 7) & 7;
            x += DIR8[dir][0];
            y += DIR8[dir][1];
            add(cells, x, y, 0);
            if (depth > 0 && R.nextFloat() < 0.18F) {
                walk(cells, x, y, (dir + (R.nextBoolean() ? 2 : 6)) & 7, 2 + R.nextInt(4), depth - 1);
            }
        }
    }

    private static void add(List<Integer> cells, int x, int y, int color) {
        cells.add(x);
        cells.add(y);
        cells.add(color);
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    // ---------- Жизнь ----------

    private static void tick(Minecraft mc) {
        if (mc.isPaused()) return;
        float speed = SlowMotionClient.isSlowMotion() ? SLOW_AGE : 1.0F;
        for (Iterator<Burst> it = BURSTS.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            b.prevAge = b.age;
            b.age += speed;
            if (b.age >= b.life) it.remove();
        }
        for (Iterator<Shard> it = SHARDS_LIST.iterator(); it.hasNext(); ) {
            Shard s = it.next();
            s.prevAge = s.age;
            s.age += speed;
            s.prevPos = s.pos;
            s.pos = s.pos.add(s.vel.scale(speed));
            s.vel = s.vel.scale(Math.pow(0.86, speed)).add(0, -0.025 * speed, 0);
            if (s.age >= s.life) it.remove();
        }
    }

    // ---------- Отрисовка ----------

    /** Отправляет все вспышки на отрисовку (координаты относительно камеры). */
    public static void submit(SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        if (!hasAny()) return;
        Vec3 cam = camera.pos;
        long now = System.currentTimeMillis();

        for (Burst b : BURSTS) {
            float age = Mth.lerp(partial, b.prevAge, b.age);
            if (age < 0) continue;
            float t = Mth.clamp(age / b.life, 0.0F, 1.0F);
            int[] cells = visibleCells(b, t, now);
            if (cells.length == 0) continue;

            PoseStack pose = new PoseStack();
            Vec3 p = b.pos.subtract(cam);
            pose.translate(p.x, p.y, p.z);
            pose.mulPose(camera.orientation); // лицом к камере
            pose.mulPose(Axis.ZP.rotationDegrees(b.roll));
            // Звезда «выстреливает»: быстро растёт и чуть сжимается к концу.
            float scale = b.star ? b.pixel * (0.55F + 0.75F * (float) Math.sqrt(Math.min(1.0F, t * 3.0F))) * (1.0F - 0.3F * t)
                    : b.pixel;
            pose.scale(scale, -scale, scale);

            float alpha = b.star ? (t < 0.6F ? 1.0F : 1.0F - (t - 0.6F) / 0.4F) : 1.0F;
            int light = HoloUi.argb(alpha, b.star ? STAR_LIGHT : CRACK_COLOR);
            int dark = HoloUi.argb(alpha, STAR_DARK);
            int halo = HoloUi.argb(1.0F, scaleRgb(b.star ? STAR_LIGHT : CRACK_COLOR, HALO * alpha));

            final int[] c = cells;
            collector.submitCustomGeometry(pose, GlowRender.type(), (pp, buffer) -> {
                for (int i = 0; i < c.length; i += 3) square(pp, buffer, c[i] - 1.0F, c[i + 1] - 1.0F, c[i] + 2.0F, c[i + 1] + 2.0F, halo, false);
            });
            collector.submitCustomGeometry(pose, RenderTypes.textBackground(), (pp, buffer) -> {
                for (int i = 0; i < c.length; i += 3) {
                    square(pp, buffer, c[i], c[i + 1], c[i] + 1.0F, c[i + 1] + 1.0F, c[i + 2] == 1 ? dark : light, true);
                }
            });
        }

        if (!SHARDS_LIST.isEmpty()) {
            List<float[]> quads = new ArrayList<>();
            for (Shard s : SHARDS_LIST) {
                float age = Mth.lerp(partial, s.prevAge, s.age);
                float t = Mth.clamp(age / s.life, 0.0F, 1.0F);
                float alpha = t < 0.7F ? 1.0F : 1.0F - (t - 0.7F) / 0.3F;
                Vec3 a = s.prevPos.lerp(s.pos, partial).subtract(cam);
                Vec3 dir = s.vel.lengthSqr() > 1.0E-6 ? s.vel.normalize() : new Vec3(0, 1, 0);
                Vec3 bEnd = a.add(dir.scale(s.len));
                Vec3 side = bEnd.subtract(a).cross(a);
                if (side.lengthSqr() < 1.0E-8) continue;
                side = side.normalize().scale(0.022);
                quads.add(new float[]{
                        (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z),
                        (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z),
                        (float) (bEnd.x + side.x), (float) (bEnd.y + side.y), (float) (bEnd.z + side.z),
                        (float) (bEnd.x - side.x), (float) (bEnd.y - side.y), (float) (bEnd.z - side.z),
                        Float.intBitsToFloat(HoloUi.argb(alpha, SHARD_COLOR))});
            }
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.textBackground(), (pp, buffer) -> {
                for (float[] q : quads) {
                    int color = Float.floatToRawIntBits(q[12]);
                    int[] order = {0, 1, 2, 3, 3, 2, 1, 0}; // с обеих сторон
                    for (int k : order) {
                        buffer.addVertex(pp, q[k * 3], q[k * 3 + 1], q[k * 3 + 2]).setColor(color).setLight(FULL_BRIGHT);
                    }
                }
            });
        }
    }

    /** Какие пиксели видны сейчас: трещина вырастает от центра, потом рассыпается по пикселю; всё мерцает. */
    private static int[] visibleCells(Burst b, float t, long now) {
        int count = b.cells.length / 3;
        int shown = count;
        if (!b.star) shown = Math.max(1, Math.round(count * Math.min(1.0F, t / 0.25F)));
        float dropout = b.star ? 0.0F : Math.max(0.0F, (t - 0.45F) / 0.55F); // доля рассыпавшихся пикселей
        long flickerFrame = now / 45;
        int[] out = new int[shown * 3];
        int n = 0;
        for (int i = 0; i < shown; i++) {
            int h = hash(b.seed, i);
            if ((h & 0xFFFF) / 65535.0F < dropout) continue;
            if (!b.star && hash(b.seed + (int) flickerFrame, i) % 9 == 0) continue; // мерцание
            out[n++] = b.cells[i * 3];
            out[n++] = b.cells[i * 3 + 1];
            out[n++] = b.cells[i * 3 + 2];
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static int hash(int seed, int i) {
        int h = seed * 0x9E3779B1 + i * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private static void square(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float x1, float y1,
                               int color, boolean withLight) {
        float[][] v = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
        int[] order = {0, 1, 2, 3, 3, 2, 1, 0}; // с обеих сторон
        for (int k : order) {
            if (!withLight) {
                GlowRender.vertex(buffer, pose, v[k][0], v[k][1], 0.0F, color); // ореол — светящийся
                continue;
            }
            buffer.addVertex(pose, v[k][0], v[k][1], 0.0F).setColor(color).setLight(FULL_BRIGHT);
        }
    }

    private static int scaleRgb(int rgb, float k) {
        k = Mth.clamp(k, 0.0F, 1.0F);
        return (Math.round(((rgb >> 16) & 0xFF) * k) << 16) | (Math.round(((rgb >> 8) & 0xFF) * k) << 8)
                | Math.round((rgb & 0xFF) * k);
    }
}
