import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Маска свечения костюма из текстуры пользователя: остаются только ЯРКО-КРАСНЫЕ пиксели (красный сильно больше
 * зелёного и синего), остальное — прозрачное. Запуск:
 *   java GlowMask.java текстура.png маска.png          — сделать маску
 *   java GlowMask.java текстура.png                    — только показать, какие красные есть
 */
public class GlowMask {
    /** Порог: красный канал не меньше MIN_R, а зелёный и синий — меньше доли от красного. */
    static final int MIN_R = 170;
    static final double MAX_GB = 0.25; // розовые (#E8324E кабели, #FF6F82) — не светятся, как просил пользователь

    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int w = src.getWidth(), h = src.getHeight();
        Map<String, Integer> reds = new TreeMap<>();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int kept = 0, opaque = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int al = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                if (al < 128) continue;
                opaque++;
                if (r > 120 && g < r * 0.6 && b < r * 0.6) reds.merge(String.format("%02X%02X%02X", r, g, b), 1, Integer::sum);
                boolean pure = r >= MIN_R && g <= r * MAX_GB && b <= r * MAX_GB;
                boolean highlight = r >= 250 && Math.abs(g - b) < 8 && g < 130; // светлые блики на красных огнях
                if (pure || highlight) {
                    out.setRGB(x, y, argb | 0xFF000000);
                    kept++;
                }
            }
        }
        System.out.println("opaque " + opaque + ", glowing " + kept);
        reds.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(25)
                .forEach(e -> System.out.println("  #" + e.getKey() + "  " + e.getValue()));
        if (a.length > 1) ImageIO.write(out, "png", new File(a[1]));
    }
}
