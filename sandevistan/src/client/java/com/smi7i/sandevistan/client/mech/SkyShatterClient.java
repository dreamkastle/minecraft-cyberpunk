package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smi7i.sandevistan.client.GlowRender;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.client.IrisCompat;
import com.smi7i.sandevistan.mech.SkyShatter;
import com.smi7i.sandevistan.mech.SkyShatterPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Небо раскалывается после сверхновой: трещины по небу над игроком, куски отваливаются и падают (снизу — день,
 * сверху — ночь), на их месте — дыры в глитчевый космос. Приземлившийся кусок дальше — настоящие блоки (сервер).
 */
public final class SkyShatterClient {
    /** Через сколько тиков после падения последнего куска небо начинает заживать, за сколько заживает всё и одна дыра. */
    public static final int HEAL_DELAY = 200, HEAL_TOTAL = 200, HEAL_ONE = 60;
    private static final int FULL_BRIGHT = 15728880;
    private static final Random R = new Random();

    private static final class Shard {
        double[] sky = new double[8];
        Vec3 ground;
        double w, l, yaw, tilt;
        int fallStart, fallDur;
        boolean landed, opened, healStarted, healed, flip;
        int healAt;
    }

    private static final class Event {
        long start;
        double cx, cz, skyY, groundY;
        List<Shard> shards = new ArrayList<>();
        boolean cracked, healAnnounced;
        long healStart;
    }

    private static final List<Event> EVENTS = new ArrayList<>();
    private static DynamicTexture spaceTex, dayTex, nightTex;
    private static NativeImage spaceBase;
    private static Identifier spaceId, dayId, nightId;

    private SkyShatterClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SkyShatterPayload.TYPE, (p, context) -> {
            Event e = new Event();
            e.start = p.start();
            e.cx = p.cx();
            e.cz = p.cz();
            e.skyY = p.skyY();
            e.groundY = p.groundY();
            List<Float> d = p.shards();
            for (int i = 0; i + SkyShatter.FLOATS <= d.size(); i += SkyShatter.FLOATS) {
                Shard s = new Shard();
                for (int k = 0; k < 8; k++) s.sky[k] = d.get(i + k);
                s.ground = new Vec3(d.get(i + 8), d.get(i + 9), d.get(i + 10));
                s.w = d.get(i + 11);
                s.l = d.get(i + 12);
                s.yaw = d.get(i + 13);
                s.tilt = d.get(i + 14);
                s.fallStart = Math.round(d.get(i + 15));
                s.fallDur = Math.round(d.get(i + 16));
                s.flip = d.get(i + 17) > 0.5F;
                e.shards.add(s);
            }
            // Заживление: через HEAL_DELAY после последнего приземления; сначала дальние от центра дыры, центральная — последней.
            int last = 0;
            for (Shard s : e.shards) last = Math.max(last, s.fallStart + s.fallDur);
            e.healStart = last + HEAL_DELAY;
            List<Shard> byDist = new ArrayList<>(e.shards);
            byDist.sort((x, y) -> Double.compare(Math.hypot(y.sky[0] - e.cx, y.sky[1] - e.cz), Math.hypot(x.sky[0] - e.cx, x.sky[1] - e.cz)));
            int cnt = byDist.size();
            for (int i = 0; i < cnt; i++) byDist.get(i).healAt = (int) (e.healStart + (cnt > 1 ? i * (HEAL_TOTAL - HEAL_ONE) / (cnt - 1) : 0));
            EVENTS.add(e);
        });
        ClientTickEvents.END_CLIENT_TICK.register(SkyShatterClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> EVENTS.clear());
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!IrisCompat.shadersActive() || !hasAny()) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submit(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
    }

    public static boolean hasAny() {
        return !EVENTS.isEmpty();
    }

    // ---------- Текстуры ----------

    private static void textures() {
        if (spaceTex != null) return;
        Minecraft mc = Minecraft.getInstance();
        int S = 256;
        Random r = new Random(99);
        spaceBase = new NativeImage(S, S, false);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double n1 = 0.5 + 0.5 * Math.sin(x * 0.045 + Math.sin(y * 0.03) * 2.1) * Math.cos(y * 0.037 + Math.sin(x * 0.02) * 1.7);
                double n2 = 0.5 + 0.5 * Math.sin(x * 0.021 - y * 0.033 + 1.3);
                int rr = (int) (4 + 70 * Math.pow(n1, 3) + 20 * n2), gg = (int) (3 + 18 * Math.pow(n1, 3) + 40 * Math.pow(n2, 4)),
                        bb = (int) (14 + 90 * Math.pow(n1, 2.5) + 70 * Math.pow(n2, 3));
                spaceBase.setPixel(x, y, argb(255, rr, gg, bb));
            }
        }
        for (int i = 0; i < 900; i++) {
            int x = r.nextInt(S), y = r.nextInt(S), b = 120 + r.nextInt(136);
            int t = r.nextInt(4);
            spaceBase.setPixel(x, y, t == 0 ? argb(255, b, (int) (b * 0.8), (int) (b * 0.6)) : t == 1 ? argb(255, (int) (b * 0.7), (int) (b * 0.85), b) : argb(255, b, b, b));
        }
        spaceId = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/sky_space");
        NativeImage copy = new NativeImage(S, S, false);
        copy.copyFrom(spaceBase);
        spaceTex = new DynamicTexture(() -> "sandevistan sky space", copy);
        mc.getTextureManager().register(spaceId, spaceTex);
        // День: голубой с облаками; ночь: тёмная с звёздами (как блок, но крупнее — на весь кусок).
        int T = 128;
        NativeImage day = new NativeImage(T, T, false), night = new NativeImage(T, T, false);
        for (int y = 0; y < T; y++) {
            for (int x = 0; x < T; x++) {
                double c = Math.max(0, Math.sin(x * 0.09 + Math.sin(y * 0.07) * 2) * Math.cos(y * 0.08 + x * 0.02) * 1.4 - 0.3);
                c = Math.min(1, c * 1.5);
                day.setPixel(x, y, argb(255, (int) (125 + 125 * c), (int) (180 + 72 * c), (int) (245 + 10 * c)));
                double neb = Math.max(0, Math.sin(x * 0.05 - y * 0.04) * Math.sin(y * 0.06 + 1) - 0.2);
                night.setPixel(x, y, argb(255, (int) (8 + 60 * neb), (int) (8 + 20 * neb), (int) (26 + 80 * neb)));
            }
        }
        for (int i = 0; i < 140; i++) {
            int b = 140 + r.nextInt(116);
            night.setPixel(r.nextInt(T), r.nextInt(T), argb(255, b, b, (int) Math.min(255, b * 1.1)));
        }
        dayId = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/sky_day");
        nightId = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/sky_night");
        dayTex = new DynamicTexture(() -> "sandevistan sky day", day);
        nightTex = new DynamicTexture(() -> "sandevistan sky night", night);
        mc.getTextureManager().register(dayId, dayTex);
        mc.getTextureManager().register(nightId, nightTex);
    }

    private static int argb(int a, int r, int g, int b) {
        // NativeImage хранит ABGR.
        return (a << 24) | (Math.min(255, b) << 16) | (Math.min(255, g) << 8) | Math.min(255, r);
    }

    /** Глитч космоса: строки сдвигаются, цвет раздваивается, битые блоки мигают. Раз в 2 тика. */
    private static void glitch() {
        NativeImage img = spaceTex.getPixels();
        if (img == null) return;
        int S = spaceBase.getWidth();
        img.copyFrom(spaceBase);
        int bands = 3 + R.nextInt(5);
        for (int b = 0; b < bands; b++) {
            int y0 = R.nextInt(S), h = 2 + R.nextInt(14), shift = R.nextInt(41) - 20;
            boolean split = R.nextBoolean();
            for (int y = y0; y < Math.min(S, y0 + h); y++) {
                for (int x = 0; x < S; x++) {
                    int src = spaceBase.getPixel(Math.floorMod(x - shift, S), y);
                    if (split) {
                        int red = spaceBase.getPixel(Math.floorMod(x - shift - 4, S), y) & 0xFF;
                        int blue = (spaceBase.getPixel(Math.floorMod(x - shift + 4, S), y) >> 16) & 0xFF;
                        src = (src & 0xFF00FF00) | (blue << 16) | red;
                    }
                    img.setPixel(x, y, src);
                }
            }
        }
        for (int i = 0; i < 6; i++) {
            int bx = R.nextInt(S - 16), by = R.nextInt(S - 16), s = 4 + R.nextInt(12);
            int col = R.nextInt(3) == 0 ? argb(255, 255, 40, 90) : R.nextBoolean() ? argb(255, 60, 230, 255) : argb(255, 0, 0, 0);
            for (int y = by; y < by + s; y++) for (int x = bx; x < bx + s; x++) img.setPixel(x, y, col);
        }
        spaceTex.upload();
    }

    // ---------- Тик ----------

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.isPaused() || EVENTS.isEmpty()) return;
        textures();
        long now = mc.level.getGameTime();
        if (now % 2 == 0) glitch();
        for (Iterator<Event> it = EVENTS.iterator(); it.hasNext(); ) {
            Event e = it.next();
            long n = now - e.start;
            if (n > e.healStart + HEAL_TOTAL + 40) {
                it.remove();
                continue;
            }
            if (!e.cracked && n >= 12) {
                e.cracked = true;
                Vec3 at = new Vec3(e.cx, e.skyY, e.cz);
                mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.AMBIENT, 12.0F, 0.45F, false);
                mc.level.playLocalSound(at.x, at.y, at.z, com.smi7i.sandevistan.ModSounds.BOOM_MOTION, SoundSource.AMBIENT, 8.0F, 0.6F, false);
            }
            if (!e.healAnnounced && n >= e.healStart) {
                e.healAnnounced = true;
                Vec3 at = new Vec3(e.cx, e.skyY, e.cz);
                mc.level.playLocalSound(at.x, at.y, at.z, com.smi7i.sandevistan.ModSounds.BOOM_MOTION, SoundSource.AMBIENT, 6.0F, 1.2F, false);
            }
            for (Shard s : e.shards) {
                Vec3 skyMid = skyCenter(e, s);
                if (!s.opened && n >= s.fallStart) {
                    mc.level.playLocalSound(skyMid.x, skyMid.y, skyMid.z, com.smi7i.sandevistan.ModSounds.BOOM_FLASH, SoundSource.AMBIENT, 4.0F, 0.7F, false);
                    mc.level.playLocalSound(s.ground.x, s.ground.y, s.ground.z, com.smi7i.sandevistan.ModSounds.BOOM_LANDFALL, SoundSource.AMBIENT, 4.0F, 0.9F + R.nextFloat() * 0.2F, false);
                    s.opened = true;
                    mc.level.playLocalSound(skyMid.x, skyMid.y, skyMid.z, SoundEvents.GLASS_BREAK, SoundSource.AMBIENT, 10.0F, 0.5F + R.nextFloat() * 0.3F, false);
                    mc.level.playLocalSound(skyMid.x, skyMid.y, skyMid.z, com.smi7i.sandevistan.ModSounds.BOOM_WHOOSH, SoundSource.AMBIENT, 6.0F, 0.6F + R.nextFloat() * 0.2F, false);
                    for (int i = 0; i < 20; i++) {
                        MechFx.glowPuff(skyMid.add(rnd(6, 0.5, 6)), rnd(0.15, 0.1, 0.15).add(0, -0.15, 0), 0.6F + R.nextFloat(), -0.01F,
                                20 + R.nextInt(20), R.nextBoolean() ? 0x9AD8FF : 0xC080FF, 0.9F);
                    }
                }
                if (n >= s.fallStart && n < s.fallStart + s.fallDur) {
                    float k = (float) (n - s.fallStart) / s.fallDur;
                    Vec3 c = skyMid.lerp(s.ground, k * k);
                    MechFx.glowPuff(c.add(rnd(2, 1, 2)), new Vec3(0, 0.2, 0), 0.8F + R.nextFloat(), 0.02F, 14 + R.nextInt(10),
                            R.nextBoolean() ? 0x9AD8FF : 0xFFFFFF, 0.6F);
                }
                // Заживление дыры: искры по кромке, в конце — вспышка и кольцо по небу.
                if (s.opened && !s.healed && n >= s.healAt) {
                    if (!s.healStarted) {
                        s.healStarted = true;
                        mc.level.playLocalSound(skyMid.x, skyMid.y, skyMid.z, com.smi7i.sandevistan.ModSounds.BOOM_SPARKLE, SoundSource.AMBIENT, 6.0F, 0.9F + R.nextFloat() * 0.3F, false);
                    }
                    float h = (float) (n - s.healAt) / HEAL_ONE;
                    float sc = 1 - h * h * (3 - 2 * h);
                    for (int i = 0; i < 4; i++) {
                        int c = R.nextInt(4);
                        Vec3 a = new Vec3(s.sky[c * 2], e.skyY, s.sky[c * 2 + 1]), b = new Vec3(s.sky[((c + 1) % 4) * 2], e.skyY, s.sky[((c + 1) % 4) * 2 + 1]);
                        Vec3 edge = skyMid.lerp(a.lerp(b, R.nextDouble()), sc);
                        MechFx.glowPuff(edge, rnd(0.05, 0.03, 0.05).add(0, -0.04, 0), 0.4F + R.nextFloat() * 0.5F, -0.01F, 12 + R.nextInt(10),
                                R.nextBoolean() ? 0xFFFFFF : 0x9AD8FF, 0.95F);
                    }
                    if (h >= 1) {
                        s.healed = true;
                        MechFx.waveAt(skyMid.add(0, -0.2, 0), 14, 16, 1.2F, 0x9AD8FF, true);
                        for (int i = 0; i < 24; i++) {
                            MechFx.glowPuff(skyMid.add(rnd(2, 0.3, 2)), rnd(0.35, 0.05, 0.35), 0.6F + R.nextFloat() * 0.8F, -0.01F, 14 + R.nextInt(10),
                                    R.nextBoolean() ? 0xFFFFFF : 0xBFE8FF, 0.95F);
                        }
                        mc.level.playLocalSound(skyMid.x, skyMid.y, skyMid.z, com.smi7i.sandevistan.ModSounds.BOOM_FLASH, SoundSource.AMBIENT, 5.0F, 1.1F + R.nextFloat() * 0.2F, false);
                    }
                }
                if (!s.landed && n >= s.fallStart + s.fallDur) {
                    s.landed = true;
                    Vec3 g = new Vec3(s.ground.x, s.ground.y - Math.sin(s.tilt) * s.l / 2 * 0.85, s.ground.z);
                    MechFx.dustRingAt(g.add(0, 0.2, 0), 50, 0.6, 1.2, 2.0F, 3.5F, 50, 90);
                    MechFx.groundChunks(g, 26);
                    MechFx.waveAt(g.add(0, 0.08, 0), 14, 16, 1.4F, 0x9AD8FF, true);
                    for (int i = 0; i < 24; i++) {
                        MechFx.glowPuff(s.ground.add(rnd(s.w / 2, 1, s.l / 2)), rnd(0.2, 0.1, 0.2).add(0, 0.1, 0), 0.4F + R.nextFloat() * 0.6F,
                                -0.01F, 20 + R.nextInt(20), R.nextBoolean() ? 0x9AD8FF : 0xC080FF, 0.9F);
                    }
                    if (mc.player != null) {
                        double d = mc.player.position().distanceTo(g);
                        if (d < 60) MechShake.kick((float) (2.6 * (1 - d / 60)));
                    }
                }
            }
        }
    }

    private static Vec3 skyCenter(Event e, Shard s) {
        double x = 0, z = 0;
        for (int k = 0; k < 4; k++) {
            x += s.sky[k * 2] / 4;
            z += s.sky[k * 2 + 1] / 4;
        }
        return new Vec3(x, e.skyY, z);
    }

    // ---------- Отрисовка ----------

    public static void submit(SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || EVENTS.isEmpty()) return;
        textures();
        Vec3 cam = camera.pos;
        float time = mc.level.getGameTime() + partial;
        List<float[]> glow = new ArrayList<>();
        for (Event e : EVENTS) {
            double n = mc.level.getGameTime() - e.start + partial;
            float alpha = 1.0F;
            for (Shard s : e.shards) {
                Vec3[] sky = new Vec3[4];
                for (int k = 0; k < 4; k++) sky[k] = new Vec3(s.sky[k * 2], e.skyY, s.sky[k * 2 + 1]).subtract(cam);
                // Трещины: светлые линии по краям будущих кусков, проступают от центра к краям.
                double crackAt = 12 + Math.hypot(s.sky[0] - e.cx, s.sky[1] - e.cz) / SkyShatter.RADIUS * 45;
                if (n >= crackAt && n < s.fallStart + 4) {
                    float grow = (float) Mth.clamp((n - crackAt) / 10, 0, 1);
                    float fl = 0.7F + 0.3F * R.nextFloat();
                    for (int k = 0; k < 4; k++) {
                        Vec3 a = sky[k], b = a.lerp(sky[(k + 1) % 4], grow);
                        ribbon(glow, a, b, 0.9F, scale(0x9AD8FF, 0.45F * fl));
                        ribbon(glow, a, b, 0.25F, scale(0xFFFFFF, 0.95F * fl));
                    }
                }
                // Дыра в космос: сам космос (глитчит текстурой) + красно-голубое раздвоение + светящаяся кромка.
                float heal = (float) Mth.clamp((n - s.healAt) / HEAL_ONE, 0, 1);
                if (n >= s.fallStart && heal < 1) {
                    float open = (float) Mth.clamp((n - s.fallStart) / 6, 0, 1);
                    float shrink = 1 - heal * heal * (3 - 2 * heal);
                    Vec3 mid = skyCenter(e, s).subtract(cam);
                    Vec3[] hole = new Vec3[4];
                    for (int k = 0; k < 4; k++) hole[k] = mid.lerp(sky[k], (0.2 + 0.8 * open) * shrink);
                    if (heal > 0) {
                        // Нити: от исходного края к схлопывающемуся — небо «стягивает» дыру.
                        for (int k = 0; k < 4; k++) {
                            for (int m = 0; m < 3; m++) {
                                double f = (m + 0.5) / 3.0;
                                Vec3 from = sky[k].lerp(sky[(k + 1) % 4], f), to = hole[k].lerp(hole[(k + 1) % 4], f);
                                ribbon(glow, from, to, 0.25F, scale(0xBFE8FF, 0.7F * (1 - heal * 0.6F)));
                            }
                        }
                    }
                    texturedQuad(collector, cam, RenderTypes.entityTranslucentEmissive(spaceId), hole, e, 0, 0, 0xFFFFFF, alpha, true);
                    if (R.nextFloat() < 0.5F) {
                        float off = 0.01F + R.nextFloat() * 0.02F;
                        texturedQuad(collector, cam, RenderTypes.eyes(spaceId), lower(hole, 0.05), e, off, 0, 0xB02040, alpha * 0.8F, true);
                        texturedQuad(collector, cam, RenderTypes.eyes(spaceId), lower(hole, 0.1), e, -off, 0, 0x2080B0, alpha * 0.8F, true);
                    }
                    float fl = (0.6F + 0.4F * Mth.sin(time * 1.7F + s.fallStart)) * alpha;
                    float hot = 1 + 2.5F * heal;
                    for (int k = 0; k < 4; k++) {
                        ribbon(glow, hole[k], hole[(k + 1) % 4], 1.4F * hot, scale(heal > 0 ? 0xBFE8FF : 0xA060FF, Math.min(1, 0.4F * fl * hot)));
                        ribbon(glow, hole[k], hole[(k + 1) % 4], 0.35F * hot, scale(heal > 0 ? 0xFFFFFF : 0x9AD8FF, Math.min(1, 0.9F * fl * hot)));
                    }
                }
                // Падающий кусок: снизу день, сверху ночь, по краям светящаяся кромка; падает с ускорением.
                if (n >= s.fallStart && n < s.fallStart + s.fallDur) {
                    float k = (float) ((n - s.fallStart) / s.fallDur);
                    float e2 = k * k;
                    Vec3[] ax = SkyShatter.axes(s.yaw, s.tilt);
                    Vec3 g = s.ground.subtract(cam);
                    Vec3[] gTop = new Vec3[4], gBot = new Vec3[4];
                    double[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                    for (int c = 0; c < 4; c++) {
                        Vec3 p = g.add(ax[0].scale(sg[c][0] * s.w / 2)).add(ax[1].scale(sg[c][1] * s.l / 2));
                        gTop[c] = p.add(ax[2].scale(0.6));
                        gBot[c] = p.add(ax[2].scale(-0.6));
                    }
                    Vec3[] top = new Vec3[4], bot = new Vec3[4];
                    for (int c = 0; c < 4; c++) {
                        top[c] = sky[c].add(0, 0.8, 0).lerp(gTop[c], e2);
                        bot[c] = sky[c].lerp(gBot[c], e2);
                    }
                    // Кувырок: перевёрнутый кусок в полёте переворачивается через ребро (ложится небом вверх),
                    // остальные просто покачиваются.
                    Vec3 mid = Vec3.ZERO;
                    for (int c = 0; c < 4; c++) mid = mid.add(top[c].add(bot[c]).scale(0.125));
                    double sm = k * k * (3 - 2 * k);
                    double ang = s.flip ? Math.PI * (1 - sm) : 0.25 * Math.sin(k * 9 + s.fallStart) * (1 - sm);
                    for (int c = 0; c < 4; c++) {
                        top[c] = rotateAround(top[c], mid, ax[0], ang);
                        bot[c] = rotateAround(bot[c], mid, ax[0], ang);
                    }
                    // У перевёрнутого «верх» плиты — дневное небо.
                    Identifier upTex = s.flip ? dayId : nightId, downTex = s.flip ? nightId : dayId;
                    texturedQuad(collector, cam, RenderTypes.entityTranslucentEmissive(downTex), bot, null, 0, 0, 0xFFFFFF, 1, false);
                    texturedQuad(collector, cam, RenderTypes.entityTranslucentEmissive(upTex), top, null, 0, 0, 0xFFFFFF, 1, false);
                    for (int c = 0; c < 4; c++) {
                        ribbon(glow, top[c], top[(c + 1) % 4], 0.8F, scale(0x9AD8FF, 0.6F));
                        ribbon(glow, bot[c], bot[(c + 1) % 4], 0.5F, scale(0xC080FF, 0.6F));
                        ribbon(glow, top[c], bot[c], 0.5F, scale(0xFFFFFF, 0.5F));
                    }
                }
            }
        }
        if (glow.isEmpty()) return;
        collector.submitCustomGeometry(new PoseStack(), GlowRender.type(), (pose, buffer) -> {
            for (float[] f : glow) {
                int c0 = Float.floatToRawIntBits(f[12]);
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) GlowRender.vertex(buffer, pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2], c0);
            }
        });
    }

    /** Поворот точки p вокруг оси (через точку c, направление axis) на угол a (формула Родрига). */
    private static Vec3 rotateAround(Vec3 p, Vec3 c, Vec3 axis, double a) {
        if (a == 0) return p;
        Vec3 v = p.subtract(c), k = axis.normalize();
        double cos = Math.cos(a), sin = Math.sin(a);
        Vec3 r = v.scale(cos).add(k.cross(v).scale(sin)).add(k.scale(k.dot(v) * (1 - cos)));
        return c.add(r);
    }

    private static Vec3[] lower(Vec3[] q, double dy) {
        Vec3[] r = new Vec3[q.length];
        for (int i = 0; i < q.length; i++) r[i] = q[i].add(0, -dy, 0);
        return r;
    }

    /** Четырёхугольник с текстурой (с обеих сторон). Для дыр UV — по мировым x/z (космос «стоит» в небе), для кусков — 0..1. */
    private static void texturedQuad(SubmitNodeCollector collector, Vec3 cam, RenderType type, Vec3[] q, Event e, float du, float dv, int rgb,
                                     float alpha, boolean worldUv) {
        int col = HoloUi.argb(alpha, rgb);
        float[][] uv = new float[4][2];
        for (int k = 0; k < 4; k++) {
            if (worldUv && e != null) {
                uv[k][0] = (float) ((q[k].x + cam.x - e.cx) / 70.0 + 0.5) + du;
                uv[k][1] = (float) ((q[k].z + cam.z - e.cz) / 70.0 + 0.5) + dv;
            } else {
                uv[k][0] = k == 1 || k == 2 ? 1 : 0;
                uv[k][1] = k >= 2 ? 1 : 0;
            }
        }
        collector.submitCustomGeometry(new PoseStack(), type, (pose, buffer) -> {
            int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
            for (int k : order) vertex(buffer, pose, q[k], uv[k][0], uv[k][1], col);
        });
    }

    private static void vertex(VertexConsumer b, PoseStack.Pose pose, Vec3 p, float u, float v, int col) {
        b.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(pose, 0, -1, 0);
    }

    private static void ribbon(List<float[]> out, Vec3 a, Vec3 b, float width, int rgb) {
        Vec3 side = b.subtract(a).cross(a.add(b).scale(0.5));
        if (side.lengthSqr() < 1.0E-10) return;
        side = side.normalize().scale(width / 2);
        int col = HoloUi.argb(1, rgb);
        out.add(new float[]{
                (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z),
                (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z),
                (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z),
                (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z),
                Float.intBitsToFloat(col)});
    }

    private static int scale(int rgb, float k) {
        k = Math.max(0, Math.min(1, k));
        return (Math.round(((rgb >> 16) & 0xFF) * k) << 16) | (Math.round(((rgb >> 8) & 0xFF) * k) << 8) | Math.round((rgb & 0xFF) * k);
    }

    private static Vec3 rnd(double x, double y, double z) {
        return new Vec3((R.nextDouble() - 0.5) * 2 * x, (R.nextDouble() - 0.5) * 2 * y, (R.nextDouble() - 0.5) * 2 * z);
    }
}
