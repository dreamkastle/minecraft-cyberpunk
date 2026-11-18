import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Перерисовывает текстуру модели пользователя (skelet.bbmodel) по материалам из названий кубиков
 * (armor_, light_, cable_, glow_cyan_, cube) в стиле его образца, делает маску свечения и превью.
 * Аргументы: вход.bbmodel  выход.bbmodel  текстура.png  маска.png  превью.png
 */
public class Texturer {
    // Цвета из текстуры пользователя.
    // Цвета по референсу из Roblox: ровная тёмно-синяя броня с чёрной обводкой и светлыми фасками.
    static final int NAVY = 0x111D3B, PINK = 0xD42A3C, RED = 0xFF1E1E, PALE = 0xC5F1F0, GRAY = 0x596C6C;
    // Мягкие края: обводка и фаски лишь чуть темнее/светлее основы, оттенок — тёмно-синий без фиолетового.
    static final int OUTLINE = 0x0A1229, NAVY_HI = 0x233862, NAVY_SIDE = 0x0E1935, NAVY_LO = 0x0B152E,
            NAVY_UP = 0x17274E, NAVY_DOWN = 0x0D1834, PANEL = 0x132144, GROOVE = 0x0B1531, SEAM = 0x10306A,
            DARK = 0x09112A, DARK_HI = 0x111E3C,
            RED_EDGE = 0xC80A0A, RED_HOT = 0xFF6A6A, PINK_DARK = 0x8C1828, PINK_HI = 0xF0606E,
            CYAN_EDGE = 0x5BBCF4, WHITE = 0xF2FFFF;

    static int S; // пикселей текстуры на единицу UV
    static BufferedImage tex, glow;
    static int[] owner;
    static int overlaps;

    // ---------- Мини-JSON ----------
    static String src;
    static int pos;

    static Object parse() {
        ws();
        char c = src.charAt(pos);
        if (c == '{') {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (src.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = str();
                ws(); pos++; // :
                m.put(k, parse());
                ws();
                if (src.charAt(pos++) == '}') return m;
            }
        } else if (c == '[') {
            pos++;
            List<Object> l = new ArrayList<>();
            ws();
            if (src.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(parse());
                ws();
                if (src.charAt(pos++) == ']') return l;
            }
        } else if (c == '"') {
            return str();
        } else if (src.startsWith("true", pos)) { pos += 4; return true; }
        else if (src.startsWith("false", pos)) { pos += 5; return false; }
        else if (src.startsWith("null", pos)) { pos += 4; return null; }
        int st = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        return Double.parseDouble(src.substring(st, pos));
    }

    static void ws() { while (Character.isWhitespace(src.charAt(pos))) pos++; }

    static String str() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(c);
        }
    }

    @SuppressWarnings("unchecked") static Map<String, Object> M(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> L(Object o) { return (List<Object>) o; }
    static double D(Object o) { return ((Number) o).doubleValue(); }
    static double[] V(Object o) { List<Object> l = L(o); double[] r = new double[l.size()]; for (int i = 0; i < r.length; i++) r[i] = D(l.get(i)); return r; }

    // ---------- Модель ----------
    record Group(String name, double[] origin, double[] rot, Group parent) {}
    static final Map<String, Group> PARENT = new HashMap<>(); // uuid кубика -> группа

    public static void main(String[] a) throws Exception {
        if (a[0].equals("anim")) { animMain(a); return; }
        src = Files.readString(Path.of(a[0]), StandardCharsets.UTF_8);
        pos = 0;
        Map<String, Object> model = M(parse());
        Map<String, Object> texInfo = M(L(model.get("textures")).get(0));
        int tw = (int) D(texInfo.get("width")), th = (int) D(texInfo.get("height"));
        S = (int) Math.round(tw / D(texInfo.get("uv_width")));
        String oldB64 = ((String) texInfo.get("source")).substring("data:image/png;base64,".length());
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(oldB64)));

        // Группы.
        Map<String, Map<String, Object>> groupDefs = new HashMap<>();
        if (model.get("groups") != null) for (Object g : L(model.get("groups"))) groupDefs.put((String) M(g).get("uuid"), M(g));
        for (Object node : L(model.get("outliner"))) walk(node, null, groupDefs);

        tex = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        glow = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        owner = new int[tw * th];

        List<Map<String, Object>> elements = new ArrayList<>();
        for (Object e : L(model.get("elements"))) elements.add(M(e));
        int idx = 0, painted = 0;
        for (Map<String, Object> e : elements) {
            idx++;
            String name = (String) e.get("name");
            Map<String, Object> faces = M(e.get("faces"));
            for (Map.Entry<String, Object> f : faces.entrySet()) {
                Map<String, Object> face = M(f.getValue());
                if (face.get("texture") == null || face.get("uv") == null) continue;
                paintFace(material(name), f.getKey(), V(face.get("uv")), (e.get("uuid") + f.getKey()).hashCode(), idx);
                painted++;
            }
        }
        System.out.println("faces painted: " + painted + ", overlapping pixels: " + overlaps);

        // Второй проход — «по модели в 3D»: детали по положению в пространстве и тени в стыках.
        int[] stats = new int[2];
        for (Map<String, Object> e : elements) {
            String mat = material((String) e.get("name"));
            if (mat.equals("light") || mat.equals("cyan")) continue;
            Map<String, Object> faces = M(e.get("faces"));
            double[] f = V(e.get("from")), t = V(e.get("to"));
            String group = topGroup((String) e.get("uuid"));
            for (Map.Entry<String, Object> fe : faces.entrySet()) {
                Map<String, Object> face = M(fe.getValue());
                if (face.get("texture") == null || face.get("uv") == null) continue;
                double[][] c = faceCorners(fe.getKey(), f, t);
                double[][] w = new double[4][];
                for (int k = 0; k < 4; k++) w[k] = toWorld(c[k], e);
                double[] n = normal(w);
                double[] uv = V(face.get("uv"));
                paint3d(e, mat, group, w, n, uv, elements, stats);
            }
        }
        System.out.println("decal pixels: " + stats[0] + ", shadowed pixels: " + stats[1]);

        ImageIO.write(tex, "png", new File(a[2]));
        ImageIO.write(glow, "png", new File(a[3]));

        // Новый .bbmodel: та же модель, текстура заменена.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(tex, "png", bytes);
        String newB64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
        String out = src.replace(oldB64, newB64);
        Files.writeString(Path.of(a[1]), out, StandardCharsets.UTF_8);

        render(elements, a[4]);
        if (a.length > 5 && a[5].equals("dump")) dump(elements);
    }

    // ===== Рисование по модели в 3D =====
    static final int HOLE = 0x03060F;     // тёмная середина прорезей и колец
    static final double AO_STRENGTH = 0.3; // насколько темнеют пиксели в стыках (0..1)

    /** Для каждого пикселя грани: его точка в мире -> детали (по положению) и тень от соседних кубиков. */
    static void paint3d(Map<String, Object> self, String mat, String group, double[][] w, double[] n, double[] uv,
                        List<Map<String, Object>> elements, int[] stats) {
        double u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
        int x0 = (int) Math.floor(Math.min(u1, u2) * S + 1e-6), x1 = (int) Math.ceil(Math.max(u1, u2) * S - 1e-6);
        int y0 = (int) Math.floor(Math.min(v1, v2) * S + 1e-6), y1 = (int) Math.ceil(Math.max(v1, v2) * S - 1e-6);
        if (Math.abs(u2 - u1) < 1e-9 || Math.abs(v2 - v1) < 1e-9) return;
        boolean arm = group.contains("arm"), leg = group.contains("leg");
        for (int ty = y0; ty < y1; ty++) for (int tx = x0; tx < x1; tx++) {
            if (tx < 0 || ty < 0 || tx >= tex.getWidth() || ty >= tex.getHeight()) continue;
            double s = ((tx + 0.5) / S - u1) / (u2 - u1), q = ((ty + 0.5) / S - v1) / (v2 - v1);
            double[] p = new double[3];
            for (int k = 0; k < 3; k++) p[k] = w[0][k] + s * (w[1][k] - w[0][k]) + q * (w[3][k] - w[0][k]);
            double ax = Math.abs(p[0]);

            int decal = -1;
            if (mat.equals("armor") || mat.equals("dark")) {
                // 1) Сзади на капсулах: красное кольцо вокруг тёмного диска (глазки — отдельные кубики).
                if (arm && n[2] > 0.9 && p[2] > 3.4 && p[2] < 5.2) {
                    double r = Math.hypot(ax - 16, p[1] - 35);
                    if (r >= 3.0 && r <= 4.2) decal = RED;
                    else if (r < 3.0) decal = HOLE;
                }
                // 2) Сбоку на предплечьях: красные кольца на четырёх выступах.
                if (arm && Math.abs(n[0]) > 0.9 && Math.signum(n[0]) == Math.signum(p[0]) && ax > 21.8
                        && p[1] > 14.5 && p[1] < 23.5) {
                    double cy = p[1] < 19 ? 16.5 : 21.5, cz = p[2] < -1 ? -5.5 : 3.5;
                    double r = Math.hypot(p[1] - cy, p[2] - cz);
                    if (r >= 0.7 && r <= 1.35) decal = RED;
                    else if (r < 0.7) decal = HOLE;
                }
                // 3) Спереди на бёдрах: красная прямоугольная прорезь с тёмной серединой.
                if (leg && n[2] < -0.9 && p[1] > 21.3 && p[1] < 26.3) {
                    double dx = Math.abs(ax - 5.9), dy = Math.abs(p[1] - 23.8);
                    if (dx <= 1.6 && dy <= 1.9) decal = (dx > 0.9 || dy > 1.2) ? RED : HOLE;
                }
                // 4) Сзади на голенях: тонкая красная линия.
                if (leg && n[2] > 0.7 && p[1] > 3.2 && p[1] < 8.8 && Math.abs(ax - 6.0) < 0.26) decal = RED;
            }
            if (decal != -1) {
                set(tx, ty, decal);
                stats[0]++;
                if (decal == RED) continue; // огни не затеняем
            }

            // Тень в стыках: смотрим из точки в нескольких направлениях — есть ли рядом другой кубик.
            double occ = occlusion(self, p, n, elements);
            if (occ > 0.01) {
                int argb = tex.getRGB(tx, ty);
                double k = 1.0 - AO_STRENGTH * occ;
                int r = (int) (((argb >> 16) & 0xFF) * k), g = (int) (((argb >> 8) & 0xFF) * k), b = (int) ((argb & 0xFF) * k);
                tex.setRGB(tx, ty, 0xFF000000 | (r << 16) | (g << 8) | b);
                stats[1]++;
            }
        }
    }

    static final double[][] HEMI = buildHemi();

    static double[][] buildHemi() {
        List<double[]> dirs = new ArrayList<>();
        // Направления вокруг нормали (локально: z — нормаль), под разными углами.
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            dirs.add(new double[]{Math.cos(a) * 0.85, Math.sin(a) * 0.85, 0.53});
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.PI / 4;
            dirs.add(new double[]{Math.cos(a) * 0.4, Math.sin(a) * 0.4, 0.92});
        }
        return dirs.toArray(new double[0][]);
    }

    static double occlusion(Map<String, Object> self, double[] p, double[] n, List<Map<String, Object>> elements) {
        double[] t1 = Math.abs(n[0]) < 0.9 ? cross(n, new double[]{1, 0, 0}) : cross(n, new double[]{0, 1, 0});
        normalize(t1);
        double[] t2 = cross(n, t1);
        int hits = 0;
        double[] start = {p[0] + n[0] * 0.06, p[1] + n[1] * 0.06, p[2] + n[2] * 0.06};
        for (double[] h : HEMI) {
            double[] d = {t1[0] * h[0] + t2[0] * h[1] + n[0] * h[2], t1[1] * h[0] + t2[1] * h[1] + n[1] * h[2],
                    t1[2] * h[0] + t2[2] * h[1] + n[2] * h[2]};
            boolean hit = false;
            for (double dist : new double[]{0.35, 0.8, 1.4}) {
                double[] q = {start[0] + d[0] * dist, start[1] + d[1] * dist, start[2] + d[2] * dist};
                if (insideAny(self, q, elements)) { hit = true; break; }
            }
            if (hit) hits++;
        }
        return hits / (double) HEMI.length;
    }

    static final Map<Map<String, Object>, double[][]> BOXES = new IdentityHashMap<>();

    /** Точка внутри какого-нибудь другого кубика (с текстурой) модели? */
    static boolean insideAny(Map<String, Object> self, double[] q, List<Map<String, Object>> elements) {
        for (Map<String, Object> e : elements) {
            if (e == self) continue;
            double[][] box = BOXES.computeIfAbsent(e, k -> {
                boolean textured = false;
                for (Object fo : M(k.get("faces")).values()) if (M(fo).get("texture") != null) textured = true;
                return textured ? new double[][]{V(k.get("from")), V(k.get("to"))} : new double[0][];
            });
            if (box.length == 0) continue;
            double[] l = toLocal(q, e);
            if (l[0] > box[0][0] && l[0] < box[1][0] && l[1] > box[0][1] && l[1] < box[1][1]
                    && l[2] > box[0][2] && l[2] < box[1][2]) return true;
        }
        return false;
    }

    /** Обратное к toWorld: точку мира — в собственные координаты кубика. */
    static double[] toLocal(double[] p, Map<String, Object> e) {
        List<Group> chain = new ArrayList<>();
        for (Group g = PARENT.get((String) e.get("uuid")); g != null; g = g.parent) chain.add(g);
        double[] q = p;
        for (int i = chain.size() - 1; i >= 0; i--) q = rotateInv(q, chain.get(i).rot, chain.get(i).origin);
        if (e.get("rotation") != null) q = rotateInv(q, V(e.get("rotation")), V(e.get("origin")));
        return q;
    }

    static double[] rotateInv(double[] p, double[] rot, double[] origin) {
        double x = p[0] - origin[0], y = p[1] - origin[1], z = p[2] - origin[2];
        double ax = Math.toRadians(-rot[0]), ay = Math.toRadians(-rot[1]), az = Math.toRadians(-rot[2]);
        // Обратный порядок: Z, затем Y, затем X.
        double x3 = x * Math.cos(az) - y * Math.sin(az), y3 = x * Math.sin(az) + y * Math.cos(az); x = x3; y = y3;
        double x2 = x * Math.cos(ay) + z * Math.sin(ay), z2 = -x * Math.sin(ay) + z * Math.cos(ay); x = x2; z = z2;
        double y1 = y * Math.cos(ax) - z * Math.sin(ax), z1 = y * Math.sin(ax) + z * Math.cos(ax); y = y1; z = z1;
        return new double[]{x + origin[0], y + origin[1], z + origin[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static void normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 0) { v[0] /= l; v[1] /= l; v[2] /= l; }
    }

    static String topGroup(String uuid) {
        Group g = PARENT.get(uuid);
        if (g == null) return "-";
        while (g.parent != null) g = g.parent;
        return g.name;
    }

    /** Список крупных граней: группа, имя, направление в мире, центр, размер — чтобы найти места для деталей. */
    static void dump(List<Map<String, Object>> elements) {
        int idx = 0;
        for (Map<String, Object> e : elements) {
            idx++;
            Map<String, Object> faces = M(e.get("faces"));
            double[] f = V(e.get("from")), t = V(e.get("to"));
            for (Map.Entry<String, Object> fe : faces.entrySet()) {
                Map<String, Object> face = M(fe.getValue());
                if (face.get("texture") == null || face.get("uv") == null) continue;
                double[][] c = faceCorners(fe.getKey(), f, t);
                double[][] w = new double[4][];
                for (int k = 0; k < 4; k++) w[k] = toWorld(c[k], e);
                double fw = dist(w[0], w[1]), fh = dist(w[0], w[3]);
                if (fw * fh < 9) continue;
                double[] n = normal(w);
                double[] cen = {(w[0][0] + w[2][0]) / 2, (w[0][1] + w[2][1]) / 2, (w[0][2] + w[2][2]) / 2};
                System.out.printf(Locale.ROOT, "#%d %-10s %-12s %-5s n(%.1f,%.1f,%.1f) c(%.1f,%.1f,%.1f) %.1fx%.1f%n",
                        idx, topGroup((String) e.get("uuid")), e.get("name"), fe.getKey(), n[0], n[1], n[2], cen[0], cen[1], cen[2], fw, fh);
            }
        }
    }

    static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    static void walk(Object node, Group parent, Map<String, Map<String, Object>> defs) {
        if (node instanceof String uuid) {
            PARENT.put(uuid, parent);
            return;
        }
        Map<String, Object> n = M(node);
        Map<String, Object> def = defs.getOrDefault((String) n.get("uuid"), n);
        Group g = new Group((String) def.get("name"), V(def.get("origin")),
                def.get("rotation") != null ? V(def.get("rotation")) : new double[3], parent);
        GROUPS_BY_NAME.put(g.name(), g);
        for (Object c : L(n.get("children"))) walk(c, g, defs);
    }

    static final Map<String, Group> GROUPS_BY_NAME = new HashMap<>();

    static String material(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("light")) return "light";
        if (n.startsWith("glow_cyan")) return "cyan";
        if (n.startsWith("cable")) return "cable";
        if (n.startsWith("armor")) return "armor";
        return "dark";
    }

    // ---------- Рисование граней ----------

    static void set(int x, int y, int rgb) {
        if (x < 0 || y < 0 || x >= tex.getWidth() || y >= tex.getHeight()) return;
        tex.setRGB(x, y, 0xFF000000 | rgb);
        if (rgb == RED || rgb == RED_HOT) glow.setRGB(x, y, 0xFF000000 | rgb);
    }

    static void glowPx(int x, int y, int rgb) {
        if (x < 0 || y < 0 || x >= glow.getWidth() || y >= glow.getHeight()) return;
        glow.setRGB(x, y, 0xFF000000 | rgb);
    }

    static void paintFace(String mat, String dir, double[] uv, int seed, int id) {
        int x0 = (int) Math.floor(Math.min(uv[0], uv[2]) * S + 1e-6);
        int x1 = (int) Math.ceil(Math.max(uv[0], uv[2]) * S - 1e-6);
        int y0 = (int) Math.floor(Math.min(uv[1], uv[3]) * S + 1e-6);
        int y1 = (int) Math.ceil(Math.max(uv[1], uv[3]) * S - 1e-6);
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) return;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            if (x < 0 || y < 0 || x >= tex.getWidth() || y >= tex.getHeight()) continue;
            int i = y * tex.getWidth() + x;
            if (owner[i] != 0 && owner[i] != id) overlaps++;
            owner[i] = id;
        }
        boolean side = !dir.equals("up") && !dir.equals("down");
        boolean flipV = uv[3] < uv[1];
        Random r = new Random(seed);
        // «Локальные» координаты: lx 0..w-1, ly 0..h-1, где ly=0 — верх грани в мире.
        java.util.function.BiConsumer<int[], Integer> put = (p, c) -> set(x0 + p[0], flipV ? y1 - 1 - p[1] : y0 + p[1], c);

        switch (mat) {
            case "light" -> {
                fill(put, w, h, RED);
                if (w >= 3 && h >= 3) border(put, w, h, RED_EDGE);
                if (w >= 4 && h >= 4) { put.accept(new int[]{1, 1}, RED_HOT); }
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) glowPx(x0 + x, y0 + y, tex.getRGB(x0 + x, y0 + y));
            }
            case "cyan" -> {
                fill(put, w, h, PALE);
                if (w >= 3 && h >= 3) border(put, w, h, CYAN_EDGE);
                if (w >= 4 && h >= 4) for (int y = 1; y < h - 1; y++) put.accept(new int[]{w / 2, y}, WHITE);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) glowPx(x0 + x, y0 + y, tex.getRGB(x0 + x, y0 + y));
            }
            case "cable" -> {
                fill(put, w, h, PINK);
                boolean alongX = w >= h;
                int len = alongX ? w : h;
                for (int i = 0; i < len; i++) {
                    if (i % 3 != 2) continue;
                    for (int k = 0; k < (alongX ? h : w); k++) put.accept(alongX ? new int[]{i, k} : new int[]{k, i}, PINK_DARK);
                }
                if (side && h >= 2) for (int x = 0; x < w; x++) put.accept(new int[]{x, 0}, PINK_HI);
            }
            case "dark" -> {
                fill(put, w, h, DARK);
                if (w >= 3 && h >= 3) {
                    for (int x = 0; x < w; x++) put.accept(new int[]{x, 0}, DARK_HI);
                }
            }
            default -> paintArmor(put, w, h, dir, side, r);
        }
    }

    static void fill(java.util.function.BiConsumer<int[], Integer> put, int w, int h, int c) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) put.accept(new int[]{x, y}, c);
    }

    static void border(java.util.function.BiConsumer<int[], Integer> put, int w, int h, int c) {
        for (int x = 0; x < w; x++) { put.accept(new int[]{x, 0}, c); put.accept(new int[]{x, h - 1}, c); }
        for (int y = 0; y < h; y++) { put.accept(new int[]{0, y}, c); put.accept(new int[]{w - 1, y}, c); }
    }

    /**
     * Броня как в Roblox-референсе: ровная плита, чёрная обводка, светлая фаска сверху, тёмная снизу,
     * на больших плитах — вдавленная панель со швом. Никаких случайных деталей.
     */
    static void paintArmor(java.util.function.BiConsumer<int[], Integer> put, int w, int h, String dir, boolean side, Random r) {
        int base = side ? NAVY : dir.equals("up") ? NAVY_UP : NAVY_DOWN;
        fill(put, w, h, base);
        if (w < 3 || h < 3) {
            // Тонкая грань: только светлая кромка сверху.
            if (side && h >= 2) for (int x = 0; x < w; x++) put.accept(new int[]{x, 0}, NAVY_HI);
            return;
        }
        border(put, w, h, OUTLINE);
        // Фаска внутри обводки: сверху светлая, снизу тёмная, по бокам чуть темнее основы.
        if (w >= 5 && h >= 5) {
            for (int x = 1; x < w - 1; x++) {
                put.accept(new int[]{x, 1}, side ? NAVY_HI : NAVY_SIDE);
                put.accept(new int[]{x, h - 2}, side ? NAVY_LO : NAVY_SIDE);
            }
            for (int y = 2; y < h - 2; y++) {
                put.accept(new int[]{1, y}, NAVY_SIDE);
                put.accept(new int[]{w - 2, y}, NAVY_SIDE);
            }
        }
        // Вдавленная панель на больших плитах: шов (тёмная линия) и чуть светлее внутри.
        if (w >= 12 && h >= 12) {
            int m = Math.max(3, Math.min(w, h) / 6);
            int px0 = m, py0 = m, px1 = w - 1 - m, py1 = h - 1 - m;
            for (int y = py0; y <= py1; y++) for (int x = px0; x <= px1; x++) {
                boolean edge = x == px0 || x == px1 || y == py0 || y == py1;
                put.accept(new int[]{x, y}, edge ? GROOVE : PANEL);
            }
            // Светлая кромка под нижним швом — панель «утоплена».
            for (int x = px0; x <= px1; x++) put.accept(new int[]{x, py1 + 1}, NAVY_HI);
        }
    }

    static void paintArmorNoisy(java.util.function.BiConsumer<int[], Integer> put, int w, int h, String dir, boolean side, Random r) {
        int base = side ? NAVY : dir.equals("up") ? NAVY_UP : NAVY_DOWN;
        fill(put, w, h, base);
        // Фаски: светлый верх, тёмный низ, бока чуть светлее основы.
        if (w >= 3 && h >= 3) {
            for (int y = 0; y < h; y++) { put.accept(new int[]{0, y}, NAVY_SIDE); put.accept(new int[]{w - 1, y}, NAVY_SIDE); }
            for (int x = 0; x < w; x++) {
                put.accept(new int[]{x, 0}, side ? NAVY_HI : NAVY_SIDE);
                put.accept(new int[]{x, h - 1}, side ? NAVY_LO : NAVY_SIDE);
            }
        } else if (h == 2 && side) {
            for (int x = 0; x < w; x++) put.accept(new int[]{x, 0}, NAVY_HI);
        }
        if (w < 6 || h < 6) {
            // Маленькая грань: максимум одна деталька.
            if (w >= 4 && h >= 3 && r.nextFloat() < 0.35F) {
                int x = 1 + r.nextInt(w - 2);
                put.accept(new int[]{x, 1 + r.nextInt(h - 2)}, r.nextFloat() < 0.5F ? PALE : PINK);
            }
            return;
        }
        int iw = w - 2, ih = h - 2; // внутренняя область (без фасок)
        // Швы между панелями на больших гранях.
        if (w >= 16 && r.nextFloat() < 0.8F) {
            int sx = 3 + r.nextInt(w - 6);
            for (int y = 1; y < h - 1; y++) put.accept(new int[]{sx, y}, SEAM);
        }
        if (h >= 16 && r.nextFloat() < 0.8F) {
            int sy = 3 + r.nextInt(h - 6);
            for (int x = 1; x < w - 1; x++) put.accept(new int[]{x, sy}, SEAM);
        }
        int area = iw * ih;
        // Тонкие линии: голубые и серые, чаще у краёв.
        int lines = Math.max(1, area / 38);
        for (int i = 0; i < lines; i++) {
            int[] p = nearEdge(r, w, h);
            if (p == null) continue;
            boolean horizontal = r.nextFloat() < 0.45F;
            int len = 2 + r.nextInt(5);
            int c = r.nextFloat() < 0.72F ? PALE : GRAY;
            for (int k = 0; k < len; k++) {
                int x = p[0] + (horizontal ? k : 0), y = p[1] + (horizontal ? 0 : k);
                if (x >= 1 && x < w - 1 && y >= 1 && y < h - 1) put.accept(new int[]{x, y}, c);
            }
        }
        // Розовые плашки (не светятся).
        int plates = area / 170 + (r.nextFloat() < 0.5F ? 1 : 0);
        for (int i = 0; i < plates; i++) {
            int pw = 2 + r.nextInt(Math.min(5, Math.max(1, iw - 2))), ph = 1 + r.nextInt(Math.min(3, Math.max(1, ih - 2)));
            int[] p = r.nextFloat() < 0.6F ? nearEdge(r, w, h) : new int[]{1 + r.nextInt(iw), 1 + r.nextInt(ih)};
            if (p == null) continue;
            for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
                int px = p[0] + x, py = p[1] + y;
                if (px >= 1 && px < w - 1 && py >= 1 && py < h - 1) put.accept(new int[]{px, py}, PINK);
            }
        }
        // Ярко-красные огоньки (светятся): точки и квадратные рамочки.
        int reds = area / 260 + (r.nextFloat() < 0.4F ? 1 : 0);
        for (int i = 0; i < reds; i++) {
            int[] p = nearEdge(r, w, h);
            if (p == null) continue;
            if (r.nextFloat() < 0.4F && p[0] + 3 < w - 1 && p[1] + 3 < h - 1) {
                for (int k = 0; k < 4; k++) {
                    put.accept(new int[]{p[0] + k, p[1]}, RED);
                    put.accept(new int[]{p[0] + k, p[1] + 3}, RED);
                    put.accept(new int[]{p[0], p[1] + k}, RED);
                    put.accept(new int[]{p[0] + 3, p[1] + k}, RED);
                }
            } else {
                put.accept(new int[]{p[0], p[1]}, RED);
            }
        }
        // «Гребёнка» у нижнего края боковых граней, как в образце.
        if (side && w >= 10 && h >= 8 && r.nextFloat() < 0.65F) {
            for (int x = 2; x < w - 2; x += 2) {
                if (r.nextFloat() < 0.25F) continue;
                int len = 1 + r.nextInt(3);
                float pick = r.nextFloat();
                int c = pick < 0.6F ? PALE : pick < 0.85F ? GRAY : PINK;
                for (int k = 0; k < len; k++) put.accept(new int[]{x, h - 2 - k}, c);
            }
        }
    }

    /** Случайная точка внутри грани, чаще у краёв. */
    static int[] nearEdge(Random r, int w, int h) {
        for (int tries = 0; tries < 12; tries++) {
            int x = 1 + r.nextInt(Math.max(1, w - 2)), y = 1 + r.nextInt(Math.max(1, h - 2));
            float e = Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
            float half = Math.max(1, Math.min(w, h) / 2.0F);
            float weight = 1.0F - e / half;
            if (r.nextFloat() < 0.15F + 0.85F * weight * weight) return new int[]{x, y};
        }
        return null;
    }

    // ---------- Превью ----------

    static double[] rotate(double[] p, double[] rot, double[] origin) {
        double x = p[0] - origin[0], y = p[1] - origin[1], z = p[2] - origin[2];
        double ax = Math.toRadians(rot[0]), ay = Math.toRadians(rot[1]), az = Math.toRadians(rot[2]);
        // X, затем Y, затем Z (порядок Blockbench «ZYX»).
        double y1 = y * Math.cos(ax) - z * Math.sin(ax), z1 = y * Math.sin(ax) + z * Math.cos(ax);
        y = y1; z = z1;
        double x2 = x * Math.cos(ay) + z * Math.sin(ay), z2 = -x * Math.sin(ay) + z * Math.cos(ay);
        x = x2; z = z2;
        double x3 = x * Math.cos(az) - y * Math.sin(az), y3 = x * Math.sin(az) + y * Math.cos(az);
        x = x3; y = y3;
        return new double[]{x + origin[0], y + origin[1], z + origin[2]};
    }

    /** Точка, движущаяся вместе с группой g (и всеми её родителями). */
    static double[] chainWorld(double[] p, Group start) {
        double[] q = p;
        for (Group g = start; g != null; g = g.parent) {
            double[] ar = ANIM_ROT.get(g.name), ap = ANIM_POS.get(g.name), as = ANIM_SCALE.get(g.name);
            double[] rot = ar == null ? g.rot : new double[]{g.rot[0] + ar[0], g.rot[1] + ar[1], g.rot[2] + ar[2]};
            double[] origin = PIVOT_OVERRIDE.getOrDefault(g.name, g.origin);
            if (as != null) q = new double[]{origin[0] + (q[0] - origin[0]) * as[0], origin[1] + (q[1] - origin[1]) * as[1],
                    origin[2] + (q[2] - origin[2]) * as[2]};
            q = rotate(q, rot, origin);
            if (ap != null) q = new double[]{q[0] + ap[0], q[1] + ap[1], q[2] + ap[2]};
        }
        return q;
    }

    static double[] toWorld(double[] p, Map<String, Object> e) {
        double[] q = p;
        if (e.get("rotation") != null) q = rotate(q, V(e.get("rotation")), V(e.get("origin")));
        for (Group g = PARENT.get((String) e.get("uuid")); g != null; g = g.parent) {
            double[] ar = ANIM_ROT.get(g.name), ap = ANIM_POS.get(g.name), as = ANIM_SCALE.get(g.name);
            double[] rot = ar == null ? g.rot : new double[]{g.rot[0] + ar[0], g.rot[1] + ar[1], g.rot[2] + ar[2]};
            double[] origin = PIVOT_OVERRIDE.getOrDefault(g.name, g.origin);
            if (as != null) q = new double[]{origin[0] + (q[0] - origin[0]) * as[0], origin[1] + (q[1] - origin[1]) * as[1],
                    origin[2] + (q[2] - origin[2]) * as[2]};
            q = rotate(q, rot, origin);
            if (ap != null) q = new double[]{q[0] + ap[0], q[1] + ap[1], q[2] + ap[2]};
        }
        return q;
    }

    // ================= Анимация ходьбы =================
    static final Map<String, double[]> ANIM_ROT = new HashMap<>(), ANIM_POS = new HashMap<>(), ANIM_SCALE = new HashMap<>();

    // ===== Кабели: косточки, которые держат концы кабелей на своих местах =====
    /** stretch — длинный кабель корпус→капсула (опора у капсулы, конец у корпуса держится); pin — кусочек на груди (стоит с корпусом). */
    record CableRig(String name, String side, boolean stretch, double[] pivot, double[] torsoEnd) {}
    static final List<CableRig> RIGS = new ArrayList<>();

    /** Оборачивает кабели из групп рук в отдельные косточки (группы) — сами кубики не двигаются. */
    static void rigCables(Map<String, Object> model, Map<String, Map<String, Object>> groupDefs) {
        Map<String, Map<String, Object>> elById = new HashMap<>();
        for (Object e : L(model.get("elements"))) elById.put((String) M(e).get("uuid"), M(e));
        int idx = 0;
        for (Object node : L(model.get("outliner"))) {
            if (!(node instanceof Map)) continue;
            Map<String, Object> top = M(node);
            Map<String, Object> def = groupDefs.get((String) top.get("uuid"));
            String gname = (String) def.get("name");
            if (!gname.endsWith("_arm")) continue;
            String side = gname.startsWith("left") ? "left" : "right";
            List<Object> children = L(top.get("children"));
            for (int c = 0; c < children.size(); c++) {
                if (!(children.get(c) instanceof String uuid)) continue;
                Map<String, Object> el = elById.get(uuid);
                if (!((String) el.get("name")).startsWith("cable")) continue;
                double[][] ends = cableEnds(el);
                double[] near = Math.abs(ends[0][0]) < Math.abs(ends[1][0]) ? ends[0] : ends[1];
                double[] far = near == ends[0] ? ends[1] : ends[0];
                boolean stretch = Math.abs(far[0]) > 9; // дотягивается до капсулы
                double[] pivot = stretch ? far : PIVOT_OVERRIDE.get(gname);
                String name = "cable_rig_" + (++idx);
                Map<String, Object> g = new LinkedHashMap<>(def);
                g.put("name", name);
                g.put("uuid", UUID.randomUUID().toString());
                g.put("origin", new ArrayList<>(List.of(pivot[0], pivot[1], pivot[2])));
                g.put("rotation", new ArrayList<>(List.of(0.0, 0.0, 0.0)));
                L(model.get("groups")).add(g);
                groupDefs.put((String) g.get("uuid"), g);
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("uuid", g.get("uuid"));
                wrap.put("isOpen", false);
                wrap.put("children", new ArrayList<>(List.of(uuid)));
                children.set(c, wrap);
                RIGS.add(new CableRig(name, side, stretch, pivot, near));
            }
        }
        System.out.println("cable rigs: " + RIGS.size() + " (stretch " + RIGS.stream().filter(CableRig::stretch).count() + ")");
    }

    /**
     * Над каждой капсулой — невидимая косточка-«носитель» с опорой в центре корпуса: она поворачивается вместе с корпусом,
     * а капсула внутри — только сама по себе. Так углы не складываются и не доходят до 90° (где Blockbench «клинит»).
     */
    static void addCarriers(Map<String, Object> model, Map<String, Map<String, Object>> groupDefs) {
        List<Object> outliner = L(model.get("outliner"));
        double[] c = PIVOT_OVERRIDE.get("torso");
        for (int i = 0; i < outliner.size(); i++) {
            if (!(outliner.get(i) instanceof Map)) continue;
            Map<String, Object> node = M(outliner.get(i));
            Map<String, Object> def = groupDefs.get((String) node.get("uuid"));
            String name = (String) def.get("name");
            if (!name.equals("left_arm") && !name.equals("right_arm")) continue;
            Map<String, Object> g = new LinkedHashMap<>(def);
            g.put("name", name + "_carrier");
            g.put("uuid", UUID.randomUUID().toString());
            g.put("origin", new ArrayList<>(List.of(c[0], c[1], c[2])));
            g.put("rotation", new ArrayList<>(List.of(0.0, 0.0, 0.0)));
            L(model.get("groups")).add(g);
            groupDefs.put((String) g.get("uuid"), g);
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("uuid", g.get("uuid"));
            wrap.put("isOpen", true);
            wrap.put("children", new ArrayList<>(List.of(node)));
            outliner.set(i, wrap);
        }
    }

    /** Два конца кабеля (центры торцов по самой длинной оси) в координатах модели. */
    static double[][] cableEnds(Map<String, Object> el) {
        double[] f = V(el.get("from")), t = V(el.get("to"));
        double[] size = {t[0] - f[0], t[1] - f[1], t[2] - f[2]};
        int ax = 0;
        for (int k = 1; k < 3; k++) if (Math.abs(size[k]) > Math.abs(size[ax])) ax = k;
        double[] c = {(f[0] + t[0]) / 2, (f[1] + t[1]) / 2, (f[2] + t[2]) / 2};
        double[] a = c.clone(), b = c.clone();
        a[ax] = f[ax];
        b[ax] = t[ax];
        double[] rot = el.get("rotation") != null ? V(el.get("rotation")) : new double[3];
        double[] org = el.get("origin") != null ? V(el.get("origin")) : new double[3];
        return new double[][]{rotate(a, rot, org), rotate(b, rot, org)};
    }

    // --- Матрицы поворота в порядке модели (X, потом Y, потом Z: M = Rz·Ry·Rx) ---
    static double[][] mat(double[] e) {
        double[] x = rotate(new double[]{1, 0, 0}, e, new double[3]), y = rotate(new double[]{0, 1, 0}, e, new double[3]),
                z = rotate(new double[]{0, 0, 1}, e, new double[3]);
        return new double[][]{{x[0], y[0], z[0]}, {x[1], y[1], z[1]}, {x[2], y[2], z[2]}};
    }

    static double[] euler(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double x = Math.atan2(m[2][1], m[2][2]), z = Math.atan2(m[1][0], m[0][0]);
        return new double[]{Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)};
    }

    /**
     * Углы того же поворота, но ближайшие к предыдущему кадру. Один поворот можно записать двумя наборами углов
     * (и ещё ±360°) — без этого Blockbench между кадрами иногда прокручивает деталь почти на целый оборот.
     */
    static double[] eulerNear(double[][] m, double[] prev) {
        double[] e1 = euler(m);
        if (prev == null) return e1;
        double[] e2 = {e1[0] + 180, 180 - e1[1], e1[2] + 180};
        double[] best = null;
        double bestD = 1e18;
        for (double[] c : new double[][]{e1, e2}) {
            double[] u = new double[3];
            double d = 0;
            for (int k = 0; k < 3; k++) {
                u[k] = c[k] + 360 * Math.round((prev[k] - c[k]) / 360);
                d += (u[k] - prev[k]) * (u[k] - prev[k]);
            }
            if (d < bestD) { bestD = d; best = u; }
        }
        return best;
    }

    static double[][] matMul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    static double[][] transpose(double[][] m) {
        return new double[][]{{m[0][0], m[1][0], m[2][0]}, {m[0][1], m[1][1], m[2][1]}, {m[0][2], m[1][2], m[2][2]}};
    }

    static double[] mul(double[][] m, double[] v) {
        return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    /** Поворот, переводящий направление a в направление b (кратчайший). */
    static double[][] between(double[] a, double[] b) {
        double la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        double[] u = {a[0] / la, a[1] / la, a[2] / la}, v = {b[0] / lb, b[1] / lb, b[2] / lb};
        double[] k = cross(u, v);
        double s = Math.sqrt(k[0] * k[0] + k[1] * k[1] + k[2] * k[2]), c = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
        if (s < 1e-9) return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        k = new double[]{k[0] / s, k[1] / s, k[2] / s};
        double t = 1 - c;
        return new double[][]{
                {c + k[0] * k[0] * t, k[0] * k[1] * t - k[2] * s, k[0] * k[2] * t + k[1] * s},
                {k[1] * k[0] * t + k[2] * s, c + k[1] * k[1] * t, k[1] * k[2] * t - k[0] * s},
                {k[2] * k[0] * t - k[1] * s, k[2] * k[1] * t + k[0] * s, c + k[2] * k[2] * t}};
    }

    /** Ключи косточек кабелей по ключам капсул и корпуса. */
    static void addCableKeys(Map<String, Map<String, double[][]>> keys) {
        int n = KEYS + 1;
        double[][] torsoPos = keys.get("torso").get("position");
        double[][] torsoRot = keys.get("torso").get("rotation"); // в атаках корпус поворачивается
        double[] c0 = PIVOT_OVERRIDE.get("torso");
        for (CableRig rig : RIGS) {
            String arm = rig.side() + "_arm";
            double[][] armRot = keys.get(arm).get("rotation"), armPos = keys.get(arm).get("position");
            double[] pa = PIVOT_OVERRIDE.get(arm);
            double[][] rot = new double[n][], pos = new double[n][], scl = new double[n][];
            for (int i = 0; i < n; i++) {
                double[][] ra = mat(armRot[i]);
                double[] ta = armPos[i], tt = torsoPos[i];
                Map<String, double[][]> car = keys.get(arm + "_carrier");
                if (car != null) {
                    // Капсула внутри носителя: одно общее движение M = Rc·Ra вокруг P, сдвиг Rc(P + tA − C) + C + tC − P.
                    double[][] rc = mat(car.get("rotation")[i]);
                    double[] tc = car.get("position")[i];
                    double[] q = mul(rc, new double[]{pa[0] + ta[0] - c0[0], pa[1] + ta[1] - c0[1], pa[2] + ta[2] - c0[2]});
                    ra = matMul(rc, ra);
                    ta = new double[]{q[0] + c0[0] + tc[0] - pa[0], q[1] + c0[1] + tc[1] - pa[1], q[2] + c0[2] + tc[2] - pa[2]};
                }
                double[][] inv = transpose(ra);
                double[][] rt = mat(torsoRot != null ? torsoRot[i] : new double[3]);
                if (!rig.stretch()) {
                    // Стоит вместе с корпусом: Rig(x) = M(x − P) + P + pos, M = Ra⁻¹·Rt,
                    // pos = M(P − C) + Ra⁻¹(C + tT − P − tA)  (C — опора корпуса).
                    double[][] mm = matMul(inv, rt);
                    double[] a1 = mul(mm, new double[]{pa[0] - c0[0], pa[1] - c0[1], pa[2] - c0[2]});
                    double[] a2 = mul(inv, new double[]{c0[0] + tt[0] - pa[0] - ta[0], c0[1] + tt[1] - pa[1] - ta[1], c0[2] + tt[2] - pa[2] - ta[2]});
                    rot[i] = eulerNear(mm, i > 0 ? rot[i - 1] : null);
                    pos[i] = new double[]{a1[0] + a2[0], a1[1] + a2[1], a1[2] + a2[2]};
                    scl[i] = new double[]{1, 1, 1};
                } else {
                    // Конец у корпуса (в мире Rt(N − C) + C + tT) переводим в систему капсулы и тянем к нему кабель от опоры F.
                    double[] nr = mul(rt, new double[]{rig.torsoEnd()[0] - c0[0], rig.torsoEnd()[1] - c0[1], rig.torsoEnd()[2] - c0[2]});
                    double[] nw = {nr[0] + c0[0] + tt[0], nr[1] + c0[1] + tt[1], nr[2] + c0[2] + tt[2]};
                    double[] local = mul(inv, new double[]{nw[0] - pa[0] - ta[0], nw[1] - pa[1] - ta[1], nw[2] - pa[2] - ta[2]});
                    local = new double[]{local[0] + pa[0], local[1] + pa[1], local[2] + pa[2]};
                    double[] f = rig.pivot();
                    double[] v0 = {rig.torsoEnd()[0] - f[0], rig.torsoEnd()[1] - f[1], rig.torsoEnd()[2] - f[2]};
                    double[] v1 = {local[0] - f[0], local[1] - f[1], local[2] - f[2]};
                    double k = Math.sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2]) / Math.sqrt(v0[0] * v0[0] + v0[1] * v0[1] + v0[2] * v0[2]);
                    rot[i] = eulerNear(between(v0, v1), i > 0 ? rot[i - 1] : null);
                    pos[i] = new double[]{0, 0, 0};
                    scl[i] = new double[]{k, k, k};
                }
            }
            Map<String, double[][]> c = ch("rotation", rot);
            c.put("position", pos);
            c.put("scale", scl);
            keys.put(rig.name(), c);
        }
    }
    static final Map<String, double[]> PIVOT_OVERRIDE = new LinkedHashMap<>();
    static final double WALK_LEN = 1.6;                  // ходьба: цикл из 2 шагов, секунды — тяжёлый медленный шаг
    static final double IDLE_LEN = 3.2;                  // стойка: медленное «дыхание», секунды
    static double LEN = WALK_LEN;                        // длина текущей анимации
    static int KEYS = 32;                                // ключевых кадров на анимацию (+ последний)
    static double[] TIMES = new double[KEYS + 1];

    static void setLen(double len) { setLen(len, 32); }

    static void setLen(double len, int keys) {
        KEYS = keys;
        TIMES = new double[KEYS + 1];
        LEN = len;
        for (int i = 0; i <= KEYS; i++) TIMES[i] = r2(LEN * i / KEYS);
    }

    // ===== Атака: комбо из трёх ударов (по видео пользователя) =====
    static final double[] ATTACK_LEN = {1.0, 1.0, 1.5};
    static final int SMOOTH = 0, STRIKE = 1, WIND = 2, BLOW = 3, HEAVY = 4; // BLOW — резкий удар с перелётом (инерция тяжёлой руки) // переходы: плавный / резкий удар (быстро в начале) / замах (разгон)

    /** Значение дорожки в момент t; ключи {время, значение, тип перехода к этому ключу}. */
    static double track(double t, double[][] k) {
        if (t <= k[0][0]) return k[0][1];
        for (int i = 1; i < k.length; i++) {
            if (t <= k[i][0]) {
                double x = (t - k[i - 1][0]) / (k[i][0] - k[i - 1][0]);
                int type = (int) k[i][2];
                double e = type == HEAVY ? Math.pow(x, 2.4)       // тяжёлое падение: разгон под весом до самого удара
                        : type == BLOW ? 1 + 2.4 * Math.pow(x - 1, 3) + 1.4 * Math.pow(x - 1, 2) // перелёт ~10% и назад
                        : type == STRIKE ? 1 - Math.pow(1 - x, 3)   // резко в начале, плавно в конце
                        : type == WIND ? x * x                        // разгон
                        : x * x * (3 - 2 * x);                        // плавно
                return k[i - 1][1] + (k[i][1] - k[i - 1][1]) * e;
            }
        }
        return k[k.length - 1][1];
    }

    static double[][] K(double... v) {
        double[][] r = new double[v.length / 3][];
        for (int i = 0; i < r.length; i++) r[i] = new double[]{v[i * 3], v[i * 3 + 1], v[i * 3 + 2]};
        return r;
    }

    /**
     * Позы удара в момент t. Возвращает: [0] поворот корпуса (рыскание), [1] наклон корпуса (− вперёд), [2] опускание тела,
     * и для рук (main — бьющая, off — другая, для удара 3 обе одинаковые): рыскание капсулы, наклон капсулы, крен капсулы,
     * подъём предплечья (90 — вперёд горизонтально), отвод предплечья, сжатие пальцев.
     */
    static double[][] attackPose(int stage, double t) {
        double[] body = new double[3], main = new double[6], off = new double[6];
        double c = IDLE_CROUCH;
        if (stage < 2) {
            double peak = stage == 0 ? 85 : 100; // второй удар — по более высокой дуге
            body[0] = track(t, K(0, 0, 0, 0.3, -22, SMOOTH, 0.48, 24, STRIKE, 0.62, 22, SMOOTH, 1.0, 0, SMOOTH));
            body[1] = track(t, K(0, 0, 0, 0.3, 2, SMOOTH, 0.48, -5, STRIKE, 0.62, -4, SMOOTH, 1.0, 0, SMOOTH));
            body[2] = track(t, K(0, c, 0, 0.3, c - 0.6, SMOOTH, 0.48, c - 1.8, STRIKE, 0.62, c - 1.6, SMOOTH, 1.0, c, SMOOTH));
            main[0] = track(t, K(0, 0, 0, 0.3, -40, WIND, 0.48, 55, STRIKE, 0.62, 50, SMOOTH, 1.0, 0, SMOOTH));
            main[1] = track(t, K(0, 0, 0, 0.3, 8, SMOOTH, 0.48, -6, STRIKE, 0.62, -5, SMOOTH, 1.0, 0, SMOOTH));
            main[2] = track(t, K(0, 0, 0, 0.3, -6, SMOOTH, 0.48, 8, STRIKE, 0.62, 6, SMOOTH, 1.0, 0, SMOOTH));
            main[3] = track(t, K(0, 3, 0, 0.3, 60, WIND, 0.48, peak, STRIKE, 0.62, peak - 5, SMOOTH, 1.0, 3, SMOOTH));
            main[4] = track(t, K(0, 0, 0, 0.3, 12, SMOOTH, 0.48, -6, STRIKE, 0.62, -4, SMOOTH, 1.0, 0, SMOOTH));
            main[5] = track(t, K(0, 0, 0, 0.3, -12, SMOOTH, 0.48, 20, STRIKE, 0.7, 18, SMOOTH, 1.0, 0, SMOOTH));
            off[0] = track(t, K(0, 0, 0, 0.3, 10, SMOOTH, 0.48, -15, STRIKE, 0.62, -12, SMOOTH, 1.0, 0, SMOOTH));
            off[1] = track(t, K(0, 0, 0, 0.3, -3, SMOOTH, 0.48, 4, STRIKE, 1.0, 0, SMOOTH));
            off[3] = track(t, K(0, 3, 0, 0.3, -12, SMOOTH, 0.48, 18, STRIKE, 0.62, 15, SMOOTH, 1.0, 3, SMOOTH));
            off[5] = track(t, K(0, 0, 0, 0.3, 6, SMOOTH, 0.48, 10, STRIKE, 1.0, 0, SMOOTH));
        } else {
            // Удар об землю двумя руками: поднять над головой → обрушить → пауза → подняться.
            body[1] = track(t, K(0, 0, 0, 0.45, 6, SMOOTH, 0.6, -18, STRIKE, 0.9, -15, SMOOTH, 1.5, 0, SMOOTH));
            body[2] = track(t, K(0, c, 0, 0.45, c + 0.3, SMOOTH, 0.6, c - 4.5, STRIKE, 0.9, c - 4.0, SMOOTH, 1.5, c, SMOOTH));
            main[1] = track(t, K(0, 0, 0, 0.45, 25, WIND, 0.6, -25, STRIKE, 0.9, -20, SMOOTH, 1.5, 0, SMOOTH));
            main[2] = track(t, K(0, 0, 0, 0.45, -10, SMOOTH, 0.6, 6, STRIKE, 0.9, 5, SMOOTH, 1.5, 0, SMOOTH));
            main[3] = track(t, K(0, 3, 0, 0.45, 150, WIND, 0.6, 35, STRIKE, 0.9, 40, SMOOTH, 1.5, 3, SMOOTH));
            main[5] = track(t, K(0, 0, 0, 0.45, -12, SMOOTH, 0.6, 22, STRIKE, 0.9, 20, SMOOTH, 1.5, 0, SMOOTH));
            off = main.clone();
        }
        return new double[][]{body, main, off};
    }

    /** Ключи удара stage (0..2): удар 1 — правой, удар 2 — левой (зеркально), удар 3 — двумя руками об землю. */
    static Map<String, Map<String, double[][]>> attackKeys(int stage) {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        int mainSide = stage == 1 ? -1 : 1; // удар 1 — правой (+X), удар 2 — левой
        double[] c0 = PIVOT_OVERRIDE.get("torso");
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[10][n][]);
        for (int i = 0; i < n; i++) {
            double t = LEN * i / KEYS;
            double[][] p = attackPose(stage, t);
            double[] body = p[0];
            // Зеркалим повороты для удара левой: рыскание и крен с обратным знаком.
            double yaw = body[0] * mainSide;
            tr[i] = new double[]{body[1], yaw, 0};
            tp[i] = new double[]{0, body[2], 0};
            double[][] rt = mat(tr[i]);
            for (int s : new int[]{-1, 1}) {
                double[] a = s == mainSide ? p[1] : p[2];
                double[][][] out = sides.get(s);
                // Собственный поворот капсулы (для левой стороны рыскание и крен зеркальны).
                double[][] ro = mat(new double[]{a[1], a[0] * s, a[2] * s});
                // Капсула едет вместе с корпусом: итог = Rt·Ro, сдвиг = Rt(P − C) + C − P (+ опускание тела).
                double[] pp = PIVOT_OVERRIDE.get((s < 0 ? "left" : "right") + "_arm");
                double[] rp = mul(rt, new double[]{pp[0] - c0[0], pp[1] - c0[1], pp[2] - c0[2]});
                out[0][i] = euler(matMul(rt, ro));
                out[1][i] = new double[]{rp[0] + c0[0] - pp[0], rp[1] + c0[1] - pp[1] + body[2], rp[2] + c0[2] - pp[2]};
                out[2][i] = new double[]{a[3], 0, a[4] * s};
                for (int k = 0; k < 3; k++) {
                    double curl = a[5] - 1.5 * k * Math.signum(a[5]);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                double[] leg = solveLeg(AY, AZ, 0, body[2]);
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, body[2], 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            m.put(side + "_arm2", ch("rotation", o[2]));
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Атака одной анимацией: 3 удара подряд, медленный тяжёлый замах → резкий удар =====
    static final double COMBO_LEN = 7.0;
    static final int COMBO_KEYS = 328;                   // ключ каждые 0.025 с — резкие удары не размываются
    /** Запаздывание частей за корпусом (секунды): капсулы, предплечья, пальцы — «хлыст». */
    static final double LAG_POD = 0.03, LAG_ARM = 0.07, LAG_FINGER = 0.11;

    // Дорожки (время, значение, переход). S — плавно, B — резкий удар с перелётом.
    static final int TS = SMOOTH, TB = BLOW, TH = HEAVY;
    static final double CC = -0.6; // = IDLE_CROUCH (стойка)
    // Атака по видео пользователя «атака.mp4» (Roblox, с 25 с; разбор по кадрам 8 к/с), 7.0 с:
    // замах — правая рука отходит в сторону-назад, корпус закручен; удар 1 (1.6 с) — широкий горизонтальный размах
    // прямой правой рукой и вниз в землю; левая рука взлетает над головой → удар 2 (3.15 с) с выпадом, правую руку
    // по инерции подбрасывает вверх; руки широко в стороны; правая над головой → удар 3 (5.15 с), медленный подъём.
    static final double[][] T_YAW = K(0, 0, TS, 0.6, 5, TS, 1.25, 34, TS, 1.6, -40, BLOW, 1.95, -30, TS, 2.4, -6, TS,
            3.0, 18, TS, 3.15, -12, BLOW, 3.5, -8, TS, 4.3, 0, TS, 4.95, -18, TS, 5.15, 14, BLOW, 5.6, 10, TS, 7.0, 0, TS);
    static final double[][] T_PITCH = K(0, 0, TS, 0.6, 2, TS, 1.25, 6, TS, 1.45, -4, TS, 1.6, -16, STRIKE, 1.95, -8, TS, 2.4, -2, TS,
            3.0, 9, TS, 3.15, -24, HEAVY, 3.5, -16, TS, 4.3, -4, TS, 4.95, 8, TS, 5.15, -26, HEAVY, 5.6, -22, TS, 6.2, -14, TS, 7.0, 0, TS);
    static final double[][] T_DROP = K(0, CC, TS, 0.6, CC - 1.5, TS, 1.25, CC - 3.5, TS, 1.45, CC - 2.5, TS, 1.6, CC - 4.2, STRIKE,
            1.95, CC - 2.5, TS, 2.4, CC - 1.5, TS, 3.0, CC + 0.3, TS, 3.15, CC - 5.5, HEAVY, 3.5, CC - 4.5, TS, 4.3, CC - 2.5, TS,
            4.95, CC + 0.2, TS, 5.15, CC - 6.0, HEAVY, 5.6, CC - 5.4, TS, 6.2, CC - 4.0, TS, 7.0, CC, TS);
    // Рука: рыскание капсулы (+ поперёк, − наружу-назад), наклон, крен (+ в сторону), подъём предплечья, отвод, пальцы (− раскрыты).
    static final double[][][] T_RIGHT = {
            K(0, 0, TS, 0.6, -15, TS, 1.25, -55, TS, 1.45, 15, BLOW, 1.6, 85, STRIKE, 1.95, 50, TS, 2.4, 5, TS, 3.0, -25, TS, 3.15, -10, BLOW,
                    3.5, 10, TS, 4.3, -40, TS, 4.95, -6, TS, 5.15, 6, HEAVY, 5.6, 4, TS, 7.0, 0, TS),
            K(0, 0, TS, 0.6, 4, TS, 1.25, 10, TS, 1.6, -24, BLOW, 1.95, -10, TS, 2.4, 0, TS, 3.0, -6, TS, 3.15, 4, BLOW, 3.5, 22, TS,
                    4.3, 5, TS, 4.95, 34, TS, 5.15, -32, HEAVY, 5.6, -26, TS, 6.2, -16, TS, 7.0, 0, TS),
            K(0, 0, TS, 0.6, 12, TS, 1.25, 30, TS, 1.6, 6, BLOW, 1.95, 0, TS, 3.0, 8, TS, 3.5, -6, TS, 4.3, 30, TS, 4.95, 8, TS, 5.15, 4, HEAVY, 7.0, 0, TS),
            K(0, 3, TS, 0.6, 12, TS, 1.25, 35, TS, 1.45, 92, BLOW, 1.6, 48, STRIKE, 1.95, 40, TS, 2.4, 20, TS, 3.0, 10, TS, 3.15, 30, BLOW,
                    3.5, 140, TS, 4.3, 75, TS, 4.95, 170, TS, 5.15, 34, HEAVY, 5.6, 38, TS, 6.2, 30, TS, 7.0, 3, TS),
            K(0, 0, TS, 0.6, 10, TS, 1.25, 35, TS, 1.45, 0, BLOW, 1.6, -8, TS, 1.95, 0, TS, 3.0, 15, TS, 3.5, -6, TS, 4.3, 30, TS,
                    4.95, -8, TS, 5.15, 6, HEAVY, 7.0, 0, TS),
            K(0, 0, TS, 0.6, -10, TS, 1.25, -20, TS, 1.45, 24, BLOW, 1.6, 28, TS, 1.95, 12, TS, 2.4, 0, TS, 3.0, -6, TS, 3.5, -14, TS,
                    4.3, -18, TS, 4.95, 26, TS, 5.15, 30, HEAVY, 5.6, 26, TS, 6.2, 14, TS, 7.0, 0, TS)};
    static final double[][][] T_LEFT = {
            K(0, 0, TS, 0.6, -8, TS, 1.25, -20, TS, 1.6, -40, BLOW, 1.95, -30, TS, 2.4, -10, TS, 3.0, -6, TS, 3.15, 30, HEAVY, 3.5, 20, TS,
                    4.3, -40, TS, 4.95, -30, TS, 5.15, -36, BLOW, 5.6, -30, TS, 7.0, 0, TS),
            K(0, 0, TS, 0.6, 2, TS, 1.25, 4, TS, 1.6, 8, BLOW, 1.95, 0, TS, 2.4, 10, TS, 3.0, 36, TS, 3.15, -32, HEAVY, 3.5, -24, TS,
                    4.3, 5, TS, 4.95, 6, TS, 5.15, 8, BLOW, 5.6, 4, TS, 7.0, 0, TS),
            K(0, 0, TS, 0.6, 6, TS, 1.25, 10, TS, 1.6, 14, TS, 1.95, 0, TS, 3.0, -8, TS, 3.15, 6, HEAVY, 4.3, 30, TS, 4.95, 24, TS,
                    5.15, 16, BLOW, 7.0, 0, TS),
            K(0, 3, TS, 0.6, 10, TS, 1.25, 20, TS, 1.6, 18, TS, 1.95, 30, TS, 2.4, 80, TS, 3.0, 172, TS, 3.15, 32, HEAVY, 3.5, 36, TS,
                    4.3, 75, TS, 4.95, 60, TS, 5.15, 40, BLOW, 5.6, 30, TS, 7.0, 3, TS),
            K(0, 0, TS, 0.6, 6, TS, 1.25, 12, TS, 1.6, 16, TS, 1.95, 0, TS, 3.0, -8, TS, 3.15, 6, HEAVY, 4.3, 30, TS, 4.95, 24, TS,
                    5.15, 10, BLOW, 7.0, 0, TS),
            K(0, 0, TS, 0.6, -6, TS, 1.6, 4, TS, 1.95, 6, TS, 2.4, -12, TS, 3.0, 26, TS, 3.15, 30, HEAVY, 3.5, 26, TS, 4.3, -18, TS,
                    4.95, -14, TS, 5.15, -4, TS, 6.2, 6, TS, 7.0, 0, TS)};
    // Время дорожек уже настоящее (без растяжения).
    static final double[] OLD_T = {0, 7.0};
    static final double[] NEW_T = {0, 7.0};
    static double toOld(double t) {
        if (t <= 0) return 0;
        for (int i = 1; i < NEW_T.length; i++) {
            if (t <= NEW_T[i]) return OLD_T[i - 1] + (OLD_T[i] - OLD_T[i - 1]) * (t - NEW_T[i - 1]) / (NEW_T[i] - NEW_T[i - 1]);
        }
        return OLD_T[OLD_T.length - 1];
    }

    static double tr(double tNew, double[][] k) {
        return track(toOld(tNew), k);
    }

    /** Растяжение предплечья (scale по длине руки): в своём хуке — дотянуться, в ударе об землю — до земли (kSlam). */
    static double[][] armStretch(int s, double kSlam) {
        double h1 = s > 0 ? 1.12 : 1.0, h2 = s > 0 ? 1.0 : 1.12;
        // Правая рука в горизонтальном размахе вытягивается; в своём ударе об землю (левая — 2-й, правая — 3-й) — до земли.
        double sweep = s > 0 ? 1.15 : 1.0, mid = s > 0 ? 1.08 : 1.0;
        double k2 = s < 0 ? kSlam : 1.0, k3 = s > 0 ? kSlam : 1.0;
        return K(0, 1, TS, 1.25, 1, TS, 1.45, sweep, BLOW, 1.6, mid, TS, 1.95, 1, TS, 3.0, 1, TS, 3.15, k2, HEAVY, 3.5, 1, TS,
                4.95, 1, TS, 5.15, k3, HEAVY, 5.6, k3, TS, 6.4, 1, TS, 7.0, 1, TS);
    }
    static double SLAM_STRETCH = 1.3;

    /** Ключи атаки: каждые 0.025 с, а в моменты ударов — каждые 0.005 с (быстрые повороты без расхождений). */
    static void setComboTimes() {
        List<Double> ts = new ArrayList<>();
        double[][] fast = {{1.20, 1.70}, {3.00, 3.30}, {5.00, 5.30}};
        double t = 0;
        while (t < COMBO_LEN - 1e-9) {
            ts.add(Math.round(t * 1000) / 1000.0);
            boolean inFast = false;
            for (double[] w : fast) if (t >= w[0] - 1e-9 && t < w[1]) inFast = true;
            t += inFast ? 0.005 : 0.025;
        }
        ts.add(COMBO_LEN);
        LEN = COMBO_LEN;
        KEYS = ts.size() - 1;
        TIMES = new double[ts.size()];
        for (int i = 0; i < TIMES.length; i++) TIMES[i] = ts.get(i);
    }

    /** Одна анимация «attack»: 3 удара подряд с запаздыванием частей («хлыст») и перелётом при ударе. */
    static Map<String, Map<String, double[][]>> comboKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[] c0 = PIVOT_OVERRIDE.get("torso");
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double drop = tr(t, T_DROP);
            tr[i] = new double[]{tr(t, T_PITCH), tr(t, T_YAW), 0};
            tp[i] = new double[]{0, drop, 0};
            // Капсулы едут с корпусом, но чуть позже — берём поворот корпуса с запаздыванием.
            double tPod = t - LAG_POD;
            double[][] rt = mat(new double[]{tr(tPod, T_PITCH), tr(tPod, T_YAW), 0});
            double dropPod = tr(tPod, T_DROP);
            for (int s : new int[]{-1, 1}) {
                double[][][] tk = s > 0 ? T_RIGHT : T_LEFT;
                double[][][] out = sides.get(s);
                // Капсула: только свой поворот — углы пишутся напрямую. Поворот корпуса (с запаздыванием) несёт «носитель».
                out[0][i] = new double[]{tr(tPod, tk[1]), tr(tPod, tk[0]) * s, tr(tPod, tk[2]) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{tr(tPod, T_PITCH), tr(tPod, T_YAW), 0};
                out[12][i] = new double[]{0, dropPod, 0};
                double tArm = t - LAG_ARM, tFin = t - LAG_FINGER;
                out[2][i] = new double[]{tr(tArm, tk[3]), 0, tr(tArm, tk[4]) * s};
                out[10][i] = new double[]{1, track(tArm, armStretch(s, SLAM_STRETCH)), 1};
                for (int k = 0; k < 3; k++) {
                    double curl = tr(tFin - 0.03 * k, tk[5]); // пальцы смыкаются по очереди
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                double[] leg = solveLeg(AY, AZ, 0, drop);
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Появление (spawn): падение с неба → тяжёлое приземление → подъём в стойку =====
    static final double SPAWN_LEN = 4.2;
    /** Высота, с которой падает костюм (пиксели модели; 16 = 1 блок). */
    static final double SPAWN_HEIGHT = 400;
    static final int TST = STRIKE, TW = WIND;
    static final double[][] S_FALL = K(0, SPAWN_HEIGHT, TS, 1.0, 0, TW, 4.2, 0, TS);           // разгон как при падении
    static final double[][] S_DROP = K(0, 0, TS, 1.0, 0, TS, 1.16, -7.0, TST, 1.3, -6.5, TS, 1.9, -6.8, TS,
            3.2, CC + 0.3, TS, 3.7, CC, TS, 4.2, CC, TS);
    static final double[][] S_PITCH = K(0, 6, TS, 0.5, -4, TS, 0.85, 4, TS, 1.0, 4, TS, 1.2, -22, TST, 1.9, -18, TS,
            3.2, 2, TS, 3.7, 0, TS, 4.2, 0, TS);
    static final double[][] S_YAW = K(0, 0, TS, 0.3, 8, TS, 0.65, -6, TS, 1.0, 0, TS, 4.2, 0, TS);
    static final double[][] S_ROLL = K(0, 0, TS, 0.4, -4, TS, 0.8, 3, TS, 1.0, 0, TS, 4.2, 0, TS);
    static final double[][] S_TUCK = K(0, 3.5, TS, 0.75, 3.5, TS, 0.97, 0, TS, 4.2, 0, TS);   // поджатые в полёте ноги
    static final double[][] S_TOE = K(0, -15, TS, 0.75, -15, TS, 0.97, 0, TS, 4.2, 0, TS);    // носки свисают в полёте
    // Руки (обе одинаково, левая зеркально): рыскание, наклон, крен капсулы, подъём и отвод предплечья, пальцы, растяжение.
    static final double[][][] S_ARM = {
            K(0, 0, TS, 0.4, 6, TS, 0.8, -4, TS, 1.0, 0, TS, 4.2, 0, TS),
            K(0, -12, TS, 0.8, -8, TS, 1.0, 0, TS, 1.2, -18, TST, 1.9, -14, TS, 3.2, 0, TS, 4.2, 0, TS),
            K(0, -14, TS, 0.9, -10, TS, 1.0, 0, TS, 1.25, 6, TST, 3.2, 0, TS, 4.2, 0, TS),
            K(0, 70, TS, 0.8, 55, TS, 1.0, 30, TS, 1.22, 60, TST, 1.5, 45, TS, 1.9, 40, TS, 3.2, 3, TS, 4.2, 3, TS),
            K(0, 18, TS, 0.9, 12, TS, 1.0, 4, TS, 1.3, -4, TS, 3.2, 0, TS, 4.2, 0, TS),
            K(0, -14, TS, 0.9, -10, TS, 1.0, -8, TS, 1.2, 22, TST, 1.9, 20, TS, 3.2, 0, TS, 4.2, 0, TS),
            K(0, 1, TS, 1.1, 1, TS, 1.3, 1.1, TS, 1.9, 1.1, TS, 2.8, 1, TS, 4.2, 1, TS)};

    /** Ключи: в момент приземления (0.95–1.4 с) — чаще. */
    static void setSpawnTimes() {
        List<Double> ts = new ArrayList<>();
        double t = 0;
        while (t < SPAWN_LEN - 1e-9) {
            ts.add(Math.round(t * 1000) / 1000.0);
            t += (t >= 0.9 && t < 1.45) ? 0.01 : 0.025;
        }
        ts.add(SPAWN_LEN);
        LEN = SPAWN_LEN;
        KEYS = ts.size() - 1;
        TIMES = new double[ts.size()];
        for (int i = 0; i < TIMES.length; i++) TIMES[i] = ts.get(i);
    }

    static Map<String, Map<String, double[][]>> spawnKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double fall = track(t, S_FALL), drop = track(t, S_DROP);
            tr[i] = new double[]{track(t, S_PITCH), track(t, S_YAW), track(t, S_ROLL)};
            tp[i] = new double[]{0, fall + drop, 0};
            double tPod = t - LAG_POD, tArm = t - LAG_ARM, tFin = t - LAG_FINGER;
            double[] leg = t < 1.0
                    ? solveLeg(AY + track(t, S_TUCK), AZ - 0.5 * track(t, S_TUCK) / 3.5, track(t, S_TOE), 0) // в полёте: поджаты
                    : solveLeg(AY, AZ, 0, drop);                                                         // на земле: стопы стоят
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                out[0][i] = new double[]{track(tPod, S_ARM[1]), track(tPod, S_ARM[0]) * s, track(tPod, S_ARM[2]) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{track(tPod, S_PITCH), track(tPod, S_YAW), track(tPod, S_ROLL)};
                out[12][i] = new double[]{0, fall + track(tPod, S_DROP), 0}; // летят вместе с корпусом, оседают с запаздыванием
                out[2][i] = new double[]{track(tArm, S_ARM[3]), 0, track(tArm, S_ARM[4]) * s};
                out[10][i] = new double[]{1, track(tArm, S_ARM[6]), 1};
                for (int k = 0; k < 3; k++) {
                    double curl = track(tFin - 0.03 * k, S_ARM[5]);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, fall + (t < 1.0 ? 0 : drop), 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Захват (grab): кат-сцена посадки — костюм резко хватает игрока двумя руками =====
    static final double GRAB_LEN = 1.4;
    /** Момент, когда руки сомкнулись (в игре — ровно тогда экран чернеет). */
    static final double GRAB_CLAMP = 0.55;
    static final double[][] G_PITCH = K(0, 0, TS, 0.35, 7, TS, GRAB_CLAMP, -17, TST, 0.8, -13, TS, 1.4, -12, TS);  // откинулся назад → рывок вперёд
    static final double[][] G_DROP = K(0, CC, TS, 0.35, CC + 0.6, TS, GRAB_CLAMP, -3.0, TST, 0.85, -2.4, TS, 1.4, -2.4, TS);
    static final double[][] G_YAW = K(0, 0, TS, 1.4, 0, TS);
    // Руки (левая зеркально): рыскание капсулы (+ внутрь), наклон, крен, подъём предплечья, отвод, пальцы, растяжение.
    static final double[][][] G_ARM = {
            K(0, 0, TS, 0.35, -24, TS, GRAB_CLAMP, 27, TST, 0.9, 24, TS, 1.4, 24, TS),
            K(0, 0, TS, 0.35, 6, TS, GRAB_CLAMP, -12, TST, 1.4, -10, TS),
            K(0, 0, TS, 0.35, -8, TS, GRAB_CLAMP, 5, TST, 1.4, 4, TS),
            K(0, 4, TS, 0.35, 42, TS, GRAB_CLAMP, 80, TST, 0.9, 74, TS, 1.4, 74, TS),
            K(0, 0, TS, 0.35, 10, TS, GRAB_CLAMP, -6, TST, 1.4, -5, TS),
            K(0, 0, TS, 0.35, -16, TS, GRAB_CLAMP - 0.02, -10, TS, GRAB_CLAMP + 0.06, 26, TST, 1.4, 28, TS),
            K(0, 1, TS, 0.35, 1, TS, GRAB_CLAMP, 1.12, TST, 1.4, 1.08, TS)};

    /** Ключи: в рывке (0.3–0.7 с) — чаще. */
    static void setGrabTimes() {
        List<Double> ts = new ArrayList<>();
        double t = 0;
        while (t < GRAB_LEN - 1e-9) {
            ts.add(Math.round(t * 1000) / 1000.0);
            t += (t >= 0.3 && t < 0.75) ? 0.01 : 0.025;
        }
        ts.add(GRAB_LEN);
        LEN = GRAB_LEN;
        KEYS = ts.size() - 1;
        TIMES = new double[ts.size()];
        for (int i = 0; i < TIMES.length; i++) TIMES[i] = ts.get(i);
    }

    static Map<String, Map<String, double[][]>> grabKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double drop = track(t, G_DROP);
            tr[i] = new double[]{track(t, G_PITCH), track(t, G_YAW), 0};
            tp[i] = new double[]{0, drop, 0};
            double tPod = t - LAG_POD, tArm = t - LAG_ARM, tFin = t - LAG_FINGER;
            double[] leg = solveLeg(AY, AZ, 0, drop); // стопы стоят на месте, колени пружинят
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                out[0][i] = new double[]{track(tPod, G_ARM[1]), track(tPod, G_ARM[0]) * s, track(tPod, G_ARM[2]) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{track(tPod, G_PITCH), track(tPod, G_YAW), 0};
                out[12][i] = new double[]{0, track(tPod, G_DROP), 0};
                out[2][i] = new double[]{track(tArm, G_ARM[3]), 0, track(tArm, G_ARM[4]) * s};
                out[10][i] = new double[]{1, track(tArm, G_ARM[6]), 1};
                for (int k = 0; k < 3; k++) {
                    double curl = track(tFin - 0.02 * k, G_ARM[5]);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Способность «вихрь» (skill): заряд → взлёт → парит (руки разведены) → удар вниз → плавный спуск =====
    static final double SKILL_LEN = 5.0;
    /** Удар (все вокруг падают) — как взрыв в звуке из видео пользователя (3.6 с от начала). */
    static final double SKILL_SLAM = 3.6;
    static final double[][] SK_PITCH = K(0, 0, TS, 0.5, -8, TS, 1.0, 6, TS, 3.3, 4, TS, 3.45, 10, TS, SKILL_SLAM, -20, TST,
            4.2, -8, TS, 5.0, 0, TS);
    static final double[][] SK_DROP = K(0, CC, TS, 0.5, -3.5, TS, 0.95, -0.4, TS, 3.45, -0.6, TS, SKILL_SLAM, -2.6, TST,
            4.3, -1.0, TS, 4.75, -1.2, TS, 4.88, -3.2, TST, 5.0, CC, TS);
    /** Ноги: поджаты в воздухе (0.6–4.7 с), носки свисают. */
    static final double[][] SK_TUCK = K(0, 0, TS, 0.6, 0, TS, 1.0, 3.0, TS, 4.5, 3.0, TS, 4.82, 0, TS, 5.0, 0, TS);
    static final double[][] SK_TOE = K(0, 0, TS, 0.6, 0, TS, 1.0, -18, TS, 4.5, -18, TS, 4.82, 0, TS, 5.0, 0, TS);
    // Руки (левая зеркально): рыскание капсулы (+ внутрь), наклон, крен, подъём предплечья, отвод, пальцы, растяжение.
    static final double[][][] SK_ARM = {
            K(0, 0, TS, 0.5, -10, TS, 1.0, -30, TS, 3.45, -38, TS, SKILL_SLAM, 10, TST, 4.3, 0, TS, 5.0, 0, TS),
            K(0, 0, TS, 1.0, -15, TS, 3.45, -25, TS, SKILL_SLAM, 15, TST, 4.3, 2, TS, 5.0, 0, TS),
            K(0, 0, TS, 1.0, -18, TS, 3.45, -22, TS, SKILL_SLAM, 6, TST, 4.3, 0, TS, 5.0, 0, TS),
            K(0, 4, TS, 0.5, 20, TS, 1.0, 35, TS, 3.45, 55, TS, SKILL_SLAM, -10, TST, 4.3, 8, TS, 5.0, 4, TS),
            K(0, 0, TS, 1.0, 18, TS, 3.45, 22, TS, SKILL_SLAM, 0, TST, 5.0, 0, TS),
            K(0, 0, TS, 0.5, 10, TS, 1.0, -18, TS, 3.45, -20, TS, SKILL_SLAM, 26, TST, 4.5, 8, TS, 5.0, 0, TS),
            K(0, 1, TS, 5.0, 1, TS)};

    /** Покачивание в воздухе (пиксели и градусы): только пока парит. */
    static double skillHover(double t) {
        double k = Math.min(smooth((t - 0.9) / 0.4), 1 - smooth((t - 3.3) / 0.25));
        return k * Math.sin((t - 0.9) * 2 * Math.PI / 1.2);
    }

    /** Ключи: на ударе и посадке — чаще. */
    static void setSkillTimes() {
        List<Double> ts = new ArrayList<>();
        double t = 0;
        while (t < SKILL_LEN - 1e-9) {
            ts.add(Math.round(t * 1000) / 1000.0);
            t += ((t >= 3.4 && t < 3.8) || (t >= 4.75 && t < 5.0)) ? 0.01 : 0.025;
        }
        ts.add(SKILL_LEN);
        LEN = SKILL_LEN;
        KEYS = ts.size() - 1;
        TIMES = new double[ts.size()];
        for (int i = 0; i < TIMES.length; i++) TIMES[i] = ts.get(i);
    }

    static Map<String, Map<String, double[][]>> skillKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double hover = skillHover(t);
            double drop = track(t, SK_DROP) + 0.5 * hover;
            tr[i] = new double[]{track(t, SK_PITCH) + 1.5 * hover, 0, 0};
            tp[i] = new double[]{0, drop, 0};
            double tPod = t - LAG_POD, tArm = t - LAG_ARM, tFin = t - LAG_FINGER;
            double tuck = track(t, SK_TUCK);
            double[] leg = tuck > 0.01
                    ? solveLeg(AY + tuck, AZ - 0.5 * tuck / 3.0, track(t, SK_TOE), 0) // в воздухе: ноги поджаты, носки свисают
                    : solveLeg(AY, AZ, 0, drop);                                      // на земле: стопы стоят
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                double hoverPod = skillHover(tPod);
                out[0][i] = new double[]{track(tPod, SK_ARM[1]) - 3 * hoverPod, track(tPod, SK_ARM[0]) * s, (track(tPod, SK_ARM[2]) + 3 * hoverPod) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{track(tPod, SK_PITCH) + 1.5 * hoverPod, 0, 0};
                out[12][i] = new double[]{0, track(tPod, SK_DROP) + 0.5 * hoverPod, 0};
                out[2][i] = new double[]{track(tArm, SK_ARM[3]) + 5 * skillHover(tArm), 0, track(tArm, SK_ARM[4]) * s};
                out[10][i] = new double[]{1, track(tArm, SK_ARM[6]), 1};
                for (int k = 0; k < 3; k++) {
                    double curl = track(tFin - 0.02 * k, SK_ARM[5]);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, tuck > 0.01 ? 0 : drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Шаг вбок (strafe): по видео «ходьба боком.mp4» — смотрит вперёд, приставным шагом идёт вправо (+X) =====
    static final double STRAFE_LEN = 1.3;
    /** Насколько стопа уходит в сторону от своего места (пиксели), высота подъёма, присед. */
    static final double STRAFE_A = 5.5, STRAFE_LIFT = 2.8, STRAFE_CROUCH = -2.6;
    /** Угол пути стоп от «прямо вперёд» (градусы): W+A / W+D — по диагонали. */
    static final double STRAFE_ANGLE = 42;

    /** Смещение стопы вбок и её подъём по фазе ноги p (0..1): опора — едет с +A на −A, перенос — дугой обратно. */
    static double[] strafeFoot(double p) {
        p = ((p % 1) + 1) % 1;
        if (p < 0.5) return new double[]{STRAFE_A - 2 * STRAFE_A * (p / 0.5), 0};
        double s = (p - 0.5) / 0.5;
        return new double[]{-STRAFE_A + 2 * STRAFE_A * smooth(s), STRAFE_LIFT * Math.sin(Math.PI * s)};
    }

    /** dir = +1 — вперёд-вправо (+X модели), -1 — вперёд-влево. */
    static Map<String, Map<String, double[][]>> strafeKeys(int dir) {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        double legLen = HY - AY;
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double ph = 2 * Math.PI * t / STRAFE_LEN;
            // Перевалка: корпус наклоняется к опорной ноге, проседает на каждом шаге.
            double drop = STRAFE_CROUCH - 0.6 * Math.pow(Math.sin(ph), 2);
            double sway = 1.5 * Math.cos(ph);
            double lat = Math.sin(Math.toRadians(STRAFE_ANGLE)) * dir, fwd = Math.cos(Math.toRadians(STRAFE_ANGLE));
            tr[i] = new double[]{-4, 0, sway};
            tp[i] = new double[]{0, drop, 0};
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                double[] f = strafeFoot(t / STRAFE_LEN + (s > 0 ? 0 : 0.5));
                // Стопа идёт по диагонали: вбок (lat) и вперёд (fwd); вперёд = меньше z.
                double roll = Math.toDegrees(Math.atan2(f[0] * lat, legLen));
                double[] leg = solveLeg(AY + f[1], AZ - f[0] * fwd, 0, drop);
                double lag = Math.cos(ph - 0.5);
                out[0][i] = new double[]{3 * Math.sin(ph - 0.4), 2 * lag * s, (sway * 1.2 + 2) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{-4, 0, sway * 0.8};
                out[12][i] = new double[]{0, STRAFE_CROUCH - 0.6 * Math.pow(Math.sin(ph - 0.3), 2), 0};
                // Предплечья качаются в противофазе ногам, как при обычной ходьбе, но спокойнее.
                out[2][i] = new double[]{6 - 12 * Math.cos(ph - 0.3) * s, 0, 3 * s};
                out[10][i] = new double[]{1, 1, 1};
                for (int k = 0; k < 3; k++) {
                    double curl = -4 + 6 * Math.sin(ph - 1.0 - 0.3 * k);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, roll};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, -roll}; // стопа остаётся ровной
                out[9][i] = new double[]{0, drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Скольжение (glide): «Последний бой» — не шагает, а скользит по земле =====
    static final double GLIDE_LEN = 1.2;

    /**
     * Поза скольжения (цикл 1.2 с): присел, корпус наклонён вперёд, одна нога впереди, другая отставлена назад,
     * капсулы и предплечья отведены назад (встречный поток), лёгкое покачивание.
     */
    static Map<String, Map<String, double[][]>> glideKeys() {
        // «Спидстер-конькобежец»: корпус низко и наклонён, ноги по очереди толкаются назад-в-сторону,
        // руки отброшены потоком и машут в противофазе, корпус ритмично качается, пальцы трепещут.
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        double legLen = HY - AY;
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double ph = 2 * Math.PI * t / GLIDE_LEN;
            double push = Math.pow(Math.max(0, Math.sin(ph)), 2) + Math.pow(Math.max(0, -Math.sin(ph)), 2); // толчок каждой ногой
            double drop = -4.2 + 0.9 * push;
            double sway = Math.sin(ph); // корпус уходит к толкающей ноге
            tr[i] = new double[]{-22 + 2.5 * Math.cos(2 * ph), 9 * sway, -6 * sway};
            tp[i] = new double[]{0, drop, 0};
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                // Фаза ноги: 0..0.5 — толчок (стопа уходит назад и в сторону), 0.5..1 — возврат по воздуху.
                double p = ((t / GLIDE_LEN + (s > 0 ? 0 : 0.5)) % 1.0 + 1.0) % 1.0;
                double back, lat, lift;
                if (p < 0.5) {
                    double k = smooth(p / 0.5);
                    back = -2 + 9 * k;
                    lat = 4.5 * k;
                    lift = 0;
                } else {
                    double k = smooth((p - 0.5) / 0.5);
                    back = 7 - 9 * k;
                    lat = 4.5 * (1 - k);
                    lift = 2.6 * Math.sin(Math.PI * k);
                }
                double[] leg = solveLeg(AY + lift, AZ + back, p < 0.5 ? -10 * smooth(p / 0.5) : -6, drop);
                double roll = Math.toDegrees(Math.atan2(lat, legLen)) * s;
                out[6][i] = new double[]{leg[0], 0, roll};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, -roll};
                out[9][i] = new double[]{0, drop, 0};
                // Руки: отброшены назад, машут в противофазе ногам (как у конькобежца), капсулы откинуты и «пляшут».
                double armPh = Math.sin(ph + (s > 0 ? Math.PI : 0));
                out[0][i] = new double[]{28 + 6 * armPh, (-14 - 6 * armPh) * s, (-12 + 4 * sway * s) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{-22 + 2.5 * Math.cos(2 * ph - 0.4), 7 * Math.sin(ph - 0.3), -5 * Math.sin(ph - 0.3)};
                out[12][i] = new double[]{0, -4.2 + 0.9 * push, 0};
                out[2][i] = new double[]{-30 + 22 * armPh, 0, (8 + 5 * Math.max(0, armPh)) * s};
                out[10][i] = new double[]{1, 1.04, 1};
                for (int k = 0; k < 3; k++) {
                    double curl = -12 + 5 * Math.sin(ph * 3 - 0.6 * k + s);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
            }
        }        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Гибель (death): после «Последнего боя» — дрожь, колени подгибаются, склоняется и падает лицом вперёд =====
    static final double DEATH_LEN = 3.5;
    /** Удар о землю. */
    static final double DEATH_HIT = 2.35;
    static final double[][] D_PITCH = K(0, 0, TS, 0.6, -4, TS, 1.4, -24, TS, DEATH_HIT, -78, TW, 2.5, -71, TS, 2.7, -76, TS, 3.5, -76, TS);
    static final double[][] D_DROP = K(0, CC, TS, 0.6, -1.5, TS, 1.4, -8, TS, DEATH_HIT, -11, TW, 2.5, -10.3, TS, 2.7, -10.8, TS, 3.5, -10.8, TS);
    static final double[][] D_ROLL = K(0, 0, TS, 1.4, 3, TS, DEATH_HIT, -6, TW, 3.5, -5, TS);
    // Руки (левая зеркально): рыскание капсулы (+ внутрь), наклон, крен, подъём предплечья, отвод, пальцы, растяжение.
    static final double[][][] D_ARM = {
            K(0, 0, TS, 1.4, -4, TS, DEATH_HIT, -10, TW, 3.5, -10, TS),
            K(0, 0, TS, 0.6, 12, TS, 1.4, 8, TS, DEATH_HIT, -20, TW, 2.5, -14, TS, 2.7, -18, TS, 3.5, -18, TS),
            K(0, 0, TS, 1.4, -10, TS, DEATH_HIT, -24, TW, 3.5, -22, TS),
            K(0, 4, TS, 0.6, -6, TS, 1.4, -4, TS, DEATH_HIT, 45, TW, 2.5, 36, TS, 2.7, 40, TS, 3.5, 40, TS),
            K(0, 0, TS, DEATH_HIT, 10, TW, 3.5, 10, TS),
            K(0, 0, TS, 0.6, -10, TS, DEATH_HIT, -14, TS, 2.6, -4, TS, 3.5, -10, TS),
            K(0, 1, TS, 3.5, 1, TS)};

    /** Дрожь в первые 0.7 с (последние силы уходят). */
    static double deathShudder(double t) {
        double k = 1 - smooth(t / 0.7);
        return k * (Math.sin(t * 2 * Math.PI * 11) + 0.5 * Math.sin(t * 2 * Math.PI * 17 + 1));
    }

    static void setDeathTimes() {
        List<Double> ts = new ArrayList<>();
        double t = 0;
        while (t < DEATH_LEN - 1e-9) {
            ts.add(Math.round(t * 1000) / 1000.0);
            t += (t < 0.7 || (t >= 1.4 && t < 2.9)) ? 0.01 : 0.025;
        }
        ts.add(DEATH_LEN);
        LEN = DEATH_LEN;
        KEYS = ts.size() - 1;
        TIMES = new double[ts.size()];
        for (int i = 0; i < TIMES.length; i++) TIMES[i] = ts.get(i);
    }

    static Map<String, Map<String, double[][]>> deathKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double drop = track(t, D_DROP);
            double sh = deathShudder(t);
            tr[i] = new double[]{track(t, D_PITCH) + 1.5 * sh, 0.8 * sh, track(t, D_ROLL)};
            tp[i] = new double[]{0, drop, 0};
            double tPod = t - LAG_POD, tArm = t - LAG_ARM, tFin = t - LAG_FINGER;
            double[] leg = solveLeg(AY, AZ, 0, drop); // стопы на месте, колени подгибаются
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                double shPod = deathShudder(tPod);
                out[0][i] = new double[]{track(tPod, D_ARM[1]) + 2 * shPod, track(tPod, D_ARM[0]) * s, track(tPod, D_ARM[2]) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{track(tPod, D_PITCH) + 1.5 * shPod, 0, track(tPod, D_ROLL)};
                out[12][i] = new double[]{0, track(tPod, D_DROP), 0};
                out[2][i] = new double[]{track(tArm, D_ARM[3]) + 3 * deathShudder(tArm), 0, track(tArm, D_ARM[4]) * s};
                out[10][i] = new double[]{1, track(tArm, D_ARM[6]), 1};
                for (int k = 0; k < 3; k++) {
                    double curl = track(tFin - 0.03 * k, D_ARM[5]);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Гибель v2: колени подгибаются → на коленях → всё тело валится вперёд и падает плашмя =====
    /** Падение начинается (после коленей) и удар о землю — секунды. */
    static final double FALL_START = 1.45;

    /** Поза «на коленях» → «вытянулся лёжа»: k — 0 стоит, 1 на коленях, 2 тело выпрямлено для падения. */
    static Map<String, Map<String, double[][]>> deathKeys2() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double sh = deathShudder(t);
            double kneel = smooth((t - 0.6) / 0.85);                 // 0.6–1.45 колени подгибаются
            double straight = smooth((t - FALL_START) / 0.75);        // во время падения тело выпрямляется
            double hit = t >= DEATH_HIT ? Math.exp(-(t - DEATH_HIT) * 9) * Math.sin((t - DEATH_HIT) * 40) : 0;
            // Бёдра опускаются до колен, потом (в падении) нога снова выпрямляется.
            double hipDrop = CC + (-10.0 - CC) * kneel * (1 - straight);
            double torsoPitch = -4 * kneel - 22 * kneel * (1 - straight) + 1.5 * sh + 3 * hit;
            tr[i] = new double[]{torsoPitch, 0.8 * sh, -3 * kneel + 6 * straight};
            tp[i] = new double[]{0, hipDrop, 0};
            // Голень ложится на землю назад (на коленях), в падении нога вытягивается.
            double ankleZ = AZ + 8.5 * kneel * (1 - straight);
            double ankleY = AY + 0.4 * kneel * (1 - straight);
            double[] leg = solveLeg(ankleY, ankleZ, -40 * kneel * (1 - straight), hipDrop);
            double tPod = t - LAG_POD, tArm = t - LAG_ARM;
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                double brace = smooth((tArm - 1.6) / 0.45) * (1 - smooth((tArm - DEATH_HIT - 0.05) / 0.3)); // руки вперёд — упереться
                double splay = smooth((tArm - DEATH_HIT) / 0.4);                                           // после удара — раскинуты
                double podPitch = 6 * kneel + 2 * deathShudder(tPod) - 10 * brace + 4 * splay;
                out[0][i] = new double[]{podPitch, (-6 * kneel + 10 * splay) * s, (-8 * kneel - 20 * splay) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{torsoPitch, 0, 0};
                out[12][i] = new double[]{0, hipDrop, 0};
                double arm = 4 + 14 * kneel * (1 - brace) + 62 * brace - 10 * splay + 3 * deathShudder(tArm) + 6 * hit;
                out[2][i] = new double[]{arm, 0, (4 + 18 * splay) * s};
                out[10][i] = new double[]{1, 1, 1};
                for (int k = 0; k < 3; k++) {
                    double curl = -12 * kneel + 18 * brace - 8 * splay - 6 * hit;
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, hipDrop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    /** Угол падения всего тела вперёд (градусы, минус — вперёд) со своей «гравитацией» и отскоком. */
    static double fallAngle(double t) {
        if (t <= FALL_START) return 0;
        if (t <= DEATH_HIT) {
            double x = (t - FALL_START) / (DEATH_HIT - FALL_START);
            return -86 * Math.pow(x, 2.3);
        }
        double d = t - DEATH_HIT;
        return -86 + 5 * Math.exp(-d * 7) * Math.sin(d * 22); // отскок после удара
    }

    /**
     * Падение целиком: каждую корневую кость поворачиваем вместе вокруг точки у колен (как одно тело)
     * и в каждом кадре опускаем/поднимаем так, чтобы самая нижняя точка модели касалась земли (y = 0).
     */
    static void applyDeathFall(Map<String, Map<String, double[][]>> keys, List<Map<String, Object>> elements) {
        int n = KEYS + 1;
        List<Group> roots = new ArrayList<>();
        for (Group g : GROUPS_BY_NAME.values()) if (g.parent() == null && !g.name().equals("cube")) roots.add(g);
        for (Group g : roots) {
            Map<String, double[][]> ch = keys.computeIfAbsent(g.name(), k -> new LinkedHashMap<>());
            if (!ch.containsKey("rotation")) { double[][] z = new double[n][]; for (int i = 0; i < n; i++) z[i] = new double[3]; ch.put("rotation", z); }
            if (!ch.containsKey("position")) { double[][] z = new double[n][]; for (int i = 0; i < n; i++) z[i] = new double[3]; ch.put("position", z); }
        }
        double[] pivot = {0, 1.0, -3.0};
        Map<String, double[]> prevEuler = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double[][] rg = mat(new double[]{FALL.applyAsDouble(t), 0, 0});
            for (Group g : roots) {
                Map<String, double[][]> ch = keys.get(g.name());
                double[] er = ch.get("rotation")[i], ep = ch.get("position")[i];
                double[] rest = g.rot();
                double[] B = PIVOT_OVERRIDE.getOrDefault(g.name(), g.origin());
                double[][] rl = mat(new double[]{rest[0] + er[0], rest[1] + er[1], rest[2] + er[2]});
                double[] e = eulerNear(matMul(rg, rl), prevEuler.get(g.name()));
                prevEuler.put(g.name(), e);
                ch.get("rotation")[i] = new double[]{e[0] - rest[0], e[1] - rest[1], e[2] - rest[2]};
                // pos' = Rg·(pos + B − P) + P − B
                double[] v = {ep[0] + B[0] - pivot[0], ep[1] + B[1] - pivot[1], ep[2] + B[2] - pivot[2]};
                double[] w = {rg[0][0] * v[0] + rg[0][1] * v[1] + rg[0][2] * v[2], rg[1][0] * v[0] + rg[1][1] * v[1] + rg[1][2] * v[2],
                        rg[2][0] * v[0] + rg[2][1] * v[1] + rg[2][2] * v[2]};
                ch.get("position")[i] = new double[]{w[0] + pivot[0] - B[0], w[1] + pivot[1] - B[1], w[2] + pivot[2] - B[2]};
            }
            // Касание земли: самая нижняя точка — на y = 0 (стоя — стопы и так на земле).
            if (t > 0.55) {
                setPose(keys, t);
                double low = 1e9;
                for (Map<String, Object> el : elements) {
                    Group pg0 = PARENT.get((String) el.get("uuid"));
                    if (pg0 == null) continue;
                    // Гибель v3: опора — ноги (колени на земле), клешни могут чуть войти в грунт.
                    if (GROUND_BY_LEGS && !pg0.name().contains("leg")) continue;
                    double[] f = V(el.get("from")), to = V(el.get("to"));
                    for (int c = 0; c < 8; c++) {
                        double[] p = {(c & 1) == 0 ? f[0] : to[0], (c & 2) == 0 ? f[1] : to[1], (c & 4) == 0 ? f[2] : to[2]};
                        low = Math.min(low, toWorld(p, el)[1]);
                    }
                }
                for (Group g : roots) keys.get(g.name()).get("position")[i][1] -= low;
                if (System.getenv("DEATHDBG") != null && (i % 25 == 0 || i == n - 1)) {
                    setPose(keys, t);
                    Map<String, Double> mins = new TreeMap<>();
                    for (Map<String, Object> el : elements) {
                        Group pg = PARENT.get((String) el.get("uuid"));
                        if (pg == null) continue;
                        double[] f = V(el.get("from")), to = V(el.get("to"));
                        for (int c = 0; c < 8; c++) {
                            double[] p = {(c & 1) == 0 ? f[0] : to[0], (c & 2) == 0 ? f[1] : to[1], (c & 4) == 0 ? f[2] : to[2]};
                            mins.merge(pg.name(), toWorld(p, el)[1], Math::min);
                        }
                    }
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "t=%.2f", t));
                    for (var en : mins.entrySet()) if (en.getKey().contains("leg") || en.getKey().startsWith("finger") || en.getKey().endsWith("arm2"))
                        sb.append(String.format(Locale.ROOT, " %s=%.2f", en.getKey(), en.getValue()));
                    System.out.println(sb);
                }
            }
        }
    }

    // ===== Гибель v3 (кинематографичная): отказ систем → правое колено → левое колено → оседает, руки до земли =====
    static final double DEATH3_LEN = 6.0;
    /** Моменты: правое колено касается земли, левое, руки падают до земли (секунды). */
    static final double D3_KNEE_R = 2.2, D3_KNEE_L = 3.4, D3_ARMS = 4.4;
    /** Наклон стопы на колене и насколько предплечья падают вперёд (подобрано, чтобы колени и клешни касались земли). */
    static double D3_FOOT = Double.parseDouble(System.getProperty("d3foot", "-10")), D3_ARM_REACH = Double.parseDouble(System.getProperty("d3arm", "40")),
            D3_CURL = Double.parseDouble(System.getProperty("d3curl", "30")),
            D3_ARM_KNEEL = Double.parseDouble(System.getProperty("d3kneel", "45")),
            D3_DROP_R = Double.parseDouble(System.getProperty("d3dropr", "-8.0"));

    static void setDeath3Times() {
        List<Double> ts = new ArrayList<>();
        double t = 0;
        while (t < DEATH3_LEN - 1e-9) {
            ts.add(Math.round(t * 1000) / 1000.0);
            t += t < 4.8 ? 0.02 : 0.05;
        }
        ts.add(DEATH3_LEN);
        LEN = DEATH3_LEN;
        KEYS = ts.size() - 1;
        TIMES = new double[ts.size()];
        for (int i = 0; i < TIMES.length; i++) TIMES[i] = ts.get(i);
    }

    /** Отказ систем: резкие подёргивания в первые 1.3 с, потом редкие затухающие. */
    static double d3Shudder(double t) {
        double a = t < 1.3 ? 1.0 : Math.exp(-(t - 1.3) * 1.8) * 0.5;
        return a * (Math.sin(t * 37) * 0.6 + Math.sin(t * 61 + 1.3) * 0.4) * (t < 0.15 ? t / 0.15 : 1);
    }

    /** Удар о землю (колено/руки): короткая затухающая дрожь после момента at. */
    static double d3Impact(double t, double at) {
        if (t < at) return 0;
        double d = t - at;
        return Math.exp(-d * 8) * Math.sin(d * 34);
    }

    static Map<String, Map<String, double[][]>> deathKeys3() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            double sh = d3Shudder(t);
            double kR = smooth((t - 1.25) / (D3_KNEE_R - 1.25));
            double kL = smooth((t - 2.45) / (D3_KNEE_L - 2.45));
            double arms = smooth((t - 3.55) / (D3_ARMS - 3.55));
            double settle = smooth((t - D3_ARMS) / 1.4);
            double hit = d3Impact(t, D3_KNEE_R) + d3Impact(t, D3_KNEE_L) + 0.6 * d3Impact(t, D3_ARMS);
            // Первый удар отказа — корпус дёргается назад, потом медленно оседает вперёд.
            double jerk = Math.exp(-Math.pow((t - 0.25) / 0.12, 2)) * 7;
            double drop = CC + (D3_DROP_R - CC) * kR + (-10.5 - D3_DROP_R) * kL - 0.6 * hit; // правое колено опускается до земли сразу (левая нога в глубоком выпаде)
            // Наклон только корпусом (ноги остаются на коленях): оседает вперёд, когда опускаются колени и руки.
            double pitch = jerk - 8 * kR - 14 * kL - 16 * arms - 7 * settle + 2.2 * sh + 2 * hit;
            double roll = 7 * kR * (1 - kL) + 2.5 * kL + 1.2 * sh;
            tr[i] = new double[]{pitch, 1.4 * sh + 4 * kR * (1 - kL), roll};
            tp[i] = new double[]{0, drop, 0};
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                double k = s > 0 ? kR : kL;
                double other = s > 0 ? kL : kR;
                // Колено на землю (голень назад); пока второе колено не опустилось — эта нога стоит ступнёй, колено вперёд.
                // На колене: бедро почти вертикально, колено уходит вперёд-вниз, голень лежит на земле (щиколотка чуть выше колена).
                double ankleZ = AZ + 2.5 * k - 2.5 * (1 - k) * (s > 0 ? kL : kR) - 2.0 * (1 - k) * (s > 0 ? 0 : kR);
                double ankleY = AY + 2.2 * k;
                double[] leg = solveLeg(ankleY, ankleZ, D3_FOOT * k, drop);
                double tPod = t - LAG_POD * 2, tArm = t - LAG_ARM * 2;
                double shPod = d3Shudder(tPod), shArm = d3Shudder(tArm);
                double armsP = smooth((tArm - 3.55) / (D3_ARMS - 3.55));
                double sag = smooth((tPod - 3.4) / 1.6);
                // Капсулы обвисают наружу и вниз, ощутимо дёргаются при ударах.
                out[0][i] = new double[]{6 * sag + 3 * shPod + 2 * hit, (-4 * k - 6 * sag) * s, (-6 * k - 22 * sag - 2 * shPod) * s};
                out[1][i] = new double[]{0, -1.2 * sag, 0};
                out[11][i] = new double[]{pitch, 0, roll * 0.8};
                out[12][i] = new double[]{0, drop, 0};
                // Предплечья: подёргивание → безвольно висят → падают вперёд, клешни упираются в землю.
                double arm = 6 + 5 * shArm + D3_ARM_KNEEL * Math.max(k, 0.5 * other) + D3_ARM_REACH * armsP + 4 * d3Impact(tArm, D3_ARMS);
                out[2][i] = new double[]{arm, 0, (5 + 7 * armsP) * s};
                out[10][i] = new double[]{1, 1, 1};
                for (int f = 0; f < 3; f++) {
                    double tw = (t < 1.3 ? 14 * Math.sin(t * (23 + 7 * f) + s) : 0) * (1 - armsP);
                    double curl = D3_CURL * armsP + (6 + 24 * Math.max(k, 0.5 * other)) * (1 - armsP) + tw - 5 * d3Impact(tArm, D3_ARMS);
                    out[3 + f][i] = new double[]{f == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    /** Гибель v3: всё тело медленно клонится вперёд вокруг колен (не падает плашмя), лёгкий отскок, когда руки ударяют о землю. */
    static double fallAngle3(double t) {
        double lean = -14 * smooth((t - 2.6) / 1.2) - 8 * smooth((t - 3.6) / 1.0) - 3 * smooth((t - 4.6) / 1.2);
        return lean + 1.5 * d3Impact(t, D3_ARMS);
    }

    static java.util.function.DoubleUnaryOperator FALL = Texturer::fallAngle;
    /** Касание земли считать только по ногам (гибель v3). */
    static boolean GROUND_BY_LEGS = false;
    // ===== «Магнит» (push): руки вперёд → притягивает (дрожь, корпус откинут) → сжатие → мощный толчок → отдача =====
    static final double PUSH_LEN = 4.5, PUSH_BLAST = 2.75;

    static Map<String, Map<String, double[][]>> pushKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        double[][] tr = new double[n][], tp = new double[n][];
        Map<Integer, double[][][]> sides = new HashMap<>();
        for (int s : new int[]{-1, 1}) sides.put(s, new double[13][n][]);
        double c = CC, B = PUSH_BLAST;
        double[][] RAISE = K(0, 4, 0, 0.45, 80, SMOOTH, B - 0.3, 80, SMOOTH, B - 0.13, 58, WIND, B, 98, STRIKE, B + 0.2, 92, SMOOTH, 3.6, 70, SMOOTH, PUSH_LEN, 4, SMOOTH);
        double[][] SPLAY = K(0, 0, 0, 0.45, -10, SMOOTH, B - 0.3, -12, SMOOTH, B - 0.13, -16, SMOOTH, B, 8, STRIKE, 3.6, 4, SMOOTH, PUSH_LEN, 0, SMOOTH);
        double[][] STRETCH = K(0, 1, 0, B - 0.13, 0.95, SMOOTH, B, 1.15, STRIKE, B + 0.25, 1.08, SMOOTH, 3.8, 1, SMOOTH, PUSH_LEN, 1, SMOOTH);
        double[][] PPITCH = K(0, 0, 0, 0.45, -12, SMOOTH, B - 0.3, -14, SMOOTH, B - 0.13, -6, SMOOTH, B, -26, STRIKE, 3.6, -10, SMOOTH, PUSH_LEN, 0, SMOOTH);
        double[][] PYAW = K(0, 0, 0, 0.45, 12, SMOOTH, B - 0.13, 16, SMOOTH, B, -6, STRIKE, PUSH_LEN, 0, SMOOTH);
        double[][] PROLL = K(0, 0, 0, 0.45, 6, SMOOTH, B - 0.13, 10, SMOOTH, B, -4, STRIKE, PUSH_LEN, 0, SMOOTH);
        double[][] TPITCH = K(0, 0, 0, 0.45, 5, SMOOTH, B - 0.3, 7, SMOOTH, B - 0.13, 9, SMOOTH, B, -12, STRIKE, B + 0.25, -10, SMOOTH, 3.8, -2, SMOOTH, PUSH_LEN, 0, SMOOTH);
        double[][] DROP = K(0, c, 0, 0.45, c - 1.2, SMOOTH, B - 0.3, c - 1.6, SMOOTH, B - 0.13, c - 2.6, SMOOTH, B, c - 1.0, STRIKE, B + 0.25, c - 2.0, SMOOTH, PUSH_LEN, c, SMOOTH);
        double[][] CURL = K(0, 0, 0, 0.45, -16, SMOOTH, B - 0.3, -16, SMOOTH, B - 0.13, 18, SMOOTH, B, -22, STRIKE, 3.6, -6, SMOOTH, PUSH_LEN, 0, SMOOTH);
        for (int i = 0; i < n; i++) {
            double t = TIMES[i];
            boolean pulling = t > 0.5 && t < B - 0.1;
            double shake = pulling ? Math.min(1, (t - 0.5) / 1.2) : 0;
            double drop = track(t, DROP);
            double pitch = track(t, TPITCH) + 0.8 * shake * Math.sin(t * 41);
            tr[i] = new double[]{pitch, 0.6 * shake * Math.sin(t * 37), 0.5 * shake * Math.sin(t * 29)};
            tp[i] = new double[]{0, drop, 0};
            double[] leg = solveLeg(AY, AZ, 0, drop);
            double tPod = t - LAG_POD, tArm = t - LAG_ARM, tFin = t - LAG_FINGER;
            for (int s : new int[]{-1, 1}) {
                double[][][] out = sides.get(s);
                out[0][i] = new double[]{track(tPod, PPITCH), track(tPod, PYAW) * s, track(tPod, PROLL) * s};
                out[1][i] = new double[]{0, 0, 0};
                out[11][i] = new double[]{track(tPod, TPITCH), 0, 0};
                out[12][i] = new double[]{0, track(tPod, DROP), 0};
                double tremble = 1.8 * shake * Math.sin(t * 47 + s);
                out[2][i] = new double[]{track(tArm, RAISE) + tremble, 0, track(tArm, SPLAY) * s};
                out[10][i] = new double[]{1, track(tArm, STRETCH), 1};
                for (int k = 0; k < 3; k++) {
                    double curl = track(tFin - 0.03 * k, CURL) + 4 * shake * Math.sin(t * 60 + k * 1.7 + s);
                    out[3 + k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
                out[6][i] = new double[]{leg[0], 0, 0};
                out[7][i] = new double[]{leg[1], 0, 0};
                out[8][i] = new double[]{leg[2], 0, 0};
                out[9][i] = new double[]{0, drop, 0};
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][][] o = sides.get(s);
            Map<String, double[][]> pod = ch("rotation", o[0]);
            pod.put("position", o[1]);
            m.put(side + "_arm", pod);
            Map<String, double[][]> carrier = ch("rotation", o[11]);
            carrier.put("position", o[12]);
            m.put(side + "_arm_carrier", carrier);
            Map<String, double[][]> arm2 = ch("rotation", o[2]);
            arm2.put("scale", o[10]);
            m.put(side + "_arm2", arm2);
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", o[3 + k]));
            Map<String, double[][]> leg = ch("rotation", o[6]);
            leg.put("position", o[9]);
            m.put(side + "_leg", leg);
            m.put(side + "_leg2", ch("rotation", o[7]));
            m.put(side + "_leg3", ch("rotation", o[8]));
        }
        Map<String, double[][]> torso = ch("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }
    // ===== Вес рук: капсулы с предплечьями «подвешены на пружине» к корпусу =====
    /** Жёсткость и демпфирование пружины (≈2 Гц, чуть пружинит), сила смещения (пиксели), опускание плеча и подъём предплечья (градусы). */
    static final double ARM_SPRING = 170, ARM_DAMP = 12, ARM_WEIGHT = 1.5, ARM_MAX = 1.6, ARM_ROLL = 2.0, ARM_LIFT = 2.5;

    /**
     * Когда корпус проседает или поднимается, руки запаздывают, по инерции проходят дальше и догоняют —
     * вертикальный «вес» рук. Пружина решается по шагам 1/240 с вдоль высоты корпуса; для циклов — 3 прогона (по последнему).
     */
    static void addArmWeight(Map<String, Map<String, double[][]>> keys, boolean loop) {
        Map<String, double[][]> torso = keys.get("torso");
        if (torso == null || !torso.containsKey("position")) return;
        double[][] tp = torso.get("position");
        int n = TIMES.length;
        double[] off = new double[n];
        double x = tp[0][1], v = 0, dt = 1 / 240.0;
        int cycles = loop ? 3 : 1;
        for (int c = 0; c < cycles; c++) {
            double t = 0;
            int seg = 0;
            for (int i = 0; i < n; i++) {
                while (t < TIMES[i]) {
                    while (seg < n - 2 && TIMES[seg + 1] < t) seg++;
                    double t0 = TIMES[seg], t1 = TIMES[Math.min(n - 1, seg + 1)];
                    double y0 = tp[seg][1], y1 = tp[Math.min(n - 1, seg + 1)][1];
                    double target = t1 > t0 ? y0 + (y1 - y0) * Math.max(0, Math.min(1, (t - t0) / (t1 - t0))) : y0;
                    double a = ARM_SPRING * (target - x) - ARM_DAMP * v;
                    v += a * dt;
                    x += v * dt;
                    t += dt;
                }
                if (c == cycles - 1) off[i] = Math.max(-ARM_MAX, Math.min(ARM_MAX, (x - tp[i][1]) * ARM_WEIGHT));
            }
        }
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            Map<String, double[][]> pod = keys.computeIfAbsent(side + "_arm", k -> new LinkedHashMap<>());
            if (!pod.containsKey("position")) { double[][] z = new double[n][]; for (int i = 0; i < n; i++) z[i] = new double[3]; pod.put("position", z); }
            if (!pod.containsKey("rotation")) { double[][] z = new double[n][]; for (int i = 0; i < n; i++) z[i] = new double[3]; pod.put("rotation", z); }
            Map<String, double[][]> arm2 = keys.computeIfAbsent(side + "_arm2", k -> new LinkedHashMap<>());
            if (!arm2.containsKey("rotation")) { double[][] z = new double[n][]; for (int i = 0; i < n; i++) z[i] = new double[3]; arm2.put("rotation", z); }
            for (int i = 0; i < n; i++) {
                pod.get("position")[i] = new double[]{pod.get("position")[i][0], pod.get("position")[i][1] + off[i], pod.get("position")[i][2]};
                double[] r = pod.get("rotation")[i];
                pod.get("rotation")[i] = new double[]{r[0], r[1], r[2] - s * ARM_ROLL * off[i]};
                double[] a = arm2.get("rotation")[i];
                arm2.get("rotation")[i] = new double[]{a[0] + ARM_LIFT * off[i], a[1], a[2]};
            }
        }
    }
    // ===== Стойка (idle) =====
    /** Насколько костюм стоит «присев» и глубина дыхания (пиксели). */
    static final double IDLE_CROUCH = -0.6, IDLE_BREATH = 0.4;

    /** Корпус в стойке: медленно опускается и поднимается («дыхание»). */
    static double idleBody(double psi) {
        return IDLE_CROUCH - IDLE_BREATH * (1 - Math.cos(psi)) / 2;
    }

    /**
     * Стойка по видео пользователя: торс не поворачивается, только «дышит»; стопы стоят на месте (колени чуть пружинят);
     * капсулы медленно покачиваются, каждая в своём ритме; предплечья висят маятником с запаздыванием;
     * пальцы медленно сжимаются и разок вздрагивают за цикл.
     */
    static Map<String, Map<String, double[][]>> idleKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][] thigh = new double[n][], knee = new double[n][], foot = new double[n][], legPos = new double[n][];
            double[][] podRot = new double[n][], podPos = new double[n][], arm = new double[n][];
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            double[][][] fing = new double[3][n][];
            double twitchAt = s > 0 ? 2.0 : 4.6; // когда вздрагивает клешня (у правой и левой — в разное время)
            for (int i = 0; i < n; i++) {
                double psi = 2 * Math.PI * i / KEYS;
                double body = idleBody(psi);
                double[] leg = solveLeg(AY, AZ, 0, body);
                thigh[i] = new double[]{leg[0], 0, 0};
                knee[i] = new double[]{leg[1], 0, 0};
                foot[i] = new double[]{leg[2], 0, 0};
                legPos[i] = new double[]{0, body, 0};
                podRot[i] = new double[]{
                        2.0 * Math.sin(psi - 0.6 + s * 0.4),
                        2.0 * Math.sin(psi + s * 1.2),
                        s * 2.5 * Math.sin(psi - 0.4) + 0.8 * Math.sin(2 * psi + s)};
                podPos[i] = new double[]{0, idleBody(psi - 0.35), 0};
                arm[i] = new double[]{3 + 4 * Math.sin(psi - 1.2 + s * 0.5), 0, s * 1.5 * Math.sin(2 * psi - 0.5)};
                for (int k = 0; k < 3; k++) {
                    double curl = -4 + 6 * Math.sin(psi - 1.6 - 0.5 * k) - 8 * gauss(psi - twitchAt - 0.15 * k, 0.22);
                    fing[k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
            }
            Map<String, double[][]> legCh = ch("rotation", thigh);
            legCh.put("position", legPos);
            m.put(side + "_leg", legCh);
            m.put(side + "_leg2", ch("rotation", knee));
            m.put(side + "_leg3", ch("rotation", foot));
            Map<String, double[][]> pod = ch("rotation", podRot);
            pod.put("position", podPos);
            m.put(side + "_arm", pod);
            m.put(side + "_arm2", ch("rotation", arm));
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", fing[k]));
        }
        double[][] tp = new double[n][];
        for (int i = 0; i < n; i++) tp[i] = new double[]{0, idleBody(2 * Math.PI * i / KEYS), 0};
        m.put("torso", ch("position", tp));
        return m;
    }

    /** Новые суставы (точки вращения) — по реальному положению частей модели. */
    static void setupPivots() {
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            PIVOT_OVERRIDE.put(side + "_leg", new double[]{6 * s, 24.5, -1.5});   // бедро
            PIVOT_OVERRIDE.put(side + "_leg2", new double[]{6 * s, 11.0, -3.0});  // колено
            PIVOT_OVERRIDE.put(side + "_leg3", new double[]{6 * s, 2.0, -1.0});   // щиколотка
            PIVOT_OVERRIDE.put(side + "_arm", new double[]{12 * s, 30.0, -1.0});  // плечо (капсула)
            PIVOT_OVERRIDE.put(side + "_arm2", new double[]{16.5 * s, 23.5, -1.0}); // подвес предплечья
        }
        PIVOT_OVERRIDE.put("finger4", new double[]{-19.6, 12.8, -4});
        PIVOT_OVERRIDE.put("finger5", new double[]{-19.6, 12.8, 2});
        PIVOT_OVERRIDE.put("finger6", new double[]{-12.0, 17.8, -4});
        PIVOT_OVERRIDE.put("finger3", new double[]{19.6, 12.8, -4});
        PIVOT_OVERRIDE.put("finger2", new double[]{19.6, 12.8, 2});
        PIVOT_OVERRIDE.put("finger", new double[]{12.0, 17.8, -4});
        PIVOT_OVERRIDE.put("torso", new double[]{0, 24.0, 0});
    }

    /** Ключевые кадры: кость -> канал -> 5 значений {x,y,z} в «модельных» градусах (+X = низ части вперёд, к -Z). */
    /**
     * Тяжёлая ходьба по видео пользователя (вид сбоку, сверху, спереди, сзади). φ — фаза цикла (0..2π),
     * при φ=0 правая нога впереди. Углы в «модельных» градусах: +X — низ части вперёд (к -Z),
     * +Y — поворот, при котором правая сторона (+X) уходит вперёд, +Z — низ части к +X.
     */
    // ===== Настройки «тяжести» =====
    /** Задержка после постановки ноги: 0 — ровный темп, 0.6 — сильно «оседает» и не спеша начинает следующий шаг. */
    static final double SETTLE = 0.2;
    /** Насколько проседает корпус после постановки ноги (пиксели). */
    static final double IMPACT_DROP = 1.0;
    /** Раскачка корпуса и капсул (градусы): поворот и крен. */
    static final double YAW = 15, ROLL = 2.5;
    /** Мах предплечий в «локте» (градусы) и отвод в сторону. */
    static final double ARM_SWING = 18, ARM_SPLAY = 2.5;

    /**
     * Неравномерное время шага: около постановок ноги (φ = 0 и π) время идёт медленнее —
     * костюм «оседает» под весом, на махе — быстрее.
     */
    static double warp(double phi) {
        return phi - SETTLE * Math.sin(2 * phi) / 2;
    }

    /**
     * «Принятие веса» после постановки любой ноги, 0..1 — одна плавная кривая для всего тела:
     * плавно вниз ~0.2 с, плавно вверх ~0.6 с (по настоящему времени, в радианах цикла).
     */
    static double impact(double raw) {
        double sum = 0;
        for (double c : new double[]{0, Math.PI}) {
            double d = Math.atan2(Math.sin(raw - c), Math.cos(raw - c));
            double down = 0.8, up = 2.4;
            if (d >= -0.1 && d < down) sum += smooth((d + 0.1) / (down + 0.1));
            else if (d >= down && d < up) sum += smooth(1 - (d - down) / (up - down));
        }
        return sum;
    }

    static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    static Map<String, Map<String, double[][]>> walkKeys() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        int n = KEYS + 1;
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            double[][] thigh = new double[n][], knee = new double[n][], foot = new double[n][], arm = new double[n][];
            String[] fingers = s < 0 ? new String[]{"finger6", "finger4", "finger5"} : new String[]{"finger", "finger3", "finger2"};
            double[][][] fing = new double[3][n][];
            double[][] legPos = new double[n][];
            for (int i = 0; i < n; i++) {
                double raw = 2 * Math.PI * i / KEYS;
                double phi = warp(raw) + (s < 0 ? Math.PI : 0); // левая сторона — на полцикла позже
                double hit = impact(raw);
                // Ноги — обратной кинематикой: стопа идёт по заданному пути, углы бедра и колена считаются.
                double p = (raw / (2 * Math.PI) + (s < 0 ? 0.5 : 0)) % 1.0;
                double hipDrop = CROUCH + bob(raw);              // корпус (и бёдра) ниже — колени пружинят
                double[] leg = legIK(p, hipDrop);
                double th = leg[0], kn = leg[1], ft = leg[2];
                legPos[i] = new double[]{0, hipDrop, 0};
                double ar = 4 - ARM_SWING * Math.cos(phi) + 2 * hit;          // предплечье против ноги; при ударе по инерции вперёд
                double splay = ARM_SPLAY * Math.max(0, Math.cos(phi));                 // на махе назад рука отходит в сторону
                thigh[i] = new double[]{th, 0, 0};
                knee[i] = new double[]{kn, 0, 0};
                foot[i] = new double[]{ft, 0, 0};
                arm[i] = new double[]{ar, 0, splay * s};
                // Пальцы: сжимаются и разжимаются с запаздыванием за рукой, каждый со своим сдвигом;
                // при ударе о землю клешня вздрагивает. Внутренний палец (первый) — в противофазе.
                for (int k = 0; k < 3; k++) {
                    double curl = -6 + 12 * Math.sin(phi - 0.9 - 0.35 * k) - 4 * hit;
                    fing[k][i] = new double[]{k == 0 ? -0.8 * curl : curl, 0, 0};
                }
            }
            Map<String, double[][]> legCh = ch("rotation", thigh);
            legCh.put("position", legPos);
            m.put(side + "_leg", legCh);
            m.put(side + "_leg2", ch("rotation", knee));
            m.put(side + "_leg3", ch("rotation", foot));
            m.put(side + "_arm2", ch("rotation", arm));
            for (int k = 0; k < 3; k++) m.put(fingers[k], ch("rotation", fing[k]));
            // Капсула: вместе с корпусом поворачивается (с запаздыванием — «вес»), при ударе догоняет корпус вниз.
            double[][] podRot = new double[n][], podPos = new double[n][];
            double px = PIVOT_OVERRIDE.get(side + "_arm")[0];
            for (int i = 0; i < n; i++) {
                double raw = 2 * Math.PI * i / KEYS;
                double phi = warp(raw) - 0.3;
                double yaw = -YAW * Math.cos(phi), roll = -ROLL * Math.sin(phi);
                double yr = Math.toRadians(yaw);
                podRot[i] = new double[]{-POD_PITCH * Math.cos(phi) * s - 1.2 * impact(raw - 0.15), yaw, roll};
                // Поворот вокруг центра корпуса: капсула уезжает вперёд/назад; вниз — с опозданием за корпусом.
                podPos[i] = new double[]{0, CROUCH + bob(raw - 0.15), -px * Math.sin(yr)};
            }
            Map<String, double[][]> pod = ch("rotation", podRot);
            pod.put("position", podPos);
            m.put(side + "_arm", pod);
        }
        double[][] tr = new double[n][], tp = new double[n][];
        for (int i = 0; i < n; i++) {
            double raw = 2 * Math.PI * i / KEYS;
            double phi = warp(raw);
            // При ударе корпус «клюёт» вперёд.
            tr[i] = new double[]{0, 0, 0}; // торс не качается — только проседает
            tp[i] = new double[]{0, CROUCH + bob(raw), 0};
        }
        Map<String, double[][]> torso = ch("position", tp);
        m.put("torso", torso);
        return m;
    }

    // ===== Ноги: путь стопы и обратная кинематика =====
    /** Насколько костюм идёт «присев» (пиксели): даёт коленям запас, чтобы пружинить. */
    static final double CROUCH = -3.0; // глубже присед — ноги дотягиваются до широкого шага
    /** Доля цикла, когда нога стоит на земле. */
    static final double STANCE = 0.55;
    /** Длина шага (пиксели вперёд и назад от точки под бедром) и высота подъёма стопы. */
    static final double STRIDE = 8.0, STRIDE_BACK = 9.0, LIFT = 3.6; // широкий шаг: скорость без скольжения ≈ 1.1 блока/с
    /** Наклон капсул вперёд-назад в такт шагу (градусы). */
    static final double POD_PITCH = 5;
    // Суставы ноги (y, z) в покое: бедро, колено, щиколотка.
    static final double HY = 24.5, HZ = -1.5, KY = 11.0, KZ = -3.0, AY = 2.0, AZ = -1.0;

    /**
     * p — фаза ноги 0..1 (0 — стопа только что встала впереди). hipDrop — насколько опущено бедро.
     * Возвращает {бедро, колено, стопа} в «модельных» градусах по X.
     */
    static double[] legIK(double p, double hipDrop) {
        double ty, tz, tilt;
        double zF = AZ - STRIDE, zB = AZ + STRIDE_BACK;
        if (p < STANCE) {
            // Опора: стопа ровно на земле и равномерно уезжает назад (без проскальзывания).
            double s = p / STANCE;
            ty = AY;
            tz = zF + (zB - zF) * s;
            tilt = 0;
        } else {
            // Перенос: стопа отрывается, дугой идёт вперёд, замедляется и мягко ставится плоско.
            double s = (p - STANCE) / (1 - STANCE);
            double e = s * s * (3 - 2 * s);
            ty = AY + LIFT * Math.pow(Math.sin(Math.PI * s), 0.85);
            tz = zB + (zF - zB) * e;
            tilt = s < 0.55 ? -14 * Math.sin(Math.PI * s / 0.55) : 5 * Math.sin(Math.PI * (s - 0.55) / 0.45);
        }
        return solveLeg(ty, tz, tilt, hipDrop);
    }

    /** Углы бедра, колена и стопы, чтобы щиколотка оказалась в (ty, tz); стопа ровно к земле + наклон tilt. */
    static double[] solveLeg(double ty, double tz, double tilt, double hipDrop) {
        double l1 = Math.hypot(HY - KY, HZ - KZ), l2 = Math.hypot(KY - AY, KZ - AZ);
        double rest1 = Math.toDegrees(Math.atan2(-(KZ - HZ), -(KY - HY)));
        double rest2 = Math.toDegrees(Math.atan2(-(AZ - KZ), -(AY - KY)));
        double hy = HY + hipDrop, hz = HZ;
        double dy = ty - hy, dz = tz - hz;
        double d = Math.min(Math.hypot(dy, dz), l1 + l2 - 0.02);
        double beta = Math.atan2(-dz, -dy);
        double gamma = Math.acos(Math.max(-1, Math.min(1, (l1 * l1 + d * d - l2 * l2) / (2 * l1 * d))));
        double a1 = beta + gamma; // колено — вперёд (как в покое)
        double ky = hy - l1 * Math.cos(a1), kz = hz - l1 * Math.sin(a1);
        double a2 = Math.atan2(-(tz - kz), -(ty - ky));
        double th1 = Math.toDegrees(a1) - rest1;
        double th2 = Math.toDegrees(a2) - rest2 - th1;
        double th3 = -(th1 + th2) + tilt; // стопа: ровно к земле (+ наклон на переносе)
        return new double[]{th1, th2, th3};
    }

    /**
     * Живая стопа (+ — носок вверх): в опоре стоит плоско (компенсирует бедро и колено),
     * при постановке ноги приходит на пятку с поднятым носком, в конце шага отрывает пятку (толчок носком),
     * на махе чуть свисает и перед постановкой снова поднимает носок.
     */
    static double footAngle(double phi, double thigh, double knee) {
        double w = Math.atan2(Math.sin(phi), Math.cos(phi)); // фаза в -π..π (0 — нога впереди, постановка)
        double flat = -(thigh + knee);
        double heelStrike = 16 * gauss(w - 0.05, 0.38);           // носок вверх при постановке
        double toeOff = -24 * gauss(w - (Math.PI - 0.55), 0.42);  // пятка отрывается, толчок носком
        double dangle = -9 * gauss(w + 1.75, 0.5);               // на махе стопа свисает
        // На махе стопа не обязана быть плоской — компенсируем слабее.
        double swing = gauss(w + Math.PI / 2, 0.9);
        return flat * (1 - 0.45 * swing) + heelStrike + toeOff + dangle;
    }

    static double gauss(double x, double sigma) {
        x = Math.atan2(Math.sin(x), Math.cos(x)); // по кругу
        return Math.exp(-x * x / (2 * sigma * sigma));
    }

    /** Проседание корпуса: ниже всего чуть после постановки каждой ноги. */
    static double bob(double raw) {
        // Мягкое общее покачивание + резкое проседание от удара, которое медленно выпрямляется.
        // Общее плавное покачивание и проседание под весом — по одной кривой impact, в такт шагу.
        return -0.2 - 0.2 * Math.cos(2 * warp(raw)) - IMPACT_DROP * impact(raw);
    }

    static Map<String, double[][]> ch(String name, double[][] v) {
        Map<String, double[][]> c = new LinkedHashMap<>();
        c.put(name, v);
        return c;
    }

    static Map<String, Map<String, double[][]>> walkKeysOld() {
        Map<String, Map<String, double[][]>> m = new LinkedHashMap<>();
        double[] thighR = {25, 0, -25, 0, 25}, kneeR = {-5, -5, -12, -40, -5};
        double[] armR = {-25, 5, 35, 5, -25};
        for (int s : new int[]{-1, 1}) {
            String side = s < 0 ? "left" : "right";
            int shift = s < 0 ? 2 : 0; // левая сторона — на полцикла позже
            double[] thigh = shifted(thighR, shift), knee = shifted(kneeR, shift), arm = shifted(armR, shift);
            double[] foot = new double[5], pod = new double[5], finger = new double[5];
            for (int i = 0; i < 5; i++) {
                foot[i] = -(thigh[i] + knee[i]) * 0.6;   // ступня остаётся почти ровной
                pod[i] = arm[i] * 0.18;                   // капсула чуть следует за рукой
                finger[i] = -arm[i] * 0.3;               // пальцы сгибаются в такт
            }
            m.put(side + "_leg", rotX(thigh));
            m.put(side + "_leg2", rotX(knee));
            m.put(side + "_leg3", rotX(foot));
            Map<String, double[][]> podCh = rotX(pod);
            double[][] podPos = new double[5][];
            for (int i = 0; i < 5; i++) podPos[i] = new double[]{0, BOB[i], 0};
            podCh.put("position", podPos);
            m.put(side + "_arm", podCh);
            m.put(side + "_arm2", rotX(arm));
            for (String f : s < 0 ? new String[]{"finger4", "finger5", "finger6"} : new String[]{"finger", "finger2", "finger3"}) {
                m.put(f, rotX(finger));
            }
        }
        Map<String, double[][]> torso = new LinkedHashMap<>();
        double[] yaw = {-4, 0, 4, 0, -4}, roll = {2, 0, -2, 0, 2};
        double[][] tr = new double[5][], tp = new double[5][];
        for (int i = 0; i < 5; i++) { tr[i] = new double[]{2, yaw[i], roll[i]}; tp[i] = new double[]{0, BOB[i], 0}; }
        torso.put("rotation", tr);
        torso.put("position", tp);
        m.put("torso", torso);
        return m;
    }

    static final double[] BOB = {-0.6, 0.3, -0.6, 0.3, -0.6}; // проседание корпуса на каждом шаге

    static double[] shifted(double[] v, int shift) {
        double[] r = new double[5];
        for (int i = 0; i < 4; i++) r[i] = v[(i + shift) % 4];
        r[4] = r[0];
        return r;
    }

    static Map<String, double[][]> rotX(double[] v) {
        Map<String, double[][]> ch = new LinkedHashMap<>();
        double[][] r = new double[5][];
        for (int i = 0; i < 5; i++) r[i] = new double[]{v[i], 0, 0};
        ch.put("rotation", r);
        return ch;
    }

    /** Значение в момент t по ключам (Catmull-Rom по кругу, как «catmullrom» в Blockbench). */
    /** Прямое соединение ключей (для атаки: частые ключи, без «перелётов» сглаживания). */
    static boolean LINEAR = false;

    static double[] sample(double[][] keys, double t) {
        int nk = keys.length - 1; // последний ключ = первый
        double u = (t % LEN) / (LEN / nk);
        int i = (int) Math.floor(u);
        double f = u - i;
        double[] r = new double[3];
        if (LINEAR) {
            // Ключи могут стоять неравномерно (в ударах чаще) — ищем отрезок по TIMES.
            int lo = 0, hi = TIMES.length - 1;
            while (hi - lo > 1) { int mid = (lo + hi) / 2; if (TIMES[mid] <= t) lo = mid; else hi = mid; }
            double g = TIMES[hi] > TIMES[lo] ? Math.max(0, Math.min(1, (t - TIMES[lo]) / (TIMES[hi] - TIMES[lo]))) : 0;
            for (int k = 0; k < 3; k++) r[k] = keys[lo][k] + (keys[hi][k] - keys[lo][k]) * g;
            return r;
        }
        for (int k = 0; k < 3; k++) {
            double p0 = keys[(i - 1 + nk) % nk][k], p1 = keys[i % nk][k], p2 = keys[(i + 1) % nk][k], p3 = keys[(i + 2) % nk][k];
            r[k] = 0.5 * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f);
        }
        return r;
    }

    /** Проверка: прямым расчётом от бедра — где реально стопа в опоре и ровная ли она. */
    static void checkLegs() {
        double minY = 1e9, maxY = -1e9, maxTilt = 0, maxSlipErr = 0;
        for (int i = 0; i < 200; i++) {
            double p = i / 200.0;
            if (p >= STANCE) continue;
            double raw = 2 * Math.PI * p;
            double drop = CROUCH + bob(raw);
            double[] a = legIK(p, drop);
            double[] k0 = {KY - HY, KZ - HZ}, a0 = {AY - KY, AZ - KZ};
            double r1 = Math.toRadians(a[0]), r12 = Math.toRadians(a[0] + a[1]);
            double ky = HY + drop + k0[0] * Math.cos(r1) - k0[1] * Math.sin(r1), kz = HZ + k0[0] * Math.sin(r1) + k0[1] * Math.cos(r1);
            double ay = ky + a0[0] * Math.cos(r12) - a0[1] * Math.sin(r12), az = kz + a0[0] * Math.sin(r12) + a0[1] * Math.cos(r12);
            double expectZ = AZ - STRIDE + (STRIDE + STRIDE_BACK) * (p / STANCE);
            minY = Math.min(minY, ay); maxY = Math.max(maxY, ay);
            maxTilt = Math.max(maxTilt, Math.abs(a[0] + a[1] + a[2]));
            maxSlipErr = Math.max(maxSlipErr, Math.abs(az - expectZ));
        }
        System.out.printf(Locale.ROOT, "stance ankle y: %.3f..%.3f (ground %.1f), max foot tilt %.3f deg, max z error %.3f%n",
                minY, maxY, AY, maxTilt, maxSlipErr);
    }

    // ===== Выгрузка костюма в мод: модель (кубики + UV), кости, анимации =====
    static final List<Object[]> EXPORT = new ArrayList<>();
    static final String[] FACE_DIRS = {"north", "south", "east", "west", "up", "down"};

    static void exportMech(List<Map<String, Object>> elements, String dir) throws Exception {
        Files.createDirectories(Path.of(dir));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"bones\":[");
        boolean first = true;
        for (Group g : GROUPS_BY_NAME.values()) {
            if (!first) sb.append(',');
            first = false;
            double[] p = PIVOT_OVERRIDE.getOrDefault(g.name(), g.origin());
            sb.append("{\"name\":\"").append(g.name()).append("\",\"parent\":")
                    .append(g.parent() == null ? "null" : "\"" + g.parent().name() + "\"")
                    .append(",\"pivot\":").append(vec(p)).append(",\"rot\":").append(vec(g.rot())).append('}');
        }
        sb.append("],\"cubes\":[");
        first = true;
        int cubes = 0;
        for (Map<String, Object> e : elements) {
            Group g = PARENT.get((String) e.get("uuid"));
            Map<String, Object> faces = M(e.get("faces"));
            StringBuilder fs = new StringBuilder();
            for (int d = 0; d < 6; d++) {
                Object fo = faces.get(FACE_DIRS[d]);
                if (fo == null || M(fo).get("texture") == null || M(fo).get("uv") == null) continue;
                double[] uv = V(M(fo).get("uv"));
                if (fs.length() > 0) fs.append(',');
                fs.append('[').append(d).append(',').append(num(uv[0])).append(',').append(num(uv[1])).append(',')
                        .append(num(uv[2])).append(',').append(num(uv[3])).append(']');
            }
            if (g == null || fs.length() == 0) continue; // силуэт игрока и пустые — не нужны
            if (!first) sb.append(',');
            first = false;
            cubes++;
            sb.append("{\"bone\":\"").append(g.name()).append("\",\"name\":\"").append(e.get("name")).append("\",\"from\":").append(vec(V(e.get("from"))))
                    .append(",\"to\":").append(vec(V(e.get("to"))))
                    .append(",\"rot\":").append(vec(e.get("rotation") != null ? V(e.get("rotation")) : new double[3]))
                    .append(",\"origin\":").append(vec(e.get("origin") != null ? V(e.get("origin")) : new double[3]))
                    .append(",\"faces\":[").append(fs).append("]}");
        }
        sb.append("]}");
        Files.writeString(Path.of(dir, "mech_model.json"), sb.toString(), StandardCharsets.UTF_8);

        StringBuilder ab = new StringBuilder("{");
        for (int k = 0; k < EXPORT.size(); k++) {
            Object[] x = EXPORT.get(k);
            @SuppressWarnings("unchecked") Map<String, Map<String, double[][]>> keys = (Map<String, Map<String, double[][]>>) x[5];
            double[] times = (double[]) x[4];
            if (k > 0) ab.append(',');
            ab.append('"').append(x[0]).append("\":{\"length\":").append(num((Double) x[1])).append(",\"loop\":").append(x[2])
                    .append(",\"linear\":").append(x[3]).append(",\"times\":[");
            for (int i = 0; i < times.length; i++) { if (i > 0) ab.append(','); ab.append(num(times[i])); }
            ab.append("],\"bones\":{");
            boolean fb = true;
            for (Map.Entry<String, Map<String, double[][]>> bone : keys.entrySet()) {
                if (!fb) ab.append(',');
                fb = false;
                ab.append('"').append(bone.getKey()).append("\":{");
                boolean fc = true;
                for (Map.Entry<String, double[][]> c : bone.getValue().entrySet()) {
                    if (!fc) ab.append(',');
                    fc = false;
                    ab.append('"').append(c.getKey()).append("\":[");
                    double[][] v = c.getValue();
                    for (int i = 0; i < v.length; i++) {
                        if (i > 0) ab.append(',');
                        ab.append(num(v[i][0])).append(',').append(num(v[i][1])).append(',').append(num(v[i][2]));
                    }
                    ab.append(']');
                }
                ab.append('}');
            }
            ab.append("}}");
        }
        ab.append('}');
        Files.writeString(Path.of(dir, "mech_anims.json"), ab.toString(), StandardCharsets.UTF_8);
        System.out.println("exported mech: " + cubes + " cubes, " + GROUPS_BY_NAME.size() + " bones, " + EXPORT.size() + " animations");
    }

    static String vec(double[] v) {
        return "[" + num(v[0]) + "," + num(v[1]) + "," + num(v[2]) + "]";
    }

    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return String.valueOf((long) d);
        String s = String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static void animMain(String[] a) throws Exception {
        src = Files.readString(Path.of(a[1]), StandardCharsets.UTF_8);
        pos = 0;
        Map<String, Object> model = M(parse());
        Map<String, Object> texInfo = M(L(model.get("textures")).get(0));
        S = (int) Math.round(D(texInfo.get("width")) / D(texInfo.get("uv_width")));
        String b64 = ((String) texInfo.get("source")).substring("data:image/png;base64,".length());
        tex = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        glow = ImageIO.read(new File(a[3]));

        Map<String, Map<String, Object>> groupDefs = new HashMap<>();
        for (Object g : L(model.get("groups"))) groupDefs.put((String) M(g).get("uuid"), M(g));
        setupPivots();
        rigCables(model, groupDefs);
        addCarriers(model, groupDefs);
        for (Object node : L(model.get("outliner"))) walk(node, null, groupDefs);
        setLen(WALK_LEN);
        Map<String, Map<String, double[][]>> keys = walkKeys();
        applyToeOut(keys);
        checkLegs();
        addArmWeight(keys, true);
        addCableKeys(keys);

        // 1) Суставы и анимация — в .bbmodel.
        Map<String, String> uuidByName = new HashMap<>();
        for (Map<String, Object> g : groupDefs.values()) {
            String name = (String) g.get("name");
            uuidByName.put(name, (String) g.get("uuid"));
            double[] p = PIVOT_OVERRIDE.get(name);
            if (p != null) g.put("origin", new ArrayList<>(List.of(p[0], p[1], p[2])));
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        for (Object e : L(model.get("elements"))) elements.add(M(e));
        Map<String, Object> walkAnim = buildAnim("walk", keys, uuidByName, false);
        EXPORT.add(new Object[]{"walk", LEN, true, LINEAR, TIMES.clone(), keys});
        preview(keys, elements, a[4]);
        checkCables(elements, keys);

        setLen(IDLE_LEN);
        Map<String, Map<String, double[][]>> idle = idleKeys();
        applyToeOut(idle);
        addArmWeight(idle, true);
        addCableKeys(idle);
        Map<String, Object> idleAnim = buildAnim("idle", idle, uuidByName, true);
        EXPORT.add(new Object[]{"idle", LEN, true, LINEAR, TIMES.clone(), idle});
        preview(idle, elements, a[4].replace(".png", "_idle.png"));
        checkCables(elements, idle);

        List<Object> anims = new ArrayList<>(List.of(walkAnim, idleAnim));
        setComboTimes();
        LINEAR = true;
        // Подбираем растяжение рук так, чтобы в ударе об землю клешни ровно доставали до земли.
        double lo = 1.0, hi = 2.2;
        for (int it = 0; it < 18; it++) {
            SLAM_STRETCH = (lo + hi) / 2;
            double low = lowestClaw(comboKeys(), elements, 5.1, 5.7);
            if (low > SLAM_GROUND) lo = SLAM_STRETCH; else hi = SLAM_STRETCH;
        }
        System.out.printf(Locale.ROOT, "slam arm stretch %.3f, claws lowest %.2f%n", SLAM_STRETCH,
                lowestClaw(comboKeys(), elements, 5.1, 5.7));
        Map<String, Map<String, double[][]>> atk = comboKeys();
        applyToeOut(atk);
        addArmWeight(atk, false);
        addCableKeys(atk);
        Map<String, Object> an = buildAnim("attack", atk, uuidByName, false);
        EXPORT.add(new Object[]{"attack", LEN, false, LINEAR, TIMES.clone(), atk});
        an.put("loop", "once");
        anims.add(an);
        previewTimes(atk, elements, a[4].replace(".png", "_attack.png"),
                new double[]{1.25, 1.45, 1.6, 3.0, 3.15, 3.5, 4.3, 4.95, 5.15});
        checkCables(elements, atk);
        setSpawnTimes();
        LINEAR = true;
        Map<String, Map<String, double[][]>> spawn = spawnKeys();
        applyToeOut(spawn);
        addCableKeys(spawn);
        Map<String, Object> sp = buildAnim("spawn", spawn, uuidByName, false);
        EXPORT.add(new Object[]{"spawn", LEN, false, LINEAR, TIMES.clone(), spawn});
        sp.put("loop", "once");
        anims.add(sp);
        previewTimes(spawn, elements, a[4].replace(".png", "_spawn.png"),
                new double[]{0.96, 1.0, 1.06, 1.16, 1.35, 1.9, 2.6, 3.2, 4.2});
        checkCables(elements, spawn);
        checkSpawnFeet(spawn);
        setGrabTimes();
        LINEAR = true;
        Map<String, Map<String, double[][]>> grab = grabKeys();
        applyToeOut(grab);
        addCableKeys(grab);
        Map<String, Object> gr = buildAnim("grab", grab, uuidByName, false);
        EXPORT.add(new Object[]{"grab", LEN, false, LINEAR, TIMES.clone(), grab});
        gr.put("loop", "once");
        anims.add(gr);
        previewTimes(grab, elements, a[4].replace(".png", "_grab.png"),
                new double[]{0.0, 0.2, 0.35, 0.45, 0.5, 0.55, 0.65, 0.9, 1.4});
        checkCables(elements, grab);
        setSkillTimes();
        LINEAR = true;
        Map<String, Map<String, double[][]>> skill = skillKeys();
        applyToeOut(skill);
        addArmWeight(skill, false);
        addCableKeys(skill);
        Map<String, Object> skA = buildAnim("skill", skill, uuidByName, false);
        EXPORT.add(new Object[]{"skill", LEN, false, LINEAR, TIMES.clone(), skill});
        skA.put("loop", "once");
        anims.add(skA);
        previewTimes(skill, elements, a[4].replace(".png", "_skill.png"),
                new double[]{0.0, 0.5, 1.0, 2.0, 3.45, 3.6, 4.2, 4.88, 5.0});
        checkCables(elements, skill);
        setLen(STRAFE_LEN, 32);
        LINEAR = false;
        for (int sd : new int[]{1, -1}) {
            String nm = sd > 0 ? "strafe_r" : "strafe_l";
            Map<String, Map<String, double[][]>> strafe = strafeKeys(sd);
            applyToeOut(strafe);
            addArmWeight(strafe, true);
            addCableKeys(strafe);
            Map<String, Object> stA = buildAnim(nm, strafe, uuidByName, false);
            EXPORT.add(new Object[]{nm, LEN, true, LINEAR, TIMES.clone(), strafe});
            anims.add(stA);
            if (sd > 0) previewTimes(strafe, elements, a[4].replace(".png", "_strafe.png"), new double[]{0.0, 0.22, 0.43, 0.65, 0.87, 1.08, 1.3});
            checkCables(elements, strafe);
        }
        setLen(GLIDE_LEN, 24);
        LINEAR = false;
        Map<String, Map<String, double[][]>> glide = glideKeys();
        applyToeOut(glide);
        addArmWeight(glide, true);
        addCableKeys(glide);
        Map<String, Object> glA = buildAnim("glide", glide, uuidByName, false);
        EXPORT.add(new Object[]{"glide", LEN, true, LINEAR, TIMES.clone(), glide});
        anims.add(glA);
        previewTimes(glide, elements, a[4].replace(".png", "_glide.png"), new double[]{0.0, 0.3, 0.6, 0.9, 1.2});
        checkCables(elements, glide);
        setLen(PUSH_LEN, 180);
        LINEAR = true;
        Map<String, Map<String, double[][]>> push = pushKeys();
        applyToeOut(push);
        addArmWeight(push, false);
        addCableKeys(push);
        Map<String, Object> puA = buildAnim("push", push, uuidByName, false);
        EXPORT.add(new Object[]{"push", LEN, false, LINEAR, TIMES.clone(), push});
        anims.add(puA);
        previewTimes(push, elements, a[4].replace(".png", "_push.png"), new double[]{0.0, 0.45, 1.5, 2.45, 2.62, 2.75, 2.9, 3.6, 4.5});
        checkCables(elements, push);
        setDeath3Times();
        LINEAR = true;
        FALL = tt -> 0; // v3: всё тело не поворачиваем — колени остаются на земле, клонится только корпус
        GROUND_BY_LEGS = true;
        Map<String, Map<String, double[][]>> death = deathKeys3();
        applyToeOut(death);
        addCableKeys(death);
        applyDeathFall(death, elements);
        Map<String, Object> deA = buildAnim("death", death, uuidByName, false);
        EXPORT.add(new Object[]{"death", LEN, false, LINEAR, TIMES.clone(), death});
        deA.put("loop", "hold_on_last_frame");
        anims.add(deA);
        previewTimes(death, elements, a[4].replace(".png", "_death.png"),
                new double[]{0.0, 0.25, 1.0, 1.8, 2.2, 2.9, 3.4, 4.0, 4.4, 6.0});
        checkCables(elements, death);
        model.put("animations", anims);
        StringBuilder sb = new StringBuilder(src.length() + 65536);
        jsonWrite(sb, model);
        Files.writeString(Path.of(a[2]), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("animations written: walk, idle");
        if (a.length > 5) exportMech(elements, a[5]);
    }

    /** Анимация Blockbench из ключей (длина — текущая LEN). */
    static Map<String, Object> buildAnim(String name, Map<String, Map<String, double[][]>> keys, Map<String, String> uuidByName,
                                         boolean selected) {
        Map<String, Object> animators = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, double[][]>> bone : keys.entrySet()) {
            List<Object> kfs = new ArrayList<>();
            for (Map.Entry<String, double[][]> ch : bone.getValue().entrySet()) {
                boolean rot = ch.getKey().equals("rotation");
                for (int i = 0; i < ch.getValue().length; i++) {
                    double[] v = ch.getValue()[i];
                    // Blockbench (проверено по его коду: displayRotation/displayPosition) прибавляет значения
                    // к кости как есть, без переворота осей — пишем модельные углы напрямую.
                    double x = v[0], y = v[1], z = v[2];
                    Map<String, Object> dp = new LinkedHashMap<>();
                    dp.put("x", r2(x)); dp.put("y", r2(y)); dp.put("z", r2(z));
                    Map<String, Object> kf = new LinkedHashMap<>();
                    kf.put("channel", ch.getKey());
                    kf.put("data_points", new ArrayList<>(List.of(dp)));
                    kf.put("uuid", UUID.randomUUID().toString());
                    kf.put("time", TIMES[i]);
                    kf.put("color", -1.0);
                    kf.put("interpolation", LINEAR ? "linear" : "catmullrom");
                    kfs.add(kf);
                }
            }
            Map<String, Object> an = new LinkedHashMap<>();
            an.put("name", bone.getKey());
            an.put("type", "bone");
            an.put("keyframes", kfs);
            animators.put(uuidByName.get(bone.getKey()), an);
        }
        Map<String, Object> anim = new LinkedHashMap<>();
        anim.put("uuid", UUID.randomUUID().toString());
        anim.put("name", name);
        anim.put("loop", "loop");
        anim.put("override", false);
        anim.put("length", LEN);
        anim.put("snapping", 20.0);
        anim.put("selected", selected);
        anim.put("anim_time_update", "");
        anim.put("blend_weight", "");
        anim.put("start_delay", "");
        anim.put("loop_delay", "");
        anim.put("animators", animators);
        System.out.println(name + ": bones " + animators.size() + ", length " + LEN);
        return anim;
    }

    /** Высота, до которой клешни опускаются в ударе об землю (земля под стопами ≈ -0.1). */
    static final double SLAM_GROUND = 0.0;

    static void setPose(Map<String, Map<String, double[][]>> keys, double t) {
        ANIM_ROT.clear(); ANIM_POS.clear(); ANIM_SCALE.clear();
        for (Map.Entry<String, Map<String, double[][]>> bone : keys.entrySet()) {
            double[][] r = bone.getValue().get("rotation"), p = bone.getValue().get("position"), sc = bone.getValue().get("scale");
            if (r != null) ANIM_ROT.put(bone.getKey(), sample(r, t));
            if (p != null) ANIM_POS.put(bone.getKey(), sample(p, t));
            if (sc != null) ANIM_SCALE.put(bone.getKey(), sample(sc, t));
        }
    }

    /** Самая низкая точка предплечий и клешней за промежуток времени. */
    static double lowestClaw(Map<String, Map<String, double[][]>> keys, List<Map<String, Object>> elements, double t0, double t1) {
        double low = 1e9;
        for (double t = t0; t <= t1; t += 0.035) {
            setPose(keys, t);
            for (Map<String, Object> e : elements) {
                boolean arm2 = false;
                for (Group g = PARENT.get((String) e.get("uuid")); g != null; g = g.parent) if (g.name.endsWith("_arm2")) arm2 = true;
                if (!arm2) continue;
                double[] f = V(e.get("from")), to = V(e.get("to"));
                for (int c = 0; c < 8; c++) {
                    double[] p = {(c & 1) == 0 ? f[0] : to[0], (c & 2) == 0 ? f[1] : to[1], (c & 4) == 0 ? f[2] : to[2]};
                    low = Math.min(low, toWorld(p, e)[1]);
                }
            }
        }
        return low;
    }

    /** Проверка появления: после касания земли щиколотки стоят на земле (y = AY). */
    static void checkSpawnFeet(Map<String, Map<String, double[][]>> keys) {
        double maxErr = 0;
        for (double t = 1.0; t <= SPAWN_LEN; t += 0.01) {
            setPose(keys, t);
            Group leg3 = GROUPS_BY_NAME.get("right_leg3");
            double[] ankle = chainWorld(PIVOT_OVERRIDE.get("right_leg3"), leg3);
            maxErr = Math.max(maxErr, Math.abs(ankle[1] - AY));
        }
        System.out.printf(Locale.ROOT, "spawn: ankle height error after landing max %.3f px%n", maxErr);
    }

    /** Ноги носками наружу (градусы): стоят и шагают чуть по диагонали в сторону. */
    static final double TOE_OUT = 10;

    /** Разворачивает ноги целиком вокруг бедра (стопа остаётся ровной на земле). */
    static void applyToeOut(Map<String, Map<String, double[][]>> keys) {
        for (int s : new int[]{-1, 1}) {
            double[][] r = keys.get((s < 0 ? "left" : "right") + "_leg").get("rotation");
            for (double[] v : r) v[1] += -s * TOE_OUT; // правая — носком к +X, левая — к -X
        }
    }

    /** Превью в заданные моменты: сверху сбоку, снизу спереди. */
    static void previewTimes(Map<String, Map<String, double[][]>> keys, List<Map<String, Object>> elements, String file,
                             double[] times) throws Exception {
        int scale = 4, cell = 66 * scale, H = 56 * scale, cols = times.length;
        BufferedImage img = new BufferedImage(cell * cols, H * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, 0x8FB07E);
        double[] depth = new double[img.getWidth() * img.getHeight()];
        for (int k = 0; k < cols * 2; k++) {
            double t = times[k % cols];
            ANIM_ROT.clear(); ANIM_POS.clear(); ANIM_SCALE.clear();
            for (Map.Entry<String, Map<String, double[][]>> bone : keys.entrySet()) {
                double[][] r = bone.getValue().get("rotation"), p = bone.getValue().get("position"), sc = bone.getValue().get("scale");
                if (r != null) ANIM_ROT.put(bone.getKey(), sample(r, t));
                if (p != null) ANIM_POS.put(bone.getKey(), sample(p, t));
                if (sc != null) ANIM_SCALE.put(bone.getKey(), sample(sc, t));
            }
            Arrays.fill(depth, Double.NEGATIVE_INFINITY);
            drawView(img, depth, elements, k < cols ? -90 : 0, (k % cols) * cell + cell / 2, (k / cols) * H + H - 5 * scale, scale);
        }
        ImageIO.write(img, "png", new File(file));
    }

    /** Превью: 6 кадров сбоку и 6 спереди. */
    static void preview(Map<String, Map<String, double[][]>> keys, List<Map<String, Object>> elements, String file) throws Exception {
        int scale = 5, cell = 60 * scale, H = 52 * scale;
        BufferedImage img = new BufferedImage(cell * 6, H * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, 0x8FB07E);
        double[] depth = new double[img.getWidth() * img.getHeight()];
        for (int k = 0; k < 12; k++) {
            double t = (k % 6) * (LEN / 6);
            double yaw = k < 6 ? -90 : 0;
            ANIM_ROT.clear(); ANIM_POS.clear(); ANIM_SCALE.clear();
            for (Map.Entry<String, Map<String, double[][]>> bone : keys.entrySet()) {
                double[][] r = bone.getValue().get("rotation"), p = bone.getValue().get("position");
                if (r != null) ANIM_ROT.put(bone.getKey(), sample(r, t));
                if (p != null) ANIM_POS.put(bone.getKey(), sample(p, t));
                double[][] sc = bone.getValue().get("scale");
                if (sc != null) ANIM_SCALE.put(bone.getKey(), sample(sc, t));
            }
            Arrays.fill(depth, Double.NEGATIVE_INFINITY);
            drawView(img, depth, elements, yaw, (k % 6) * cell + cell / 2, (k / 6) * H + H - 4 * scale, scale);
        }
        ImageIO.write(img, "png", new File(file));
    }

    /** Проверка кабелей: конец у корпуса едет только с корпусом, конец у капсулы — с капсулой. */
    static void checkCables(List<Map<String, Object>> elements, Map<String, Map<String, double[][]>> keys) {
        double errTorso = 0, errPod = 0, minK = 9, maxK = 0;
        double worstT = 0, worstErr = 0;
        boolean onKeys = System.getProperty("checkOnKeys") != null;
        int steps = onKeys ? KEYS : 600;
        for (int step = 0; step < steps; step++) {
            double t = LEN * step / steps;
            double before = errTorso;
            ANIM_ROT.clear(); ANIM_POS.clear(); ANIM_SCALE.clear();
            for (Map.Entry<String, Map<String, double[][]>> bone : keys.entrySet()) {
                double[][] r = bone.getValue().get("rotation"), p = bone.getValue().get("position"), sc = bone.getValue().get("scale");
                if (r != null) ANIM_ROT.put(bone.getKey(), sample(r, t));
                if (p != null) ANIM_POS.put(bone.getKey(), sample(p, t));
                if (sc != null) { ANIM_SCALE.put(bone.getKey(), sample(sc, t)); minK = Math.min(minK, sample(sc, t)[0]); maxK = Math.max(maxK, sample(sc, t)[0]); }
            }
            double torsoY = sample(keys.get("torso").get("position"), t)[1];
            for (Map<String, Object> e : elements) {
                Group g = PARENT.get((String) e.get("uuid"));
                if (g == null || !g.name.startsWith("cable_rig")) continue;
                CableRig rig = RIGS.stream().filter(r -> r.name().equals(g.name)).findFirst().orElseThrow();
                // Точки в собственных координатах кубика: торцы по длинной оси.
                double[] f = V(e.get("from")), to = V(e.get("to"));
                double[] size = {to[0] - f[0], to[1] - f[1], to[2] - f[2]};
                int ax = 0;
                for (int k = 1; k < 3; k++) if (Math.abs(size[k]) > Math.abs(size[ax])) ax = k;
                double[] c = {(f[0] + to[0]) / 2, (f[1] + to[1]) / 2, (f[2] + to[2]) / 2};
                double[] pa = c.clone(), pb = c.clone();
                pa[ax] = f[ax]; pb[ax] = to[ax];
                double[] wa = toWorld(pa, e), wb = toWorld(pb, e);
                double[][] rest = cableEnds(e);
                double[] wNear = Math.abs(rest[0][0]) < Math.abs(rest[1][0]) ? wa : wb;
                double[] restNear = Math.abs(rest[0][0]) < Math.abs(rest[1][0]) ? rest[0] : rest[1];
                double[] tr = ANIM_ROT.getOrDefault("torso", new double[3]);
                double[] er = rotate(restNear, tr, PIVOT_OVERRIDE.get("torso"));
                double[] exp = {er[0], er[1] + torsoY, er[2]};
                errTorso = Math.max(errTorso, dist(wNear, exp));
                if (rig.stretch()) {
                    double[] restFar = restNear == rest[0] ? rest[1] : rest[0];
                    double[] wFar = wNear == wa ? wb : wa;
                    // Где окажется та же точка, если бы она просто ехала с капсулой.
                    String arm = rig.side() + "_arm";
                    errPod = Math.max(errPod, dist(wFar, chainWorld(restFar, g.parent)));
                }
            }
            if (errTorso > before && errTorso > worstErr) { worstErr = errTorso; worstT = t; }
        }
        System.out.printf(Locale.ROOT, "cables: torso-end max error %.3f px (at %.2f s), pod-end max error %.3f px, stretch %.2f..%.2f%s%n",
                errTorso, worstT, errPod, minK, maxK, onKeys ? " [on keys]" : "");
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }

    static void drawView(BufferedImage img, double[] depth, List<Map<String, Object>> elements, double yaw, int ox, int oy, int scale) {
        for (Map<String, Object> e : elements) {
            double[] f = V(e.get("from")), t = V(e.get("to"));
            Map<String, Object> faces = M(e.get("faces"));
            for (Map.Entry<String, Object> fe : faces.entrySet()) {
                Map<String, Object> face = M(fe.getValue());
                if (face.get("texture") == null || face.get("uv") == null) continue;
                double[][] corners = faceCorners(fe.getKey(), f, t);
                double[] uv = V(face.get("uv"));
                double[][] uvc = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
                double[][] sp = new double[4][], wp = new double[4][];
                for (int k = 0; k < 4; k++) {
                    wp[k] = toWorld(corners[k], e);
                    double[] v = rotate(wp[k], new double[]{0, yaw, 0}, new double[3]);
                    sp[k] = new double[]{ox - v[0] * scale, oy - v[1] * scale, -v[2]};
                }
                double[] n = normal(wp);
                double light = 0.55 + 0.45 * Math.max(0, n[0] * 0.3 + n[1] * 0.85 + n[2] * -0.42);
                // Отдельный буфер глубины на ячейку: tri пишет только туда, где ближе.
                tri(img, depth, sp[0], sp[1], sp[2], uvc[0], uvc[1], uvc[2], light);
                tri(img, depth, sp[0], sp[2], sp[3], uvc[0], uvc[2], uvc[3], light);
            }
        }
    }

    static void jsonWrite(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof Boolean b) sb.append(b);
        else if (o instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) (double) d);
            else {
                String s = String.format(Locale.ROOT, "%.6f", d).replaceAll("0+$", "");
                sb.append(s.endsWith(".") ? s.substring(0, s.length() - 1) : s);
            }
        } else if (o instanceof String s) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\""); case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n"); case '\r' -> sb.append("\\r"); case '\t' -> sb.append("\\t");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            sb.append('"');
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                jsonWrite(sb, e.getKey()); sb.append(':'); jsonWrite(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); jsonWrite(sb, l.get(i)); }
            sb.append(']');
        }
    }

    static void render(List<Map<String, Object>> elements, String file) throws Exception {
        int scale = 9, cell = 66 * scale, H = 60 * scale;
        String[] titles = {"front", "back", "side", "3/4"};
        BufferedImage img = new BufferedImage(cell * 4, H, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < img.getWidth() * img.getHeight(); i++) img.setRGB(i % img.getWidth(), i / img.getWidth(), 0x8FB07E);
        double[] depth = new double[img.getWidth() * img.getHeight()];
        for (int view = 0; view < 4; view++) {
            Arrays.fill(depth, Double.NEGATIVE_INFINITY);
            double yaw = switch (view) { case 0 -> 0; case 1 -> 180; case 2 -> -90; default -> 35; };
            double pitch = view == 3 ? 15 : 0;
            int ox = view * cell + cell / 2, oy = H - 6 * scale;
            for (Map<String, Object> e : elements) {
                double[] f = V(e.get("from")), t = V(e.get("to"));
                Map<String, Object> faces = M(e.get("faces"));
                for (Map.Entry<String, Object> fe : faces.entrySet()) {
                    Map<String, Object> face = M(fe.getValue());
                    if (face.get("texture") == null || face.get("uv") == null) continue;
                    double[][] corners = faceCorners(fe.getKey(), f, t); // порядок: верх-лево, верх-право, низ-право, низ-лево (как в UV)
                    double[] uv = V(face.get("uv"));
                    double[][] uvc = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
                    double[][] sp = new double[4][];
                    double[][] wp = new double[4][];
                    for (int k = 0; k < 4; k++) {
                        wp[k] = toWorld(corners[k], e);
                        double[] v = rotate(wp[k], new double[]{0, yaw, 0}, new double[3]);
                        v = rotate(v, new double[]{pitch, 0, 0}, new double[3]);
                        // Камера смотрит в +Z (стоит спереди, на -Z): экран x = -x мира, глубина = -z.
                        sp[k] = new double[]{ox - v[0] * scale, oy - v[1] * scale, -v[2]};
                    }
                    double[] n = normal(wp);
                    double light = 0.55 + 0.45 * Math.max(0, n[0] * 0.3 + n[1] * 0.85 + n[2] * -0.42);
                    tri(img, depth, sp[0], sp[1], sp[2], uvc[0], uvc[1], uvc[2], light);
                    tri(img, depth, sp[0], sp[2], sp[3], uvc[0], uvc[2], uvc[3], light);
                }
            }
        }
        ImageIO.write(img, "png", new File(file));
    }

    static double[] normal(double[][] w) {
        double[] a = {w[1][0] - w[0][0], w[1][1] - w[0][1], w[1][2] - w[0][2]};
        double[] b = {w[3][0] - w[0][0], w[3][1] - w[0][1], w[3][2] - w[0][2]};
        double[] n = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        double l = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        return l == 0 ? new double[]{0, 1, 0} : new double[]{-n[0] / l, -n[1] / l, -n[2] / l};
    }

    /** Углы грани в порядке UV: верх-лево, верх-право, низ-право, низ-лево (как Minecraft раскладывает грани). */
    static double[][] faceCorners(String dir, double[] f, double[] t) {
        double x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        return switch (dir) {
            case "north" -> new double[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}};
            case "south" -> new double[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
            case "east" -> new double[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case "west" -> new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
            case "up" -> new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            default -> new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}};
        };
    }

    static void tri(BufferedImage img, double[] depth, double[] a, double[] b, double[] c,
                    double[] ua, double[] ub, double[] uc, double light) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double px = x + 0.5, py = y + 0.5;
            double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
            double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
            double w2 = 1 - w0 - w1;
            if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
            double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
            int i = y * img.getWidth() + x;
            if (z <= depth[i]) continue;
            double u = (w0 * ua[0] + w1 * ub[0] + w2 * uc[0]) * S, v = (w0 * ua[1] + w1 * ub[1] + w2 * uc[1]) * S;
            int tx = Math.min(tex.getWidth() - 1, Math.max(0, (int) Math.floor(u))), ty = Math.min(tex.getHeight() - 1, Math.max(0, (int) Math.floor(v)));
            int argb = tex.getRGB(tx, ty);
            if ((argb >>> 24) == 0) continue;
            depth[i] = z;
            boolean glowing = (glow.getRGB(tx, ty) >>> 24) != 0;
            double k = glowing ? 1.0 : light;
            int rr = (int) (((argb >> 16) & 0xFF) * k), gg = (int) (((argb >> 8) & 0xFF) * k), bb = (int) ((argb & 0xFF) * k);
            img.setRGB(x, y, (rr << 16) | (gg << 8) | bb);
        }
    }
}
