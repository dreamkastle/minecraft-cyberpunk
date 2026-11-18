import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Убирает мелькание (z-fighting) в .bbmodel: находит грани разных кубиков, лежащие в одной плоскости,
 * смотрящие в одну сторону и перекрывающиеся, и выталкивает меньшую из них наружу на EPS.
 * Аргументы: вход.bbmodel выход.bbmodel
 */
public class Fixer {
    static final double EPS = 0.02;      // на сколько выталкивать грань (в пикселях модели)
    static final double PLANE_TOL = 0.004;

    // ---------- Мини-JSON (чтение и запись) ----------
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
                ws(); String k = str(); ws(); pos++;
                m.put(k, parse()); ws();
                if (src.charAt(pos++) == '}') return m;
            }
        } else if (c == '[') {
            pos++;
            List<Object> l = new ArrayList<>();
            ws();
            if (src.charAt(pos) == ']') { pos++; return l; }
            while (true) { l.add(parse()); ws(); if (src.charAt(pos++) == ']') return l; }
        } else if (c == '"') return str();
        else if (src.startsWith("true", pos)) { pos += 4; return true; }
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
                    case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(c);
        }
    }

    static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof Boolean b) sb.append(b);
        else if (o instanceof Double d) sb.append(num(d));
        else if (o instanceof String s) {
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
                write(sb, e.getKey()); sb.append(':'); write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); write(sb, l.get(i)); }
            sb.append(']');
        }
    }

    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(Locale.ROOT, "%.6f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    @SuppressWarnings("unchecked") static Map<String, Object> M(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> L(Object o) { return (List<Object>) o; }
    static double D(Object o) { return ((Number) o).doubleValue(); }
    static double[] V(Object o) { List<Object> l = L(o); double[] r = new double[l.size()]; for (int i = 0; i < r.length; i++) r[i] = D(l.get(i)); return r; }

    // ---------- Геометрия ----------
    record Group(double[] origin, double[] rot, Group parent) {}
    static final Map<String, Group> PARENT = new HashMap<>();

    static void walk(Object node, Group parent, Map<String, Map<String, Object>> defs) {
        if (node instanceof String uuid) { PARENT.put(uuid, parent); return; }
        Map<String, Object> n = M(node);
        Map<String, Object> def = defs.getOrDefault((String) n.get("uuid"), n);
        Group g = new Group(V(def.get("origin")), def.get("rotation") != null ? V(def.get("rotation")) : new double[3], parent);
        for (Object c : L(n.get("children"))) walk(c, g, defs);
    }

    static double[] rotate(double[] p, double[] rot, double[] o) {
        double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
        double ax = Math.toRadians(rot[0]), ay = Math.toRadians(rot[1]), az = Math.toRadians(rot[2]);
        double y1 = y * Math.cos(ax) - z * Math.sin(ax), z1 = y * Math.sin(ax) + z * Math.cos(ax); y = y1; z = z1;
        double x2 = x * Math.cos(ay) + z * Math.sin(ay), z2 = -x * Math.sin(ay) + z * Math.cos(ay); x = x2; z = z2;
        double x3 = x * Math.cos(az) - y * Math.sin(az), y3 = x * Math.sin(az) + y * Math.cos(az); x = x3; y = y3;
        return new double[]{x + o[0], y + o[1], z + o[2]};
    }

    static double[] toWorld(double[] p, Map<String, Object> e) {
        double[] q = p;
        if (e.get("rotation") != null) q = rotate(q, V(e.get("rotation")), V(e.get("origin")));
        for (Group g = PARENT.get((String) e.get("uuid")); g != null; g = g.parent) q = rotate(q, g.rot, g.origin);
        return q;
    }

    static final String[] DIRS = {"north", "south", "east", "west", "up", "down"};

    static double[][] corners(String dir, double[] f, double[] t) {
        double x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        return switch (dir) {
            case "north" -> new double[][]{{x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1}};
            case "south" -> new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}, {x1, y2, z2}};
            case "east" -> new double[][]{{x2, y1, z1}, {x2, y1, z2}, {x2, y2, z2}, {x2, y2, z1}};
            case "west" -> new double[][]{{x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}, {x1, y2, z1}};
            case "up" -> new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            default -> new double[][]{{x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}, {x1, y1, z2}};
        };
    }

    static double[] localNormal(String dir) {
        return switch (dir) {
            case "north" -> new double[]{0, 0, -1}; case "south" -> new double[]{0, 0, 1};
            case "east" -> new double[]{1, 0, 0}; case "west" -> new double[]{-1, 0, 0};
            case "up" -> new double[]{0, 1, 0}; default -> new double[]{0, -1, 0};
        };
    }

    record Face(Map<String, Object> el, String dir, double[] n, double d, double[][] pts, double area) {}

    static List<Face> faces(List<Map<String, Object>> els) {
        List<Face> out = new ArrayList<>();
        for (Map<String, Object> e : els) {
            Map<String, Object> fs = M(e.get("faces"));
            double[] f = V(e.get("from")), t = V(e.get("to"));
            for (String dir : DIRS) {
                Object fo = fs.get(dir);
                if (fo == null || M(fo).get("texture") == null) continue;
                double[][] c = corners(dir, f, t);
                double[][] w = new double[4][];
                for (int k = 0; k < 4; k++) w[k] = toWorld(c[k], e);
                // Нормаль в мире: поворачиваем точку «центр + нормаль».
                double[] cen = {(c[0][0] + c[2][0]) / 2, (c[0][1] + c[2][1]) / 2, (c[0][2] + c[2][2]) / 2};
                double[] ln = localNormal(dir);
                double[] a = toWorld(cen, e), b = toWorld(new double[]{cen[0] + ln[0], cen[1] + ln[1], cen[2] + ln[2]}, e);
                double[] n = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
                double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                n = new double[]{n[0] / len, n[1] / len, n[2] / len};
                double d = n[0] * a[0] + n[1] * a[1] + n[2] * a[2];
                double[] e1 = {w[1][0] - w[0][0], w[1][1] - w[0][1], w[1][2] - w[0][2]};
                double[] e2 = {w[3][0] - w[0][0], w[3][1] - w[0][1], w[3][2] - w[0][2]};
                double area = Math.sqrt(sq(e1)) * Math.sqrt(sq(e2));
                if (area < 1e-6) continue;
                out.add(new Face(e, dir, n, d, w, area));
            }
        }
        return out;
    }

    static double sq(double[] v) { return v[0] * v[0] + v[1] * v[1] + v[2] * v[2]; }

    /** Перекрываются ли две грани в общей плоскости (по прямоугольникам в базисе плоскости). */
    static boolean overlap(Face a, Face b) {
        double[] n = a.n;
        double[] u = Math.abs(n[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] e1 = cross(n, u), e2;
        double l = Math.sqrt(sq(e1)); e1 = new double[]{e1[0] / l, e1[1] / l, e1[2] / l};
        e2 = cross(n, e1);
        double[] ra = rect(a.pts, e1, e2), rb = rect(b.pts, e1, e2);
        double ox = Math.min(ra[1], rb[1]) - Math.max(ra[0], rb[0]);
        double oy = Math.min(ra[3], rb[3]) - Math.max(ra[2], rb[2]);
        return ox > 0.01 && oy > 0.01;
    }

    static double[] rect(double[][] pts, double[] e1, double[] e2) {
        double[] r = {1e9, -1e9, 1e9, -1e9};
        for (double[] p : pts) {
            double x = p[0] * e1[0] + p[1] * e1[1] + p[2] * e1[2], y = p[0] * e2[0] + p[1] * e2[1] + p[2] * e2[2];
            r[0] = Math.min(r[0], x); r[1] = Math.max(r[1], x); r[2] = Math.min(r[2], y); r[3] = Math.max(r[3], y);
        }
        return r;
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** Выталкивает грань кубика наружу на EPS (в его собственных координатах). */
    static void push(Map<String, Object> e, String dir) {
        List<Object> from = L(e.get("from")), to = L(e.get("to"));
        switch (dir) {
            case "north" -> from.set(2, D(from.get(2)) - EPS);
            case "south" -> to.set(2, D(to.get(2)) + EPS);
            case "east" -> to.set(0, D(to.get(0)) + EPS);
            case "west" -> from.set(0, D(from.get(0)) - EPS);
            case "up" -> to.set(1, D(to.get(1)) + EPS);
            default -> from.set(1, D(from.get(1)) - EPS);
        }
    }

    public static void main(String[] a) throws Exception {
        src = Files.readString(Path.of(a[0]), StandardCharsets.UTF_8);
        pos = 0;
        Map<String, Object> model = M(parse());
        Map<String, Map<String, Object>> defs = new HashMap<>();
        if (model.get("groups") != null) for (Object g : L(model.get("groups"))) defs.put((String) M(g).get("uuid"), M(g));
        for (Object node : L(model.get("outliner"))) walk(node, null, defs);
        List<Map<String, Object>> els = new ArrayList<>();
        for (Object e : L(model.get("elements"))) els.add(M(e));

        int total = 0;
        for (int pass = 1; pass <= 6; pass++) {
            List<Face> fs = faces(els);
            Set<String> pushed = new HashSet<>();
            int found = 0;
            for (int i = 0; i < fs.size(); i++) for (int j = i + 1; j < fs.size(); j++) {
                Face x = fs.get(i), y = fs.get(j);
                if (x.el == y.el) continue;
                double dot = x.n[0] * y.n[0] + x.n[1] * y.n[1] + x.n[2] * y.n[2];
                if (dot < 0.9995 || Math.abs(x.d - y.d) > PLANE_TOL) continue;
                if (!overlap(x, y)) continue;
                found++;
                Face small = x.area <= y.area ? x : y; // деталь поверх плиты — выталкиваем её
                String key = small.el.get("uuid") + small.dir;
                if (pushed.add(key)) push(small.el, small.dir);
            }
            System.out.println("pass " + pass + ": overlapping pairs " + found + ", faces pushed " + pushed.size());
            total += pushed.size();
            if (found == 0) break;
        }
        System.out.println("total pushes: " + total);
        StringBuilder sb = new StringBuilder(src.length() + 1024);
        write(sb, model);
        Files.writeString(Path.of(a[1]), sb.toString(), StandardCharsets.UTF_8);
    }
}
