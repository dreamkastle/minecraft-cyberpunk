import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** Генератор модели костюма-меха для Blockbench (.bbmodel, формат Modded Entity). Перед игроком — -Z. */
public class MechGen {
    // Палитра: индекс -> цвет. Ячейки 4x4 в текстуре 16x16.
    static final int NAVY = 0, DARK = 1, BLACK = 2, LIGHT = 3, RED = 4, CABLE = 5, CYAN = 6, WHITE = 7;
    static final int[] PALETTE = {0x1F2B5C, 0x141A3A, 0x07080F, 0x34478C, 0xFF1E1E, 0xB8263E, 0x8FF4FF, 0xE6F6FF};

    record Cube(String name, double[] from, double[] to, int color, double[] rot, double[] origin, String uuid) {}

    static final Map<String, List<Cube>> GROUPS = new LinkedHashMap<>();
    static final Map<String, double[]> PIVOTS = new LinkedHashMap<>();
    static String group;

    static void group(String name, double x, double y, double z) {
        group = name;
        GROUPS.put(name, new ArrayList<>());
        PIVOTS.put(name, new double[]{x, y, z});
    }

    static void box(String name, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        rbox(name, x1, y1, z1, x2, y2, z2, color, 0, 0, 0, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
    }

    static void rbox(String name, double x1, double y1, double z1, double x2, double y2, double z2, int color,
                     double rx, double ry, double rz, double ox, double oy, double oz) {
        GROUPS.get(group).add(new Cube(name,
                new double[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)},
                new double[]{Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)},
                color, new double[]{rx, ry, rz}, new double[]{ox, oy, oz}, UUID.randomUUID().toString()));
    }

    /** Копия группы, отражённая по X (правая сторона -> левая). */
    static void mirrorGroup(String src, String dst) {
        double[] p = PIVOTS.get(src);
        group(dst, -p[0], p[1], p[2]);
        for (Cube c : GROUPS.get(src)) {
            GROUPS.get(dst).add(new Cube(c.name.replace("right", "left"),
                    new double[]{-c.to[0], c.from[1], c.from[2]},
                    new double[]{-c.from[0], c.to[1], c.to[2]},
                    c.color, new double[]{c.rot[0], -c.rot[1], -c.rot[2]},
                    new double[]{-c.origin[0], c.origin[1], c.origin[2]}, UUID.randomUUID().toString()));
        }
    }

    /** Отражённая по X пара внутри текущей группы (для корпуса). */
    static void pair(String name, double x1, double y1, double z1, double x2, double y2, double z2, int color,
                     double rx, double ry, double rz, double ox, double oy, double oz) {
        rbox(name + "_r", x1, y1, z1, x2, y2, z2, color, rx, ry, rz, ox, oy, oz);
        rbox(name + "_l", -x1, y1, z1, -x2, y2, z2, color, rx, -ry, -rz, -ox, oy, oz);
    }

    static void pair(String name, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        pair(name, x1, y1, z1, x2, y2, z2, color, 0, 0, 0, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
    }

    public static void main(String[] args) throws Exception {
        build();
        Path out = Path.of(args[0]);
        Files.writeString(out, json(), StandardCharsets.UTF_8);
        int n = GROUPS.values().stream().mapToInt(List::size).sum();
        System.out.println("cubes: " + n + " -> " + out);
    }

    static void build() {
        // ===== Корпус: пояс, рамка на груди, рюкзак, баллоны, кабели =====
        group("body", 0, 24, 0);
        box("waist_belt", -5, 11, -3.2, 5, 13.5, 3.2, NAVY);
        box("chest_frame", -4.6, 13.5, -3.4, 4.6, 15, -2.6, NAVY);
        box("chest_core", -1.5, 12, -3.9, 1.5, 15.6, -3.2, DARK);
        box("crotch_plate", -2, 9.5, -3.4, 2, 11.5, -2.6, NAVY);
        pair("shoulder_strap", -4.6, 19.5, -3, -2.6, 24.2, -2.4, NAVY);
        box("backpack", -5, 13, 2.2, 5, 23, 5.6, NAVY);
        box("backpack_upper", -3, 22, 2.2, 3, 27.5, 5, NAVY);
        box("backpack_cap", -2, 27.5, 2.6, 2, 28.6, 4.6, DARK);
        for (int i = 0; i < 5; i++) {
            box("spine_" + i, -1.2, 14 + i * 1.8, 5.6, 1.2, 15.1 + i * 1.8, 6.5, DARK);
        }
        pair("thruster", -5.6, 23, 2.6, -3.4, 29.6, 4.8, DARK);
        pair("thruster_glow", -5.2, 24, 2.3, -3.8, 28.4, 2.6, CYAN);
        pair("thruster_cap", -5.9, 29.6, 2.3, -3.1, 30.6, 5.1, NAVY);
        // Красные кабели к рукам: сзади и поверх груди.
        pair("cable_back", -10.5, 20, 2.6, -5, 21, 3.6, CABLE, 0, 0, -25, -5, 20.5, 3.1);
        pair("cable_chest_upper", -9.5, 17.5, -2.6, -4, 18.5, -1.6, CABLE, 0, 0, 30, -4, 18, -2.1);
        pair("cable_chest_lower", -9, 15.2, -2.9, -3.2, 16.2, -1.9, CABLE, 0, 0, 18, -3.2, 15.7, -2.4);

        // ===== Правая рука: капсула-«плечо», предплечье, клешня =====
        group("right_arm", -5, 22, 0);
        box("pod_wide_right", -18.5, 24, -4, -6.5, 32, 6, NAVY);
        box("pod_tall_right", -16.5, 22, -4, -8.5, 34, 6, NAVY);
        box("pod_cap_right", -15, 34, -1.5, -10, 35.6, 3.5, DARK);
        box("pod_band_right", -18.8, 27.4, -1.2, -6.2, 28.4, 3.2, BLACK);
        // Красные огни спереди (по диагоналям).
        double[][] lights = {{-15.5, 31, -45}, {-9.5, 31, 45}, {-15.5, 25, 45}, {-9.5, 25, -45}};
        for (double[] l : lights) {
            rbox("pod_light_right", l[0] - 1.1, l[1] - 0.5, -4.4, l[0] + 1.1, l[1] + 0.5, -3.9, RED,
                    0, 0, l[2], l[0], l[1], -4.15);
        }
        // Красное кольцо сзади с «глазами».
        box("ring_top_right", -16, 30.5, 6, -9, 31.5, 6.5, RED);
        box("ring_bottom_right", -16, 24.5, 6, -9, 25.5, 6.5, RED);
        box("ring_side_a_right", -16, 25.5, 6, -15, 30.5, 6.5, RED);
        box("ring_side_b_right", -10, 25.5, 6, -9, 30.5, 6.5, RED);
        box("ring_core_right", -15, 25.5, 6, -10, 30.5, 6.8, DARK);
        box("ring_eye_a_right", -13.8, 28, 6.8, -13.1, 28.6, 7.05, RED);
        box("ring_eye_b_right", -11.9, 28, 6.8, -11.2, 28.6, 7.05, RED);
        // Шея и предплечье.
        box("neck_right", -15, 19, -2, -11, 22, 3, DARK);
        box("forearm_right", -19.5, 9, -4, -8.5, 19, 5, NAVY);
        box("forearm_top_right", -19, 18.6, -3.6, -9, 19.6, 4.6, LIGHT);
        box("forearm_inner_right", -8.9, 10, -3, -8.2, 18, 4, DARK);
        box("tab_upper_right", -21, 15, -4.5, -19.5, 17.5, -2, NAVY);
        box("tab_lower_right", -21, 10.5, -4.5, -19.5, 13, -2, NAVY);
        // Красные круги на внешней стороне.
        for (double y : new double[]{11.5, 16}) {
            for (double z : new double[]{0, 3.3}) {
                box("side_ring_right", -19.8, y - 1, z - 1, -19.5, y + 1, z + 1, RED);
                box("side_ring_core_right", -19.95, y - 0.45, z - 0.45, -19.7, y + 0.45, z + 0.45, DARK);
            }
        }
        // Кисть и клешня (два пальца спереди, один сзади).
        box("wrist_right", -16, 7.5, -2, -12, 9, 3, DARK);
        box("palm_right", -17, 5.8, -2.5, -11, 7.6, 3, NAVY);
        rbox("finger_a_right", -17, 3, -2.2, -15.5, 6, -0.7, NAVY, -15, 0, 0, -16.25, 6, -1.45);
        rbox("finger_a_tip_right", -17, 0.4, -3.2, -15.5, 3.1, -1.7, DARK, 25, 0, 0, -16.25, 3.1, -2.4);
        rbox("finger_b_right", -12.5, 3, -2.2, -11, 6, -0.7, NAVY, -15, 0, 0, -11.75, 6, -1.45);
        rbox("finger_b_tip_right", -12.5, 0.4, -3.2, -11, 3.1, -1.7, DARK, 25, 0, 0, -11.75, 3.1, -2.4);
        rbox("thumb_right", -15, 3, 1, -13.5, 6, 2.5, NAVY, 15, 0, 0, -14.25, 6, 1.75);
        rbox("thumb_tip_right", -15, 0.4, 1.8, -13.5, 3.1, 3.3, DARK, -25, 0, 0, -14.25, 3.1, 2.5);
        mirrorGroup("right_arm", "left_arm");

        // ===== Правая нога: бедро, колено, голень, ботинок =====
        group("right_leg", -1.9, 12, 0);
        box("hip_slot_right", -4, 10.4, -3.6, -1, 12.4, -3.1, RED);
        box("hip_slot_core_right", -3.4, 10.9, -3.8, -1.6, 11.9, -3.5, DARK);
        box("thigh_right", -5, 7, -3, 0.6, 12, 3, NAVY);
        box("knee_right", -4.6, 4.5, -3.9, 0.2, 7.5, -2.6, NAVY);
        box("knee_groove_a_right", -4, 6.5, -4.05, -0.4, 6.9, -3.8, BLACK);
        box("knee_groove_b_right", -4, 5.1, -4.05, -0.4, 5.5, -3.8, BLACK);
        box("shin_right", -4.8, 2, -3, 0.4, 7, 3, NAVY);
        box("calf_piston_right", -3.4, 2.5, 3, -0.8, 8, 4.3, DARK);
        box("calf_line_right", -2.3, 3, 4.3, -1.9, 7.5, 4.45, RED);
        box("boot_right", -5.2, 0, -4.2, 0.8, 2.5, 3.4, NAVY);
        box("boot_toe_right", -4.8, 0, -5.4, 0.4, 1.8, -4.2, DARK);
        mirrorGroup("right_leg", "left_leg");
    }

    // ---------- JSON ----------

    static String n(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 1000) / 1000.0);
    }

    static String arr(double[] a) {
        return "[" + n(a[0]) + "," + n(a[1]) + "," + n(a[2]) + "]";
    }

    static String uv(int color) {
        double u = (color % 4) * 4 + 1, v = (color / 4) * 4 + 1;
        return "[" + n(u) + "," + n(v) + "," + n(u + 2) + "," + n(v + 2) + "]";
    }

    static String face(int color) {
        return "{\"uv\":" + uv(color) + ",\"texture\":0}";
    }

    static String json() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"modded_entity\",\"box_uv\":false},");
        sb.append("\"name\":\"sandevistan_mech_suit\",\"model_identifier\":\"\",\"modded_entity_entity_class\":\"\",");
        sb.append("\"modded_entity_version\":\"Fabric 1.17+\",\"modded_entity_flip_y\":true,");
        sb.append("\"visible_box\":[1,1,0],\"variable_placeholders\":\"\",\"variable_placeholder_buttons\":[],");
        sb.append("\"timeline_setups\":[],\"unhandled_root_fields\":{},");
        sb.append("\"resolution\":{\"width\":16,\"height\":16},\"elements\":[");
        boolean first = true;
        for (List<Cube> cubes : GROUPS.values()) {
            for (Cube c : cubes) {
                if (!first) sb.append(",");
                first = false;
                int up = c.color == NAVY ? LIGHT : c.color;
                int down = c.color == NAVY ? DARK : c.color;
                sb.append("{\"name\":\"").append(c.name).append("\",\"box_uv\":false,\"rescale\":false,\"locked\":false,")
                        .append("\"light_emission\":0,\"render_order\":\"default\",\"allow_mirror_modeling\":true,")
                        .append("\"from\":").append(arr(c.from)).append(",\"to\":").append(arr(c.to))
                        .append(",\"autouv\":0,\"color\":").append(c.color % 8)
                        .append(",\"rotation\":").append(arr(c.rot))
                        .append(",\"origin\":").append(arr(c.origin))
                        .append(",\"faces\":{\"north\":").append(face(c.color))
                        .append(",\"east\":").append(face(c.color))
                        .append(",\"south\":").append(face(c.color))
                        .append(",\"west\":").append(face(c.color))
                        .append(",\"up\":").append(face(up))
                        .append(",\"down\":").append(face(down))
                        .append("},\"type\":\"cube\",\"uuid\":\"").append(c.uuid).append("\"}");
            }
        }
        sb.append("],\"outliner\":[");
        first = true;
        for (Map.Entry<String, List<Cube>> g : GROUPS.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(g.getKey()).append("\",\"origin\":").append(arr(PIVOTS.get(g.getKey())))
                    .append(",\"rotation\":[0,0,0],\"color\":0,\"uuid\":\"").append(UUID.randomUUID())
                    .append("\",\"export\":true,\"mirror_uv\":false,\"isOpen\":true,\"locked\":false,")
                    .append("\"visibility\":true,\"autouv\":0,\"children\":[");
            boolean f2 = true;
            for (Cube c : g.getValue()) {
                if (!f2) sb.append(",");
                f2 = false;
                sb.append("\"").append(c.uuid).append("\"");
            }
            sb.append("]}");
        }
        sb.append("],\"textures\":[{\"path\":\"\",\"name\":\"mech_palette.png\",\"folder\":\"\",\"namespace\":\"\",")
                .append("\"id\":\"0\",\"group\":\"\",\"width\":16,\"height\":16,\"uv_width\":16,\"uv_height\":16,")
                .append("\"particle\":false,\"use_as_default\":false,\"layers_enabled\":false,\"sync_to_project\":\"\",")
                .append("\"render_mode\":\"default\",\"render_sides\":\"auto\",\"frame_time\":1,")
                .append("\"frame_order_type\":\"loop\",\"frame_order\":\"\",\"frame_interpolate\":false,")
                .append("\"visible\":true,\"internal\":true,\"saved\":false,\"uuid\":\"").append(UUID.randomUUID())
                .append("\",\"source\":\"data:image/png;base64,").append(palettePng()).append("\"}]}");
        return sb.toString();
    }

    static String palettePng() throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 16; i++) {
            int rgb = i < PALETTE.length ? PALETTE[i] : 0x808080;
            int cx = (i % 4) * 4, cy = (i / 4) * 4;
            for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) img.setRGB(cx + x, cy + y, 0xFF000000 | rgb);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
