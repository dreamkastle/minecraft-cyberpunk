package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.client.IrisCompat;
import com.smi7i.sandevistan.client.SlowMotionClient;
import com.smi7i.sandevistan.mech.MechFxPayload;
import com.smi7i.sandevistan.mech.MechSuitEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Свои эффекты костюма-меха (без ванильных частиц), по мотивам Roblox-видео пользователя:
 *  - клубы дыма/пыли цвета земли (мягкие пушистые «облака» — текстура рисуется кодом при запуске);
 *  - куски блоков: настоящие маленькие кубики с текстурой блока, крутятся по любым осям, отскакивают от земли;
 *  - красный круг на земле там, куда падает вызванный костюм (сходящееся кольцо — отсчёт до удара).
 * Сервер присылает MechFxPayload, всё остальное считается и рисуется здесь.
 */
public final class MechFx {
    // ===== Настройки =====
    /** В слоумо (зона Сандевистана) эффекты живут во столько раз медленнее. */
    private static final float SLOW_SPEED = 0.25F;
    private static final int MAX_PUFFS = 2200, MAX_CHUNKS = 500;
    /** Цвет пыли без земли (светло-серо-бежевый) и насколько к нему подмешивается цвет земли. */
    private static final int DUST_BASE = 0xC9C2B4;
    private static final float GROUND_MIX = 0.55F;
    private static final int MARK_RED = 0xFF2A2A;

    private static final class Puff {
        Vec3 pos, prevPos, vel;
        float size, prevSize, grow, age, prevAge, life, roll, rollSpeed, maxAlpha;
        int rgb, variant;
        /** Светится сам (огонь взрыва) — не зависит от освещения. */
        boolean glow;
    }

    private static final class Chunk {
        Vec3 pos, prevPos, vel;
        Vector3f axis;
        float angle, prevAngle, spin, size, age, prevAge, life;
        /** Текстура и кусок на ней (атлас блоков или текстура костюма). */
        Identifier texture;
        float u0, v0, u1, v1;
        int tint;
        /** Обломок раскалён — светится сам. */
        boolean glow;
        /** Левитирует (третий удар атаки): поднимается, висит, синеет и резко падает. */
        boolean lev;
        Vec3 levBase;
        double levH;
        float levDelay, blue;
        BlockState levState;
        /** Кружит по орбите вокруг игрока с этим id (вихрь); -1 — летит сам. */
        int orbitOwner = -1;
        /** «Сингулярность»: кусок падает по спирали в чёрную дыру игрока sinkOwner. */
        int sinkOwner = -1;
        /** «Магнит»: кусок притягивается к шару у рук игрока magnetOwner, при выбросе летит вперёд. */
        int magnetOwner = -1;
        double magA, magR, magY;
        double sinkR, sinkA, sinkY;
        double orbitR, orbitH, orbitA, orbitSpeed, orbitGround;
        boolean resting;
        int trail;
    }

    /** Зелёная полоса рывка: от точки ухода до точки появления, ярко вспыхивает и сужаясь тает. */
    private static final class Streak {
        Vec3 from, to;
        float age, prevAge;
    }

    private static final List<Streak> STREAKS = new ArrayList<>();
    private static Vec3 dashFrom;
    /** Полоса рывка: жизнь (тики), ширина (блоки), высота над землёй (середина корпуса), цвета. */
    private static final float STREAK_LIFE = 16, STREAK_WIDTH = 1.1F, STREAK_HEIGHT = 1.3F;
    private static final int STREAK_GLOW = 0x2BFF5C, STREAK_CORE = 0xC8FFD2;
    private static final long DASH_FLASH_MS = 450;

    private static final List<Puff> PUFFS = new ArrayList<>();
    private static final List<Chunk> CHUNKS = new ArrayList<>();
    private static final Random R = new Random();

    private MechFx() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(MechFxPayload.TYPE, (payload, context) -> spawn(payload));
        ClientTickEvents.END_CLIENT_TICK.register(MechFx::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            PUFFS.clear();
            CHUNKS.clear();
            STREAKS.clear();
            BOLTS.clear();
            WAVES.clear();
            CRACKS.clear();
            VORTEX_SEEN.clear();
        });
        // С шейдерпаком Iris — вместе с миром; без него — в SandevistanWorldPass.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!IrisCompat.shadersActive() || !hasAny()) return; // с шейдерами — только когда есть что рисовать
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submit(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
    }

    /** Есть что рисовать: эффекты или падающий костюм (для красного круга). */
    public static boolean hasAny() {
        return !PUFFS.isEmpty() || !CHUNKS.isEmpty() || MechWreck.hasAny() ||!STREAKS.isEmpty() || !BOLTS.isEmpty() || !VORTEX_SEEN.isEmpty()
                || !WAVES.isEmpty() || !CRACKS.isEmpty() || !METEOR_SEEN.isEmpty() || !SHOULDERS.isEmpty() || !SCORCHES.isEmpty() || !DEATH_SEEN.isEmpty() || !FOOTPRINTS.isEmpty()
                || (Minecraft.getInstance().player != null && com.smi7i.sandevistan.mech.MechMeteor.isHidden(Minecraft.getInstance().player))
                || fallingSuit() != null;
    }

    // ---------- Появление ----------

    private static void spawn(MechFxPayload p) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 at = new Vec3(p.x(), p.y(), p.z());
        BlockState ground = Block.stateById(p.ground());
        int dust = dustColor(level, ground, BlockPos.containing(at).below());
        Vec3 fwd = Vec3.directionFromRotation(0, p.yaw());
        int power = Math.max(1, p.power());
        switch (p.kind()) {
            case MechFxPayload.LAND -> {
                // Купол пыли по земле во все стороны + столб вверх + куски земли (блоки не ломаются).
                ring(at, dust, 46, 0.42, 0.62, 1.3F, 2.1F, 55, 95);
                ring(at, dust, 26, 0.22, 0.35, 1.6F, 2.4F, 70, 110);
                column(at, dust, 14, 1.6F);
                fakeChunks(level, at, ground, 28, 0.25, 0.5, 0.45, 0.8, null);
            }
            case MechFxPayload.HIT -> {
                cone(at, fwd, dust, 9 * power, 0.25 + 0.1 * power, 0.7F + 0.2F * power);
                ring(at, dust, 10 * power, 0.12 + 0.05 * power, 0.22, 0.6F, 1.0F + 0.2F * power, 30, 60);
                brokenChunks(level, p, fwd, 2 + power, 0.25 + 0.08 * power, 0.35 + 0.1 * power);
                fakeChunks(level, at, ground, 5 * power, 0.12, 0.3, 0.3, 0.5, fwd);
                shock(mc, at, 5.0F + 2.0F * power, 0.7F + 0.3F * power, dust, 0xFFE8C8);
                arcWaves(at, fwd, 9.0F + 2.0F * power, 0xFFE8C8, 0.7F + 0.2F * power);
            }
            case MechFxPayload.SLAM -> {
                ring(at, dust, 56, 0.45, 0.7, 1.4F, 2.3F, 60, 100);
                ring(at, dust, 30, 0.2, 0.32, 1.8F, 2.6F, 80, 120);
                column(at, dust, 18, 1.9F);
                cone(at, fwd, dust, 24, 0.55, 1.6F);
                brokenChunks(level, p, fwd, 3, 0.3, 0.55);
                fakeChunks(level, at, ground, 22, 0.25, 0.55, 0.5, 0.9, fwd);
                shock(mc, at, 10.0F, 1.6F, dust, 0xFFE8C8);
                arcWaves(at, fwd, 16.0F, 0xFFE8C8, 1.4F);
            }
            case MechFxPayload.WAVE -> {
                Vec3 side = new Vec3(-fwd.z, 0, fwd.x);
                for (int i = 0; i < 3 + power * 2; i++) {
                    Vec3 o = at.add(side.scale((R.nextDouble() - 0.5) * power));
                    puff(o.add(0, 0.3, 0), fwd.scale(0.12 + R.nextDouble() * 0.1).add(rnd(0.08, 0.04, 0.08)).add(0, 0.05, 0),
                            0.9F + R.nextFloat() * 0.6F, 0.035F, 40 + R.nextInt(30), dust, 0.85F);
                }
                brokenChunks(level, p, fwd, 2, 0.2, 0.6);
            }
            case MechFxPayload.DASH -> {
                dashFrom = at;
                // Место, откуда исчез: облако пыли по силуэту костюма, срывается назад.
                for (int i = 0; i < 22; i++) {
                    Vec3 o = at.add(rnd(0.7, 0, 0.7)).add(0, R.nextDouble() * 2.6, 0);
                    puff(o, fwd.scale(-0.04 - R.nextDouble() * 0.06).add(rnd(0.05, 0.03, 0.05)),
                            0.7F + R.nextFloat() * 0.6F, 0.04F, 20 + R.nextInt(20), shade(dust, 0.15F), 0.8F);
                }
                ring(at, dust, 12, 0.12, 0.2, 0.6F, 1.0F, 20, 40);
            }
            case MechFxPayload.DASH_END -> {
                // Место, где появился: пыль кольцом от ног и куски земли.
                ring(at, dust, 18, 0.18, 0.3, 0.7F, 1.2F, 25, 45);
                fakeChunks(level, at, ground, 6, 0.1, 0.25, 0.25, 0.45, null);
                // Зелёная полоса от точки ухода до точки появления; свой рывок — ещё и зелёная вспышка экрана.
                if (dashFrom != null) {
                    Streak s = new Streak();
                    s.from = dashFrom;
                    s.to = at;
                    STREAKS.add(s);
                    if (mc.player != null && (mc.player.position().distanceTo(at) < 2.0 || mc.player.position().distanceTo(dashFrom) < 2.0)) {
                        MechLifeHud.greenFlash(DASH_FLASH_MS);
                    }
                    dashFrom = null;
                }
            }
            case MechFxPayload.SUIT_EXPLODE -> explodeSuit(level, at, dust);
            case MechFxPayload.PULL -> {
                // «Магнит»: куски земли отрываются и летят по спирали в шар у рук.
                for (int i = 0; i < p.blocks().length && CHUNKS.size() < MAX_CHUNKS; i++) {
                    BlockPos bp = BlockPos.of(p.blocks()[i]);
                    BlockState st = Block.stateById(p.states()[i]);
                    if (st.isAir()) continue;
                    TextureAtlasSprite sp = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(st).sprite();
                    Chunk c = new Chunk();
                    c.magnetOwner = p.power();
                    c.magA = R.nextDouble() * Math.PI * 2;
                    c.magR = 0.9 + R.nextDouble() * 1.4;
                    c.magY = (R.nextDouble() - 0.5) * 1.6;
                    c.pos = c.prevPos = Vec3.atCenterOf(bp).add(0, 0.6, 0);
                    c.vel = new Vec3(0, 0.25, 0);
                    c.axis = new Vector3f(R.nextFloat() - 0.5F, R.nextFloat() - 0.5F, R.nextFloat() - 0.5F);
                    if (c.axis.lengthSquared() < 1.0E-4F) c.axis.set(0, 1, 0);
                    c.axis.normalize();
                    c.spin = (3 + R.nextFloat() * 5) * (R.nextBoolean() ? 1 : -1);
                    c.size = 0.45F + R.nextFloat() * 0.5F;
                    c.life = 260;
                    c.texture = sp.atlasLocation();
                    c.u0 = sp.getU0(); c.v0 = sp.getV0(); c.u1 = sp.getU1(); c.v1 = sp.getV1();
                    c.tint = 0xFFFFFFFF;
                    addChunk(c);
                    puff(Vec3.atCenterOf(bp).add(0, 0.6, 0), new Vec3(0, 0.08, 0), 0.9F, 0.04F, 20, shade(dustColor(level, st, bp), 0.1F), 0.7F);
                }
            }
            case MechFxPayload.ABSORB -> {
                // Поглощённые чёрной дырой блоки: целые кубы с текстурой блока по спирали летят к горизонту.
                for (int i = 0; i < p.blocks().length && CHUNKS.size() < MAX_CHUNKS; i++) {
                    BlockPos bp = BlockPos.of(p.blocks()[i]);
                    BlockState st = Block.stateById(p.states()[i]);
                    if (st.isAir()) continue;
                    TextureAtlasSprite sp = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(st).sprite();
                    Chunk c = new Chunk();
                    Vec3 rel = Vec3.atCenterOf(bp).subtract(at);
                    c.sinkOwner = p.power();
                    c.sinkR = Math.sqrt(rel.x * rel.x + rel.z * rel.z) + 1.5;
                    c.sinkA = Math.atan2(rel.z, rel.x);
                    c.sinkY = rel.y;
                    c.levBase = at;
                    c.pos = c.prevPos = Vec3.atCenterOf(bp);
                    c.vel = Vec3.ZERO;
                    c.axis = new Vector3f(R.nextFloat() - 0.5F, R.nextFloat() - 0.5F, R.nextFloat() - 0.5F);
                    if (c.axis.lengthSquared() < 1.0E-4F) c.axis.set(0, 1, 0);
                    c.axis.normalize();
                    c.spin = (4 + R.nextFloat() * 6) * (R.nextBoolean() ? 1 : -1);
                    c.size = 0.9F;
                    c.life = 400;
                    c.texture = sp.atlasLocation();
                    c.u0 = sp.getU0(); c.v0 = sp.getV0(); c.u1 = sp.getU1(); c.v1 = sp.getV1();
                    c.tint = 0xFFFFFFFF;
                    addChunk(c);
                }
            }
            case MechFxPayload.LEVITATE -> {
                for (int i = 0; i < p.blocks().length && CHUNKS.size() < MAX_CHUNKS; i++) {
                    BlockPos bp = BlockPos.of(p.blocks()[i]);
                    BlockState st = Block.stateById(p.states()[i]);
                    if (st.isAir()) continue;
                    TextureAtlasSprite sp = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(st).sprite();
                    Chunk c = new Chunk();
                    c.lev = true;
                    c.levState = st;
                    c.levBase = Vec3.atCenterOf(bp);
                    c.pos = c.prevPos = c.levBase;
                    c.vel = Vec3.ZERO;
                    c.levH = 1.6 + R.nextDouble() * 2.6;
                    c.levDelay = R.nextFloat() * 6;
                    c.axis = new Vector3f(R.nextFloat() - 0.5F, R.nextFloat() - 0.5F, R.nextFloat() - 0.5F);
                    if (c.axis.lengthSquared() < 1.0E-4F) c.axis.set(0, 1, 0);
                    c.axis.normalize();
                    c.angle = c.prevAngle = 0;
                    c.spin = (2 + R.nextFloat() * 4) * (R.nextBoolean() ? 1 : -1);
                    c.size = 0.92F;
                    c.life = 200;
                    c.texture = sp.atlasLocation();
                    c.u0 = sp.getU0(); c.v0 = sp.getV0(); c.u1 = sp.getU1(); c.v1 = sp.getV1();
                    c.tint = 0xFFFFFFFF;
                    addChunk(c);
                    puff(c.levBase.add(0, 0.3, 0), new Vec3(0, 0.05, 0), 0.9F, 0.04F, 24, shade(dust, 0.15F), 0.75F);
                }
            }
            case MechFxPayload.EJECT -> {
                Vec3 c = at.add(0, 1.6, 0);
                for (int i = 0; i < 34; i++) {
                    puff(c.add(rnd(0.4, 0.5, 0.4)), rnd(0.25, 0.2, 0.25), 0.8F + R.nextFloat() * 0.5F, 0.04F,
                            40 + R.nextInt(30), shade(0xD8D8D8, 0.15F), 0.9F);
                }
                fakeChunks(level, at, ground, 10, 0.2, 0.4, 0.3, 0.6, null);
            }
            default -> { }
        }
    }

    /**
     * Взрыв брошенного костюма: огненный шар (светится сам), чёрный дым столбом, куски брони костюма
     * (с его текстурой) и раскалённые обломки разлетаются и крутятся, вспышки-искры, пыль кольцом, тряска.
     */
    private static void explodeSuit(ClientLevel level, Vec3 at, int dust) {
        Vec3 c = at.add(0, 1.4, 0);
        // Огненный шар: от бело-жёлтого в центре к оранжевому и красному по краям.
        int[] fire = {0xFFF4C0, 0xFFD050, 0xFF9A2A, 0xFF5A1A, 0xE0301A};
        for (int i = 0; i < 46; i++) {
            Vec3 dir = rnd(1, 1, 1).normalize();
            int col = fire[Math.min(fire.length - 1, (int) (R.nextFloat() * fire.length))];
            Puff p = puff(c.add(dir.scale(R.nextDouble() * 0.6)), dir.scale(0.12 + R.nextDouble() * 0.22).add(0, 0.04, 0),
                    1.0F + R.nextFloat() * 1.2F, 0.07F, 10 + R.nextInt(14), col, 1.0F);
            if (p != null) p.glow = true;
        }
        // Чёрно-серый дым: густой, поднимается столбом и расползается.
        for (int i = 0; i < 40; i++) {
            puff(c.add(rnd(0.9, 0.8, 0.9)), rnd(0.12, 0, 0.12).add(0, 0.08 + R.nextDouble() * 0.18, 0),
                    1.4F + R.nextFloat() * 1.4F, 0.06F, 60 + R.nextInt(60), shade(0x2A2A30, 0.3F), 0.9F);
        }
        ring(at, dust, 40, 0.35, 0.6, 1.2F, 2.0F, 45, 80);
        // Куски брони костюма — с его текстурой; часть раскалена и светится.
        MechModel model = MechModel.get();
        for (int i = 0; i < 48; i++) {
            if (CHUNKS.size() >= MAX_CHUNKS) break;
            Vec3 dir = rnd(1, 0.4, 1).normalize();
            Chunk k = new Chunk();
            k.pos = k.prevPos = c.add(dir.scale(0.3 + R.nextDouble() * 0.6)).add(0, R.nextDouble() * 1.2 - 0.6, 0);
            k.vel = dir.scale(0.25 + R.nextDouble() * 0.45).add(0, 0.35 + R.nextDouble() * 0.6, 0);
            k.axis = new Vector3f(R.nextFloat() - 0.5F, R.nextFloat() - 0.5F, R.nextFloat() - 0.5F);
            if (k.axis.lengthSquared() < 1.0E-4F) k.axis.set(0, 1, 0);
            k.axis.normalize();
            k.angle = k.prevAngle = R.nextFloat() * 360;
            k.spin = (12 + R.nextFloat() * 30) * (R.nextBoolean() ? 1 : -1);
            k.size = 0.18F + R.nextFloat() * (i < 8 ? 0.6F : 0.32F); // несколько крупных кусков
            k.life = 90 + R.nextInt(70);
            float[] uv = model.randomFaceUv(R);
            k.texture = MechModel.TEXTURE;
            k.u0 = uv[0];
            k.v0 = uv[1];
            k.u1 = uv[2];
            k.v1 = uv[3];
            k.glow = R.nextFloat() < 0.2F;
            k.tint = k.glow ? 0xFFFFB070 : 0xFFFFFFFF;
            addChunk(k);
        }
        // Искры-вспышки (пиксельные звёзды и разряды).
        for (int i = 0; i < 4; i++) {
            com.smi7i.sandevistan.client.HitBurst.spawn(c.add(rnd(0.8, 0.8, 0.8)), rnd(1, 0.5, 1).normalize());
        }
        MechShake.explosion(at);
    }

    /** «Последний бой»: из-под летящего костюма (и клонов) вылетают осколки земли и пыль. */
    public static void groundShards(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockPos below = BlockPos.containing(at.x, at.y - 0.6, at.z);
        BlockState gs = mc.level.getBlockState(below);
        for (int i = 0; i < 3 && gs.isAir(); i++) {
            below = below.below();
            gs = mc.level.getBlockState(below);
        }
        if (gs.isAir()) return;
        Vec3 g = new Vec3(at.x, below.getY() + 1.0, at.z);
        fakeChunks(mc.level, g, gs, 2, 0.08, 0.22, 0.25, 0.5, null);
        puff(g.add(rnd(0.4, 0, 0.4)).add(0, 0.2, 0), rnd(0.04, 0.02, 0.04), 0.7F + R.nextFloat() * 0.4F, 0.03F,
                20 + R.nextInt(14), shade(dustColor(mc.level, gs, below), 0.15F), 0.65F);
    }

    /** Кольцо дыма по земле: count клубов разлетаются во все стороны. */
    private static void ring(Vec3 at, int rgb, int count, double speedMin, double speedMax, float sizeMin, float sizeMax,
                             int lifeMin, int lifeMax) {
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * (i + R.nextDouble() * 0.6) / count;
            double s = speedMin + R.nextDouble() * (speedMax - speedMin);
            Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
            puff(at.add(dir.scale(0.6)).add(0, 0.25 + R.nextDouble() * 0.4, 0), dir.scale(s).add(0, 0.015 + R.nextDouble() * 0.04, 0),
                    sizeMin + R.nextFloat() * (sizeMax - sizeMin), 0.05F, lifeMin + R.nextInt(lifeMax - lifeMin + 1),
                    shade(rgb, 0.18F), 0.95F);
        }
    }

    /** Столб пыли вверх (при приземлении и ударе об землю). */
    private static void column(Vec3 at, int rgb, int count, float size) {
        for (int i = 0; i < count; i++) {
            puff(at.add(rnd(0.9, 0.2, 0.9)).add(0, 0.5, 0), rnd(0.08, 0, 0.08).add(0, 0.12 + R.nextDouble() * 0.22, 0),
                    size * (0.7F + R.nextFloat() * 0.6F), 0.06F, 60 + R.nextInt(50), shade(rgb, 0.2F), 0.85F);
        }
    }

    /** Веер дыма вперёд (куда пришёлся удар). */
    private static void cone(Vec3 at, Vec3 fwd, int rgb, int count, double speed, float size) {
        for (int i = 0; i < count; i++) {
            Vec3 dir = fwd.add(rnd(0.7, 0, 0.7)).normalize();
            puff(at.add(0, 0.3 + R.nextDouble() * 0.5, 0), dir.scale(speed * (0.5 + R.nextDouble())).add(0, 0.03 + R.nextDouble() * 0.06, 0),
                    size * (0.6F + R.nextFloat() * 0.6F), 0.045F, 35 + R.nextInt(35), shade(rgb, 0.2F), 0.9F);
        }
    }

    private static Puff puff(Vec3 pos, Vec3 vel, float size, float grow, int life, int rgb, float alpha) {
        if (PUFFS.size() >= MAX_PUFFS) return null;
        Puff p = new Puff();
        p.pos = p.prevPos = pos;
        p.vel = vel;
        p.size = p.prevSize = size;
        p.grow = grow;
        p.life = life;
        p.roll = R.nextFloat() * 360;
        p.rollSpeed = (R.nextFloat() - 0.5F) * 4.0F;
        p.rgb = rgb;
        p.maxAlpha = alpha;
        p.variant = R.nextInt(VARIANTS);
        PUFFS.add(p);
        return p;
    }

    /** Куски из настоящих выбитых блоков (по 'perBlock' на блок). */
    private static void brokenChunks(ClientLevel level, MechFxPayload p, Vec3 fwd, int perBlock, double out, double up) {
        for (int i = 0; i < p.blocks().length; i++) {
            BlockPos pos = BlockPos.of(p.blocks()[i]);
            BlockState st = Block.stateById(p.states()[i]);
            Vec3 c = Vec3.atCenterOf(pos);
            Vec3 away = new Vec3(c.x - p.x(), 0, c.z - p.z());
            away = away.lengthSqr() > 1.0E-4 ? away.normalize() : fwd;
            Vec3 dir = away.add(fwd).normalize();
            for (int k = 0; k < perBlock; k++) {
                Vec3 v = dir.scale(out * (0.6 + R.nextDouble() * 0.8)).add(rnd(0.12, 0, 0.12))
                        .add(0, up * (0.7 + R.nextDouble() * 0.6), 0);
                chunk(level, c.add(rnd(0.3, 0.3, 0.3)), v, st, pos, 0.2F + R.nextFloat() * 0.25F);
            }
            // Облачко на месте выбитого блока.
            puff(c.add(0, 0.4, 0), new Vec3(0, 0.04, 0).add(rnd(0.05, 0, 0.05)), 0.8F + R.nextFloat() * 0.4F, 0.04F,
                    30 + R.nextInt(25), shade(dustColor(level, st, pos), 0.15F), 0.8F);
        }
    }

    /** Куски земли без разрушения (приземление): вылетают из-под ног во все стороны или вперёд (fwd). */
    private static void fakeChunks(ClientLevel level, Vec3 at, BlockState ground, int count, double outMin, double outMax,
                                   double upMin, double upMax, Vec3 fwd) {
        if (ground.isAir()) return;
        BlockPos gp = BlockPos.containing(at).below();
        for (int i = 0; i < count; i++) {
            double a = R.nextDouble() * Math.PI * 2;
            Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
            if (fwd != null) dir = dir.scale(0.6).add(fwd).normalize();
            double out = outMin + R.nextDouble() * (outMax - outMin);
            double up = upMin + R.nextDouble() * (upMax - upMin);
            chunk(level, at.add(dir.scale(0.4 + R.nextDouble() * 0.8)).add(0, 0.2, 0), dir.scale(out).add(0, up, 0), ground, gp,
                    0.14F + R.nextFloat() * 0.26F);
        }
    }

    private static void chunk(ClientLevel level, Vec3 pos, Vec3 vel, BlockState state, BlockPos from, float size) {
        if (CHUNKS.size() >= MAX_CHUNKS || state.isAir()) return;
        Chunk c = new Chunk();
        c.pos = c.prevPos = pos;
        c.vel = vel;
        c.axis = new Vector3f(R.nextFloat() - 0.5F, R.nextFloat() - 0.5F, R.nextFloat() - 0.5F);
        if (c.axis.lengthSquared() < 1.0E-4F) c.axis.set(1, 0, 0);
        c.axis.normalize();
        c.angle = c.prevAngle = R.nextFloat() * 360;
        c.spin = (8 + R.nextFloat() * 22) * (R.nextBoolean() ? 1 : -1); // градусов за тик, по своей оси
        c.size = size;
        c.life = 70 + R.nextInt(50);
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        // Кусок — не весь блок: случайная половина текстуры.
        float uw = (sprite.getU1() - sprite.getU0()) * 0.5F, vw = (sprite.getV1() - sprite.getV0()) * 0.5F;
        c.texture = sprite.atlasLocation();
        c.u0 = sprite.getU0() + R.nextInt(8) / 7.0F * uw;
        c.v0 = sprite.getV0() + R.nextInt(8) / 7.0F * vw;
        c.u1 = c.u0 + uw;
        c.v1 = c.v0 + vw;
        // Листва и трава в атласе серые — подкрашиваем цветом блока на карте.
        c.tint = state.is(BlockTags.LEAVES) || state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)
                ? 0xFF000000 | state.getMapColor(level, from).col : 0xFFFFFFFF;
        addChunk(c);
    }

    // ---------- Жизнь ----------

    /** Куски, появившиеся, пока перебирается список (например, при падении синих блоков), — добавляются после перебора. */
    private static final List<Chunk> NEW_CHUNKS = new ArrayList<>();
    private static boolean tickingChunks;

    private static void addChunk(Chunk c) {
        if (tickingChunks) NEW_CHUNKS.add(c);
        else CHUNKS.add(c);
    }

    private static void tick(Minecraft mc) {
        if (mc.isPaused() || mc.level == null) return;
        tickingChunks = true;
        try {
            tickInner(mc);
        } finally {
            tickingChunks = false;
            CHUNKS.addAll(NEW_CHUNKS);
            NEW_CHUNKS.clear();
        }
    }

    private static void tickInner(Minecraft mc) {
        float dt = SlowMotionClient.isSlowMotion() ? SLOW_SPEED : 1.0F;
        tickVortex(mc);
        tickMeteor(mc);
        tickShoulders();
        tickScorches();
        tickDeath(mc);
        tickDamage(mc);
        for (Iterator<Streak> it = STREAKS.iterator(); it.hasNext(); ) {
            Streak s = it.next();
            s.prevAge = s.age;
            s.age += dt;
            if (s.age >= STREAK_LIFE) it.remove();
        }
        for (Iterator<Puff> it = PUFFS.iterator(); it.hasNext(); ) {
            Puff p = it.next();
            p.prevPos = p.pos;
            p.prevSize = p.size;
            p.prevAge = p.age;
            p.age += dt;
            p.pos = p.pos.add(p.vel.scale(dt));
            p.vel = p.vel.scale(Math.pow(0.9, dt)).add(0, 0.0025 * dt, 0); // тормозит и медленно поднимается
            p.size += p.grow * dt * (1.0F - p.age / p.life);              // расползается
            p.roll += p.rollSpeed * dt;
            if (p.age >= p.life) it.remove();
        }
        List<Chunk> trail = new ArrayList<>();
        for (Iterator<Chunk> it = CHUNKS.iterator(); it.hasNext(); ) {
            Chunk c = it.next();
            c.prevPos = c.pos;
            c.prevAngle = c.angle;
            c.prevAge = c.age;
            c.age += dt;
            if (c.lev) {
                if (tickLevitate(mc, c, dt)) it.remove();
                continue;
            }
            if (c.sinkOwner >= 0) {
                if (sinkChunk(c, dt)) it.remove();
                continue;
            }
            if (c.magnetOwner >= 0) {
                if (magnetChunk(c, dt)) continue; // ещё у шара; иначе — уже выброшен, дальше обычная физика
            }
            if (c.orbitOwner >= 0) {
                orbitChunk(mc, c, dt);
                if (c.age >= c.life) it.remove();
                continue;
            }
            if (!c.resting) {
                c.vel = c.vel.add(0, -0.06 * dt, 0).scale(Math.pow(0.985, dt));
                Vec3 next = c.pos.add(c.vel.scale(dt));
                BlockPos below = BlockPos.containing(next.x, next.y - c.size / 2, next.z);
                BlockState st = mc.level.getBlockState(below);
                if (c.vel.y < 0 && !st.getCollisionShape(mc.level, below).isEmpty()) {
                    // Ударился о землю: отскок, трение, вращение затухает.
                    double top = below.getY() + st.getCollisionShape(mc.level, below).max(net.minecraft.core.Direction.Axis.Y);
                    next = new Vec3(next.x, top + c.size / 2, next.z);
                    c.vel = new Vec3(c.vel.x * 0.55, -c.vel.y * 0.3, c.vel.z * 0.55);
                    c.spin *= 0.55F;
                    if (Math.abs(c.vel.y) < 0.06) {
                        c.resting = true;
                        c.vel = Vec3.ZERO;
                    }
                }
                c.pos = next;
                c.angle += c.spin * dt;
                // Пылевой след за быстро летящим куском.
                if (c.vel.lengthSqr() > 0.04 && ++c.trail % 3 == 0) trail.add(c);
            }
            if (c.age >= c.life) it.remove();
        }
        for (Chunk c : trail) {
            puff(c.pos, c.vel.scale(0.1), 0.25F + c.size * 0.6F, 0.02F, 14 + R.nextInt(8), shade(0xB8B0A2, 0.1F), 0.55F);
        }
    }

    // ---------- Отрисовка ----------

    private static final int VARIANTS = 4, TEX = 32;
    private static Identifier smokeTexture;

    /** Мягкие «облачка» дыма (4 варианта в ряд), рисуются кодом: круг с размытым краем × шум. */
    private static Identifier smoke() {
        if (smokeTexture != null) return smokeTexture;
        NativeImage img = new NativeImage(TEX * VARIANTS, TEX, true);
        Random r = new Random(1337);
        for (int v = 0; v < VARIANTS; v++) {
            // Несколько «бугров» — облако неровное.
            float[][] blobs = new float[6][];
            for (int b = 0; b < blobs.length; b++) {
                blobs[b] = new float[]{0.5F + (r.nextFloat() - 0.5F) * 0.4F, 0.5F + (r.nextFloat() - 0.5F) * 0.4F, 0.18F + r.nextFloat() * 0.16F};
            }
            for (int y = 0; y < TEX; y++) {
                for (int x = 0; x < TEX; x++) {
                    float fx = (x + 0.5F) / TEX, fy = (y + 0.5F) / TEX;
                    float d = 0;
                    for (float[] b : blobs) {
                        float dx = fx - b[0], dy = fy - b[1];
                        d = Math.max(d, 1.0F - (float) Math.sqrt(dx * dx + dy * dy) / b[2]);
                    }
                    float edge = 1.0F - Math.min(1.0F, (float) Math.hypot(fx - 0.5F, fy - 0.5F) * 2.0F);
                    float noise = 0.75F + 0.25F * valueNoise(x * 0.35F + v * 17, y * 0.35F, 99 + v);
                    float a = Mth.clamp(d * 1.6F, 0, 1) * Mth.clamp(edge * 2.5F, 0, 1) * noise;
                    a = a * a * (3 - 2 * a);
                    // Светлее сверху, темнее снизу — объём.
                    int lum = Math.round(255 * (0.82F + 0.18F * (1.0F - fy)) * (0.9F + 0.1F * noise));
                    lum = Mth.clamp(lum, 0, 255);
                    img.setPixel(v * TEX + x, y, (Math.round(a * 255) << 24) | (lum << 16) | (lum << 8) | lum);
                }
            }
        }
        smokeTexture = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/mech_smoke");
        Minecraft.getInstance().getTextureManager().register(smokeTexture, new DynamicTexture(() -> "sandevistan mech smoke", img));
        return smokeTexture;
    }

    private static float valueNoise(float x, float y, int seed) {
        int xi = Mth.floor(x), yi = Mth.floor(y);
        float tx = x - xi, ty = y - yi;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        float a = hash(xi, yi, seed), b = hash(xi + 1, yi, seed), c = hash(xi, yi + 1, seed), d = hash(xi + 1, yi + 1, seed);
        return Mth.lerp(ty, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 982451653;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
    }

    /** Всё — на отрисовку (координаты относительно камеры). */
    public static void submit(SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = camera.pos;
        submitMarker(collector, cam, partial);
        submitStreaks(collector, cam, partial);
        submitFootprints(collector, cam);
        submitMeteorZone(collector, cam, partial);
        submitMeteorExtras(collector, cam, partial);
        submitVortex(collector, cam, partial);
        submitChunks(collector, level, cam, partial);
        MechWreck.submit(collector, level, cam, partial);
        submitLevGlow(collector, cam, partial);
        submitPuffs(collector, level, camera, partial);
    }

    private static void submitChunks(SubmitNodeCollector collector, ClientLevel level, Vec3 cam, float partial) {
        if (CHUNKS.isEmpty()) return;
        // Кубики — по атласу (обычно один — блоков).
        java.util.Map<Identifier, List<Chunk>> byAtlas = new java.util.HashMap<>();
        for (Chunk c : CHUNKS) byAtlas.computeIfAbsent(c.texture, k -> new ArrayList<>()).add(c);
        for (var e : byAtlas.entrySet()) {
            RenderType type = RenderTypes.entityCutout(e.getKey());
            List<Chunk> list = e.getValue();
            collector.submitCustomGeometry(new PoseStack(), type, (pose, buffer) -> {
                for (Chunk c : list) cube(pose, buffer, level, c, cam, partial);
            });
        }
    }

    private static final float[][][] FACES = {
            // нормаль, затем 4 угла (знаки по x, y, z)
            {{0, 1, 0}, {-1, 1, -1}, {-1, 1, 1}, {1, 1, 1}, {1, 1, -1}},
            {{0, -1, 0}, {-1, -1, 1}, {-1, -1, -1}, {1, -1, -1}, {1, -1, 1}},
            {{0, 0, -1}, {1, 1, -1}, {1, -1, -1}, {-1, -1, -1}, {-1, 1, -1}},
            {{0, 0, 1}, {-1, 1, 1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}},
            {{-1, 0, 0}, {-1, 1, -1}, {-1, -1, -1}, {-1, -1, 1}, {-1, 1, 1}},
            {{1, 0, 0}, {1, 1, 1}, {1, -1, 1}, {1, -1, -1}, {1, 1, -1}},
    };

    private static void cube(PoseStack.Pose base, VertexConsumer buffer, ClientLevel level, Chunk c, Vec3 cam, float partial) {
        float age = Mth.lerp(partial, c.prevAge, c.age);
        float shrink = Mth.clamp((c.life - age) / 15.0F, 0, 1); // в конце жизни уменьшается и исчезает
        if (shrink <= 0) return;
        Vec3 p = c.prevPos.lerp(c.pos, partial);
        float half = c.size / 2 * shrink;
        Quaternionf q = new Quaternionf().rotateAxis((float) Math.toRadians(Mth.lerp(partial, c.prevAngle, c.angle)), c.axis);
        BlockPos lp = BlockPos.containing(p);
        int light = c.glow ? FULL_BRIGHT
                : LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lp), level.getBrightness(LightLayer.SKY, lp));
        float[][] uv = {{c.u0, c.v0}, {c.u0, c.v1}, {c.u1, c.v1}, {c.u1, c.v0}};
        Vector3f v = new Vector3f();
        for (float[][] f : FACES) {
            Vector3f n = new Vector3f(f[0][0], f[0][1], f[0][2]).rotate(q);
            for (int k = 0; k < 4; k++) {
                v.set(f[k + 1][0] * half, f[k + 1][1] * half, f[k + 1][2] * half).rotate(q);
                buffer.addVertex(base, (float) (p.x - cam.x) + v.x, (float) (p.y - cam.y) + v.y, (float) (p.z - cam.z) + v.z)
                        .setColor(c.tint).setUv(uv[k][0], uv[k][1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(base, n.x, n.y, n.z);
            }
        }
    }

    private static void submitPuffs(SubmitNodeCollector collector, ClientLevel level, CameraRenderState camera, float partial) {
        if (PUFFS.isEmpty()) return;
        Vec3 cam = camera.pos;
        // Полупрозрачные — от дальних к ближним.
        List<Puff> sorted = new ArrayList<>(PUFFS);
        sorted.sort(Comparator.comparingDouble((Puff p) -> -p.pos.distanceToSqr(cam)));
        Quaternionf face = new Quaternionf(camera.orientation);
        collector.submitCustomGeometry(new PoseStack(), RenderTypes.entityTranslucent(smoke()), (pose, buffer) -> {
            Vector3f v = new Vector3f();
            for (Puff p : sorted) {
                float age = Mth.lerp(partial, p.prevAge, p.age);
                float t = Mth.clamp(age / p.life, 0, 1);
                float fadeIn = Mth.clamp(age / 3.0F, 0, 1);
                float fadeOut = t < 0.35F ? 1.0F : 1.0F - (t - 0.35F) / 0.65F;
                float alpha = p.maxAlpha * fadeIn * fadeOut * fadeOut;
                if (alpha <= 0.01F) continue;
                Vec3 pos = p.prevPos.lerp(p.pos, partial);
                // Клуб рядом с камерой становится прозрачнее (не закрывает весь экран).
                double dist = pos.distanceTo(cam);
                alpha *= (float) Mth.clamp((dist - 0.6) / 2.0, 0.0, 1.0);
                if (alpha <= 0.01F) continue;
                float size = Mth.lerp(partial, p.prevSize, p.size) / 2;
                BlockPos lp = BlockPos.containing(pos);
                int light = p.glow ? FULL_BRIGHT
                        : LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lp), level.getBrightness(LightLayer.SKY, lp));
                int color = HoloUi.argb(alpha, p.rgb);
                Quaternionf q = new Quaternionf(face).rotateZ((float) Math.toRadians(p.roll));
                float u0 = (float) p.variant / VARIANTS, u1 = (float) (p.variant + 1) / VARIANTS;
                float[][] corners = {{-1, -1, u0, 1}, {-1, 1, u0, 0}, {1, 1, u1, 0}, {1, -1, u1, 1}};
                Vector3f n = new Vector3f(0, 0, 1).rotate(q);
                for (float[] k : corners) {
                    v.set(k[0] * size, k[1] * size, 0).rotate(q);
                    buffer.addVertex(pose, (float) (pos.x - cam.x) + v.x, (float) (pos.y - cam.y) + v.y, (float) (pos.z - cam.z) + v.z)
                            .setColor(color).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                            .setNormal(pose, n.x, n.y, n.z);
                }
            }
        });
    }

    /**
     * Полосы рывка: лента, повёрнутая к камере, светится (сложение света): широкое мягкое свечение + яркая середина.
     * Сначала вспыхивает, потом сужается и тает; у точки ухода тает быстрее (хвост).
     */
    private static void submitStreaks(SubmitNodeCollector collector, Vec3 cam, float partial) {
        if (STREAKS.isEmpty()) return;
        List<float[]> quads = new ArrayList<>();
        for (Streak s : STREAKS) {
            float t = Mth.clamp(Mth.lerp(partial, s.prevAge, s.age) / STREAK_LIFE, 0, 1);
            float fade = (1 - t) * (1 - t);
            float width = STREAK_WIDTH * (1 - 0.7F * t);
            Vec3 a = s.from.add(0, STREAK_HEIGHT, 0).subtract(cam);
            Vec3 b = s.to.add(0, STREAK_HEIGHT, 0).subtract(cam);
            // Несколько слоёв: широкое слабое свечение → узкая яркая середина.
            float[][] layers = {{1.0F, 0.18F, 0}, {0.55F, 0.35F, 0}, {0.18F, 0.8F, 1}};
            for (float[] l : layers) {
                int rgb = l[2] > 0 ? STREAK_CORE : STREAK_GLOW;
                int tail = HoloUi.argb(1.0F, scale(rgb, l[1] * fade * 0.25F));
                int head = HoloUi.argb(1.0F, scale(rgb, l[1] * fade));
                ribbon(quads, a, b, width * l[0] / 2, tail, head);
            }
        }
        collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
            for (float[] q : quads) {
                int c0 = Float.floatToRawIntBits(q[12]), c1 = Float.floatToRawIntBits(q[13]);
                int[] colors = {c0, c0, c1, c1};
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) com.smi7i.sandevistan.client.GlowRender.vertex(buffer, pose, q[k * 3], q[k * 3 + 1], q[k * 3 + 2], colors[k]);
            }
        });
    }

    /** Лента от a до b шириной 2*half, повёрнутая к камере (камера в начале координат). */
    private static void ribbon(List<float[]> out, Vec3 a, Vec3 b, float half, int colorA, int colorB) {
        Vec3 dir = b.subtract(a);
        Vec3 mid = a.add(b).scale(0.5);
        Vec3 side = dir.cross(mid);
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(half);
        out.add(new float[]{
                (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z),
                (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z),
                (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z),
                (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z),
                Float.intBitsToFloat(colorA), Float.intBitsToFloat(colorB)});
    }

    // ===== «Вихрь» =====
    private static final int ZONE_RED = 0xFF2030, VORTEX_BLUE = 0x3FC8FF, VORTEX_WHITE = 0xD8F6FF;

    /** Игроки с активным вихрем: id → {время начала, высота земли, последний обработанный тик}. */
    private static final java.util.Map<Integer, double[]> VORTEX_SEEN = new java.util.HashMap<>();

    /** Молния: ломаная линия, живёт несколько тиков, мерцает. */
    private static final class Bolt {
        Vec3[] pts;
        float age, life;
    }

    private static final List<Bolt> BOLTS = new ArrayList<>();

    /** Ударная волна: расширяющееся светящееся кольцо по земле (ground) или пузырь к камере. */
    private static final class Wave {
        Vec3 c;
        float age, prevAge, life, maxR, width;
        int rgb;
        boolean ground;
        /** Направленная волна: угол направления (радианы, x=cos, z=sin) и полуширина дуги (0 — полный круг). */
        float dirAngle, arc;
    }

    /** Светящаяся трещина на земле: ломаная, «прорастает» от центра, потом гаснет. */
    private static final class Crack {
        Vec3[] pts;
        float age, prevAge, life;
    }

    private static final List<Wave> WAVES = new ArrayList<>();
    private static final List<Crack> CRACKS = new ArrayList<>();

    /**
     * Ударная волна от удара: светлое кольцо бежит по земле, за ним второе, пузырь воздуха, кольцо пыли
     * у самой земли и толчок камеры (сильнее вблизи).
     */
    private static void shock(Minecraft mc, Vec3 at, float radius, float strength, int dust, int rgb) {
        Vec3 g = at.add(0, 0.08, 0);
        wave(g, radius, 12, 0.9F * strength, scale(rgb, 0.55F), true);
        wave(g, radius * 0.6F, 16, 0.5F * strength, scale(rgb, 0.35F), true);
        wave(at.add(0, 0.9, 0), radius * 0.55F, 9, 0.35F * strength, scale(rgb, 0.45F), false);
        ring(at, dust, (int) (10 + radius * 3), 0.25 + radius * 0.04, 0.4 + radius * 0.05, 0.7F, 1.2F, 18, 34);
        if (mc.player != null) {
            double dist = mc.player.position().distanceTo(at);
            float far = 10 + radius * 3;
            if (dist < far) {
                float k = 1.0F - (float) (dist / far);
                MechShake.kick(1.6F * strength * k * k);
            }
        }
    }

    /** Направленный удар: три дуги-волны уходят вперёд веером (+ пыль по направлению). */
    private static void arcWaves(Vec3 at, Vec3 fwd, float reach, int rgb, float strength) {
        float dir = (float) Math.atan2(fwd.z, fwd.x);
        Vec3 g = at.add(0, 0.08, 0);
        for (int i = 0; i < 3; i++) {
            Wave w = new Wave();
            w.c = g.subtract(fwd.scale(1.0));
            w.maxR = reach * (1.0F - 0.18F * i);
            w.life = 12 + 4 * i;
            w.width = (0.9F - 0.2F * i) * strength;
            w.rgb = scale(rgb, 0.6F - 0.12F * i);
            w.ground = true;
            w.dirAngle = dir;
            w.arc = 0.85F - 0.1F * i;
            WAVES.add(w);
        }
    }

    private static void wave(Vec3 c, float maxR, float life, float width, int rgb, boolean ground) {
        Wave w = new Wave();
        w.c = c;
        w.maxR = maxR;
        w.life = life;
        w.width = width;
        w.rgb = rgb;
        w.ground = ground;
        WAVES.add(w);
    }

    private static void tickVortex(Minecraft mc) {
        var level = mc.level;
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        for (var p : level.players()) {
            long start = p.getAttachedOrElse(com.smi7i.sandevistan.ModAttachments.MECH_SKILL_START, 0L);
            if (start <= 0) continue;
            long age = level.getGameTime() - start;
            if (age < 0 || age > com.smi7i.sandevistan.mech.MechVortex.END + 10) continue;
            alive.add(p.getId());
            double[] st = VORTEX_SEEN.get(p.getId());
            if (st == null || st[0] != start) {
                st = new double[]{start, p.getY(), -1};
                VORTEX_SEEN.put(p.getId(), st);
            }
            long prev = (long) st[2];
            st[2] = age;
            Vec3 ground = new Vec3(p.getX(), st[1], p.getZ());
            Vec3 center = p.position().add(0, 1.6, 0);
            BlockState gs = level.getBlockState(BlockPos.containing(ground).below());
            int dust = dustColor(level, gs, BlockPos.containing(ground).below());
            if (prev < 0 && age >= 0) {
                shoulderWaves(p, 4);
                // Зона вспыхивает красным по земле.
                for (int i = 0; i < 26; i++) {
                    double a = 2 * Math.PI * i / 26;
                    Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                    Puff f = puff(ground.add(d.scale(2 + R.nextDouble() * 6.5)).add(0, 0.15, 0), d.scale(0.05), 0.9F, 0.03F,
                            12 + R.nextInt(8), 0xFF3040, 0.55F);
                    if (f != null) f.glow = true;
                }
            }
            if (crossed(prev, age, com.smi7i.sandevistan.mech.MechVortex.ORBIT_START)) {
                // Взлёт: кольцо пыли, луч вверх, куски земли поднимаются и начинают кружить.
                ring(ground, dust, 34, 0.3, 0.5, 1.0F, 1.7F, 35, 60);
                Bolt beam = new Bolt();
                beam.pts = new Vec3[]{center, center.add(0, 40, 0)};
                beam.life = 10;
                BOLTS.add(beam);
                if (!gs.isAir()) {
                    for (int i = 0; i < 18; i++) {
                        if (CHUNKS.size() >= MAX_CHUNKS) break;
                        Chunk c = newChunk(gs, BlockPos.containing(ground).below(), 0.45F + R.nextFloat() * (i < 5 ? 0.9F : 0.45F));
                        if (c == null) break;
                        c.orbitOwner = p.getId();
                        c.orbitR = 3.5 + R.nextDouble() * 5.0;
                        c.orbitH = 0.5 + R.nextDouble() * 4.5;
                        c.orbitA = R.nextDouble() * Math.PI * 2;
                        c.orbitSpeed = 0.06 + R.nextDouble() * 0.06;
                        c.orbitGround = st[1];
                        c.pos = c.prevPos = ground.add(Math.cos(c.orbitA) * c.orbitR, 0.2, Math.sin(c.orbitA) * c.orbitR);
                        c.life = 200;
                    }
                }
            }
            if (age >= 0 && age < com.smi7i.sandevistan.mech.MechVortex.SLAM) {
                // Энергию со всей зоны «засасывает» к центру: искры летят по спирали внутрь (сначала красные, потом голубые).
                boolean charging = age < com.smi7i.sandevistan.mech.MechVortex.ORBIT_START;
                for (int i = 0; i < (charging ? 4 : 3); i++) {
                    double a = R.nextDouble() * Math.PI * 2, r = com.smi7i.sandevistan.mech.MechVortex.RADIUS - R.nextDouble();
                    Vec3 at = ground.add(Math.cos(a) * r, 0.2 + R.nextDouble() * (charging ? 0.5 : 3.5), Math.sin(a) * r);
                    Vec3 in = center.subtract(at).normalize().scale(0.38).add(new Vec3(-Math.sin(a), 0, Math.cos(a)).scale(0.18));
                    Puff s = puff(at, in, 0.22F + R.nextFloat() * 0.18F, -0.004F, 18 + R.nextInt(6),
                            charging ? 0xFF4050 : (R.nextBoolean() ? VORTEX_BLUE : 0xFFFFFF), 0.95F);
                    if (s != null) s.glow = true;
                }
            }
            if (age >= com.smi7i.sandevistan.mech.MechVortex.ORBIT_START && age < com.smi7i.sandevistan.mech.MechVortex.SLAM) {
                // Молнии потрескивают по пузырю, чаще ближе к удару.
                float build = (age - com.smi7i.sandevistan.mech.MechVortex.ORBIT_START)
                        / (float) (com.smi7i.sandevistan.mech.MechVortex.SLAM - com.smi7i.sandevistan.mech.MechVortex.ORBIT_START);
                if (R.nextFloat() < 0.25F + 0.6F * build) {
                    Vec3 d = rnd(1, 0.8, 1).normalize();
                    Bolt b = new Bolt();
                    int n = 5;
                    b.pts = new Vec3[n];
                    for (int i = 0; i < n; i++) b.pts[i] = center.add(d.scale(0.6 + 1.8 * i / (n - 1))).add(i == 0 ? Vec3.ZERO : rnd(0.25, 0.25, 0.25));
                    b.life = 3 + R.nextInt(2);
                    BOLTS.add(b);
                }
                // Голубое свечение вокруг костюма и закручивающиеся потоки воздуха.
                for (int i = 0; i < 2; i++) {
                    Puff f = puff(center.add(rnd(0.6, 0.8, 0.6)), rnd(0.03, 0.03, 0.03), 1.6F + R.nextFloat() * 1.2F, 0.06F,
                            8 + R.nextInt(6), R.nextBoolean() ? VORTEX_BLUE : VORTEX_WHITE, 0.35F);
                    if (f != null) f.glow = true;
                }
                double a = R.nextDouble() * Math.PI * 2, r = 2.5 + R.nextDouble() * 4;
                Vec3 at = ground.add(Math.cos(a) * r, 0.3 + R.nextDouble() * 4, Math.sin(a) * r);
                Vec3 tangent = new Vec3(-Math.sin(a), 0.04, Math.cos(a)).scale(0.35).add(Math.cos(a) * -0.08, 0, Math.sin(a) * -0.08);
                Puff w = puff(at, tangent, 0.5F + R.nextFloat() * 0.5F, 0.02F, 14 + R.nextInt(10), VORTEX_WHITE, 0.45F);
                if (w != null) w.glow = true;
                if (age % 2 == 0) puff(ground.add(Math.cos(a) * r, 0.2, Math.sin(a) * r), tangent.scale(0.6), 0.9F, 0.04F,
                        25, shade(dust, 0.15F), 0.6F);
            }
            if (crossed(prev, age, com.smi7i.sandevistan.mech.MechVortex.SLAM)) {
                slamFx(mc, p, ground, center, gs, dust);
                shoulderWaves(p, 2);
            }
        }
        VORTEX_SEEN.keySet().retainAll(alive);
        for (Iterator<Wave> it = WAVES.iterator(); it.hasNext(); ) {
            Wave w = it.next();
            w.prevAge = w.age;
            if (++w.age >= w.life) it.remove();
        }
        for (Iterator<Crack> it = CRACKS.iterator(); it.hasNext(); ) {
            Crack c = it.next();
            c.prevAge = c.age;
            if (++c.age >= c.life) it.remove();
        }
        for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            b.age++;
            if (b.age >= b.life) it.remove();
        }
    }

    private static boolean crossed(long prev, long now, long mark) {
        return prev < mark && mark <= now;
    }

    /** Удар вихря: голубая вспышка, молнии, взрыв света и пыли, куски разлетаются, тряска. */
    private static void slamFx(Minecraft mc, Entity p, Vec3 ground, Vec3 center, BlockState gs, int dust) {
        for (int i = 0; i < 40; i++) {
            Vec3 d = rnd(1, 0.6, 1).normalize();
            Puff f = puff(center.add(d.scale(R.nextDouble())), d.scale(0.2 + R.nextDouble() * 0.35), 1.6F + R.nextFloat() * 1.8F,
                    0.1F, 12 + R.nextInt(12), R.nextInt(3) == 0 ? 0xFFFFFF : VORTEX_BLUE, 0.9F);
            if (f != null) f.glow = true;
        }
        for (int i = 0; i < 30; i++) {
            puff(ground.add(rnd(2.5, 0.3, 2.5)).add(0, 0.6, 0), rnd(0.2, 0, 0.2).add(0, 0.05 + R.nextDouble() * 0.15, 0),
                    1.6F + R.nextFloat() * 1.6F, 0.07F, 50 + R.nextInt(50), shade(0x506A86, 0.25F), 0.85F);
        }
        ring(ground, dust, 60, 0.5, 0.85, 1.3F, 2.3F, 50, 90);
        fakeChunks(mc.level, ground.add(0, 0.3, 0), gs, 24, 0.25, 0.6, 0.4, 0.9, null);
        for (int i = 0; i < 12; i++) BOLTS.add(bolt(center, 3 + R.nextDouble() * 7, 8 + R.nextInt(8)));
        // Столб света в небо.
        Bolt pillar = new Bolt();
        pillar.pts = new Vec3[]{ground, ground.add(0, 60, 0)};
        pillar.life = 16;
        BOLTS.add(pillar);
        // Ударные волны: две по земле (быстрая и широкая медленная) и разлетающийся пузырь.
        Vec3 g = ground.add(0, 0.06, 0);
        wave(g, 26, 16, 1.3F, VORTEX_WHITE, true);
        wave(g, 16, 26, 2.4F, VORTEX_BLUE, true);
        wave(g, 34, 30, 0.8F, 0x8070FF, true);
        wave(center, 12, 9, 0.9F, VORTEX_WHITE, false);
        wave(center, 20, 14, 0.5F, VORTEX_BLUE, false);
        // Лучи света из точки удара.
        for (int i = 0; i < 16; i++) {
            Vec3 d = rnd(1, 0.5, 1).normalize();
            Bolt ray = new Bolt();
            ray.pts = new Vec3[]{center, center.add(d.scale(12 + R.nextDouble() * 12))};
            ray.life = 6 + R.nextInt(4);
            BOLTS.add(ray);
        }
        // Светящиеся трещины по земле во все стороны.
        for (int i = 0; i < 12; i++) {
            Crack c = new Crack();
            double a = 2 * Math.PI * i / 12 + R.nextDouble() * 0.4;
            int n = 9;
            c.pts = new Vec3[n];
            double len = 5 + R.nextDouble() * 6;
            for (int k = 0; k < n; k++) {
                double r = len * k / (n - 1);
                double aa = a + (k == 0 ? 0 : (R.nextDouble() - 0.5) * 0.35);
                c.pts[k] = g.add(Math.cos(aa) * r, 0.01, Math.sin(aa) * r);
            }
            c.life = 50 + R.nextInt(30);
            CRACKS.add(c);
        }
        // Тлеющие искры медленно поднимаются ещё пару секунд.
        for (int i = 0; i < 50; i++) {
            Vec3 at = ground.add(rnd(5, 0, 5)).add(0, 0.3 + R.nextDouble() * 1.5, 0);
            Puff e = puff(at, rnd(0.02, 0, 0.02).add(0, 0.03 + R.nextDouble() * 0.05, 0), 0.18F + R.nextFloat() * 0.2F, -0.002F,
                    40 + R.nextInt(40), R.nextInt(3) == 0 ? 0xFFFFFF : VORTEX_BLUE, 0.9F);
            if (e != null) e.glow = true;
        }
        if (mc.player != null && mc.player.distanceTo(p) < 40) MechShake.rumble(mc.player == p ? 1.6F : 1.1F, 2.2F);
        if (mc.player != null && mc.player.distanceTo(p) < 28) {
            MechLifeHud.colorFlash(0x7FE6FF, 650);
            MechShake.kick(mc.player == p ? 3.0F : 2.2F);
        }
    }

    /** Ломаная молния от центра в случайную сторону. */
    private static Bolt bolt(Vec3 from, double len, int life) {
        Bolt b = new Bolt();
        Vec3 dir = rnd(1, 0.7, 1).normalize();
        int n = 7;
        b.pts = new Vec3[n];
        b.pts[0] = from;
        for (int i = 1; i < n; i++) {
            b.pts[i] = from.add(dir.scale(len * i / (n - 1))).add(rnd(0.5, 0.5, 0.5));
        }
        b.life = life;
        return b;
    }

    /** Кусок на орбите: кружит вокруг владельца; после удара — резко вниз (обычная физика). */
    private static void orbitChunk(Minecraft mc, Chunk c, float dt) {
        Entity owner = mc.level.getEntity(c.orbitOwner);
        long start = owner instanceof net.minecraft.world.entity.player.Player pl
                ? pl.getAttachedOrElse(com.smi7i.sandevistan.ModAttachments.MECH_SKILL_START, 0L) : 0;
        long age = start > 0 ? mc.level.getGameTime() - start : Long.MAX_VALUE;
        c.prevPos = c.pos;
        c.prevAngle = c.angle;
        if (owner == null || age >= com.smi7i.sandevistan.mech.MechVortex.SLAM) {
            // Удар — все куски вниз.
            c.orbitOwner = -1;
            c.vel = new Vec3((R.nextDouble() - 0.5) * 0.3, -1.4, (R.nextDouble() - 0.5) * 0.3);
            c.life = c.age + 60 + R.nextInt(40);
            return;
        }
        float k = Mth.clamp((age - com.smi7i.sandevistan.mech.MechVortex.ORBIT_START) / 20.0F, 0, 1); // плавно поднимаются
        c.orbitA += c.orbitSpeed * (1 + 1.5 * (age - com.smi7i.sandevistan.mech.MechVortex.ORBIT_START) / 60.0) * dt;
        double y = c.orbitGround + 0.2 + c.orbitH * k + 0.3 * Math.sin(age * 0.2 + c.orbitA);
        c.pos = new Vec3(owner.getX() + Math.cos(c.orbitA) * c.orbitR, y, owner.getZ() + Math.sin(c.orbitA) * c.orbitR);
        c.angle += c.spin * 0.5F * dt;
    }

    // Левитация: подъём 12 тиков, зависание (покачивание), синеет; потом резкое падение и удар — разбивается.
    private static final float LEV_RISE = 12, LEV_HOVER = 24, LEV_GRAVITY = 0.12F;
    private static final int LEV_BLUE = 0x5FA8FF;

    /** true — кусок разбился (убрать). */
    private static boolean tickLevitate(Minecraft mc, Chunk c, float dt) {
        c.prevPos = c.pos;
        c.prevAngle = c.angle;
        float a = c.age;
        c.blue = (float) Mth.clamp((a - 8) / 14.0, 0, 1);
        int col = lerpColor(c.blue, 0xFFFFFF, LEV_BLUE);
        c.tint = 0xFF000000 | col;
        c.glow = c.blue > 0.3F;
        if (a < LEV_RISE) {
            double k = a / LEV_RISE;
            k = 1 - (1 - k) * (1 - k);
            c.pos = c.levBase.add(0, c.levH * k, 0);
            c.angle += c.spin * dt;
            return false;
        }
        if (a < LEV_RISE + LEV_HOVER + c.levDelay) {
            c.pos = c.levBase.add(0, c.levH + 0.12 * Math.sin(a * 0.35 + c.levH * 3), 0);
            c.angle += c.spin * 0.5F * dt;
            return false;
        }
        // Падение — резко, с ускорением, почти без вращения.
        if (c.vel.y > -0.6) c.vel = new Vec3(0, -0.6, 0); // срывается вниз сразу
        c.vel = c.vel.add(0, -LEV_GRAVITY * 4 * dt, 0);
        c.pos = c.pos.add(c.vel.scale(dt));
        if (c.pos.y <= c.levBase.y) {
            Vec3 at = new Vec3(c.pos.x, c.levBase.y - 0.4, c.pos.z);
            fakeChunks(mc.level, at, c.levState, 7, 0.12, 0.3, 0.25, 0.55, null);
            for (int i = 0; i < 5; i++) {
                Puff f = puff(at.add(rnd(0.4, 0.1, 0.4)).add(0, 0.3, 0), rnd(0.08, 0.04, 0.08), 0.9F, 0.05F, 18 + R.nextInt(10), LEV_BLUE, 0.8F);
                if (f != null) f.glow = true;
            }
            int d = dustColor(mc.level, c.levState, BlockPos.containing(at));
            ring(at, d, 14, 0.18, 0.32, 0.8F, 1.3F, 22, 40);
            fakeChunks(mc.level, at, c.levState, 6, 0.25, 0.5, 0.45, 0.8, null);
            // Звук удара блока о землю (у каждого свой тон).
            mc.level.playLocalSound(at.x, at.y, at.z, com.smi7i.sandevistan.ModSounds.MECH_LAND,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.1F, 1.2F + R.nextFloat() * 0.35F, false);
            mc.level.playLocalSound(at.x, at.y, at.z, com.smi7i.sandevistan.ModSounds.MECH_SLAM,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.55F, 1.35F + R.nextFloat() * 0.3F, false);
            mc.level.playLocalSound(at.x, at.y, at.z, com.smi7i.sandevistan.ModSounds.BOOM_COLLISION,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.15F + R.nextFloat() * 0.3F, false);
            // Каждый синий блок бьёт как маленький удар: голубая ударная волна и сильный толчок камеры.
            shock(mc, at.add(0, 0.4, 0), 4.5F, 1.1F, d, LEV_BLUE);
            return true;
        }
        return false;
    }

    private static int lerpColor(float k, int a, int b) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** Голубое свечение вокруг левитирующих блоков (сложение света, к камере). */
    private static void submitLevGlow(SubmitNodeCollector collector, Vec3 cam, float partial) {
        List<float[]> q = new ArrayList<>();
        for (Chunk c : CHUNKS) {
            if (!c.lev || c.blue <= 0.02F) continue;
            Vec3 p = c.prevPos.lerp(c.pos, partial).subtract(cam);
            Vec3 toCam = p.scale(-1).normalize();
            Vec3 u = toCam.cross(new Vec3(0, 1, 0));
            if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
            float pulse = 0.85F + 0.15F * Mth.sin(c.age * 0.6F);
            for (float[] layer : new float[][]{{1.9F, 0.22F}, {1.25F, 0.4F}}) {
                Vec3 uu = u.normalize().scale(layer[0] / 2), vv = uu.cross(toCam).normalize().scale(layer[0] / 2);
                int col = HoloUi.argb(1, scale(LEV_BLUE, layer[1] * c.blue * pulse));
                Vec3 a = p.subtract(uu).add(vv), b = p.add(uu).add(vv), d = p.add(uu).subtract(vv), e = p.subtract(uu).subtract(vv);
                q.add(new float[]{(float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, (float) d.x, (float) d.y, (float) d.z,
                        (float) e.x, (float) e.y, (float) e.z, Float.intBitsToFloat(col), Float.intBitsToFloat(col)});
            }
        }
        if (q.isEmpty()) return;
        collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
            for (float[] f : q) {
                int c0 = Float.floatToRawIntBits(f[12]);
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) com.smi7i.sandevistan.client.GlowRender.vertex(buffer, pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2], c0);
            }
        });
    }

    /** Новый кусок блока (без добавления в список). */
    private static Chunk newChunk(BlockState state, BlockPos from, float size) {
        int before = CHUNKS.size();
        chunk(Minecraft.getInstance().level, Vec3.atCenterOf(from), Vec3.ZERO, state, from, size);
        if (CHUNKS.size() == before) return null;
        return CHUNKS.get(CHUNKS.size() - 1);
    }

    /** Отрисовка вихря: красная зона на земле, голубые кольца вокруг костюма, потоки воздуха, луч и молнии. */
    private static void submitVortex(SubmitNodeCollector collector, Vec3 cam, float partial) {
        Minecraft mc = Minecraft.getInstance();
        List<float[]> glowQuads = new ArrayList<>();
        for (var e : VORTEX_SEEN.entrySet()) {
            Entity p = mc.level.getEntity(e.getKey());
            if (p == null) continue;
            double[] st = e.getValue();
            float age = (float) (mc.level.getGameTime() - st[0]) + partial;
            Vec3 ground = new Vec3(p.getX(), st[1], p.getZ()).subtract(cam).add(0, 0.05, 0);
            float zoneIn = Math.min(1, age / 5.0F), zoneOut = 1 - Mth.clamp((age - (com.smi7i.sandevistan.mech.MechVortex.SLAM - 2)) / 6.0F, 0, 1);
            float zone = zoneIn * zoneOut;
            if (zone > 0) {
                float flash = age < 6 ? 1.6F - age / 10.0F : 1.0F;
                float R0 = (float) com.smi7i.sandevistan.mech.MechVortex.RADIUS;
                float spin = age * 0.02F;
                collector.submitCustomGeometry(new PoseStack(), RenderTypes.textBackground(), (pose, buffer) -> {
                    disk(pose, buffer, ground, 0, R0, HoloUi.argb(Math.min(1, 0.2F * zone * flash), ZONE_RED));
                    ringQuad(pose, buffer, ground.add(0, 0.01, 0), R0 - 0.25F, R0, HoloUi.argb(0.9F * zone, ZONE_RED));
                    ringQuad(pose, buffer, ground.add(0, 0.01, 0), 2.6F, 2.8F, HoloUi.argb(0.6F * zone, ZONE_RED));
                    // Шевроны по кругу (как плашки в Roblox), медленно вращаются.
                    for (int i = 0; i < 14; i++) {
                        double a = spin + 2 * Math.PI * i / 14;
                        float c = (float) Math.cos(a), s = (float) Math.sin(a), tc = (float) -s, ts = c;
                        float r1 = R0 - 2.6F, r2 = R0 - 0.8F, wd = 0.7F;
                        quad(pose, buffer, ground.add(0, 0.015, 0), c * r1 - tc * wd, s * r1 - ts * wd, c * r2 - tc * wd * 0.6F,
                                s * r2 - ts * wd * 0.6F, c * r2 + tc * wd * 0.6F, s * r2 + ts * wd * 0.6F, c * r1 + tc * wd, s * r1 + ts * wd,
                                HoloUi.argb(0.75F * zone, 0xFF4050), true);
                    }
                });
                int glowCol = HoloUi.argb(1.0F, scale(ZONE_RED, 0.35F * zone));
                collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) ->
                        ringQuadNoLight(pose, buffer, ground.add(0, 0.02, 0), R0 - 0.7F, R0 + 0.4F, glowCol));
            }
            // Голубые кольца и потоки — пока парит.
            float orbit = Mth.clamp((age - com.smi7i.sandevistan.mech.MechVortex.ORBIT_START) / 6.0F, 0, 1)
                    * (1 - Mth.clamp((age - com.smi7i.sandevistan.mech.MechVortex.SLAM) / 4.0F, 0, 1));
            if (orbit > 0) {
                Vec3 c = p.getPosition(partial).add(0, 1.6, 0).subtract(cam);
                // Кольцо вокруг костюма к камере (пузырь) — дрожит по толщине.
                float rr = 2.3F + 0.1F * Mth.sin(age * 1.3F);
                billboardRing(glowQuads, c, rr, 0.35F, scale(VORTEX_BLUE, 0.55F * orbit));
                billboardRing(glowQuads, c, rr - 0.12F, 0.1F, scale(VORTEX_WHITE, 0.8F * orbit));
                // Закрученные потоки воздуха: дуги на разной высоте, вращаются.
                for (int k = 0; k < 4; k++) {
                    double a0 = age * (0.18 + 0.04 * k) + k * 1.7;
                    double rad = 2.6 + 0.5 * k, h = -1.0 + 0.9 * k;
                    Vec3 prevPt = null;
                    for (int j = 0; j <= 10; j++) {
                        double a = a0 + j * 0.24;
                        Vec3 pt = c.add(Math.cos(a) * rad, h + 0.25 * Math.sin(a * 2), Math.sin(a) * rad);
                        if (prevPt != null) {
                            float fade = j / 10.0F;
                            ribbon(glowQuads, prevPt, pt, 0.07F + 0.06F * fade,
                                    HoloUi.argb(1, scale(VORTEX_WHITE, 0.6F * orbit * (fade - 0.1F))), HoloUi.argb(1, scale(VORTEX_WHITE, 0.6F * orbit * fade)));
                        }
                        prevPt = pt;
                    }
                }
            }
        }
        // Луч и молнии.
        for (Bolt b : BOLTS) {
            float k = 1 - b.age / b.life;
            if (R.nextFloat() < 0.25F) continue; // мерцают
            for (int i = 1; i < b.pts.length; i++) {
                Vec3 a = b.pts[i - 1].subtract(cam), bb = b.pts[i].subtract(cam);
                float wdt = b.pts.length == 2 ? 0.5F * k : 0.12F * k;
                ribbon(glowQuads, a, bb, wdt * 2.5F, HoloUi.argb(1, scale(VORTEX_BLUE, 0.5F * k)), HoloUi.argb(1, scale(VORTEX_BLUE, 0.5F * k)));
                ribbon(glowQuads, a, bb, wdt, HoloUi.argb(1, scale(0xFFFFFF, k)), HoloUi.argb(1, scale(0xFFFFFF, k)));
            }
        }
        // Пузырь-волна к камере.
        for (Wave w : WAVES) {
            if (w.ground) continue;
            float t = Mth.clamp(Mth.lerp(partial, w.prevAge, w.age) / w.life, 0, 1);
            float r = w.maxR * (1 - (1 - t) * (1 - t));
            billboardRing(glowQuads, w.c.subtract(cam), r, w.width * (1 - t * 0.6F), scale(w.rgb, (1 - t) * 0.9F));
        }
        // Трещины по земле: прорастают от центра, светятся и гаснут.
        for (Crack c : CRACKS) {
            float t = Mth.clamp(Mth.lerp(partial, c.prevAge, c.age) / c.life, 0, 1);
            float grow = Mth.clamp(Mth.lerp(partial, c.prevAge, c.age) / 5.0F, 0, 1);
            float glow = (1 - t) * (1 - t) * (0.85F + 0.15F * Mth.sin(c.age * 1.7F));
            int shown = Math.max(1, Math.round((c.pts.length - 1) * grow));
            for (int i = 1; i <= shown; i++) {
                Vec3 a = c.pts[i - 1].subtract(cam), b = c.pts[i].subtract(cam);
                float wdt = 0.16F * (1 - i / (float) c.pts.length) + 0.04F;
                flatRibbon(glowQuads, a, b, wdt * 2.4F, scale(VORTEX_BLUE, 0.45F * glow));
                flatRibbon(glowQuads, a, b, wdt, scale(VORTEX_WHITE, glow));
            }
        }
        if (!WAVES.isEmpty()) {
            List<Wave> groundWaves = new ArrayList<>();
            for (Wave w : WAVES) if (w.ground) groundWaves.add(w);
            if (!groundWaves.isEmpty()) {
                collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
                    for (Wave w : groundWaves) {
                        float t = Mth.clamp(Mth.lerp(partial, w.prevAge, w.age) / w.life, 0, 1);
                        float r = w.maxR * (1 - (1 - t) * (1 - t));
                        float wd = w.width * (1 - t * 0.5F);
                        int col = HoloUi.argb(1, scale(w.rgb, (1 - t) * 0.9F));
                        if (w.arc > 0) arcQuadNoLight(pose, buffer, w.c.subtract(cam), Math.max(0, r - wd), r, w.dirAngle, w.arc, col);
                        else ringQuadNoLight(pose, buffer, w.c.subtract(cam), Math.max(0, r - wd), r, col);
                    }
                });
            }
        }
        submitShoulders(glowQuads, cam, partial);
        if (glowQuads.isEmpty()) return;
        collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
            for (float[] q : glowQuads) {
                int c0 = Float.floatToRawIntBits(q[12]), c1 = Float.floatToRawIntBits(q[13]);
                int[] colors = {c0, c0, c1, c1};
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) com.smi7i.sandevistan.client.GlowRender.vertex(buffer, pose, q[k * 3], q[k * 3 + 1], q[k * 3 + 2], colors[k]);
            }
        });
    }

    /** Плоская лента по земле от a до b (ширина поперёк в горизонтальной плоскости). */
    private static void flatRibbon(List<float[]> out, Vec3 a, Vec3 b, float width, int rgb) {
        Vec3 d = b.subtract(a);
        Vec3 side = new Vec3(-d.z, 0, d.x);
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width / 2);
        int col = HoloUi.argb(1, rgb);
        out.add(new float[]{
                (float) (a.x - side.x), (float) a.y, (float) (a.z - side.z),
                (float) (a.x + side.x), (float) a.y, (float) (a.z + side.z),
                (float) (b.x + side.x), (float) b.y, (float) (b.z + side.z),
                (float) (b.x - side.x), (float) b.y, (float) (b.z - side.z),
                Float.intBitsToFloat(col), Float.intBitsToFloat(col)});
    }

    /** Кольцо, повёрнутое к камере (камера в начале координат). */
    private static void billboardRing(List<float[]> out, Vec3 c, float radius, float width, int rgb) {
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(toCam).normalize();
        int col = HoloUi.argb(1, rgb);
        int seg = 40;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            Vec3 d0 = u.scale(Math.cos(a0)).add(v.scale(Math.sin(a0))), d1 = u.scale(Math.cos(a1)).add(v.scale(Math.sin(a1)));
            Vec3 i0 = c.add(d0.scale(radius - width / 2)), o0 = c.add(d0.scale(radius + width / 2));
            Vec3 i1 = c.add(d1.scale(radius - width / 2)), o1 = c.add(d1.scale(radius + width / 2));
            out.add(new float[]{(float) i0.x, (float) i0.y, (float) i0.z, (float) o0.x, (float) o0.y, (float) o0.z,
                    (float) o1.x, (float) o1.y, (float) o1.z, (float) i1.x, (float) i1.y, (float) i1.z,
                    Float.intBitsToFloat(col), Float.intBitsToFloat(col)});
        }
    }

    /** Падающий сейчас костюм (для красного круга), если есть рядом. */
    private static MechSuitEntity fallingSuit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof MechSuitEntity suit && suit.getSpawnTime() > 0
                    && mc.level.getGameTime() - suit.getSpawnTime() < MechSuitEntity.LAND_TICK + 4) {
                return suit;
            }
        }
        return null;
    }

    /**
     * Красный круг на земле, куда упадёт костюм: постоянное кольцо + второе, сходящееся к нему к моменту удара,
     * крест в середине; всё пульсирует и светится.
     */
    private static void submitMarker(SubmitNodeCollector collector, Vec3 cam, float partial) {
        Minecraft mc = Minecraft.getInstance();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof MechSuitEntity suit) || suit.getSpawnTime() <= 0) continue;
            float age = mc.level.getGameTime() - suit.getSpawnTime() + partial;
            if (age >= MechSuitEntity.LAND_TICK + 4) continue;
            float t = Mth.clamp(age / MechSuitEntity.LAND_TICK, 0, 1);
            float fade = age > MechSuitEntity.LAND_TICK ? 1.0F - (age - MechSuitEntity.LAND_TICK) / 4.0F : Math.min(1.0F, age / 4.0F);
            float pulse = 0.7F + 0.3F * Mth.sin(age * 0.9F);
            Vec3 c = suit.position().subtract(cam).add(0, 0.04, 0);
            float r = 1.5F;
            float closing = r + (4.5F - r) * (1 - t) * (1 - t); // сходится к кругу к моменту удара
            int solid = HoloUi.argb(0.85F * fade * pulse, MARK_RED);
            int soft = HoloUi.argb(0.22F * fade, MARK_RED);
            int glow = HoloUi.argb(1.0F, scale(MARK_RED, 0.35F * fade * pulse));
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.textBackground(), (pose, buffer) -> {
                disk(pose, buffer, c, 0, r, soft);
                ringQuad(pose, buffer, c, r - 0.09F, r, solid);
                ringQuad(pose, buffer, c, closing - 0.06F, closing, HoloUi.argb(0.7F * fade, MARK_RED));
                // Крест в середине.
                rect(pose, buffer, c, -0.5F, -0.04F, 0.5F, 0.04F, solid);
                rect(pose, buffer, c, -0.04F, -0.5F, 0.04F, 0.5F, solid);
            });
            collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) ->
                    ringQuadNoLight(pose, buffer, c, r - 0.3F, r + 0.25F, glow));
        }
    }

    private static final int SEGMENTS = 48;
    private static final int FULL_BRIGHT = 15728880;

    private static void ringQuad(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float r0, float r1, int color) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / SEGMENTS, a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
            quad(pose, b, c, (float) Math.cos(a0) * r0, (float) Math.sin(a0) * r0, (float) Math.cos(a0) * r1, (float) Math.sin(a0) * r1,
                    (float) Math.cos(a1) * r1, (float) Math.sin(a1) * r1, (float) Math.cos(a1) * r0, (float) Math.sin(a1) * r0, color, true);
        }
    }

    private static void ringQuadNoLight(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float r0, float r1, int color) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / SEGMENTS, a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
            quad(pose, b, c, (float) Math.cos(a0) * r0, (float) Math.sin(a0) * r0, (float) Math.cos(a0) * r1, (float) Math.sin(a0) * r1,
                    (float) Math.cos(a1) * r1, (float) Math.sin(a1) * r1, (float) Math.cos(a1) * r0, (float) Math.sin(a1) * r0, color, false);
        }
    }

    /** Дуга кольца на земле: по направлению dir (радианы) ± half. */
    private static void arcQuadNoLight(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float r0, float r1, float dir, float half, int color) {
        int seg = 16;
        for (int i = 0; i < seg; i++) {
            double a0 = dir - half + 2 * half * i / seg, a1 = dir - half + 2 * half * (i + 1) / seg;
            // Края дуги тают.
            float edge = (float) Math.min(1, Math.min(i + 1, seg - i) / 3.0);
            int col = (color & 0xFF000000) | (scale(color & 0xFFFFFF, edge) & 0xFFFFFF);
            quad(pose, b, c, (float) Math.cos(a0) * r0, (float) Math.sin(a0) * r0, (float) Math.cos(a0) * r1, (float) Math.sin(a0) * r1,
                    (float) Math.cos(a1) * r1, (float) Math.sin(a1) * r1, (float) Math.cos(a1) * r0, (float) Math.sin(a1) * r0, col, false);
        }
    }

    private static void disk(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float r0, float r1, int color) {
        ringQuad(pose, b, c, r0, r1, color);
    }

    private static void rect(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float x0, float z0, float x1, float z1, int color) {
        quad(pose, b, c, x0, z0, x0, z1, x1, z1, x1, z0, color, true);
    }

    /** Плоский четырёхугольник на земле (с обеих сторон). */
    private static void quad(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float x0, float z0, float x1, float z1,
                             float x2, float z2, float x3, float z3, int color, boolean light) {
        float[][] v = {{x0, z0}, {x1, z1}, {x2, z2}, {x3, z3}};
        int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
        for (int k : order) {
            if (!light) {
                com.smi7i.sandevistan.client.GlowRender.vertex(b, pose, (float) c.x + v[k][0], (float) c.y, (float) c.z + v[k][1], color); // светящееся
                continue;
            }
            b.addVertex(pose, (float) c.x + v[k][0], (float) c.y, (float) c.z + v[k][1]).setColor(color).setLight(FULL_BRIGHT);
        }
    }

    // ===== «Метеор» =====
    /** Игроки с «метеором»: id → {начало, прошлый возраст, прошлое «с появления», высота земли, x и z зоны, рыскание}. */
    private static final java.util.Map<Integer, double[]> METEOR_SEEN = new java.util.HashMap<>();

    private static void tickMeteor(Minecraft mc) {
        var level = mc.level;
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        long gt = level.getGameTime();
        int launch = com.smi7i.sandevistan.mech.MechMeteor.LAUNCH, aim = com.smi7i.sandevistan.mech.MechMeteor.AIM;
        for (var p : level.players()) {
            long s = com.smi7i.sandevistan.mech.MechMeteor.startTime(p);
            if (s <= 0) continue;
            alive.add(p.getId());
            double[] st = METEOR_SEEN.get(p.getId());
            if (st == null || st[0] != s) {
                st = new double[]{s, -1, -1, p.getY(), p.getX(), p.getZ(), p.getYRot()};
                METEOR_SEEN.put(p.getId(), st);
                shoulderWaves(p, 3);
            }
            long age = gt - s, prev = (long) st[1];
            st[1] = age;
            if (crossed(prev, age, launch)) vanishFx(mc, p, new Vec3(p.getX(), st[3], p.getZ()));
            long d = com.smi7i.sandevistan.mech.MechMeteor.dropTime(p);
            if (d <= 0) continue;
            long since = gt - d, ps = (long) st[2];
            if (ps < 0 && since >= 0) {
                st[3] = MechClient.groundUnder(level, p.getX(), p.getZ());
                st[4] = p.getX();
                st[5] = p.getZ();
                st[6] = p.getYRot();
                appearFx(mc, p);
                shoulderWaves(p, 4);
            }
            Vec3 zc = new Vec3(st[4], st[3], st[5]);
            if (since >= 0 && since < aim) {
                // По краю зоны вспыхивают красные искры и стягиваются к центру.
                double R0 = com.smi7i.sandevistan.mech.MechMeteor.RADIUS;
                for (int i = 0; i < 4; i++) {
                    double a = R.nextDouble() * Math.PI * 2;
                    Vec3 at = zc.add(Math.cos(a) * R0, 0.2 + R.nextDouble() * 0.4, Math.sin(a) * R0);
                    Puff f = puff(at, zc.subtract(at).normalize().scale(0.35).add(0, 0.03, 0), 0.25F + R.nextFloat() * 0.2F,
                            -0.004F, 16 + R.nextInt(6), R.nextInt(4) == 0 ? 0xFFFFFF : 0xFF3048, 0.95F);
                    if (f != null) f.glow = true;
                }
            }
            if (crossed(ps, since, aim)) {
                meteorSlamFx(mc, p, zc);
                shoulderWaves(p, 2);
            }
            st[2] = since;
        }
        METEOR_SEEN.keySet().retainAll(alive);
    }

    /** Прыжок и исчезновение: белое кольцо по земле, пыль, трещины, зелёный росчерк в небо — «очень быстрый». */
    private static void vanishFx(Minecraft mc, Entity p, Vec3 ground) {
        BlockState gs = mc.level.getBlockState(BlockPos.containing(ground).below());
        int dust = dustColor(mc.level, gs, BlockPos.containing(ground).below());
        Vec3 g = ground.add(0, 0.06, 0);
        wave(g, 7, 9, 0.7F, 0xC8C8C8, true);
        wave(g, 4, 14, 1.2F, 0x606060, true);
        ring(ground, dust, 40, 0.35, 0.6, 1.0F, 1.8F, 30, 55);
        fakeChunks(mc.level, ground.add(0, 0.3, 0), gs, 12, 0.2, 0.45, 0.4, 0.8, null);
        for (int i = 0; i < 6; i++) {
            Crack c = new Crack();
            double a = 2 * Math.PI * i / 6 + R.nextDouble() * 0.6;
            int n = 6;
            c.pts = new Vec3[n];
            double len = 2.5 + R.nextDouble() * 2.5;
            for (int k = 0; k < n; k++) {
                double r = len * k / (n - 1), aa = a + (k == 0 ? 0 : (R.nextDouble() - 0.5) * 0.4);
                c.pts[k] = g.add(Math.cos(aa) * r, 0.01, Math.sin(aa) * r);
            }
            c.life = 30 + R.nextInt(15);
            CRACKS.add(c);
        }
        Vec3 body = p.position().add(0, 1.4, 0);
        Streak s = new Streak();
        s.from = ground.add(0, 1.0, 0);
        s.to = body.add(0, 34, 0);
        STREAKS.add(s);
        for (int i = 0; i < 26; i++) {
            Puff f = puff(body.add(rnd(0.7, 1.2, 0.7)), rnd(0.06, 0, 0.06).add(0, 0.25 + R.nextDouble() * 0.5, 0),
                    0.4F + R.nextFloat() * 0.5F, -0.01F, 8 + R.nextInt(8), R.nextBoolean() ? 0x39FF6A : 0xE8FFF0, 0.9F);
            if (f != null) f.glow = true;
        }
        if (p == mc.player) {
            MechLifeHud.greenFlash(380);
            MechShake.kick(1.4F);
        } else if (mc.player != null && mc.player.distanceTo(p) < 20) {
            MechShake.kick(0.8F);
        }
    }

    /** Появление в воздухе: вспышка, красный пузырь, росчерк сверху. */
    private static void appearFx(Minecraft mc, Entity p) {
        Vec3 c = p.position().add(0, 1.6, 0);
        for (int i = 0; i < 28; i++) {
            Vec3 d = rnd(1, 1, 1).normalize();
            Puff f = puff(c.add(d.scale(0.4)), d.scale(0.12 + R.nextDouble() * 0.2), 0.5F + R.nextFloat() * 0.6F, -0.01F,
                    8 + R.nextInt(8), R.nextInt(3) == 0 ? 0xFFFFFF : 0xFF3048, 0.9F);
            if (f != null) f.glow = true;
        }
        wave(c, 4.5F, 8, 0.35F, 0xFF3048, false);
        Streak s = new Streak();
        s.from = c.add(0, 30, 0);
        s.to = c;
        STREAKS.add(s);
        if (p == mc.player) MechLifeHud.colorFlash(0xFF3048, 300);
    }

    /** Удар «метеора»: синие молнии и волны (как у вихря) + огромный взрыв пыли и куски земли во все стороны. */
    private static void meteorSlamFx(Minecraft mc, Entity p, Vec3 ground) {
        BlockState gs = mc.level.getBlockState(BlockPos.containing(ground).below());
        int dust = dustColor(mc.level, gs, BlockPos.containing(ground).below());
        slamFx(mc, p, ground, ground.add(0, 1.6, 0), gs, dust);
        float R0 = (float) com.smi7i.sandevistan.mech.MechMeteor.RADIUS;
        for (int i = 0; i < 60; i++) {
            double a = R.nextDouble() * Math.PI * 2, sp = 0.25 + R.nextDouble() * 0.55;
            puff(ground.add(Math.cos(a) * 0.8, 0.4 + R.nextDouble() * 1.5, Math.sin(a) * 0.8),
                    new Vec3(Math.cos(a) * sp, 0.04 + R.nextDouble() * 0.12, Math.sin(a) * sp),
                    1.8F + R.nextFloat() * 2.0F, 0.08F, 50 + R.nextInt(50), dust, 0.9F);
        }
        column(ground, dust, 22, 2.2F);
        ring(ground, dust, 70, 0.6, 1.0, 1.4F, 2.6F, 50, 90);
        fakeChunks(mc.level, ground.add(0, 0.3, 0), gs, 40, 0.35, 0.9, 0.6, 1.3, null);
        Vec3 g = ground.add(0, 0.07, 0);
        wave(g, R0 * 2.6F, 22, 2.2F, 0x7A6A58, true);
        wave(g, R0 * 1.4F, 14, 1.0F, 0xFFFFFF, true);
        wave(g, R0, 10, 0.8F, 0xFF3048, true);
        for (int i = 0; i < 6; i++) BOLTS.add(bolt(ground.add(rnd(R0 * 0.6, 0, R0 * 0.6)).add(0, 0.5, 0), 3 + R.nextDouble() * 5, 6 + R.nextInt(6)));
        scorch(ground);
    }

    /** Красная зона CAUTION под костюмом, пока он висит: заливка, кольца, полосы-предупреждения, треугольник «!». */
    private static void submitMeteorZone(SubmitNodeCollector collector, Vec3 cam, float partial) {
        Minecraft mc = Minecraft.getInstance();
        int aim = com.smi7i.sandevistan.mech.MechMeteor.AIM;
        for (var e : METEOR_SEEN.entrySet()) {
            double[] st = e.getValue();
            if (!(mc.level.getEntity(e.getKey()) instanceof net.minecraft.world.entity.player.Player p)) continue;
            long d = com.smi7i.sandevistan.mech.MechMeteor.dropTime(p);
            if (d <= 0 || st[2] < 0) continue;
            float since = (float) (mc.level.getGameTime() - d) + partial;
            if (since < 0 || since > aim + 4) continue;
            float grow = Mth.clamp(since / 6.0F, 0, 1);
            grow = 1 - (1 - grow) * (1 - grow);
            float zone = Mth.clamp(since / 3.0F, 0, 1) * (1 - Mth.clamp((since - aim) / 4.0F, 0, 1));
            float pulse = 0.72F + 0.28F * Mth.sin(since * (0.6F + 0.04F * since));
            float R0 = (float) com.smi7i.sandevistan.mech.MechMeteor.RADIUS * (0.35F + 0.65F * grow);
            Vec3 c = new Vec3(st[4], st[3] + 0.05, st[5]).subtract(cam);
            double yaw = Math.toRadians(st[6]);
            float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw), rx = (float) -Math.cos(yaw), rz = (float) -Math.sin(yaw);
            float spin = since * 0.03F;
            float zz = zone, pp = pulse, RR = R0, gg = grow, sn = since;
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.textBackground(), (pose, buffer) -> {
                disk(pose, buffer, c, 0, RR, HoloUi.argb(0.26F * zz * pp, ZONE_RED));
                ringQuad(pose, buffer, c.add(0, 0.01, 0), RR - 0.35F, RR, HoloUi.argb(0.95F * zz, ZONE_RED));
                ringQuad(pose, buffer, c.add(0, 0.01, 0), RR * 0.62F, RR * 0.62F + 0.12F, HoloUi.argb(0.7F * zz, ZONE_RED));
                // Полосы-предупреждения по кругу (косые плашки), медленно вращаются.
                int n = 40;
                for (int i = 0; i < n; i += 2) {
                    double a0 = spin + 2 * Math.PI * i / n, a1 = spin + 2 * Math.PI * (i + 1) / n;
                    float r1 = RR - 1.5F, r2 = RR - 0.6F;
                    quad(pose, buffer, c.add(0, 0.012, 0),
                            (float) Math.cos(a0) * r1, (float) Math.sin(a0) * r1, (float) Math.cos(a0 + 0.06) * r2, (float) Math.sin(a0 + 0.06) * r2,
                            (float) Math.cos(a1 + 0.06) * r2, (float) Math.sin(a1 + 0.06) * r2, (float) Math.cos(a1) * r1, (float) Math.sin(a1) * r1,
                            HoloUi.argb(0.8F * zz * pp, 0xFF4050), true);
                }
                // Треугольник «!» (вершиной туда, куда смотрел пилот — читается из-за спины).
                float ts = 2.9F * gg;
                float[][] tri = {{fx * ts * 1.15F, fz * ts * 1.15F},
                        {-fx * ts * 0.6F + rx * ts, -fz * ts * 0.6F + rz * ts},
                        {-fx * ts * 0.6F - rx * ts, -fz * ts * 0.6F - rz * ts}};
                int tc = HoloUi.argb(0.95F * zz, 0xFF5060);
                for (int i = 0; i < 3; i++) line(pose, buffer, c.add(0, 0.014, 0), tri[i], tri[(i + 1) % 3], 0.32F * gg, tc);
                line(pose, buffer, c.add(0, 0.014, 0), new float[]{fx * ts * 0.62F, fz * ts * 0.62F},
                        new float[]{-fx * ts * 0.05F, -fz * ts * 0.05F}, 0.38F * gg, tc);
                line(pose, buffer, c.add(0, 0.014, 0), new float[]{-fx * ts * 0.22F, -fz * ts * 0.22F},
                        new float[]{-fx * ts * 0.36F, -fz * ts * 0.36F}, 0.38F * gg, tc);
                // Шевроны снаружи стягиваются к зоне.
                for (int i = 0; i < 12; i++) {
                    double a = -spin * 2 + 2 * Math.PI * i / 12;
                    float rr = RR + 0.6F + (1.8F - (sn * 0.18F) % 1.8F);
                    float cx = (float) Math.cos(a), cz = (float) Math.sin(a), tx = -cz, tz = cx;
                    float[] tip = {cx * (rr - 0.5F), cz * (rr - 0.5F)};
                    line(pose, buffer, c.add(0, 0.013, 0), new float[]{cx * rr + tx * 0.5F, cz * rr + tz * 0.5F}, tip, 0.16F, HoloUi.argb(0.7F * zz, 0xFF4050));
                    line(pose, buffer, c.add(0, 0.013, 0), new float[]{cx * rr - tx * 0.5F, cz * rr - tz * 0.5F}, tip, 0.16F, HoloUi.argb(0.7F * zz, 0xFF4050));
                }
            });
            int glowCol = HoloUi.argb(1.0F, scale(ZONE_RED, 0.45F * zone * pulse));
            float flashR = since < 8 ? RR + (8 - since) * 1.6F : -1;
            int flashCol = HoloUi.argb(1.0F, scale(0xFF5060, 0.6F * Math.max(0, 1 - since / 8.0F)));
            collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
                ringQuadNoLight(pose, buffer, c.add(0, 0.02, 0), RR - 0.8F, RR + 0.5F, glowCol);
                if (flashR > 0) ringQuadNoLight(pose, buffer, c.add(0, 0.03, 0), flashR - 0.5F, flashR, flashCol);
            });
        }
    }

    /** Толстая линия на земле от a до b (координаты x, z относительно c). */
    private static void line(PoseStack.Pose pose, VertexConsumer b, Vec3 c, float[] a, float[] e, float w, int color) {
        float dx = e[0] - a[0], dz = e[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4F) return;
        float nx = -dz / len * w / 2, nz = dx / len * w / 2;
        quad(pose, b, c, a[0] - nx, a[1] - nz, a[0] + nx, a[1] + nz, e[0] + nx, e[1] + nz, e[0] - nx, e[1] - nz, color, true);
    }

    // ===== «Метеор»: прицел в полёте, луч, схлопывание, шлейф, раскалённый след =====
    private static final class Scorch {
        Vec3 c;
        float age, prevAge;
    }

    private static final List<Scorch> SCORCHES = new ArrayList<>();
    private static final float SCORCH_LIFE = 90;

    private static void tickScorches() {
        for (Iterator<Scorch> it = SCORCHES.iterator(); it.hasNext(); ) {
            Scorch s = it.next();
            s.prevAge = s.age;
            s.age++;
            // Тлеющие угольки поднимаются из воронки.
            if (s.age < SCORCH_LIFE * 0.7F) {
                for (int i = 0; i < 2; i++) {
                    double a = R.nextDouble() * Math.PI * 2, r = R.nextDouble() * 3.2;
                    Puff e = puff(s.c.add(Math.cos(a) * r, 0.15, Math.sin(a) * r), rnd(0.015, 0, 0.015).add(0, 0.03 + R.nextDouble() * 0.05, 0),
                            0.12F + R.nextFloat() * 0.15F, -0.002F, 25 + R.nextInt(25), R.nextInt(3) == 0 ? 0xFFD080 : 0xFF5A20, 0.95F);
                    if (e != null) e.glow = true;
                }
            }
            if (s.age >= SCORCH_LIFE) it.remove();
        }
    }

    /** Отрисовать список светящихся четырёхугольников (сложение света). */
    private static void submitGlowList(SubmitNodeCollector collector, List<float[]> quads) {
        if (quads.isEmpty()) return;
        collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
            for (float[] q : quads) {
                int c0 = Float.floatToRawIntBits(q[12]), c1 = Float.floatToRawIntBits(q[13]);
                int[] colors = {c0, c0, c1, c1};
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) com.smi7i.sandevistan.client.GlowRender.vertex(buffer, pose, q[k * 3], q[k * 3 + 1], q[k * 3 + 2], colors[k]);
            }
        });
    }

    private static void submitMeteorExtras(SubmitNodeCollector collector, Vec3 cam, float partial) {
        Minecraft mc = Minecraft.getInstance();
        List<float[]> glow = new ArrayList<>();
        Vec3 ex = new Vec3(1, 0, 0), ez = new Vec3(0, 0, 1);
        float time = mc.level.getGameTime() + partial;
        // Прицел под собой, пока летишь невидимым: куда придётся удар (видишь только ты).
        var me = mc.player;
        if (me != null && com.smi7i.sandevistan.mech.MechMeteor.isHidden(me)) {
            Vec3 pos = me.getPosition(partial);
            double gy = MechClient.groundUnder(mc.level, pos.x, pos.z);
            Vec3 c = new Vec3(pos.x, gy + 0.07, pos.z).subtract(cam);
            float R0 = (float) com.smi7i.sandevistan.mech.MechMeteor.RADIUS;
            float pulse = 0.75F + 0.25F * Mth.sin(time * 0.25F);
            float spin = time * 0.04F;
            for (int i = 0; i < 18; i++) {
                double a0 = spin + 2 * Math.PI * i / 18;
                planeArc(glow, c, ex, ez, R0 - 0.12F, R0, a0, a0 + 2 * Math.PI / 18 * 0.62, scale(0xFF3048, 0.42F * pulse));
            }
            planeArc(glow, c, ex, ez, R0 + 0.35F, R0 + 0.4F, 0, Math.PI * 2, scale(0xFF3048, 0.18F));
            planeArc(glow, c, ex, ez, R0 * 0.38F, R0 * 0.38F + 0.08F, 0, Math.PI * 2, scale(0xFF6070, 0.4F * pulse));
            for (int k = 0; k < 4; k++) {
                double a = -spin * 0.5 + k * Math.PI / 2;
                planeArc(glow, c, ex, ez, R0 * 0.48F, R0 * 0.86F, a - 0.012, a + 0.012, scale(0xFF4050, 0.5F * pulse));
                planeArc(glow, c, ex, ez, R0 * 0.9F, R0 * 1.06F, a + Math.PI / 4 - 0.05, a + Math.PI / 4 + 0.05, scale(0xFF4050, 0.6F));
            }
            planeArc(glow, c, ex, ez, 0, 0.35F, 0, Math.PI * 2, scale(0xFFFFFF, 0.5F * pulse));
        }
        int aim = com.smi7i.sandevistan.mech.MechMeteor.AIM;
        for (var e : METEOR_SEEN.entrySet()) {
            double[] st = e.getValue();
            if (!(mc.level.getEntity(e.getKey()) instanceof net.minecraft.world.entity.player.Player p)) continue;
            long d = com.smi7i.sandevistan.mech.MechMeteor.dropTime(p);
            if (d <= 0 || st[2] < 0) continue;
            float since = (float) (mc.level.getGameTime() - d) + partial;
            Vec3 zc = new Vec3(st[4], st[3] + 0.08, st[5]).subtract(cam);
            Vec3 body = p.getPosition(partial).add(0, 1.4, 0).subtract(cam);
            // Красный луч наведения: от костюма вниз в центр зоны, пульсирует всё чаще.
            if (since >= 1 && since < aim - 2) {
                float k = Mth.clamp((since - 1) / 4.0F, 0, 1);
                float beat = 0.6F + 0.4F * Mth.sin(since * (0.8F + 0.06F * since));
                ribbon(glow, body, zc, 0.35F * k, HoloUi.argb(1, scale(0xFF2030, 0.35F * beat)), HoloUi.argb(1, scale(0xFF2030, 0.5F * beat)));
                ribbon(glow, body, zc, 0.07F * k, HoloUi.argb(1, scale(0xFFD0D0, 0.9F * beat)), HoloUi.argb(1, scale(0xFFFFFF, 0.9F * beat)));
                planeArc(glow, zc, ex, ez, 0, 0.6F * k, 0, Math.PI * 2, scale(0xFFFFFF, 0.6F * beat));
            }
            // Перед ударом зона схлопывается к центру (вспышка кольцом), потом — взрыв.
            if (since >= aim - 5 && since < aim) {
                float k = (since - (aim - 5)) / 5.0F;
                float r = (float) com.smi7i.sandevistan.mech.MechMeteor.RADIUS * (1 - k * k);
                planeArc(glow, zc, ex, ez, Math.max(0, r - 0.6F), r, 0, Math.PI * 2, scale(0xFFFFFF, 0.5F + 0.5F * k));
                planeArc(glow, zc, ex, ez, Math.max(0, r - 1.6F), Math.max(0, r - 0.6F), 0, Math.PI * 2, scale(0xFF3048, 0.6F));
            }
            // Падение: бело-голубой шлейф над костюмом.
            if (since >= aim - 4 && since < aim + 2) {
                float k = 1 - Mth.clamp((since - aim) / 2.0F, 0, 1);
                Vec3 top = body.add(0, 9, 0);
                ribbon(glow, body, top, 0.9F * k, HoloUi.argb(1, scale(VORTEX_BLUE, 0.6F * k)), HoloUi.argb(1, 0));
                ribbon(glow, body, top, 0.3F * k, HoloUi.argb(1, scale(0xFFFFFF, 0.9F * k)), HoloUi.argb(1, 0));
            }
        }
        // Раскалённая воронка после удара: светится оранжевым и медленно остывает.
        for (Scorch s : SCORCHES) {
            float t = Mth.lerp(partial, s.prevAge, s.age) / SCORCH_LIFE;
            float heat = (1 - t) * (1 - t);
            Vec3 c = s.c.add(0, 0.05, 0).subtract(cam);
            for (int i = 0; i < 5; i++) {
                float r0 = i * 0.7F, r1 = r0 + 0.7F;
                float fall = 1 - i / 5.0F;
                planeArc(glow, c, ex, ez, r0, r1, 0, Math.PI * 2, scale(i < 2 ? 0xFFA040 : 0xFF4018, 0.55F * heat * fall));
            }
            planeArc(glow, c, ex, ez, 3.4F, 3.6F + 1.2F * t, 0, Math.PI * 2, scale(0xFF3020, 0.5F * heat));
        }
        submitGlowList(collector, glow);
    }

    /** После удара «метеора»: раскалённая воронка с угольками. */
    private static void scorch(Vec3 ground) {
        Scorch s = new Scorch();
        s.c = ground;
        SCORCHES.add(s);
    }
    // ===== Гибель костюма: искры, разряды, дым из капсул, пыль от коленей =====
    private static final java.util.Map<Integer, Float> DEATH_SEEN = new java.util.HashMap<>();

    private static void tickDeath(Minecraft mc) {
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        for (var p : mc.level.players()) {
            float t = MechShowdownClient.deathTime(p, 0);
            if (t < 0 || t > 12) continue;
            alive.add(p.getId());
            float prev = DEATH_SEEN.getOrDefault(p.getId(), -1.0F);
            DEATH_SEEN.put(p.getId(), t);
            double yaw = Math.toRadians(MechMotion.bodyYaw(p, 1));
            Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
            float sink = Math.min(1, t / 3.4F);
            Vec3 base = p.position();
            for (int side = -1; side <= 1; side += 2) {
                Vec3 pod = base.add(r.scale(0.95 * side)).add(0, 2.05 - 0.6 * sink, 0).subtract(f.scale(0.15 + 0.4 * sink));
                // Искры: в отказе систем часто, потом реже.
                float sparkChance = t < 1.3F ? 0.55F : 0.12F * Math.max(0, 1 - (t - 1.3F) / 3.0F);
                if (R.nextFloat() < sparkChance) {
                    for (int i = 0; i < 6; i++) {
                        Vec3 v = rnd(0.18, 0.1, 0.18).add(0, 0.12 + R.nextDouble() * 0.12, 0);
                        Puff s = puff(pod.add(rnd(0.3, 0.3, 0.3)), v, 0.08F + R.nextFloat() * 0.08F, -0.003F, 8 + R.nextInt(8),
                                R.nextInt(3) == 0 ? 0xFFFFFF : 0xFFB040, 1.0F);
                        if (s != null) s.glow = true;
                    }
                }
                // Голубые разряды по броне.
                if (R.nextFloat() < (t < 1.3F ? 0.25F : 0.05F * Math.max(0, 1 - (t - 1.3F) / 2.5F))) {
                    Bolt b = new Bolt();
                    int n = 4;
                    b.pts = new Vec3[n];
                    Vec3 dir = rnd(1, 0.8, 1).normalize();
                    for (int i = 0; i < n; i++) b.pts[i] = pod.add(dir.scale(0.15 + 0.35 * i)).add(i == 0 ? Vec3.ZERO : rnd(0.12, 0.12, 0.12));
                    b.life = 2 + R.nextInt(2);
                    BOLTS.add(b);
                }
                // Тяжёлый дым из капсул — с 0.8 с, всё гуще.
                if (t > 0.8F && R.nextFloat() < 0.6F) {
                    puff(pod.add(rnd(0.25, 0.15, 0.25)).subtract(f.scale(0.2)), rnd(0.015, 0, 0.015).add(0, 0.035 + R.nextDouble() * 0.03, 0),
                            0.5F + R.nextFloat() * 0.4F, 0.03F, 50 + R.nextInt(40), R.nextBoolean() ? 0x2A2A2E : 0x3C3A3A, 0.75F);
                }
            }
            // Колени и руки ударяют о землю: пыль, куски, толчок.
            float[] hits = {2.2F, 3.4F, 4.4F};
            for (int h = 0; h < hits.length; h++) {
                if (prev < hits[h] && t >= hits[h]) {
                    Vec3 at = h == 2 ? base.add(f.scale(1.5)) : base.add(r.scale(h == 0 ? 0.45 : -0.45)).add(f.scale(-0.2));
                    BlockState gs = mc.level.getBlockState(BlockPos.containing(at).below());
                    int dust = dustColor(mc.level, gs, BlockPos.containing(at).below());
                    ring(at, dust, h == 2 ? 22 : 30, 0.15, 0.32, 0.8F, 1.4F, 30, 55);
                    fakeChunks(mc.level, at.add(0, 0.2, 0), gs, h == 2 ? 6 : 10, 0.1, 0.3, 0.25, 0.5, null);
                    if (mc.player != null && mc.player.distanceTo(p) < 24) MechShake.kick(h == 2 ? 0.8F : 1.6F);
                }
            }
        }
        DEATH_SEEN.keySet().retainAll(alive);
    }
    /** Кусок у шара «Магнита». true — ещё притянут (позицию поставили сами); false — выброшен, дальше обычная физика. */
    private static boolean magnetChunk(Chunk c, float dt) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level.getEntity(c.magnetOwner) instanceof net.minecraft.world.entity.player.Player p)
                || !com.smi7i.sandevistan.mech.MechPush.isActive(p)) {
            c.magnetOwner = -1;
            c.vel = rnd(0.2, 0.1, 0.2);
            return false;
        }
        long age = mc.level.getGameTime() - com.smi7i.sandevistan.mech.MechPush.startTime(p);
        Vec3 ball = com.smi7i.sandevistan.mech.MechPush.ball(p);
        if (age >= com.smi7i.sandevistan.mech.MechPush.BLAST) {
            // Выброс: вперёд конусом, вверх, раскалённые.
            Vec3 f = com.smi7i.sandevistan.mech.MechPush.forward(p);
            c.magnetOwner = -1;
            c.vel = f.scale(0.9 + R.nextDouble() * 0.9).add(rnd(0.45, 0.1, 0.45)).add(0, 0.35 + R.nextDouble() * 0.4, 0);
            c.glow = true;
            c.tint = 0xFFFF9060;
            c.resting = false;
            return false;
        }
        c.prevPos = c.pos;
        c.prevAngle = c.angle;
        c.magA += 0.22 * dt;
        Vec3 target = ball.add(Math.cos(c.magA) * c.magR, c.magY + 0.3 * Math.sin(c.magA * 1.7), Math.sin(c.magA) * c.magR);
        double k = Math.min(1, 0.12 * dt);
        c.pos = c.pos.add(target.subtract(c.pos).scale(k));
        c.angle += c.spin * dt;
        if (c.pos.distanceTo(ball) < 3) {
            c.glow = true;
            c.tint = 0xFFFF7060; // у шара — раскалённые, красные
        }
        return true;
    }
    // ===== Следы шагов костюма =====
    private static final class Footprint {
        Vec3 pos;
        double yaw;
        long born;
        /** Свой крошечный подъём над землёй — следы не спорят друг с другом (иначе мерцают). */
        double lift;
    }

    private static int printCounter;

    private static final List<Footprint> FOOTPRINTS = new ArrayList<>();
    /** Сколько живёт след (тики) и сколько последних тиков он тает; сколько следов максимум. */
    private static final int PRINT_LIFE = 600, PRINT_FADE = 120, PRINT_MAX = 120;

    /** Отпечаток стопы костюма — один тёмный слой по форме подошвы. yaw — куда смотрит костюм (радианы). */
    public static void footprint(Vec3 pos, double yaw) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        // Шаг почти в старый след — старый убираем (не накладываем друг на друга).
        FOOTPRINTS.removeIf(o -> o.pos.distanceToSqr(pos) < 0.5 * 0.5);
        Footprint f = new Footprint();
        f.pos = pos;
        f.yaw = yaw;
        f.born = level.getGameTime();
        f.lift = 0.02 + (printCounter++ % 24) * 0.0015;
        FOOTPRINTS.add(f);
        if (FOOTPRINTS.size() > PRINT_MAX) FOOTPRINTS.remove(0);
    }

    private static void submitFootprints(SubmitNodeCollector collector, Vec3 cam) {
        if (FOOTPRINTS.isEmpty()) return;
        long now = Minecraft.getInstance().level.getGameTime();
        FOOTPRINTS.removeIf(f -> now - f.born > PRINT_LIFE);
        List<Footprint> list = new ArrayList<>(FOOTPRINTS);
        collector.submitCustomGeometry(new PoseStack(), RenderTypes.textBackground(), (pose, buffer) -> {
            for (Footprint f : list) {
                long age = now - f.born;
                float a = 0.42F * Math.min(1, (PRINT_LIFE - age) / (float) PRINT_FADE);
                if (a <= 0.01F) continue;
                Vec3 c = f.pos.subtract(cam).add(0, f.lift, 0);
                double fx = -Math.sin(f.yaw), fz = Math.cos(f.yaw), rx = -Math.cos(f.yaw), rz = -Math.sin(f.yaw);
                // Один слой: тёмный отпечаток подошвы.
                // Размер стопы костюма: 11×8 пикселей модели ≈ 0.65×0.47 блока.
                printRect(pose, buffer, c, fx, fz, rx, rz, -0.32, 0.33, -0.235, 0.235, HoloUi.argb(a, 0x15100A));
            }
        });
    }

    private static void printRect(PoseStack.Pose pose, VertexConsumer b, Vec3 c, double fx, double fz, double rx, double rz,
                                  double f0, double f1, double r0, double r1, int color) {
        float x0 = (float) (fx * f0 + rx * r0), z0 = (float) (fz * f0 + rz * r0);
        float x1 = (float) (fx * f1 + rx * r0), z1 = (float) (fz * f1 + rz * r0);
        float x2 = (float) (fx * f1 + rx * r1), z2 = (float) (fz * f1 + rz * r1);
        float x3 = (float) (fx * f0 + rx * r1), z3 = (float) (fz * f0 + rz * r1);
        quad(pose, b, c, x0, z0, x1, z1, x2, z2, x3, z3, color, true);
    }

    // ===== Повреждённый костюм: дым, искры, разряды, удары по броне =====
    private static final java.util.Map<Integer, Integer> LAST_HURT = new java.util.HashMap<>();

    private static void tickDamage(Minecraft mc) {
        for (var p : mc.level.players()) {
            if (!com.smi7i.sandevistan.ModAttachments.hasMech(p) || !p.isAlive()) {
                LAST_HURT.remove(p.getId());
                continue;
            }
            float hp = p.getHealth() / Math.max(1, p.getMaxHealth());
            double yaw = Math.toRadians(MechMotion.bodyYaw(p, 1));
            Vec3 r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vec3 podL = p.position().add(0, 2.35, 0).add(r.scale(-0.94)), podR = p.position().add(0, 2.35, 0).add(r.scale(0.94));
            // Дым из капсул — тем гуще, чем меньше здоровья.
            if (hp < 0.5F && R.nextFloat() < (0.5F - hp) * 1.6F) {
                Vec3 at = (R.nextBoolean() ? podL : podR).add(rnd(0.3, 0.1, 0.3));
                puffAt(at, new Vec3(0, 0.05 + R.nextDouble() * 0.04, 0).add(rnd(0.01, 0, 0.01)), 0.7F + R.nextFloat() * (1.2F - hp), 0.04F,
                        50 + R.nextInt(40), hp < 0.25F ? 0x1E1C1C : 0x3A3838, 0.75F, false);
            }
            // Почти разбит: искры и голубые разряды.
            if (hp < com.smi7i.sandevistan.mech.MechManager.LIMP_HP) {
                if (R.nextFloat() < 0.1F) sparks((R.nextBoolean() ? podL : podR).add(rnd(0.4, 0.4, 0.4)), 7);
                if (R.nextFloat() < 0.05F) {
                    Vec3 o = (R.nextBoolean() ? podL : podR);
                    Bolt b = new Bolt();
                    b.pts = new Vec3[4];
                    Vec3 dir = rnd(1, 0.8, 1).normalize();
                    for (int i = 0; i < 4; i++) b.pts[i] = o.add(dir.scale(0.15 + 0.35 * i)).add(i == 0 ? Vec3.ZERO : rnd(0.12, 0.12, 0.12));
                    b.life = 2 + R.nextInt(2);
                    BOLTS.add(b);
                }
            }
            // Попадание по броне: сноп искр и металлический лязг.
            int prev = LAST_HURT.getOrDefault(p.getId(), 0);
            LAST_HURT.put(p.getId(), p.hurtTime);
            if (p.hurtTime > prev) {
                sparks(p.position().add(0, 1.2 + R.nextDouble() * 1.4, 0).add(r.scale((R.nextDouble() - 0.5) * 1.6)), 14);
                mc.level.playLocalSound(p.getX(), p.getY() + 1.5, p.getZ(), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.45F, 1.5F + R.nextFloat() * 0.3F, false);
                if (p == mc.player) MechShake.kick(1.0F);
            }
        }
    }

    private static void sparks(Vec3 at, int n) {
        for (int i = 0; i < n; i++) {
            Puff s = puff(at, rnd(0.18, 0.12, 0.18).add(0, 0.08, 0), 0.07F + R.nextFloat() * 0.07F, -0.003F, 8 + R.nextInt(8),
                    R.nextInt(3) == 0 ? 0xFFFFFF : 0xFFA030, 1.0F);
            if (s != null) s.glow = true;
        }
    }
    // ===== Для «Сингулярности» =====
    /** Кусок по спирали к горизонту дыры; у самой дыры сжимается («спагеттификация»). true — поглощён. */
    private static boolean sinkChunk(Chunk c, float dt) {
        double rH = MechSingularityClient.holeRadius(c.sinkOwner, 0);
        Vec3 center = MechSingularityClient.holeCenter(c.sinkOwner);
        if (rH <= 0 || center == null) return true;
        c.prevPos = c.pos;
        c.prevAngle = c.angle;
        double rr = Math.max(c.sinkR, 0.01);
        c.sinkA += (0.03 + 0.9 / (rr / rH + 0.4) * 0.1) * dt;
        c.sinkR -= (0.025 * rH + 0.18 * rH / rr) * dt;
        c.sinkY *= Math.pow(0.93, dt);
        c.pos = center.add(Math.cos(c.sinkA) * c.sinkR, c.sinkY, Math.sin(c.sinkA) * c.sinkR);
        c.angle += c.spin * (1 + 3 * rH / rr) * dt;
        c.size = (float) Math.max(0.12, 0.9 * Math.min(1, (c.sinkR - rH) / (1.5 * rH)));
        c.glow = c.sinkR < rH * 1.8;
        if (c.glow) c.tint = 0xFFFFC080;
        if (c.sinkR < rH * 1.02) {
            Puff f = puff(c.pos, Vec3.ZERO, 0.4F, -0.02F, 6, 0xFFC060, 0.9F);
            if (f != null) f.glow = true;
            return true;
        }
        return false;
    }

    public static void glowPuff(Vec3 pos, Vec3 vel, float size, float grow, int life, int rgb, float alpha) {
        Puff p = puff(pos, vel, size, grow, life, rgb, alpha);
        if (p != null) p.glow = true;
    }

    public static void puffAt(Vec3 pos, Vec3 vel, float size, float grow, int life, int rgb, float alpha, boolean glow) {
        Puff p = puff(pos, vel, size, grow, life, rgb, alpha);
        if (p != null) p.glow = glow;
    }

    public static void dustPuff(Vec3 pos, Vec3 vel, float size, float grow, int life, float alpha) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockPos below = BlockPos.containing(pos).below();
        puff(pos, vel, size, grow, life, dustColor(level, level.getBlockState(below), below), alpha);
    }

    public static void waveAt(Vec3 c, float maxR, float life, float width, int rgb, boolean ground) {
        wave(c, maxR, life, width, rgb, ground);
    }

    public static void dustRingAt(Vec3 at, int count, double sMin, double sMax, float sizeMin, float sizeMax, int lifeMin, int lifeMax) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockPos below = BlockPos.containing(at).below();
        ring(at, dustColor(level, level.getBlockState(below), below), count, sMin, sMax, sizeMin, sizeMax, lifeMin, lifeMax);
    }

    public static void groundChunks(Vec3 at, int count) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        fakeChunks(level, at.add(0, 0.3, 0), level.getBlockState(BlockPos.containing(at).below()), count, 0.5, 1.4, 0.6, 1.4, null);
    }
    // ===== Голографические ударные волны у плеч (красные кольца сзади капсул) =====
    private static final class Shoulder {
        int id;
        float age, prevAge, delay;
    }

    private static final List<Shoulder> SHOULDERS = new ArrayList<>();
    private static final float SHOULDER_LIFE = 18;

    /** Мощная способность: из красных колец на спине капсул расходятся голографические волны (count штук подряд). */
    public static void shoulderWaves(Entity p, int count) {
        if (!(p instanceof net.minecraft.world.entity.player.Player pl) || !com.smi7i.sandevistan.ModAttachments.hasMech(pl)) return;
        for (int i = 0; i < count; i++) {
            Shoulder s = new Shoulder();
            s.id = p.getId();
            s.delay = i * 4;
            SHOULDERS.add(s);
        }
    }

    private static void tickShoulders() {
        for (Iterator<Shoulder> it = SHOULDERS.iterator(); it.hasNext(); ) {
            Shoulder s = it.next();
            s.prevAge = s.age;
            if (++s.age > s.delay + SHOULDER_LIFE) it.remove();
        }
    }

    private static void submitShoulders(List<float[]> out, Vec3 cam, float partial) {
        Minecraft mc = Minecraft.getInstance();
        for (Shoulder s : SHOULDERS) {
            float t = (Mth.lerp(partial, s.prevAge, s.age) - s.delay) / SHOULDER_LIFE;
            if (t < 0 || t > 1) continue;
            if (!(mc.level.getEntity(s.id) instanceof net.minecraft.world.entity.player.Player e) || e.isInvisible()) continue;
            double yaw = Math.toRadians(MechMotion.bodyYaw(e, partial));
            Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = new Vec3(0, 1, 0);
            Vec3 base = e.getPosition(partial).subtract(cam);
            float ease = 1 - (1 - t) * (1 - t);
            float a = (float) Math.pow(1 - t, 1.4) * (0.85F + 0.15F * Mth.sin((s.age + s.id) * 2.7F));
            float radius = 0.35F + 1.9F * ease;
            for (int side = -1; side <= 1; side += 2) {
                Vec3 c = base.add(up.scale(2.05)).add(r.scale(0.94 * side)).subtract(f.scale(0.35 + 0.9 * ease));
                float rot = t * 2.4F * side;
                // Пунктирное кольцо, тонкое внутреннее и засечки — как голограмма.
                for (int i = 0; i < 24; i++) {
                    if (i % 4 == 3) continue;
                    double a0 = rot + 2 * Math.PI * i / 24, a1 = rot + 2 * Math.PI * (i + 0.8) / 24;
                    planeArc(out, c, r, up, radius - 0.07F - 0.08F * (1 - t), radius + 0.07F, a0, a1, scale(0xFF2840, 0.9F * a));
                }
                planeArc(out, c, r, up, radius * 0.68F - 0.03F, radius * 0.68F + 0.03F, 0, Math.PI * 2, scale(0xFF7080, 0.6F * a));
                for (int i = 0; i < 8; i++) {
                    double aa = -rot + 2 * Math.PI * i / 8;
                    planeArc(out, c, r, up, radius + 0.12F, radius + 0.38F, aa - 0.04, aa + 0.04, scale(0xFF4050, 0.8F * a));
                }
                planeArc(out, c, r, up, radius * 1.15F, radius * 1.15F + 0.02F, 0, Math.PI * 2, scale(0xFF2840, 0.35F * a));
            }
        }
    }

    /** Дуга кольца в плоскости (u, v) вокруг c: от радиуса r0 до r1, углы a0..a1. Для сложения света (glowQuads). */
    private static void planeArc(List<float[]> out, Vec3 c, Vec3 u, Vec3 v, float r0, float r1, double a0, double a1, int rgb) {
        int seg = Math.max(1, (int) Math.ceil((a1 - a0) / (Math.PI / 20)));
        int col = HoloUi.argb(1, rgb);
        for (int i = 0; i < seg; i++) {
            double b0 = a0 + (a1 - a0) * i / seg, b1 = a0 + (a1 - a0) * (i + 1) / seg;
            Vec3 d0 = u.scale(Math.cos(b0)).add(v.scale(Math.sin(b0))), d1 = u.scale(Math.cos(b1)).add(v.scale(Math.sin(b1)));
            Vec3 i0 = c.add(d0.scale(r0)), o0 = c.add(d0.scale(r1)), i1 = c.add(d1.scale(r0)), o1 = c.add(d1.scale(r1));
            out.add(new float[]{(float) i0.x, (float) i0.y, (float) i0.z, (float) o0.x, (float) o0.y, (float) o0.z,
                    (float) o1.x, (float) o1.y, (float) o1.z, (float) i1.x, (float) i1.y, (float) i1.z,
                    Float.intBitsToFloat(col), Float.intBitsToFloat(col)});
        }
    }

    // ---------- Цвета ----------
    /** Цвет пыли: светло-бежевый с примесью цвета земли (трава — зеленоватая пыль, как в Roblox). */
    private static int dustColor(ClientLevel level, BlockState state, BlockPos pos) {
        int g = state.isAir() ? DUST_BASE : state.getMapColor(level, pos).col;
        return mix(DUST_BASE, g, GROUND_MIX);
    }

    private static int mix(int a, int b, float k) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** Чуть светлее или темнее (для разнообразия клубов). */
    private static int shade(int rgb, float amount) {
        float k = 1.0F + (R.nextFloat() * 2 - 1) * amount;
        return scale(rgb, k);
    }

    private static int scale(int rgb, float k) {
        int r = Mth.clamp(Math.round(((rgb >> 16) & 0xFF) * k), 0, 255);
        int g = Mth.clamp(Math.round(((rgb >> 8) & 0xFF) * k), 0, 255);
        int b = Mth.clamp(Math.round((rgb & 0xFF) * k), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    private static Vec3 rnd(double x, double y, double z) {
        return new Vec3((R.nextDouble() - 0.5) * 2 * x, (R.nextDouble() - 0.5) * 2 * y, (R.nextDouble() - 0.5) * 2 * z);
    }
}
