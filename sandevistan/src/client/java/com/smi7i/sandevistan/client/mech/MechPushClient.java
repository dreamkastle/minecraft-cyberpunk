package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.client.GlowRender;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.client.IrisCompat;
import com.smi7i.sandevistan.mech.MechPush;
import com.smi7i.sandevistan.mech.PushPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * «Магнит» на клиенте (клавиша X): красная аура у рук, кольца у ладоней, большие белые дуги, стягивающиеся к костюму,
 * шар из притянутого, искажение пространства (шейдер линзы), потом выброс — тёмно-красная вспышка, молнии вперёд,
 * огонь, пыль V-шлейфами, ударная дуга и тряска. Плюс голографические волны у плеч.
 */
public final class MechPushClient {
    public static KeyMapping KEY;
    static int cooldown;
    private static final Random R = new Random();
    private static final int RED = 0xFF2A3A, ORANGE = 0xFF6A20, WHITE = 0xFFFFFF;
    private static final Map<Integer, Long> SEEN = new HashMap<>();

    private MechPushClient() {}

    public static void init(KeyMapping.Category category) {
        KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_push", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
        ClientTickEvents.END_CLIENT_TICK.register(MechPushClient::tick);
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!IrisCompat.shadersActive() || !hasAny()) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submit(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
    }

    public static boolean hasAny() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        for (Player p : mc.level.players()) if (MechPush.isActive(p)) return true;
        return false;
    }

    /** Возраст способности игрока (тики, с долей кадра), -1 — нет. */
    public static float age(Player p, float partial) {
        long s = MechPush.startTime(p);
        return s > 0 ? p.level().getGameTime() - s + partial : -1;
    }

    private static Vec3 hand(Player p, int side, float partial) {
        double yaw = Math.toRadians(MechMotion.bodyYaw(p, partial));
        Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return p.getPosition(partial).add(f.scale(2.9)).add(r.scale(0.75 * side)).add(0, 2.3, 0);
    }

    private static void tick(Minecraft mc) {
        if (cooldown > 0) cooldown--;
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        while (KEY.consumeClick()) {
            if (ModAttachments.hasMech(mc.player) && cooldown <= 0 && !MechClient.localAttacking() && !MechPush.isActive(mc.player)) {
                ClientPlayNetworking.send(new PushPayload());
            }
        }
        for (Player p : mc.level.players()) {
            long s = MechPush.startTime(p);
            if (s <= 0) {
                SEEN.remove(p.getId());
                continue;
            }
            Long prevStart = SEEN.put(p.getId(), s);
            long age = mc.level.getGameTime() - s;
            if (prevStart == null || prevStart != s) {
                MechFx.shoulderWaves(p, 4);
                if (p == mc.player) MechLifeHud.colorFlash(0xFF3048, 250);
            }
            Vec3 ball = MechPush.ball(p), f = MechPush.forward(p);
            if (age >= MechPush.RAISE && age < MechPush.BLAST) {
                float k = (age - MechPush.RAISE) / (float) (MechPush.BLAST - MechPush.RAISE);
                // Красная аура у ладоней.
                for (int side = -1; side <= 1; side += 2) {
                    Vec3 h = hand(p, side, 1);
                    MechFx.glowPuff(h.add(rnd(0.3, 0.3, 0.3)), h.subtract(p.position().add(0, 2.3, 0)).normalize().scale(0.04), 0.5F + R.nextFloat() * 0.5F,
                            0.02F, 8 + R.nextInt(6), R.nextInt(3) == 0 ? ORANGE : RED, 0.8F);
                }
                // Пыль и красные искры затягивает с земли в шар.
                for (int i = 0; i < 3; i++) {
                    double along = 3 + R.nextDouble() * 14, across = (R.nextDouble() * 2 - 1) * along * 0.7;
                    Vec3 g = p.position().add(f.scale(along)).add(-f.z * across, 0, f.x * across);
                    int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(g.x), (int) Math.floor(g.z));
                    g = new Vec3(g.x, top + 0.3, g.z);
                    Vec3 v = ball.subtract(g).normalize().scale(0.45 + 0.4 * k);
                    if (R.nextBoolean()) MechFx.dustPuff(g, v, 0.8F + R.nextFloat() * 0.6F, 0.02F, 24, 0.6F);
                    else MechFx.glowPuff(g, v, 0.18F + R.nextFloat() * 0.15F, -0.003F, 22, R.nextBoolean() ? RED : ORANGE, 0.95F);
                }
                if (age % 25 == 0) MechFx.shoulderWaves(p, 2);
            }
            if (age == MechPush.BLAST) blast(mc, p, ball, f);
            // Дым и тлеющие искры после выброса.
            if (age > MechPush.BLAST && age < MechPush.BLAST + 20) {
                MechFx.puffAt(ball.add(rnd(1, 0.6, 1)), f.scale(0.12).add(0, 0.05, 0), 1.6F, 0.05F, 40, 0x3A2A2A, 0.7F, false);
            }
        }
    }

    private static void blast(Minecraft mc, Player p, Vec3 ball, Vec3 f) {
        MechFx.shoulderWaves(p, 3);
        // Огненный выброс вперёд.
        for (int i = 0; i < 70; i++) {
            Vec3 d = f.add(rnd(0.55, 0.35, 0.55)).normalize();
            MechFx.glowPuff(ball.add(rnd(0.6, 0.6, 0.6)), d.scale(0.4 + R.nextDouble() * 0.9), 0.9F + R.nextFloat() * 1.6F, 0.06F,
                    14 + R.nextInt(18), R.nextInt(3) == 0 ? 0xFFD060 : R.nextBoolean() ? ORANGE : RED, 0.85F);
        }
        // Пыль двумя V-шлейфами по земле.
        double gy = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(p.getX()), (int) Math.floor(p.getZ()));
        for (int side = -1; side <= 1; side += 2) {
            double a = Math.toRadians(24 * side);
            Vec3 dir = new Vec3(f.x * Math.cos(a) - f.z * Math.sin(a), 0, f.x * Math.sin(a) + f.z * Math.cos(a));
            for (int i = 0; i < 26; i++) {
                double along = 2 + i * 0.7;
                Vec3 at = new Vec3(p.getX(), gy + 0.3, p.getZ()).add(dir.scale(along));
                MechFx.dustPuff(at, dir.scale(0.25 + R.nextDouble() * 0.2).add(0, 0.06, 0), 1.6F + R.nextFloat() * 1.4F, 0.06F, 50 + R.nextInt(30), 0.8F);
            }
        }
        MechFx.waveAt(ball, 9, 10, 0.8F, 0xFF4050, false);
        MechFx.waveAt(new Vec3(p.getX(), gy + 0.08, p.getZ()).add(f.scale(4)), 22, 18, 2.0F, 0xFF5040, true);
        for (int i = 0; i < 14; i++) {
            Vec3 end = ball.add(f.add(rnd(0.6, 0.3, 0.6)).normalize().scale(8 + R.nextDouble() * 12));
            MechSingularityClient.addBolt(ball, end, R.nextInt(3) == 0 ? 0xFF8090 : RED);
        }
        if (mc.player != null) {
            double d = mc.player.distanceTo(p);
            if (d < 48) MechShake.kick((float) (3.2 * (1 - d / 48)));
            if (p == mc.player) {
                MechShake.rumble(1.4F, 1.2F);
                MechLifeHud.colorFlash(0x500008, 450);
            }
        }
    }

    // ---------- Отрисовка ----------

    public static void submit(SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = camera.pos;
        List<float[]> q = new ArrayList<>();
        for (Player p : mc.level.players()) {
            float age = age(p, partial);
            if (age < 0) continue;
            double yaw = Math.toRadians(MechMotion.bodyYaw(p, partial));
            Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = new Vec3(0, 1, 0);
            Vec3 base = p.getPosition(partial).subtract(cam);
            Vec3 ball = MechPush.ball(p).subtract(cam);
            float raise = Mth.clamp((age - 3) / 8.0F, 0, 1);
            boolean pulling = age < MechPush.BLAST;
            float k = Mth.clamp((age - MechPush.RAISE) / (MechPush.BLAST - MechPush.RAISE), 0, 1);
            if (pulling && raise > 0) {
                // Кольца у ладоней: несколько пунктирных колец, крутятся в разные стороны.
                for (int side = -1; side <= 1; side += 2) {
                    Vec3 h = hand(p, side, partial).subtract(cam);
                    for (int ring = 0; ring < 3; ring++) {
                        float rr = (0.45F + ring * 0.38F) * raise * (1 + 0.08F * Mth.sin(age * 0.6F + ring));
                        double rot = age * (0.15 + ring * 0.07) * (ring % 2 == 0 ? 1 : -1);
                        for (int sgm = 0; sgm < 10; sgm++) {
                            if (sgm % 3 == 2) continue;
                            double a0 = rot + 2 * Math.PI * sgm / 10, a1 = a0 + 2 * Math.PI / 10 * 0.8;
                            arc(q, h.add(f.scale(0.2 + ring * 0.15)), r, up, rr - 0.05F, rr + 0.05F, a0, a1, scale(RED, 0.9F * raise));
                        }
                    }
                    arc(q, h, r, up, 0, 0.3F * raise, 0, Math.PI * 2, scale(0xFFB0A0, 0.8F * raise));
                }
                // Большие белые полудуги вокруг костюма — стягиваются к нему («магнит»).
                for (int i = 0; i < 3; i++) {
                    float ph = ((age * 0.07F) + i / 3.0F) % 1.0F;
                    float rad = 10 - 7 * ph;
                    float a = (1 - ph) * ph * 4 * 0.55F * raise;
                    arc(q, base.add(0, 1.2, 0).add(f.scale(1.5)), r, up, rad - 0.25F, rad, 0, Math.PI, scale(0xF0F0FF, a));
                    arc(q, base.add(0, 1.2, 0).add(f.scale(1.5)), r, up, rad - 0.9F, rad - 0.25F, 0, Math.PI, scale(0xFF8090, a * 0.35F));
                }
                // Шар из притянутого: растёт, пульсирует; лучи от ладоней к шару.
                float br = 0.3F + 1.4F * k;
                float pulse = 0.8F + 0.2F * Mth.sin(age * 1.3F);
                disk(q, ball, br * 2.2F, scale(RED, 0.35F * pulse * raise));
                disk(q, ball, br, scale(ORANGE, 0.75F * pulse * raise));
                disk(q, ball, br * 0.45F, scale(0xFFE0B0, 0.95F * raise));
                for (int side = -1; side <= 1; side += 2) {
                    Vec3 h = hand(p, side, partial).subtract(cam);
                    if (R.nextFloat() < 0.8F) ribbon(q, h, ball, 0.18F + 0.1F * k, scale(RED, 0.7F));
                }
            }
            // После выброса: красная ударная дуга уходит вперёд и растёт.
            float since = age - MechPush.BLAST;
            if (since >= 0 && since < 14) {
                float t = since / 14;
                Vec3 c = ball.add(f.scale(2 + 16 * t));
                float rad = 2 + 9 * t;
                float a = (1 - t) * (1 - t);
                arc(q, c, r, up, rad - 0.8F, rad, 0, Math.PI * 2, scale(RED, 0.8F * a));
                arc(q, c, r, up, rad - 0.25F, rad, 0, Math.PI * 2, scale(WHITE, 0.9F * a));
                disk(q, ball, 4 * (1 - t), scale(0xFFE0B0, a));
            }
        }
        if (q.isEmpty()) return;
        collector.submitCustomGeometry(new PoseStack(), GlowRender.type(), (pose, buffer) -> {
            for (float[] f : q) {
                int c0 = Float.floatToRawIntBits(f[12]);
                int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                for (int k : order) GlowRender.vertex(buffer, pose, f[k * 3], f[k * 3 + 1], f[k * 3 + 2], c0);
            }
        });
    }

    /** Параметры искажения для шейдера линзы (своего игрока или ближайшего): {x, y, z, радиус, сила, кольцо, сила кольца} или null. */
    public static double[] lens(Minecraft mc, float partial) {
        if (mc.level == null) return null;
        for (Player p : mc.level.players()) {
            float age = age(p, partial);
            if (age < MechPush.RAISE) continue;
            Vec3 b = MechPush.ball(p);
            if (age < MechPush.BLAST) {
                float k = Mth.clamp((age - MechPush.RAISE) / (MechPush.BLAST - MechPush.RAISE), 0, 1);
                // Слабое стягивание (пользователь: было так сильно, что ничего не видно).
                return new double[]{b.x, b.y, b.z, 0.35 + 0.75 * k, 0.22 + 0.3 * k, 0, 0};
            }
            float since = age - MechPush.BLAST;
            if (since < 14) return new double[]{b.x, b.y, b.z, 0, 0, 3 + 22 * since / 14.0, 0.025 * (1 - since / 14.0)};
        }
        return null;
    }

    /** Экран своего игрока: красный оттенок во время притяжения. */
    public static void renderHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float age = age(mc.player, 0);
        if (age < 0 || age >= MechPush.BLAST) return;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        float k = Mth.clamp((age - 4) / 10.0F, 0, 1);
        float pulse = 0.85F + 0.15F * Mth.sin(age * 0.9F);
        g.fill(0, 0, w, h, HoloUi.argb(0.16F * k * pulse, 0xFF2030));
        int band = Math.max(8, h / 8);
        g.fill(0, 0, w, band, HoloUi.argb(0.2F * k, 0x500010));
        g.fill(0, h - band, w, h, HoloUi.argb(0.2F * k, 0x500010));
    }

    // ---------- Геометрия ----------

    private static void arc(List<float[]> out, Vec3 c, Vec3 u, Vec3 v, float r0, float r1, double a0, double a1, int rgb) {
        int seg = Math.max(1, (int) Math.ceil((a1 - a0) / (Math.PI / 24)));
        int col = HoloUi.argb(1, rgb);
        for (int i = 0; i < seg; i++) {
            double b0 = a0 + (a1 - a0) * i / seg, b1 = a0 + (a1 - a0) * (i + 1) / seg;
            Vec3 d0 = u.scale(Math.cos(b0)).add(v.scale(Math.sin(b0))), d1 = u.scale(Math.cos(b1)).add(v.scale(Math.sin(b1)));
            Vec3 i0 = c.add(d0.scale(r0)), o0 = c.add(d0.scale(r1)), i1 = c.add(d1.scale(r0)), o1 = c.add(d1.scale(r1));
            out.add(new float[]{(float) i0.x, (float) i0.y, (float) i0.z, (float) o0.x, (float) o0.y, (float) o0.z,
                    (float) o1.x, (float) o1.y, (float) o1.z, (float) i1.x, (float) i1.y, (float) i1.z, Float.intBitsToFloat(col)});
        }
    }

    private static void disk(List<float[]> out, Vec3 c, float r, int rgb) {
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(toCam).normalize();
        arc(out, c, u, v, 0, r, 0, Math.PI * 2, rgb);
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
                (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z), Float.intBitsToFloat(col)});
    }

    private static int scale(int rgb, float k) {
        k = Math.max(0, Math.min(1, k));
        return (Math.round(((rgb >> 16) & 0xFF) * k) << 16) | (Math.round(((rgb >> 8) & 0xFF) * k) << 8) | Math.round((rgb & 0xFF) * k);
    }

    private static Vec3 rnd(double x, double y, double z) {
        return new Vec3((R.nextDouble() - 0.5) * 2 * x, (R.nextDouble() - 0.5) * 2 * y, (R.nextDouble() - 0.5) * 2 * z);
    }
}
