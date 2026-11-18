package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.client.IrisCompat;
import com.smi7i.sandevistan.mech.MechSingularity;
import com.smi7i.sandevistan.mech.SingularityFxPayload;
import com.smi7i.sandevistan.mech.SingularityPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/**
 * «Сингулярность» на клиенте: управление (клавиша U), парение пилота, кинокамера взлёта, красные и синие молнии,
 * слияние двух молний в точке прицела, чёрная дыра (горизонт событий, аккреционный диск, линзированное кольцо
 * как в «Интерстелларе», искажение света шейдером), блоки по спирали, сверхновая и её ударная волна.
 */
public final class MechSingularityClient {
    public static KeyMapping KEY;
    private static final Random R = new Random();
    private static final int RED = 0xFF2A3A, BLUE = 0x3FA8FF, WHITE = 0xFFFFFF, VIOLET = 0xA060FF;
    private static final int FULL_BRIGHT = 15728880;
    /** Сверхновая: радиус эффектов (блоки) и за сколько тиков до него доходит ударная волна. */
    public static final double NOVA_FX = 100.0;
    public static final int NOVA_FX_TICKS = 44;

    // ---------- Искажение света (шейдер) ----------
    private static final RenderPipeline LENS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("sandevistan", "pipeline/lens"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("sandevistan", "core/lens"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler").withUniform("LensInfo", UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build());
    private static TextureTarget lensCopy;
    private static MappableRingBuffer lensUbo;
    /** Шейдер не собрался / упал — дальше без искажения (вся остальная картинка остаётся). */
    private static boolean lensBroken;

    // ---------- Состояние ----------
    private static final class Hole {
        int owner;
        Vec3 center;
        double groundY;
        long fire;
        long lastT = -1;
        long seed;
    }

    private static final Map<Integer, Hole> HOLES = new HashMap<>();

    private static final class Bolt {
        Vec3[] pts;
        int rgb;
        float age, life, width;
    }

    private static final List<Bolt> BOLTS = new ArrayList<>();

    /** Столб света: красная и синяя спирали вокруг белого ядра (с неба в костюм в конце взлёта). */
    private static final class Pillar {
        Vec3 top, bottom;
        float age, life;
    }

    private static final List<Pillar> PILLARS = new ArrayList<>();

    /** Искра в аккреционном диске: падает по спирали к горизонту. */
    private static final class Spark {
        Hole h;
        double r, a, y, pr, pa, py;
        int rgb;
    }

    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final Map<Integer, Long> SEEN_START = new HashMap<>();
    private static double hoverGround = Double.NaN;
    private static CameraType previousCamera;
    private static boolean cameraForced;
    static int cooldown;

    private MechSingularityClient() {}

    public static void init(KeyMapping.Category category) {
        KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_singularity", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, category));
        ClientPlayNetworking.registerGlobalReceiver(SingularityFxPayload.TYPE, (p, context) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            Hole h = new Hole();
            h.owner = p.player();
            h.center = new Vec3(p.x(), p.y(), p.z());
            h.fire = p.fireTime();
            h.seed = p.fireTime() * 31 + p.player();
            h.groundY = p.y() - MechSingularity.HOLE_LIFT;
            HOLES.put(h.owner, h);
        });
        ClientTickEvents.START_CLIENT_TICK.register(MechSingularityClient::hoverTick);
        ClientTickEvents.END_CLIENT_TICK.register(MechSingularityClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            HOLES.clear();
            BOLTS.clear();
            SPARKS.clear();
        });
        // С шейдерпаком Iris — вместе с миром (без искажения света); без него — в SandevistanWorldPass.
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!IrisCompat.shadersActive() || !hasAny()) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submitDark(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
            submitGlow(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
    }

    public static boolean hasAny() {
        Minecraft mc = Minecraft.getInstance();
        if (!HOLES.isEmpty() || !BOLTS.isEmpty() || !SPARKS.isEmpty() || !PILLARS.isEmpty()) return true;
        return mc.player != null && MechSingularity.isActive(mc.player);
    }

    /** Радиус дыры игрока ownerId сейчас (-1 — нет дыры). */
    public static double holeRadius(int ownerId, float partial) {
        Hole h = HOLES.get(ownerId);
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.level == null) return -1;
        return MechSingularity.radius(mc.level.getGameTime() - h.fire + partial);
    }

    public static Vec3 holeCenter(int ownerId) {
        Hole h = HOLES.get(ownerId);
        return h == null ? null : h.center;
    }

    // ---------- Управление и парение ----------

    private static void hoverTick(Minecraft mc) {
        var player = mc.player;
        if (player == null || mc.level == null) return;
        long s = MechSingularity.startTime(player);
        if (s <= 0) {
            if (!Double.isNaN(hoverGround)) player.setNoGravity(false);
            hoverGround = Double.NaN;
            return;
        }
        if (Double.isNaN(hoverGround)) hoverGround = player.getY();
        player.setNoGravity(true);
        player.fallDistance = 0;
        long now = mc.level.getGameTime();
        float up = smooth((now - s) / 40.0F);
        long f = MechSingularity.fireTime(player);
        float down = 0;
        if (f > 0) down = smooth((now - f - (MechSingularity.NOVA + 8)) / 40.0F);
        double target = hoverGround + MechSingularity.HOVER * up * (1 - down) + 0.15 * Math.sin(now * 0.12) * up * (1 - down);
        player.setDeltaMovement(0, (target - player.getY()) * 0.2, 0);
    }

    private static void tick(Minecraft mc) {
        if (cooldown > 0) cooldown--;
        if (mc.level == null || mc.player == null) return;
        if (mc.isPaused()) return;
        // Клавиша: начать; во время наведения — выстрелить раньше.
        while (KEY.consumeClick()) {
            Player me = mc.player;
            if (!MechSingularity.isActive(me)) {
                if (ModAttachments.hasMech(me) && cooldown <= 0 && !MechClient.localAttacking()) {
                    ClientPlayNetworking.send(new SingularityPayload(0));
                }
            } else if (MechSingularity.fireTime(me) <= 0 && mc.level.getGameTime() - MechSingularity.startTime(me) >= MechSingularity.RISE) {
                ClientPlayNetworking.send(new SingularityPayload(1));
            }
        }
        cinematicCamera(mc);
        long now = mc.level.getGameTime();
        // Взлёт и наведение: красные и синие молнии вокруг костюма.
        for (Player p : mc.level.players()) {
            long s = MechSingularity.startTime(p);
            if (s <= 0) {
                SEEN_START.remove(p.getId());
                continue;
            }
            Long seen = SEEN_START.put(p.getId(), s);
            if (seen == null || seen != s) {
                MechFx.shoulderWaves(p, 6);
                if (p == mc.player) MechLifeHud.colorFlash(0xB040FF, 300);
            }
            long age = now - s;
            long f = MechSingularity.fireTime(p);
            if (f > 0 && now - f > MechSingularity.BIRTH) continue;
            float intensity = f > 0 ? 0.5F : age < MechSingularity.RISE ? 0.6F + 0.4F * age / MechSingularity.RISE : 0.45F;
            if (R.nextFloat() < intensity) skyStrike(mc, p, R.nextBoolean());
            if (R.nextFloat() < intensity) podArc(p, R.nextBoolean());
            if (age < MechSingularity.RISE && R.nextFloat() < 0.45F) groundStrike(mc, p);
            if (f <= 0 && age == MechSingularity.RISE - 10) {
                // Кульминация взлёта: двойная спираль из неба бьёт в костюм, вспышка, толчок.
                Pillar pl = new Pillar();
                pl.bottom = p.position().add(0, 1.6, 0);
                pl.top = pl.bottom.add(0, 90, 0);
                pl.life = 22;
                PILLARS.add(pl);
                MechFx.waveAt(p.position().add(0, 0.06 - MechSingularity.HOVER * 0.8, 0), 16, 14, 1.2F, 0xC080FF, true);
                for (int i = 0; i < 8; i++) groundStrike(mc, p);
                if (mc.player != null && mc.player.distanceTo(p) < 60) {
                    MechShake.kick(2.2F);
                    if (p == mc.player) MechLifeHud.colorFlash(0xC8A0FF, 350);
                    mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 0.8F, 0.6F, false);
                }
            }
        }
        // Дыры.
        for (Iterator<Hole> it = HOLES.values().iterator(); it.hasNext(); ) {
            Hole h = it.next();
            long t = now - h.fire;
            long prev = h.lastT;
            h.lastT = t;
            if (t > MechSingularity.NOVA + MechSingularity.TAIL + 80) {
                it.remove();
                continue;
            }
            var owner = mc.level.getEntity(h.owner);
            if (t <= MechSingularity.BIRTH && owner instanceof Player op) {
                if (t <= MechSingularity.BEAM) {
                    // Две молнии летят из капсул в точку прицела.
                    float k = Math.min(1, (t + 1) / (float) MechSingularity.BEAM);
                    for (int side = -1; side <= 1; side += 2) {
                        Vec3 from = pod(op, side, 1);
                        Vec3 to = from.lerp(h.center, k);
                        BOLTS.add(bolt(from, to, 14, 0.9, side < 0 ? RED : BLUE, 3, 0.35F));
                    }
                } else {
                    // Слияние: молнии между двумя шарами, искры.
                    Vec3[] orbs = orbs(h, t);
                    if (R.nextFloat() < 0.8F) BOLTS.add(bolt(orbs[0], orbs[1], 8, 0.5, R.nextBoolean() ? RED : BLUE, 2, 0.15F));
                    if (R.nextFloat() < 0.4F) BOLTS.add(bolt(orbs[R.nextInt(2)], h.center.add(rnd(6, 0, 6)).add(0, -MechSingularity.HOLE_LIFT, 0), 10, 0.8, R.nextBoolean() ? RED : BLUE, 3, 0.2F));
                    for (int i = 0; i < 3; i++) {
                        MechFx.glowPuff(orbs[i % 2], rnd(0.2, 0.2, 0.2), 0.3F, -0.01F, 8 + R.nextInt(6), i % 2 == 0 ? RED : BLUE, 0.9F);
                    }
                }
            }
            if (prev < MechSingularity.BIRTH && t >= MechSingularity.BIRTH) birth(mc, h);
            double rH = MechSingularity.radius(t);
            if (rH > 0) {
                for (int i = 0; i < 4; i++) {
                    Spark sp = new Spark();
                    sp.h = h;
                    sp.r = sp.pr = rH * (2.0 + R.nextDouble() * 2.6);
                    sp.a = sp.pa = R.nextDouble() * Math.PI * 2;
                    sp.y = sp.py = (R.nextDouble() - 0.5) * 0.25 * rH;
                    sp.rgb = R.nextInt(4) == 0 ? WHITE : R.nextBoolean() ? 0xFFB040 : 0xFF6A20;
                    SPARKS.add(sp);
                }
                // Пыль с земли затягивает к дыре; изредка по диску проскакивают красные и синие разряды.
                if (R.nextFloat() < 0.6F) {
                    double a = R.nextDouble() * Math.PI * 2, d = rH * (1.8 + R.nextDouble() * 2.5);
                    Vec3 g = new Vec3(h.center.x + Math.cos(a) * d, h.groundY + 0.3, h.center.z + Math.sin(a) * d);
                    MechFx.dustPuff(g, h.center.subtract(g).normalize().scale(0.25).add(-Math.sin(a) * 0.2, 0.05, Math.cos(a) * 0.2), 1.0F, 0.03F, 30, 0.7F);
                }
                if (R.nextFloat() < 0.12F) {
                    double a = R.nextDouble() * Math.PI * 2;
                    Vec3 o = h.center.add(Math.cos(a) * rH * 2.5, 0, Math.sin(a) * rH * 2.5);
                    BOLTS.add(bolt(o, h.center.add(rnd(rH, rH * 0.4, rH)), 8, 0.7, R.nextBoolean() ? RED : BLUE, 3, 0.15F));
                }
            }
            if (prev < MechSingularity.NOVA && t >= MechSingularity.NOVA) nova(mc, h);
            if (t >= MechSingularity.NOVA && t < MechSingularity.NOVA + 100) novaTick(mc, h, t - MechSingularity.NOVA);
        }
        for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            if (++b.age >= b.life) it.remove();
        }
        for (Iterator<Pillar> it = PILLARS.iterator(); it.hasNext(); ) {
            if (++it.next().age >= 22) it.remove();
        }
        for (Iterator<Spark> it = SPARKS.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            double rH = MechSingularity.radius(now - s.h.fire);
            s.pr = s.r;
            s.pa = s.a;
            s.py = s.y;
            if (rH <= 0 || !HOLES.containsValue(s.h)) {
                it.remove();
                continue;
            }
            s.a += 0.04 + 1.1 / (s.r / rH + 0.3) * 0.12;
            s.r -= 0.02 * rH + 0.22 * rH / s.r;
            s.y *= 0.92;
            if (s.r < rH * 1.05) it.remove();
        }
    }

    private static void birth(Minecraft mc, Hole h) {
        mc.level.playLocalSound(h.center.x, h.center.y, h.center.z, com.smi7i.sandevistan.ModSounds.BOOM_FLASH, SoundSource.PLAYERS, 6.0F, 0.8F, false);
        for (int i = 0; i < 50; i++) {
            Vec3 d = rnd(1, 1, 1).normalize();
            MechFx.glowPuff(h.center.add(d.scale(0.5)), d.scale(0.2 + R.nextDouble() * 0.5), 0.6F + R.nextFloat() * 0.8F, -0.01F,
                    10 + R.nextInt(14), R.nextInt(3) == 0 ? WHITE : R.nextBoolean() ? RED : BLUE, 0.95F);
        }
        MechFx.waveAt(h.center, 16, 12, 1.0F, 0xE0D8FF, false);
        MechFx.waveAt(new Vec3(h.center.x, h.groundY + 0.06, h.center.z), 22, 18, 1.4F, 0x8060C0, true);
        for (int i = 0; i < 10; i++) BOLTS.add(bolt(h.center, h.center.add(rnd(1, 0.7, 1).normalize().scale(4 + R.nextDouble() * 8)), 8, 0.8, R.nextBoolean() ? RED : BLUE, 5, 0.2F));
        if (mc.player != null) {
            double d = mc.player.position().distanceTo(h.center);
            if (d < 80) {
                MechShake.kick((float) (3.0 * (1 - d / 80)));
                MechLifeHud.colorFlash(0xF0E8FF, 260);
            }
        }
    }

    /**
     * Сверхновая — «полная свобода» по просьбе пользователя. Идеи: белый купол из «Акиры», световой крест из
     * «Евангелиона», кольцо конденсации и стена пыли как у ядерного взрыва, огненный гриб, шторм красных и синих молний,
     * лучи, разноцветная туманность. Радиус эффектов NOVA_FX = 100 блоков, сильная долгая тряска.
     */
    private static void nova(Minecraft mc, Hole h) {
        Vec3 g = new Vec3(h.center.x, h.groundY, h.center.z);
        int[] cols = {RED, BLUE, VIOLET, WHITE, 0xFF6A20};
        for (int i = 0; i < 160; i++) {
            Vec3 d = rnd(1, 0.7, 1).normalize();
            MechFx.glowPuff(h.center.add(d.scale(R.nextDouble() * 4)), d.scale(0.3 + R.nextDouble() * 1.1), 3.0F + R.nextFloat() * 5.0F,
                    0.08F, 70 + R.nextInt(90), cols[R.nextInt(cols.length)], 0.5F);
        }
        MechFx.dustRingAt(g, 160, 1.4, 2.6, 3.0F, 5.0F, 80, 150);
        MechFx.groundChunks(g, 90);
        MechFx.waveAt(g.add(0, 0.08, 0), (float) NOVA_FX, 50, 6.0F, 0xE0C8A0, true);
        MechFx.waveAt(g.add(0, 0.1, 0), (float) (NOVA_FX * 0.7), 36, 3.0F, 0xA060FF, true);
        MechFx.waveAt(g.add(0, 0.12, 0), 30, 18, 2.0F, 0xFFFFFF, true);
        for (int i = 0; i < 36; i++) BOLTS.add(bolt(h.center, h.center.add(rnd(1, 0.6, 1).normalize().scale(15 + R.nextDouble() * 45)), 16, 2.4, cols[R.nextInt(3)], 10, 0.7F));
        if (mc.player != null) {
            double d = mc.player.position().distanceTo(h.center);
            if (d < 220) {
                float k = (float) Math.max(0.15, 1 - d / 220);
                MechShake.rumble(6.0F * k, 7.0F);
                MechShake.kick(4.0F);
                MechLifeHud.colorFlash(0xFFFFFF, (long) (900 + 1500 * k));
                mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.3F, 0.5F, false);
            }
        }
    }

    /** Каждый тик после взрыва: стена пыли на фронте волны, огненный гриб, шторм молний, догоняющий толчок волны. */
    private static void novaTick(Minecraft mc, Hole h, long n) {
        Vec3 g = new Vec3(h.center.x, h.groundY, h.center.z);
        // Стена пыли катится по земле вместе с ударной волной до 100 блоков.
        if (n < NOVA_FX_TICKS + 6) {
            double k = Math.min(1, n / (double) NOVA_FX_TICKS);
            double r = NOVA_FX * (1 - Math.pow(1 - k, 2));
            for (int i = 0; i < 18; i++) {
                double a = R.nextDouble() * Math.PI * 2;
                Vec3 at = g.add(Math.cos(a) * r, 0.3 + R.nextDouble() * 2.5, Math.sin(a) * r);
                MechFx.dustPuff(at, new Vec3(Math.cos(a) * 0.45, 0.12 + R.nextDouble() * 0.2, Math.sin(a) * 0.45), 3.5F + R.nextFloat() * 3.0F,
                        0.12F, 50 + R.nextInt(40), 0.75F);
            }
            // Волна дошла до игрока — догоняющий толчок.
            if (mc.player != null) {
                double d = Math.hypot(mc.player.getX() - g.x, mc.player.getZ() - g.z);
                double rPrev = NOVA_FX * (1 - Math.pow(1 - Math.min(1, (n - 1) / (double) NOVA_FX_TICKS), 2));
                if (d > rPrev && d <= r) {
                    mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), com.smi7i.sandevistan.ModSounds.BOOM_SONIC, SoundSource.PLAYERS, 1.6F, 0.7F, false);
                    MechShake.kick(4.0F);
                    MechShake.rumble(3.0F, 2.5F);
                    MechLifeHud.colorFlash(0xFFE0B0, 220);
                }
            }
        }
        // Огненный гриб: ствол из пламени и дыма, сверху расползается шапка.
        if (n < 90) {
            double stem = Math.min(34, n * 0.6);
            for (int i = 0; i < 4; i++) {
                double y = R.nextDouble() * stem;
                Vec3 at = g.add(rnd(2.5 - y * 0.03, 0, 2.5 - y * 0.03)).add(0, y, 0);
                MechFx.puffAt(at, new Vec3(0, 0.25 + R.nextDouble() * 0.15, 0).add(rnd(0.05, 0, 0.05)), 2.5F + R.nextFloat() * 2.0F, 0.06F,
                        30 + R.nextInt(30), R.nextBoolean() ? 0xFF7A20 : 0xFFB040, 0.7F, true);
                MechFx.puffAt(at.add(rnd(1.5, 0, 1.5)), new Vec3(0, 0.22, 0).add(rnd(0.06, 0, 0.06)), 3.0F + R.nextFloat() * 2.5F, 0.07F,
                        60 + R.nextInt(40), R.nextBoolean() ? 0x2A2626 : 0x4A3A34, 0.8F, false);
            }
            if (n > 18) {
                double capR = Math.min(26, 4 + (n - 18) * 0.4);
                for (int i = 0; i < 5; i++) {
                    double a = R.nextDouble() * Math.PI * 2, rr = capR * Math.sqrt(R.nextDouble());
                    Vec3 at = g.add(Math.cos(a) * rr, stem + 2 + R.nextDouble() * 5 - rr * 0.15, Math.sin(a) * rr);
                    boolean under = R.nextInt(3) == 0;
                    MechFx.puffAt(at, new Vec3(Math.cos(a) * 0.18, 0.05, Math.sin(a) * 0.18), 4.0F + R.nextFloat() * 3.5F, 0.08F,
                            70 + R.nextInt(50), under ? 0xFF5A20 : (R.nextBoolean() ? 0x3A3232 : 0x5A4A44), under ? 0.6F : 0.85F, under);
                }
            }
        }
        // Шторм молний: по куполу и с неба в землю.
        if (n < 80) {
            int[] cols = {RED, BLUE, VIOLET};
            for (int i = 0; i < 3; i++) {
                Vec3 dome = h.center.add(rnd(1, 0.5, 1).normalize().scale(20 + R.nextDouble() * 50 * Math.min(1, n / 30.0 + 0.3)));
                BOLTS.add(bolt(h.center, dome, 14, 2.0, cols[R.nextInt(3)], 4, 0.5F));
            }
            if (R.nextFloat() < 0.6F) {
                double a = R.nextDouble() * Math.PI * 2, d = 15 + R.nextDouble() * 70;
                Vec3 hit = g.add(Math.cos(a) * d, 0, Math.sin(a) * d);
                int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(hit.x), (int) Math.floor(hit.z));
                hit = new Vec3(hit.x, top, hit.z);
                BOLTS.add(bolt(hit.add(rnd(6, 0, 6)).add(0, 60, 0), hit, 18, 2.6, cols[R.nextInt(3)], 4, 0.6F));
                MechFx.glowPuff(hit.add(0, 0.5, 0), new Vec3(0, 0.08, 0), 2.5F, 0.08F, 14, R.nextBoolean() ? RED : BLUE, 0.8F);
                if (R.nextFloat() < 0.3F) mc.level.playLocalSound(hit.x, hit.y, hit.z, com.smi7i.sandevistan.ModSounds.BOOM_ZAP, SoundSource.PLAYERS, 2.0F, 0.75F + R.nextFloat() * 0.4F, false);
            }
        }
    }
    /** Красная/синяя молния для других способностей («Магнит»): рисуется здесь же. */
    public static void addBolt(Vec3 a, Vec3 b, int rgb) {
        BOLTS.add(bolt(a, b, 12, 1.2, rgb, 5, 0.35F));
    }

    /** Ветвистая молния от a до b: основной ствол + 1–3 тонкие ветки (сразу добавляются в список). */
    private static Bolt bolt(Vec3 a, Vec3 b, int n, double jitter, int rgb, int life, float width) {
        Bolt main = boltRaw(a, b, n, jitter, rgb, life, width);
        if (n >= 6) {
            int branches = 1 + R.nextInt(3);
            double len = a.distanceTo(b);
            for (int i = 0; i < branches; i++) {
                Vec3 from = main.pts[1 + R.nextInt(n - 1)];
                Vec3 dir = b.subtract(a).normalize().add(rnd(0.9, 0.9, 0.9)).normalize();
                BOLTS.add(boltRaw(from, from.add(dir.scale(len * (0.15 + R.nextDouble() * 0.25))), Math.max(3, n / 3), jitter * 0.5, rgb,
                        Math.max(1, life - 1), width * 0.45F));
            }
        }
        return main;
    }

    private static Bolt boltRaw(Vec3 a, Vec3 b, int n, double jitter, int rgb, int life, float width) {
        Bolt bo = new Bolt();
        bo.pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            double j = jitter * Math.sin(Math.PI * k);
            bo.pts[i] = a.lerp(b, k).add(i == 0 || i == n ? Vec3.ZERO : rnd(j, j, j));
        }
        bo.rgb = rgb;
        bo.life = life + R.nextInt(2);
        bo.width = width;
        return bo;
    }

    private static void skyStrike(Minecraft mc, Player p, boolean red) {
        Vec3 to = pod(p, red ? -1 : 1, 1);
        Vec3 from = p.position().add(rnd(7, 0, 7)).add(0, 22 + R.nextDouble() * 8, 0);
        BOLTS.add(bolt(from, to, 16, 1.6, red ? RED : BLUE, 3, 0.3F));
        if (R.nextFloat() < 0.12F && mc.player != null && mc.player.distanceTo(p) < 64) {
            mc.level.playLocalSound(to.x, to.y, to.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 0.3F, 0.8F + R.nextFloat() * 0.4F, false);
        } else if (R.nextFloat() < 0.25F && mc.player != null && mc.player.distanceTo(p) < 48) {
            mc.level.playLocalSound(to.x, to.y, to.z, com.smi7i.sandevistan.ModSounds.BOOM_ZAP, SoundSource.PLAYERS, 0.8F, 0.9F + R.nextFloat() * 0.3F, false);
        }
    }

    private static void podArc(Player p, boolean red) {
        Vec3 o = pod(p, red ? -1 : 1, 1);
        BOLTS.add(bolt(o, o.add(rnd(1, 1, 1).normalize().scale(1.2 + R.nextDouble() * 1.8)), 5, 0.35, red ? RED : BLUE, 2, 0.12F));
    }

    private static void groundStrike(Minecraft mc, Player p) {
        double a = R.nextDouble() * Math.PI * 2, d = 3 + R.nextDouble() * 6;
        Vec3 g = new Vec3(p.getX() + Math.cos(a) * d, p.getY(), p.getZ() + Math.sin(a) * d);
        int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(g.x), (int) Math.floor(g.z));
        g = new Vec3(g.x, top, g.z);
        boolean red = R.nextBoolean();
        BOLTS.add(bolt(pod(p, red ? -1 : 1, 1), g, 10, 1.0, red ? RED : BLUE, 3, 0.22F));
        MechFx.glowPuff(g.add(0, 0.3, 0), new Vec3(0, 0.05, 0), 1.2F, 0.05F, 10, red ? RED : BLUE, 0.8F);
    }

    /** Центр красного кольца на спине капсулы (side -1 — левая/красная, 1 — правая/синяя). */
    private static Vec3 pod(Player p, int side, float partial) {
        double yaw = Math.toRadians(MechMotion.bodyYaw(p, partial));
        Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return p.getPosition(partial).add(0, 2.05, 0).add(r.scale(0.94 * side)).subtract(f.scale(0.3));
    }

    /** Два шара слияния: кружат вокруг центра всё ближе и быстрее (как сливающиеся чёрные дыры). */
    private static Vec3[] orbs(Hole h, float t) {
        float k = Mth.clamp((t - MechSingularity.BEAM) / (float) (MechSingularity.BIRTH - MechSingularity.BEAM), 0, 1);
        double rad = 4.0 * Math.pow(1 - k, 1.4) + 0.05;
        double ang = 2 * Math.PI * (1.5 * k + 3.5 * k * k * k);
        Vec3 o = new Vec3(Math.cos(ang) * rad, 0.4 * Math.sin(ang * 0.5) * rad * 0.3, Math.sin(ang) * rad);
        return new Vec3[]{h.center.add(o), h.center.subtract(o)};
    }

    // ---------- Кинокамера взлёта ----------

    private static void cinematicCamera(Minecraft mc) {
        boolean want = riseCamera(mc, 1) != null;
        if (want && !cameraForced) {
            previousCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            cameraForced = true;
        } else if (!want && cameraForced) {
            if (previousCamera != null) mc.options.setCameraType(previousCamera);
            cameraForced = false;
        }
    }

    /** Во время взлёта своя камера: низко спереди-сбоку, медленно облетает и смотрит снизу вверх на костюм в молниях. */
    public static double[] riseCamera(Minecraft mc, float partial) {
        if (mc.player == null || mc.level == null) return null;
        long s = MechSingularity.startTime(mc.player);
        if (s <= 0 || MechSingularity.fireTime(mc.player) > 0) return null;
        float age = mc.level.getGameTime() - s + partial;
        if (age >= MechSingularity.RISE) return null;
        float k = age / MechSingularity.RISE;
        double yaw = Math.toRadians(MechMotion.bodyYaw(mc.player, partial));
        Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), side = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        double a = Math.toRadians(-35 + 70 * smooth(k));
        Vec3 dir = f.scale(Math.cos(a)).add(side.scale(Math.sin(a)));
        Vec3 p = mc.player.getPosition(partial);
        Vec3 target = p.add(0, 2.0, 0);
        Vec3 cam = p.add(dir.scale(9.5 - 2.0 * k)).add(0, 0.5 + 1.0 * k, 0).add(0, 0.05 * Math.sin(age * 1.7), 0);
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(target, cam, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) cam = hit.getLocation().add(target.subtract(hit.getLocation()).normalize().scale(0.3));
        Vec3 d = target.subtract(cam);
        double cy = Math.toDegrees(Math.atan2(-d.x, d.z));
        double cp = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[]{cam.x, cam.y, cam.z, cy, cp};
    }

    // ---------- Искажение света ----------

    /** Перед отрисовкой эффектов: копия картинки мира → искажённая обратно на экран. */
    public static void lensPass(Minecraft mc, CameraRenderState camera, float partial) {
        if (lensBroken || mc.level == null) return;
        Hole best = null;
        double bestD = Double.MAX_VALUE;
        for (Hole h : HOLES.values()) {
            double t = mc.level.getGameTime() - h.fire + partial;
            boolean on = MechSingularity.radius(t) > 0 || (t >= MechSingularity.NOVA && t < MechSingularity.NOVA + NOVA_FX_TICKS);
            if (!on) continue;
            double d = h.center.distanceTo(camera.pos);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        if (best == null) {
            pushLens(mc, camera, partial);
            return;
        }
        double t = mc.level.getGameTime() - best.fire + partial;
        double rH = MechSingularity.radius(t);
        Vec3 rel = best.center.subtract(camera.pos);
        Vector4f clip = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix)
                .transform(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0F));
        if (clip.w < 0.2F) return; // за спиной
        float cx = clip.x / clip.w * 0.5F + 0.5F, cy = clip.y / clip.w * 0.5F + 0.5F;
        float scale = camera.projectionMatrix.m11() / clip.w * 0.5F;
        float rs = (float) (rH * scale);
        float ringR = 0, ringW = 0, ringK = 0;
        if (t >= MechSingularity.NOVA) {
            double k = Mth.clamp((t - MechSingularity.NOVA) / NOVA_FX_TICKS, 0, 1);
            double shell = NOVA_FX * (1 - Math.pow(1 - k, 3));
            if (bestD > shell + 2) {
                ringR = (float) (shell * scale);
                ringW = Math.max(0.01F, ringR * 0.12F);
                ringK = (float) (0.05 * (1 - k));
            }
        }
        if (rs <= 0 && ringK <= 0) return;
        float collapse = t > MechSingularity.BIRTH + MechSingularity.GROW ? 1.6F : 1.0F;
        runLens(mc, cx, cy, rs, 1.15F * collapse, ringR, ringW, ringK, (float) (t / 20.0), 0.55F * collapse, rs > 0 ? 1.0F : 0.0F);
    }

    /** «Магнит»: шар у рук стягивает пространство (линза без горизонта), при выбросе — кольцо-преломление. */
    private static void pushLens(Minecraft mc, CameraRenderState camera, float partial) {
        double[] pl = MechPushClient.lens(mc, partial);
        if (pl == null) return;
        Vec3 rel = new Vec3(pl[0], pl[1], pl[2]).subtract(camera.pos);
        Vector4f clip = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix)
                .transform(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0F));
        if (clip.w < 0.2F) return;
        float cx = clip.x / clip.w * 0.5F + 0.5F, cy = clip.y / clip.w * 0.5F + 0.5F;
        float scale = camera.projectionMatrix.m11() / clip.w * 0.5F;
        float rs = (float) (pl[3] * scale);
        float ringR = (float) (pl[5] * scale), ringK = (float) pl[6];
        if (rs <= 0 && ringK <= 0) return;
        runLens(mc, cx, cy, rs, (float) pl[4], ringR, Math.max(0.01F, ringR * 0.15F), ringK,
                (mc.level.getGameTime() + partial) / 20.0F, 0.12F, 0.0F);
    }

    /** Копия картинки мира → искажённая обратно на экран (шейдер core/lens.fsh). */
    private static void runLens(Minecraft mc, float cx, float cy, float rs, float strength, float ringR, float ringW, float ringK,
                                float time, float swirl, float horizon) {
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        try {
            if (lensCopy == null || lensCopy.width != main.width || lensCopy.height != main.height) {
                if (lensCopy != null) lensCopy.destroyBuffers();
                lensCopy = new TextureTarget("sandevistan lens", main.width, main.height, false, GpuFormat.RGBA8_UNORM);
            }
            if (lensUbo == null) lensUbo = new MappableRingBuffer(() -> "sandevistan LensInfo", 130, 48);
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            enc.copyTextureToTexture(main.getColorTexture(), lensCopy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            try (GpuBufferSlice.MappedView view = lensUbo.currentBuffer().map(false, true)) {
                Std140Builder.intoBuffer(view.data())
                        .putVec4(cx, cy, rs, strength)
                        .putVec4(ringR, ringW, ringK, main.width / (float) main.height)
                        .putVec4(time, swirl, horizon, 0.0F);
            }
            try (RenderPass pass = enc.createRenderPass(() -> "sandevistan lens", main.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(LENS);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("LensInfo", lensUbo.currentBuffer());
                pass.bindTexture("InSampler", lensCopy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(3, 1, 0, 0);
            }
            lensUbo.rotate();
        } catch (Throwable e) {
            lensBroken = true;
            com.smi7i.sandevistan.Sandevistan.LOGGER.error("Sandevistan: искажение света выключено", e);
        }
    }

    // ---------- Отрисовка в мире ----------

    /** Тёмное: горизонт событий (чёрный диск) и мягкая тень вокруг. Рисуется раньше светящегося. */
    public static void submitDark(SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = camera.pos;
        for (Hole h : HOLES.values()) {
            double t = mc.level.getGameTime() - h.fire + partial;
            double rH = MechSingularity.radius(t);
            if (rH <= 0) continue;
            Vec3 c = h.center.subtract(cam);
            Vec3[] uv = billboardAxes(c);
            float r = (float) rH;
            collector.submitCustomGeometry(new PoseStack(), RenderTypes.textBackground(), (pose, buffer) -> {
                int seg = 64;
                for (int i = 0; i < seg; i++) {
                    double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
                    // Мягкая тень вокруг горизонта.
                    ringSeg(pose, buffer, c, uv, r * 0.99F, r * 2.3F, a0, a1, 0x8C000000, 0x00000000);
                    // Сам горизонт — абсолютно чёрный.
                    ringSeg(pose, buffer, c, uv, 0, r, a0, a1, 0xFF000000, 0xFF000000);
                }
            });
        }
    }

    private static void ringSeg(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer b, Vec3 c, Vec3[] uv, float r0, float r1,
                                double a0, double a1, int cIn, int cOut) {
        Vec3 d0 = uv[0].scale(Math.cos(a0)).add(uv[1].scale(Math.sin(a0))), d1 = uv[0].scale(Math.cos(a1)).add(uv[1].scale(Math.sin(a1)));
        Vec3[] v = {c.add(d0.scale(r0)), c.add(d0.scale(r1)), c.add(d1.scale(r1)), c.add(d1.scale(r0))};
        int[] col = {cIn, cOut, cOut, cIn};
        int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
        for (int k : order) b.addVertex(pose, (float) v[k].x, (float) v[k].y, (float) v[k].z).setColor(col[k]).setLight(FULL_BRIGHT);
    }

    /** Светящееся (сложение света): молнии, слияние, аккреционный диск, кольца, искры, сверхновая, прицел. */
    public static void submitGlow(SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = camera.pos;
        List<float[]> q = new ArrayList<>();
        float time = mc.level.getGameTime() + partial;
        for (Bolt b : BOLTS) {
            float k = 1 - b.age / b.life;
            for (int i = 1; i < b.pts.length; i++) {
                Vec3 a = b.pts[i - 1].subtract(cam), e = b.pts[i].subtract(cam);
                ribbon(q, a, e, b.width * 2.6F * k, scale(b.rgb, 0.45F * k));
                ribbon(q, a, e, b.width * 0.7F * k, scale(mix(b.rgb, WHITE, 0.6F), 0.95F * k));
            }
        }
        // Столб из неба в костюм: белое ядро, вокруг вьются красная и синяя спирали.
        for (Pillar pl : PILLARS) {
            float k = Mth.clamp((pl.age + partial) / pl.life, 0, 1), fade = (1 - k) * (1 - k);
            Vec3 b0 = pl.bottom.subtract(cam), t0 = pl.top.subtract(cam);
            ribbon(q, b0, t0, 4.5F * fade, scale(VIOLET, 0.35F * fade));
            ribbon(q, b0, t0, 1.1F * fade, scale(WHITE, 0.95F * fade));
            for (int s = 0; s < 2; s++) {
                Vec3 prevP = null;
                for (int i = 0; i <= 48; i++) {
                    double y = i / 48.0 * 90, a = time * 0.6 + y * 0.22 + s * Math.PI, rr = 1.8 * (1 - y / 130);
                    Vec3 pt = b0.add(Math.cos(a) * rr, y, Math.sin(a) * rr);
                    if (prevP != null) ribbon(q, prevP, pt, 0.55F * fade, scale(s == 0 ? RED : BLUE, 0.95F * fade));
                    prevP = pt;
                }
            }
        }
        // Прицел во время наведения (свой игрок): куда ударит.
        Player me = mc.player;
        if (me != null && MechSingularity.isActive(me) && MechSingularity.fireTime(me) <= 0
                && mc.level.getGameTime() - MechSingularity.startTime(me) >= MechSingularity.RISE) {
            HitResult hit = me.pick(MechSingularity.AIM_RANGE, partial, false);
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 g = hit.getLocation().subtract(cam).add(0, 0.06, 0);
                Vec3 ex = new Vec3(1, 0, 0), ez = new Vec3(0, 0, 1);
                float pulse = 0.7F + 0.3F * Mth.sin(time * 0.4F);
                for (int i = 0; i < 16; i++) {
                    double a0 = time * 0.05 + 2 * Math.PI * i / 16;
                    planeArc(q, g, ex, ez, 4.6F, 4.9F, a0, a0 + 0.25, scale(RED, 0.6F * pulse));
                    double b0 = -time * 0.07 + 2 * Math.PI * i / 16;
                    planeArc(q, g, ex, ez, 3.0F, 3.2F, b0, b0 + 0.22, scale(BLUE, 0.6F * pulse));
                }
                planeArc(q, g, ex, ez, 0, 0.6F, 0, Math.PI * 2, scale(WHITE, 0.6F * pulse));
                planeArc(q, g, ex, ez, (float) MechSingularity.MAX_RADIUS - 0.08F, (float) MechSingularity.MAX_RADIUS, 0, Math.PI * 2, scale(VIOLET, 0.35F));
                Vec3 top = g.add(0, 30, 0);
                ribbon(q, g, top, 0.5F, scale(VIOLET, 0.35F * pulse));
                ribbon(q, g, top, 0.12F, scale(WHITE, 0.7F * pulse));
                Vec3 hc = g.add(0, MechSingularity.HOLE_LIFT, 0);
                billboardRing(q, hc, 1.0F, 0.06F, scale(WHITE, 0.6F * pulse));
            }
        }
        for (Hole h : HOLES.values()) {
            double t = mc.level.getGameTime() - h.fire + partial;
            Vec3 c = h.center.subtract(cam);
            // Слияние: два шара (красный и синий) с хвостами, кружат всё быстрее.
            if (t >= MechSingularity.BEAM && t < MechSingularity.BIRTH + 1) {
                for (int s = 0; s < 2; s++) {
                    int col = s == 0 ? RED : BLUE;
                    for (int j = 12; j >= 1; j--) {
                        Vec3 p0 = orbs(h, (float) (t - j * 0.6))[s].subtract(cam), p1 = orbs(h, (float) (t - (j - 1) * 0.6))[s].subtract(cam);
                        ribbon(q, p0, p1, 0.35F * (1 - j / 13F), scale(col, 0.5F * (1 - j / 13F)));
                    }
                    Vec3 o = orbs(h, (float) t)[s].subtract(cam);
                    billboardDisk(q, o, 1.1F, scale(col, 0.5F));
                    billboardDisk(q, o, 0.45F, scale(mix(col, WHITE, 0.7F), 0.95F));
                }
                float k = (float) ((t - MechSingularity.BEAM) / (MechSingularity.BIRTH - MechSingularity.BEAM));
                billboardDisk(q, c, 0.4F + 2.0F * k * k, scale(VIOLET, 0.35F * k));
            }
            double rH = MechSingularity.radius(t);
            if (rH > 0) holeGlow(q, h, c, (float) rH, t, time);
            if (t >= MechSingularity.NOVA) novaGlow(q, h, c, cam, t);
        }
        for (Spark s : SPARKS) {
            double r = Mth.lerp(partial, s.pr, s.r), a = Mth.lerp(partial, s.pa, s.a), y = Mth.lerp(partial, s.py, s.y);
            Vec3 p = s.h.center.add(diskPoint(a, r)).add(0, y, 0).subtract(cam);
            billboardDisk(q, p, 0.09F, scale(s.rgb, 0.9F));
        }
        if (q.isEmpty()) return;
        collector.submitCustomGeometry(new PoseStack(), com.smi7i.sandevistan.client.GlowRender.type(), (pose, buffer) -> {
            for (float[] f : q) {
                int c0 = Float.floatToRawIntBits(f[12]), c1 = Float.floatToRawIntBits(f[13]);
                int[] colors = {c0, c0, c1, c1};
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) com.smi7i.sandevistan.client.GlowRender.vertex(buffer, pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2], colors[k]);
            }
        });
    }

    /** Наклон диска (как в «Интерстелларе» — почти ребром к камере, но под небольшим углом). */
    private static final double TILT = Math.toRadians(12);

    private static Vec3 diskPoint(double a, double r) {
        return new Vec3(Math.cos(a) * r, -Math.sin(TILT) * Math.sin(a) * r, Math.cos(TILT) * Math.sin(a) * r);
    }

    private static void holeGlow(List<float[]> q, Hole h, Vec3 c, float rH, double t, float time) {
        double collapse = Mth.clamp((t - MechSingularity.BIRTH - MechSingularity.GROW) / MechSingularity.COLLAPSE, 0, 1);
        float boost = (float) (1 + 2.2 * collapse);
        Vec3 toCam = c.scale(-1).normalize();
        // Аккреционный диск: кольца от горячего белого у центра до тёмно-красного снаружи; вращается (внутри быстрее),
        // сторона, летящая к камере, ярче (доплер). Часть за горизонтом скрыта.
        double rIn = rH * (1.55 - 0.3 * collapse), rOut = rH * 4.2;
        int rings = 12, seg = 84;
        for (int i = 0; i < rings; i++) {
            double r0 = rIn + (rOut - rIn) * i / rings, r1 = rIn + (rOut - rIn) * (i + 1) / rings, rm = (r0 + r1) / 2;
            double temp = Math.pow(rIn / rm, 1.7);
            float g = (float) ((rm - rIn) / (rOut - rIn));
            int col = g < 0.15F ? mix(0xFFF6E8, 0xFFD27A, g / 0.15F) : g < 0.5F ? mix(0xFFD27A, 0xFF7A20, (g - 0.15F) / 0.35F) : mix(0xFF7A20, 0x701008, (g - 0.5F) / 0.5F);
            double omega = 0.9 / Math.pow(rm / rH, 1.5) * (1 + 3 * collapse);
            for (int j = 0; j < seg; j++) {
                double a0 = 2 * Math.PI * j / seg, a1 = 2 * Math.PI * (j + 1) / seg, am = (a0 + a1) / 2;
                Vec3 pm = diskPoint(am, rm);
                double along = pm.dot(toCam);
                Vec3 perp = pm.subtract(toCam.scale(along));
                if (along < 0 && perp.length() < rH * 1.02) continue;
                Vec3 tang = diskPoint(am + Math.PI / 2, 1);
                double doppler = 1 + 0.65 * tang.dot(toCam);
                double phase = am - time * 0.05 * omega;
                double streak = 0.55 + 0.25 * Math.sin(phase * 7 + rm * 2.3) + 0.2 * Math.sin(phase * 19 + rm * 5.1 + h.seed);
                float k = (float) Math.min(1.6, 0.85 * temp * streak * doppler * boost);
                Vec3 v0 = c.add(diskPoint(a0, r0)), v1 = c.add(diskPoint(a0, r1)), v2 = c.add(diskPoint(a1, r1)), v3 = c.add(diskPoint(a1, r0));
                int cc = HoloUi.argb(1, scale(col, Math.min(1, k)));
                q.add(new float[]{(float) v0.x, (float) v0.y, (float) v0.z, (float) v1.x, (float) v1.y, (float) v1.z,
                        (float) v2.x, (float) v2.y, (float) v2.z, (float) v3.x, (float) v3.y, (float) v3.z,
                        Float.intBitsToFloat(cc), Float.intBitsToFloat(cc)});
            }
        }
        // Линзированное изображение дальней части диска: огибает горизонт сверху и снизу (кольцо к камере).
        Vec3[] uv = billboardAxes(c);
        Vec3 front = c.add(toCam.scale(rH * 0.3));
        int segs = 96;
        for (int j = 0; j < segs; j++) {
            double a0 = 2 * Math.PI * j / segs, a1 = 2 * Math.PI * (j + 1) / segs, am = (a0 + a1) / 2;
            double s = Math.sin(am);
            double arch = (s > 0 ? 1.0 : 0.65) * (0.22 + 0.78 * Math.pow(Math.abs(s), 2.5));
            double streak = 0.7 + 0.3 * Math.sin(am * 11 - time * 0.12 * boost + h.seed);
            for (int band = 0; band < 3; band++) {
                float r0 = rH * (1.05F + band * 0.2F), r1 = rH * (1.25F + band * 0.2F);
                float k = (float) (arch * streak * (1 - band * 0.32) * boost);
                int col = band == 0 ? 0xFFE6B0 : band == 1 ? 0xFFB050 : 0xFF6A20;
                planeArc(q, front, uv[0], uv[1], r0, r1, a0, a1, scale(col, Math.min(1, 0.85F * k)));
            }
        }
        // Фотонное кольцо — тонкое и очень яркое по самому краю горизонта.
        planeArc(q, front, uv[0], uv[1], rH * 0.99F, rH * 1.05F, 0, Math.PI * 2, scale(0xFFF0D8, Math.min(1, 0.95F * boost)));
        // Струи из полюсов (как у квазара): голубые, пульсируют.
        Vec3 axis = new Vec3(0, Math.cos(TILT), Math.sin(TILT));
        float jet = (float) (0.75 + 0.25 * Math.sin(time * 0.9 + h.seed));
        for (int s = -1; s <= 1; s += 2) {
            Vec3 a0 = c.add(axis.scale(s * rH * 1.05)), a1 = c.add(axis.scale(s * rH * (6.5 + 1.5 * jet)));
            ribbon(q, a0, a1, rH * 0.9F, scale(0x5FA8FF, 0.25F * jet * boost));
            ribbon(q, a0, a1, rH * 0.35F, scale(0x9AD8FF, 0.55F * jet * boost));
            ribbon(q, a0, a0.lerp(a1, 0.6), rH * 0.12F, scale(WHITE, Math.min(1, 0.9F * jet * boost)));
        }
        // Гравитационная рябь: кольца расходятся от горизонта.
        for (int k = 0; k < 3; k++) {
            double ph = ((t + k * 10) % 30) / 30.0;
            billboardRing(q, front, (float) (rH * (1.3 + 5 * ph)), (float) (0.1 + 0.25 * (1 - ph)) * rH * 0.4F, scale(0x8070FF, (float) (0.22 * (1 - ph))));
        }
        // Слабое свечение вокруг.
        planeArc(q, front, uv[0], uv[1], rH * 1.6F, rH * 3.2F, 0, Math.PI * 2, scale(0xFF7A30, 0.06F * boost));
        if (collapse > 0) billboardDisk(q, front, rH * 0.5F * (float) collapse, scale(WHITE, (float) collapse));
    }

    private static void novaGlow(List<float[]> q, Hole h, Vec3 c, Vec3 cam, double t) {
        double n = t - MechSingularity.NOVA;
        double k = Mth.clamp(n / NOVA_FX_TICKS, 0, 1);
        double late = Mth.clamp(n / (MechSingularity.TAIL + 60.0), 0, 1);
        float fade = (float) Math.pow(1 - k, 1.2);
        Vec3[] uv = billboardAxes(c);
        Vec3 g = new Vec3(h.center.x, h.groundY, h.center.z).subtract(cam);
        // 1. Белый купол («Акира»): за пол-секунды раздувается до 34 блоков, светится изнутри, потом гаснет.
        double dk = Mth.clamp(n / 12.0, 0, 1);
        float domeR = (float) (3 + 31 * (1 - Math.pow(1 - dk, 3)));
        float domeA = (float) (1 - Mth.clamp((n - 10) / 30.0, 0, 1));
        if (domeA > 0) {
            billboardDisk(q, c, domeR, scale(0xF4F8FF, 0.85F * domeA));
            billboardRing(q, c, domeR, 2.5F + domeR * 0.08F, scale(0xA8E8FF, domeA));
            billboardDisk(q, c, domeR * 1.6F, scale(0x8070FF, 0.25F * domeA));
        }
        // 2. Ядро вспышки.
        billboardDisk(q, c, (float) (3 + 18 * Math.sqrt(k)) * fade, scale(WHITE, fade));
        // 3. Оболочки — до 100 блоков: белая, голубая, фиолетовая, красная.
        int[] cols = {WHITE, 0x6FE8FF, VIOLET, RED};
        for (int i = 0; i < 4; i++) {
            double ki = Mth.clamp(k - i * 0.07, 0, 1);
            if (ki <= 0) continue;
            float rr = (float) (NOVA_FX * (1 - Math.pow(1 - ki, 3)));
            billboardRing(q, c, rr, (float) (2 + 6 * ki), scale(cols[i], 0.7F * (float) Math.pow(1 - ki, 1.3)));
        }
        // 4. Световой крест («Евангелион»): столб до неба и перекладина, красное сияние вокруг белой сердцевины, мерцает.
        double ca = Mth.clamp((n - 3) / 6.0, 0, 1) * (1 - Mth.clamp((n - 45) / 45.0, 0, 1));
        if (ca > 0) {
            float flick = (float) (ca * (0.85 + 0.15 * Math.sin(n * 2.3)));
            Vec3 top = g.add(0, 170, 0);
            ribbon(q, g, top, 16F * flick, scale(RED, 0.3F * flick));
            ribbon(q, g, top, 6F * flick, scale(0xFF6A80, 0.6F * flick));
            ribbon(q, g, top, 2.2F * flick, scale(WHITE, 0.95F * flick));
            Vec3 bar = c.add(0, 26, 0);
            Vec3 l = bar.add(uv[0].scale(-70)), r = bar.add(uv[0].scale(70));
            ribbon(q, l, r, 12F * flick, scale(RED, 0.28F * flick));
            ribbon(q, l, r, 4.5F * flick, scale(0xFF6A80, 0.55F * flick));
            ribbon(q, l, r, 1.6F * flick, scale(WHITE, 0.9F * flick));
        }
        // 5. Кольцо конденсации — белый диск-кольцо высоко над взрывом.
        double ck = Mth.clamp((n - 4) / 60.0, 0, 1);
        if (ck > 0 && ck < 1) {
            float rc = (float) (10 + 80 * (1 - Math.pow(1 - ck, 2)));
            planeArc(q, g.add(0, 22, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), rc - 3.5F * (float) (1 - ck) - 0.5F, rc, 0, Math.PI * 2,
                    scale(0xF0F4FF, 0.4F * (float) (1 - ck)));
        }
        // 6. Лучи во все стороны — до 130 блоков.
        Random rays = new Random(h.seed);
        for (int i = 0; i < 48; i++) {
            Vec3 d = new Vec3(rays.nextDouble() - 0.5, (rays.nextDouble() - 0.35) * 0.8, rays.nextDouble() - 0.5).normalize();
            float len = (float) (15 + 115 * Math.sqrt(k));
            int col = i % 4 == 0 ? 0x9AD8FF : i % 4 == 1 ? 0xFF8090 : WHITE;
            ribbon(q, c, c.add(d.scale(len)), (float) (1.6 * (1 - k) + 0.15), scale(col, 0.5F * fade));
        }
        // 7. Остаток: туманность медленно тает.
        float rem = (float) (1 - late);
        billboardDisk(q, c, 30, scale(VIOLET, 0.12F * rem));
        billboardDisk(q, c, 16, scale(0x6FE8FF, 0.1F * rem));
        billboardDisk(q, c, 8, scale(0xFFB070, 0.12F * rem));
    }
    // ---------- Геометрия для сложения света ----------

    private static Vec3[] billboardAxes(Vec3 c) {
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(toCam).normalize();
        return new Vec3[]{u, v};
    }

    private static void billboardDisk(List<float[]> q, Vec3 c, float r, int rgb) {
        Vec3[] uv = billboardAxes(c);
        planeArc(q, c, uv[0], uv[1], 0, r, 0, Math.PI * 2, rgb);
    }

    private static void billboardRing(List<float[]> q, Vec3 c, float r, float w, int rgb) {
        Vec3[] uv = billboardAxes(c);
        planeArc(q, c, uv[0], uv[1], Math.max(0, r - w / 2), r + w / 2, 0, Math.PI * 2, rgb);
    }

    private static void planeArc(List<float[]> out, Vec3 c, Vec3 u, Vec3 v, float r0, float r1, double a0, double a1, int rgb) {
        int seg = Math.max(1, (int) Math.ceil((a1 - a0) / (Math.PI / 24)));
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

    /** Лента от a до b, повёрнутая к камере (камера в начале координат). */
    private static void ribbon(List<float[]> out, Vec3 a, Vec3 b, float width, int rgb) {
        Vec3 dir = b.subtract(a);
        Vec3 side = dir.cross(a.add(b).scale(0.5));
        if (side.lengthSqr() < 1.0E-10) return;
        side = side.normalize().scale(width / 2);
        int col = HoloUi.argb(1, rgb);
        out.add(new float[]{
                (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z),
                (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z),
                (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z),
                (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z),
                Float.intBitsToFloat(col), Float.intBitsToFloat(col)});
    }

    // ---------- Экран ----------

    /** Взлёт — полосы кино и название; наведение — отсчёт 5 секунд и подсказка. */
    public static void renderHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        novaOverlay(g, mc);
        if (!MechSingularity.isActive(mc.player)) return;
        long now = System.currentTimeMillis();
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        float age = mc.level.getGameTime() - MechSingularity.startTime(mc.player);
        long f = MechSingularity.fireTime(mc.player);
        if (f <= 0 && age < MechSingularity.RISE) {
            int bar = Math.round(h * 0.11F);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
            float k = Mth.clamp((age - 10) / 15.0F, 0, 1) * (1 - Mth.clamp((age - (MechSingularity.RISE - 8)) / 8.0F, 0, 1));
            if (k > 0) {
                g.pose().pushMatrix();
                g.pose().translate(w / 2.0F, h - bar - 34);
                g.pose().scale(2.2F, 2.2F);
                float fl = HoloUi.flicker(now, 77);
                HoloUi.centeredText(g, mc.font, "◆ СИНГУЛЯРНОСТЬ ◆", 0, 0, (now / 120) % 2 == 0 ? 0xFF4A5A : 0x6FC0FF, k * fl);
                g.pose().popMatrix();
                HoloUi.centeredText(g, mc.font, "синхронизация полюсов: красный ⟷ синий", w / 2, h - bar - 12, 0xC8C8FF, 0.8F * k);
            }
            return;
        }
        if (f <= 0) {
            float left = (MechSingularity.RISE + MechSingularity.AIM - age) / 20.0F;
            float fill = Mth.clamp(left / (MechSingularity.AIM / 20.0F), 0, 1);
            int pw = 200, x0 = (w - pw) / 2, y0 = h / 2 + 46;
            float fl = HoloUi.flicker(now, 53);
            int col = left < 1.5F && (now / 120) % 2 == 0 ? 0xFF3048 : 0xC080FF;
            HoloUi.centeredText(g, mc.font, String.format(Locale.ROOT, "НАВЕДЕНИЕ  %.1f с", Math.max(0, left)), w / 2, y0, col, fl);
            g.fill(x0, y0 + 12, x0 + pw, y0 + 15, HoloUi.argb(0.4F, 0x100820));
            g.fill(x0, y0 + 12, x0 + Math.round(pw * fill), y0 + 15, HoloUi.argb(0.9F, col));
            HoloUi.glow(g, x0, y0 + 12, x0 + Math.round(pw * fill), y0 + 15, col, 0.3F, 2);
            HoloUi.centeredText(g, mc.font, "[" + HoloUi.key(KEY) + "] — огонь", w / 2, y0 + 20, 0xD8D0FF, 0.75F * fl);
            // Прицел: красное и синее кольца вращаются навстречу.
            int cx = w / 2, cy = h / 2;
            for (int i = 0; i < 24; i++) {
                double a = now / 500.0 + 2 * Math.PI * i / 24, b = -now / 380.0 + 2 * Math.PI * i / 24;
                if (i % 3 != 2) {
                    int x = cx + (int) Math.round(Math.cos(a) * 16), y = cy + (int) Math.round(Math.sin(a) * 16);
                    g.fill(x, y, x + 1, y + 1, HoloUi.argb(0.85F, 0xFF3048));
                    int x2 = cx + (int) Math.round(Math.cos(b) * 11), y2 = cy + (int) Math.round(Math.sin(b) * 11);
                    g.fill(x2, y2, x2 + 1, y2 + 1, HoloUi.argb(0.85F, 0x3FA8FF));
                }
            }
        }
    }

    /** Перед взрывом мир «вдыхает» — темнеет по краям; после — тёплый засвет и фиолетовая кайма гаснут несколько секунд. */
    private static void novaOverlay(GuiGraphicsExtractor g, Minecraft mc) {
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        for (Hole hole : HOLES.values()) {
            double n = mc.level.getGameTime() - hole.fire - MechSingularity.NOVA;
            double d = mc.player.position().distanceTo(hole.center);
            if (d > 220 || n < -10 || n > 140) continue;
            float near = (float) Math.max(0.3, 1 - d / 220);
            if (n < 0) {
                float k = (float) ((n + 10) / 10.0) * near;
                for (int i = 0; i < 12; i++) {
                    int m = i * Math.max(4, w / 60);
                    int c = HoloUi.argb(0.09F * k, 0x000000);
                    g.fill(0, m, w, m + Math.max(4, w / 60), c);
                    g.fill(0, h - m - Math.max(4, w / 60), w, h - m, c);
                }
                g.fill(0, 0, w, h, HoloUi.argb(0.35F * k, 0x05000A));
            } else {
                float k = (float) (1 - n / 140.0) * near;
                g.fill(0, 0, w, h, HoloUi.argb(0.22F * k * k, 0xFF9A50));
                int band = Math.max(6, h / 10);
                g.fill(0, 0, w, band, HoloUi.argb(0.25F * k, 0x7030C0));
                g.fill(0, h - band, w, h, HoloUi.argb(0.25F * k, 0x7030C0));
            }
        }
    }

    // ---------- Мелочи ----------

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static Vec3 rnd(double x, double y, double z) {
        return new Vec3((R.nextDouble() - 0.5) * 2 * x, (R.nextDouble() - 0.5) * 2 * y, (R.nextDouble() - 0.5) * 2 * z);
    }

    private static int scale(int rgb, float k) {
        k = Math.max(0, Math.min(1, k));
        int r = Math.round(((rgb >> 16) & 0xFF) * k), gg = Math.round(((rgb >> 8) & 0xFF) * k), b = Math.round((rgb & 0xFF) * k);
        return (r << 16) | (gg << 8) | b;
    }

    private static int mix(int a, int b, float k) {
        k = Math.max(0, Math.min(1, k));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int gg = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (gg << 8) | bl;
    }
}
