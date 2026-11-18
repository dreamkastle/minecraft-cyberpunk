package com.smi7i.sandevistan.client.mech;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Модель костюма-меха из файлов, выгруженных из Blockbench (models_src/Texturer.java → assets/sandevistan/mech):
 * кости, кубики с раскладкой текстуры и анимации (walk, idle, attack, spawn). Координаты — пиксели Blockbench:
 * Y вверх, перед костюма — -Z, земля — y = 0. Позу считаем так же, как Blockbench: кость = сдвиг · поворот (X→Y→Z) · масштаб
 * вокруг своей опоры, детям — поверх родителя.
 */
public final class MechModel {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("sandevistan", "textures/entity/mech_suit.png");
    /** Размер раскладки текстуры (UV в файле — в этих единицах). */
    private static final float UV_SIZE = 128.0F;

    record Bone(String name, int parent, float[] pivot, float[] rest) {}

    /** Грань: 4 угла (уже повёрнутые поворотом кубика), UV и номер кости. */
    record Face(int bone, float[][] corners, float u1, float v1, float u2, float v2, Liquid liquid, int cube) {}

    /** Жидкость в шприце: кубик укорачивается сверху (в своей системе, до поворота) по уровню заполнения. */
    record Liquid(int dir, float[] from, float[] to, Matrix4f rotation) {}

    /** Анимация: моменты ключей и значения каналов по костям (поворот/сдвиг/масштаб, по 3 числа на ключ). */
    public record Anim(float length, boolean loop, boolean linear, float[] times, Map<Integer, float[][]> channels) {}

    private static MechModel instance;

    final List<Bone> bones = new ArrayList<>();
    final List<Face> faces = new ArrayList<>();

    /** Кусок текстуры костюма с настоящей грани модели (для обломков при взрыве): {u1, v1, u2, v2}. */
    public float[] randomFaceUv(java.util.Random r) {
        if (faces.isEmpty()) return new float[]{0, 0, 1, 1};
        Face best = null;
        // Из нескольких случайных граней берём самую большую — обломки выглядят как броня, а не как полоски.
        for (int i = 0; i < 4; i++) {
            Face f = faces.get(r.nextInt(faces.size()));
            if (best == null || Math.abs((f.u2() - f.u1()) * (f.v2() - f.v1())) > Math.abs((best.u2() - best.u1()) * (best.v2() - best.v1()))) {
                best = f;
            }
        }
        return new float[]{best.u1(), best.v1(), best.u2(), best.v2()};
    }
    final Map<String, Anim> anims = new HashMap<>();
    final Map<String, Integer> boneIndex = new HashMap<>();
    /** По кубикам: кость, имя (armor_, light_, cable_…), объём. */
    final List<Integer> cubeBone = new ArrayList<>();
    final List<String> cubeName = new ArrayList<>();
    final List<Float> cubeVolume = new ArrayList<>();
    /** Для каждой грани — на какой стадии повреждений она отваливается (0 — никогда). */
    int[] faceStage = new int[0];
    /** Отваливающиеся части по стадиям (1..6): каждая часть — набор граней, падает как одно целое. */
    public final List<List<int[]>> lostParts = new ArrayList<>();

    /**
     * Что отваливается на каждой стадии (здоровье ниже 85/70/55/40/25/12%): куски брони, левая клешня,
     * ещё броня, всё левое предплечье, правая клешня, последние куски. Выбор детерминирован — у всех одинаково.
     */
    private void buildLostParts() {
        faceStage = new int[faces.size()];
        java.util.Set<Integer> usedCubes = new java.util.HashSet<>();
        lostParts.add(List.of()); // стадия 0 — ничего
        String[][] spec = {
                {"cube:torso:0.62", "cube:left_arm:0.6"},
                {"bones:finger4,finger5,finger6"},
                {"cube:right_arm:0.6", "cube:left_leg2:0.55", "cube:torso:0.42"},
                {"bones:left_arm2"},
                {"bones:finger,finger2,finger3"},
                {"cube:right_leg2:0.55", "cube:left_arm:0.42", "cube:right_arm:0.42", "cube:left_leg:0.5"},
        };
        for (int s = 0; s < spec.length; s++) {
            List<int[]> parts = new ArrayList<>();
            for (String item : spec[s]) {
                java.util.Set<Integer> cubes = new java.util.HashSet<>();
                if (item.startsWith("cube:")) {
                    String[] a = item.split(":");
                    int c = pickCube(a[1], Float.parseFloat(a[2]), usedCubes);
                    if (c >= 0) cubes.add(c);
                } else {
                    java.util.Set<Integer> roots = new java.util.HashSet<>();
                    for (String bn : item.substring(6).split(",")) {
                        int b = bone(bn);
                        if (b >= 0) roots.add(b);
                    }
                    for (int c = 0; c < cubeBone.size(); c++) {
                        if (!usedCubes.contains(c) && underAny(cubeBone.get(c), roots) && !cubeName.get(c).startsWith("cable")) cubes.add(c);
                    }
                }
                usedCubes.addAll(cubes);
                List<Integer> fs = new ArrayList<>();
                for (int fi = 0; fi < faces.size(); fi++) {
                    if (faceStage[fi] == 0 && cubes.contains(faces.get(fi).cube())) {
                        faceStage[fi] = s + 1;
                        fs.add(fi);
                    }
                }
                if (!fs.isEmpty()) parts.add(fs.stream().mapToInt(Integer::intValue).toArray());
            }
            lostParts.add(parts);
        }
    }

    private boolean underAny(int b, java.util.Set<Integer> roots) {
        for (int x = b; x >= 0; x = bones.get(x).parent()) if (roots.contains(x)) return true;
        return false;
    }

    /** Кусок брони средней величины на кости (pct — доля по размеру среди её кубиков брони). */
    private int pickCube(String boneName, float pct, java.util.Set<Integer> used) {
        int b = bone(boneName);
        if (b < 0) return -1;
        List<Integer> list = new ArrayList<>();
        for (int c = 0; c < cubeBone.size(); c++) {
            if (cubeBone.get(c) == b && !used.contains(c) && cubeName.get(c).startsWith("armor") && cubeVolume.get(c) > 1.5F) list.add(c);
        }
        if (list.isEmpty()) {
            for (int c = 0; c < cubeBone.size(); c++) {
                if (cubeBone.get(c) == b && !used.contains(c) && !cubeName.get(c).startsWith("glow_cyan") && !cubeName.get(c).startsWith("cable")) list.add(c);
            }
        }
        if (list.isEmpty()) return -1;
        list.sort((x, y) -> Float.compare(cubeVolume.get(x), cubeVolume.get(y)));
        return list.get(Math.min(list.size() - 1, (int) (pct * list.size())));
    }

    /** Грань для обломков: 4 угла в системе кости, UV, кость. */
    public float[][] faceCorners(int fi) {
        return faces.get(fi).corners();
    }

    public float[] faceUv(int fi) {
        Face f = faces.get(fi);
        return new float[]{f.u1(), f.v1(), f.u2(), f.v2()};
    }

    public int faceBone(int fi) {
        return faces.get(fi).bone();
    }
    /** Порядок расчёта костей: родители раньше детей. */
    int[] order;

    public static MechModel get() {
        if (instance == null) instance = load();
        return instance;
    }

    public Anim anim(String name) {
        return anims.get(name);
    }

    public int bone(String name) {
        Integer i = boneIndex.get(name);
        return i == null ? -1 : i;
    }

    // ---------- Загрузка ----------

    private static JsonObject read(String path) {
        try (InputStream in = MechModel.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("нет файла " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Sandevistan: не удалось прочитать " + path, e);
        }
    }

    private static float[] vec(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        float[] r = new float[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
        return r;
    }

    private static MechModel load() {
        MechModel m = new MechModel();
        JsonObject model = read("/assets/sandevistan/mech/mech_model.json");
        JsonArray bones = model.getAsJsonArray("bones");
        List<String> parents = new ArrayList<>();
        for (JsonElement be : bones) {
            JsonObject b = be.getAsJsonObject();
            m.boneIndex.put(b.get("name").getAsString(), m.bones.size());
            parents.add(b.get("parent").isJsonNull() ? null : b.get("parent").getAsString());
            m.bones.add(new Bone(b.get("name").getAsString(), -1, vec(b.get("pivot")), vec(b.get("rot"))));
        }
        for (int i = 0; i < m.bones.size(); i++) {
            Bone b = m.bones.get(i);
            String p = parents.get(i);
            m.bones.set(i, new Bone(b.name(), p == null ? -1 : m.boneIndex.get(p), b.pivot(), b.rest()));
        }
        // Порядок: родители раньше детей.
        List<Integer> ord = new ArrayList<>();
        boolean[] done = new boolean[m.bones.size()];
        for (int i = 0; i < m.bones.size(); i++) addInOrder(m, i, done, ord);
        m.order = ord.stream().mapToInt(Integer::intValue).toArray();

        for (JsonElement ce : model.getAsJsonArray("cubes")) {
            JsonObject c = ce.getAsJsonObject();
            int bone = m.boneIndex.get(c.get("bone").getAsString());
            boolean liquid = c.has("name") && c.get("name").getAsString().startsWith("glow_cyan");
            float[] f = vec(c.get("from")), t = vec(c.get("to")), rot = vec(c.get("rot")), org = vec(c.get("origin"));
            Matrix4f er = new Matrix4f().translate(org[0], org[1], org[2])
                    .rotateZ((float) Math.toRadians(rot[2])).rotateY((float) Math.toRadians(rot[1])).rotateX((float) Math.toRadians(rot[0]))
                    .translate(-org[0], -org[1], -org[2]);
            for (JsonElement fe : c.getAsJsonArray("faces")) {
                float[] fa = vec(fe);
                float[][] corners = corners((int) fa[0], f, t);
                for (float[] p : corners) {
                    Vector3f v = er.transformPosition(new Vector3f(p[0], p[1], p[2]));
                    p[0] = v.x; p[1] = v.y; p[2] = v.z;
                }
                m.faces.add(new Face(bone, corners, fa[1] / UV_SIZE, fa[2] / UV_SIZE, fa[3] / UV_SIZE, fa[4] / UV_SIZE,
                        liquid ? new Liquid((int) fa[0], f, t, er) : null, m.cubeBone.size()));
            }
            m.cubeBone.add(bone);
            m.cubeName.add(c.has("name") ? c.get("name").getAsString() : "");
            m.cubeVolume.add(Math.abs((t[0] - f[0]) * (t[1] - f[1]) * (t[2] - f[2])));
        }

        JsonObject anims = read("/assets/sandevistan/mech/mech_anims.json");
        for (Map.Entry<String, JsonElement> ae : anims.entrySet()) {
            JsonObject a = ae.getValue().getAsJsonObject();
            Map<Integer, float[][]> ch = new HashMap<>();
            for (Map.Entry<String, JsonElement> be : a.getAsJsonObject("bones").entrySet()) {
                Integer bi = m.boneIndex.get(be.getKey());
                if (bi == null) continue;
                float[][] tracks = new float[3][]; // 0 поворот, 1 сдвиг, 2 масштаб
                JsonObject bo = be.getValue().getAsJsonObject();
                if (bo.has("rotation")) tracks[0] = vec(bo.get("rotation"));
                if (bo.has("position")) tracks[1] = vec(bo.get("position"));
                if (bo.has("scale")) tracks[2] = vec(bo.get("scale"));
                ch.put(bi, tracks);
            }
            m.anims.put(ae.getKey(), new Anim(a.get("length").getAsFloat(), a.get("loop").getAsBoolean(),
                    a.get("linear").getAsBoolean(), vec(a.get("times")), ch));
        }
        m.buildLostParts();
        return m;
    }

    private static void addInOrder(MechModel m, int i, boolean[] done, List<Integer> ord) {
        if (done[i]) return;
        int p = m.bones.get(i).parent();
        if (p >= 0) addInOrder(m, p, done, ord);
        done[i] = true;
        ord.add(i);
    }

    /** Углы грани в порядке UV: верх-лево, верх-право, низ-право, низ-лево (как раскладывает Blockbench). */
    private static float[][] corners(int dir, float[] f, float[] t) {
        float x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        return switch (dir) {
            case 0 -> new float[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}}; // north
            case 1 -> new float[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}}; // south
            case 2 -> new float[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}}; // east
            case 3 -> new float[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}}; // west
            case 4 -> new float[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}}; // up
            default -> new float[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}}; // down
        };
    }

    // ---------- Поза ----------

    /** Поза: для каждой кости поворот (градусы), сдвиг и масштаб. */
    public final class Pose {
        final float[][] rot = new float[bones.size()][3], pos = new float[bones.size()][3], scl = new float[bones.size()][3];
        /** Уровень жидкости в шприцах, 0..1 (1 — полные). */
        public float liquid = 1.0F;
        /** Яркость красных огней (1 — как обычно, 0 — погасли: гибель). */
        public float glow = 1.0F;
        /** Сколько стадий повреждений пройдено: отвалившиеся части не рисуются (см. LOST_PARTS). */
        public int lost = 0;

        public Pose() {
            for (float[] s : scl) { s[0] = 1; s[1] = 1; s[2] = 1; }
        }

        /** Подмешать анимацию в момент t (секунды) с весом w (1 — полностью). */
        public Pose blend(Anim a, float t, float w) {
            if (a == null || w <= 0) return this;
            for (int b = 0; b < bones.size(); b++) {
                float[][] tr = a.channels().get(b);
                float[] r = tr != null && tr[0] != null ? sample(a, tr[0], t) : null;
                float[] p = tr != null && tr[1] != null ? sample(a, tr[1], t) : null;
                float[] s = tr != null && tr[2] != null ? sample(a, tr[2], t) : null;
                for (int k = 0; k < 3; k++) {
                    rot[b][k] += ((r != null ? r[k] : 0) - rot[b][k]) * w;
                    pos[b][k] += ((p != null ? p[k] : 0) - pos[b][k]) * w;
                    scl[b][k] += ((s != null ? s[k] : 1) - scl[b][k]) * w;
                }
            }
            return this;
        }

        public float posY(String bone) {
            int b = bone(bone);
            return b < 0 ? 0 : pos[b][1];
        }

        /** Сдвиг кости (x, y, z) в пикселях модели. */
        public float[] pos(String bone) {
            int b = bone(bone);
            return b < 0 ? new float[3] : pos[b];
        }

        public float[] rot(String bone) {
            int b = bone(bone);
            return b < 0 ? new float[3] : rot[b];
        }
    }

    public Pose newPose() {
        return new Pose();
    }

    /** Значение канала в момент t: как в Blockbench — прямо между ключами или сглаженно (Catmull-Rom, по кругу). */
    static float[] sample(Anim a, float[] v, float t) {
        float[] times = a.times();
        int n = times.length;
        float len = a.length();
        if (a.loop()) t = ((t % len) + len) % len;
        else t = Math.max(0, Math.min(len, t));
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (times[mid] <= t) lo = mid; else hi = mid; }
        float f = times[hi] > times[lo] ? (t - times[lo]) / (times[hi] - times[lo]) : 0;
        float[] r = new float[3];
        if (a.linear() || !a.loop()) {
            for (int k = 0; k < 3; k++) r[k] = v[lo * 3 + k] + (v[hi * 3 + k] - v[lo * 3 + k]) * f;
            return r;
        }
        int nk = n - 1; // последний ключ = первый
        for (int k = 0; k < 3; k++) {
            float p0 = v[((lo - 1 + nk) % nk) * 3 + k], p1 = v[(lo % nk) * 3 + k], p2 = v[((lo + 1) % nk) * 3 + k], p3 = v[((lo + 2) % nk) * 3 + k];
            r[k] = 0.5F * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f);
        }
        return r;
    }

    // ---------- Отрисовка ----------

    /**
     * Рисует костюм в текущей системе координат poseStack (единица — пиксель Blockbench, Y вверх, перед — -Z).
     */
    /** Маска свечения: только ярко-красные пиксели текстуры пользователя (models_src/GlowMask.java). */
    public static final Identifier GLOW_TEXTURE = Identifier.fromNamespaceAndPath("sandevistan", "textures/entity/mech_suit_glow.png");
    /** Сколько слоёв свечения (сложение света) — ярче; и глубина медленной пульсации. */
    public static final int GLOW_LAYERS = 2;
    public static final float GLOW_PULSE = 0.15F;

    public void submit(Pose pose, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        submit(pose, poseStack, collector, light, RenderTypes.entityCutout(TEXTURE), -1);
        if (pose.glow > 0.01F) submitGlow(pose, poseStack, collector, pose.glow);
    }

    /** Ярко-красные огни светятся сами (как глаза эндермена): видны в темноте, складываются со светом. */
    public void submitGlow(Pose pose, PoseStack poseStack, SubmitNodeCollector collector, float strength) {
        float pulse = 1.0F - GLOW_PULSE + GLOW_PULSE * (float) Math.sin(net.minecraft.util.Util.getMillis() / 420.0);
        int v = Math.round(255 * Math.max(0, Math.min(1, strength * pulse)));
        int color = 0xFF000000 | (v << 16) | (v << 8) | v;
        for (int i = 0; i < GLOW_LAYERS; i++) {
            submit(pose, poseStack, collector, 15728880, RenderTypes.eyes(GLOW_TEXTURE), color);
        }
    }

    /** То же с выбранным типом отрисовки и цветом (послеобразы: полупрозрачно, светится, цвет послеобраза). */
    public void submit(Pose pose, PoseStack poseStack, SubmitNodeCollector collector, int light,
                       net.minecraft.client.renderer.rendertype.RenderType renderType, int color) {
        Matrix4f[] mats = boneMatrices(pose);
        int[] lostAt = faceStage;
        int lost = pose.lost;
        collector.submitCustomGeometry(poseStack, renderType, (pp, buffer) -> {
            Vector3f[] w = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
            Vector3f e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f();
            for (int fi = 0; fi < faces.size(); fi++) {
                if (lost > 0 && lostAt[fi] > 0 && lostAt[fi] <= lost) continue; // эта часть уже отвалилась
                Face face = faces.get(fi);
                Matrix4f m = mats[face.bone()];
                float[][] cs = face.corners();
                if (face.liquid() != null && pose.liquid < 0.999F) {
                    if (pose.liquid <= 0.001F) continue; // шприц пуст
                    Liquid lq = face.liquid();
                    float[] top = lq.to().clone();
                    top[1] = lq.from()[1] + (lq.to()[1] - lq.from()[1]) * pose.liquid;
                    cs = corners(lq.dir(), lq.from(), top);
                    for (float[] p : cs) {
                        Vector3f v = lq.rotation().transformPosition(new Vector3f(p[0], p[1], p[2]));
                        p[0] = v.x; p[1] = v.y; p[2] = v.z;
                    }
                }
                for (int k = 0; k < 4; k++) m.transformPosition(cs[k][0], cs[k][1], cs[k][2], w[k]);
                w[1].sub(w[0], e1);
                w[3].sub(w[0], e2);
                e2.cross(e1, n);
                if (n.lengthSquared() < 1e-12F) continue;
                n.normalize();
                float[][] uv = {{face.u1(), face.v1()}, {face.u2(), face.v1()}, {face.u2(), face.v2()}, {face.u1(), face.v2()}};
                for (int k = 0; k < 4; k++) vertex(buffer, pp, w[k], uv[k], n, light, color);
            }
        });
    }

    /** Матрицы костей позы (из системы кости в систему модели, пиксели Blockbench). */
    public Matrix4f[] boneMatrices(Pose pose) {
        Matrix4f[] mats = new Matrix4f[bones.size()];
        for (int b : order) {
            Bone bone = bones.get(b);
            float[] pv = bone.pivot(), r = pose.rot[b], p = pose.pos[b], s = pose.scl[b];
            Matrix4f m = bone.parent() >= 0 ? new Matrix4f(mats[bone.parent()]) : new Matrix4f();
            m.translate(p[0], p[1], p[2]).translate(pv[0], pv[1], pv[2])
                    .rotateZ((float) Math.toRadians(bone.rest()[2] + r[2]))
                    .rotateY((float) Math.toRadians(bone.rest()[1] + r[1]))
                    .rotateX((float) Math.toRadians(bone.rest()[0] + r[0]))
                    .scale(s[0], s[1], s[2])
                    .translate(-pv[0], -pv[1], -pv[2]);
            mats[b] = m;
        }
        return mats;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pp, Vector3f p, float[] uv, Vector3f n, int light, int color) {
        buffer.addVertex(pp, p.x, p.y, p.z).setColor(color).setUv(uv[0], uv[1]).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pp, n.x, n.y, n.z);
    }
}
