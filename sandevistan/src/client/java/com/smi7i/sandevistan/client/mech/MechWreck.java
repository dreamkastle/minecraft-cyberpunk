package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.vertex.PoseStack;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.client.HitBurst;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Отвалившиеся части костюма: чем меньше здоровья, тем больше кусков брони и конечностей (клешни, предплечье)
 * отваливается (стадии — ModAttachments.MECH_LOST, пороги — MechManager.LOST_THRESHOLDS). Часть начинает падать
 * ровно оттуда, где была на костюме, кувыркается, отскакивает и ложится на землю. Лежит, пока костюм не уничтожен:
 * когда игрок вылез/погиб и возродился, обломки рассыпаются дымом.
 */
public final class MechWreck {
    /** Масштаб модели костюма: пиксели Blockbench -> блоки (как у игрока). */
    private static final float SCALE = 0.9375F / 16.0F;
    private static final double GRAVITY = 0.06;
    private static final int MAX_PIECES = 80;

    private static final class Piece {
        UUID owner;
        Vec3 pos, prevPos, vel;
        Quaternionf rot = new Quaternionf(), prevRot = new Quaternionf();
        Vector3f spin;
        /** Вершины граней относительно центра (блоки), по 4 на грань. */
        float[][] verts;
        float[][] uvs;
        /** Самая длинная ось части (для «лечь плашмя»). */
        Vector3f axis;
        boolean resting;
        int age;
        int smokeTicks;
    }

    private static final List<Piece> PIECES = new ArrayList<>();
    /** Сколько стадий уже «отвалилось» у каждого игрока (по id сущности). */
    private static final Map<Integer, Integer> SEEN = new HashMap<>();
    /** Последняя нарисованная поза костюма игрока. */
    private static final Map<Integer, MechModel.Pose> LAST_POSE = new HashMap<>();
    private static final Random R = new Random();

    private MechWreck() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MechWreck::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            PIECES.clear();
            SEEN.clear();
            LAST_POSE.clear();
        });
    }

    public static boolean hasAny() {
        return !PIECES.isEmpty();
    }

    static void remember(int entityId, MechModel.Pose pose) {
        LAST_POSE.put(entityId, pose);
    }

    private static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return;
        if (mc.isPaused()) return;
        // Новые отвалившиеся части.
        for (Player p : level.players()) {
            int id = p.getId();
            if (!ModAttachments.hasMech(p)) {
                SEEN.remove(id);
                continue;
            }
            int lost = p.getAttachedOrElse(ModAttachments.MECH_LOST, 0);
            Integer seen = SEEN.get(id);
            if (seen == null || lost < seen) { // впервые видим (зашёл в мир, подошёл) — не роняем всё разом
                SEEN.put(id, lost);
                continue;
            }
            if (lost > seen) {
                for (int s = seen + 1; s <= lost; s++) detach(level, p, s);
                SEEN.put(id, lost);
            }
        }
        // Физика и уборка.
        for (Iterator<Piece> it = PIECES.iterator(); it.hasNext(); ) {
            Piece pc = it.next();
            Player owner = level.getPlayerByUUID(pc.owner);
            // Костюм уничтожен: игрок возродился (жив, но уже без костюма) или вышел из мира — обломки рассыпаются.
            boolean gone = owner == null ? pc.age > 20 * 60 * 5 : owner.isAlive() && !ModAttachments.hasMech(owner);
            if (gone) {
                for (int i = 0; i < 3; i++) {
                    MechFx.puffAt(pc.pos.add(R.nextGaussian() * 0.2, 0.1, R.nextGaussian() * 0.2),
                            new Vec3(R.nextGaussian() * 0.02, 0.04, R.nextGaussian() * 0.02), 0.6F, 0.02F, 30, 0x55504A, 0.6F, false);
                }
                it.remove();
                continue;
            }
            step(level, pc);
        }
    }

    /** Отвалилась стадия s: каждая её часть превращается в падающий обломок. */
    private static void detach(ClientLevel level, Player p, int s) {
        MechModel model = MechModel.get();
        if (s >= model.lostParts.size()) return;
        MechModel.Pose pose = LAST_POSE.get(p.getId());
        if (pose == null) {
            pose = model.newPose();
            pose.blend(model.anim("idle"), p.tickCount / 20.0F, 1.0F);
        }
        Matrix4f[] mats = model.boneMatrices(pose);
        float bodyYaw = MechMotion.bodyYaw(p, 1.0F);
        Quaternionf body = new Quaternionf().rotateY((float) Math.toRadians(180.0F - bodyYaw));
        Vec3 base = p.position();
        for (int[] part : model.lostParts.get(s)) {
            if (PIECES.size() >= MAX_PIECES) PIECES.remove(0);
            Piece pc = new Piece();
            pc.owner = p.getUUID();
            pc.verts = new float[part.length * 4][];
            pc.uvs = new float[part.length][];
            Vector3f sum = new Vector3f();
            Vector3f v = new Vector3f();
            for (int i = 0; i < part.length; i++) {
                int fi = part[i];
                float[][] cs = model.faceCorners(fi);
                Matrix4f m = mats[model.faceBone(fi)];
                for (int k = 0; k < 4; k++) {
                    m.transformPosition(cs[k][0], cs[k][1], cs[k][2], v);
                    v.mul(SCALE);
                    body.transform(v);
                    pc.verts[i * 4 + k] = new float[]{v.x, v.y, v.z};
                    sum.add(v);
                }
                pc.uvs[i] = model.faceUv(fi);
            }
            sum.div(part.length * 4.0F);
            float far = 0;
            pc.axis = new Vector3f(1, 0, 0);
            for (float[] q : pc.verts) {
                q[0] -= sum.x; q[1] -= sum.y; q[2] -= sum.z;
                float d = q[0] * q[0] + q[1] * q[1] + q[2] * q[2];
                if (d > far) {
                    far = d;
                    pc.axis.set(q[0], q[1], q[2]).normalize();
                }
            }
            pc.pos = pc.prevPos = base.add(sum.x, sum.y, sum.z);
            // Отлетает наружу от костюма, чуть вверх; крупные части (конечности) — тяжелее и медленнее.
            Vec3 out = new Vec3(sum.x, 0, sum.z);
            out = out.lengthSqr() > 1e-4 ? out.normalize() : new Vec3(R.nextGaussian(), 0, R.nextGaussian()).normalize();
            boolean big = part.length > 40;
            double kick = big ? 0.12 : 0.22;
            pc.vel = out.scale(kick + R.nextDouble() * 0.1).add(p.getDeltaMovement().scale(0.5)).add(0, big ? 0.1 : 0.2 + R.nextDouble() * 0.12, 0);
            pc.spin = new Vector3f((float) R.nextGaussian(), (float) R.nextGaussian(), (float) R.nextGaussian()).normalize()
                    .mul((big ? 0.12F : 0.3F) + R.nextFloat() * 0.15F);
            pc.smokeTicks = 40 + R.nextInt(40);
            PIECES.add(pc);
            // Отрыв: искры и дымок в месте, где была часть.
            HitBurst.spawn(pc.pos, out);
            for (int i = 0; i < 4; i++) {
                MechFx.puffAt(pc.pos, out.scale(0.03).add(R.nextGaussian() * 0.02, 0.03, R.nextGaussian() * 0.02),
                        0.35F, 0.02F, 25, 0x3A3A3A, 0.55F, false);
            }
        }
    }

    private static void step(ClientLevel level, Piece pc) {
        pc.age++;
        pc.prevPos = pc.pos;
        pc.prevRot.set(pc.rot);
        if (pc.smokeTicks > 0) {
            pc.smokeTicks--;
            if (pc.smokeTicks % 6 == 0) {
                MechFx.puffAt(pc.pos, new Vec3(R.nextGaussian() * 0.01, 0.04, R.nextGaussian() * 0.01), 0.25F, 0.025F, 30, 0x2E2E2E, 0.4F, false);
            }
        }
        if (pc.resting) return;
        pc.vel = pc.vel.add(0, -GRAVITY, 0).scale(0.98);
        pc.pos = pc.pos.add(pc.vel);
        if (pc.spin.lengthSquared() > 1e-6F) pc.rot.rotateAxis(pc.spin.length(), new Vector3f(pc.spin).normalize()).normalize();
        // Земля: самая нижняя вершина не уходит под верх блока.
        float low = Float.MAX_VALUE;
        Vector3f v = new Vector3f();
        for (float[] q : pc.verts) {
            pc.rot.transform(v.set(q[0], q[1], q[2]));
            low = Math.min(low, v.y);
        }
        double ground = groundY(level, pc.pos);
        if (pc.pos.y + low < ground) {
            pc.pos = new Vec3(pc.pos.x, ground - low, pc.pos.z);
            boolean hard = pc.vel.y < -0.18;
            pc.vel = new Vec3(pc.vel.x * 0.55, pc.vel.y < 0 ? -pc.vel.y * 0.3 : pc.vel.y, pc.vel.z * 0.55);
            pc.spin.mul(0.55F);
            if (hard) {
                MechFx.puffAt(new Vec3(pc.pos.x, ground + 0.05, pc.pos.z), new Vec3(0, 0.02, 0), 0.4F, 0.03F, 22, 0x8A7F70, 0.5F, false);
                Minecraft.getInstance().level.playLocalSound(pc.pos.x, pc.pos.y, pc.pos.z,
                        net.minecraft.sounds.SoundEvents.CHAIN_FALL, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.6F + R.nextFloat() * 0.3F, false);
            }
            // На земле — ложится плашмя: длинная ось уходит в горизонталь.
            Vector3f wa = pc.rot.transform(new Vector3f(pc.axis));
            Vector3f flat = new Vector3f(wa.x, 0, wa.z);
            if (flat.lengthSquared() > 1e-4F) {
                flat.normalize();
                Quaternionf fix = new Quaternionf().rotationTo(wa, flat);
                new Quaternionf().slerp(fix, 0.3F).mul(pc.rot, pc.rot);
                pc.rot.normalize();
            }
            if (pc.vel.lengthSqr() < 0.0025 && pc.spin.lengthSquared() < 0.0025F) {
                pc.resting = true;
                pc.vel = Vec3.ZERO;
            }
        }
    }

    /** Верх твёрдого блока под точкой (ищем до 12 блоков вниз). */
    private static double groundY(ClientLevel level, Vec3 p) {
        BlockPos.MutableBlockPos bp = BlockPos.containing(p.x, p.y + 1.0, p.z).mutable();
        for (int i = 0; i < 14; i++, bp.move(0, -1, 0)) {
            var st = level.getBlockState(bp);
            if (st.isAir()) continue;
            var shape = st.getCollisionShape(level, bp);
            if (shape.isEmpty()) continue;
            return bp.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return p.y - 14;
    }

    static void submit(SubmitNodeCollector collector, ClientLevel level, Vec3 cam, float partial) {
        if (PIECES.isEmpty()) return;
        List<Piece> list = new ArrayList<>(PIECES);
        collector.submitCustomGeometry(new PoseStack(), RenderTypes.entityCutout(MechModel.TEXTURE), (pp, buffer) -> {
            Vector3f[] w = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
            Vector3f e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f();
            Quaternionf q = new Quaternionf();
            for (Piece pc : list) {
                Vec3 pos = pc.resting ? pc.pos : pc.prevPos.lerp(pc.pos, partial);
                if (pc.resting) q.set(pc.rot);
                else pc.prevRot.slerp(pc.rot, partial, q);
                BlockPos lp = BlockPos.containing(pos.x, pos.y + 0.3, pos.z);
                int light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lp), level.getBrightness(LightLayer.SKY, lp));
                float ox = (float) (pos.x - cam.x), oy = (float) (pos.y - cam.y), oz = (float) (pos.z - cam.z);
                for (int f = 0; f < pc.uvs.length; f++) {
                    for (int k = 0; k < 4; k++) {
                        float[] c = pc.verts[f * 4 + k];
                        q.transform(w[k].set(c[0], c[1], c[2])).add(ox, oy, oz);
                    }
                    w[1].sub(w[0], e1);
                    w[3].sub(w[0], e2);
                    e2.cross(e1, n);
                    if (n.lengthSquared() < 1e-14F) continue;
                    n.normalize();
                    float[] uv = pc.uvs[f];
                    float[][] uvs = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
                    for (int k = 0; k < 4; k++) {
                        buffer.addVertex(pp, w[k].x, w[k].y, w[k].z).setColor(-1).setUv(uvs[k][0], uvs[k][1])
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pp, n.x, n.y, n.z);
                    }
                }
            }
        });
    }
}
