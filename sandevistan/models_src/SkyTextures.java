import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Текстуры блока «осколок неба» (32×32): день (низ куска — небо, каким его видели с земли), ночь (верх — космос со
 * звёздами) и бок (тёмно-фиолетовый с глитч-полосами, 8 кадров анимации). Запуск: java SkyTextures.java <папка block>
 */
public class SkyTextures {
    static final int S = 32;

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        ImageIO.write(day(), "png", new File(dir, "sky_shard_day.png"));
        ImageIO.write(night(), "png", new File(dir, "sky_shard_night.png"));
        ImageIO.write(side(), "png", new File(dir, "sky_shard_side.png"));
        System.out.println("ok");
    }

    static int rgb(double r, double g, double b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Плавный шум без швов (сумма синусов). */
    static double noise(double x, double y, int seed) {
        Random r = new Random(seed);
        double s = 0, w = 0;
        for (int i = 0; i < 6; i++) {
            double fx = 1 + r.nextInt(3), fy = 1 + r.nextInt(3), ph = r.nextDouble() * 6.28, amp = 1.0 / (1 + i * 0.6);
            s += amp * Math.sin(2 * Math.PI * (fx * x / S + fy * y / S) + ph);
            w += amp;
        }
        return s / w;
    }

    static BufferedImage day() {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double base = 0.5 + 0.12 * noise(x, y, 3);
                double r = 130 + 25 * base, g = 180 + 20 * base, b = 240;
                double cloud = Math.max(0, noise(x, y, 11) * 1.6 + noise(x * 2, y * 2, 17) * 0.6 - 0.25);
                cloud = Math.min(1, cloud * 0.9);
                r += (250 - r) * cloud;
                g += (252 - g) * cloud;
                b += (255 - b) * cloud;
                img.setRGB(x, y, rgb(r, g, b));
            }
        }
        return img;
    }

    /** Ночь — монолит: тёмно-синий камень-космос, мягкая туманность без резких деталей, редкие тусклые звёзды без крестиков. */
    static BufferedImage night() {
        return nightShade(1.0, 7);
    }

    static BufferedImage nightShade(double k, int seed) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(seed);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double neb = 0.5 + 0.5 * noise(x, y, 23);
                double neb2 = 0.5 + 0.5 * noise(x, y, 29);
                img.setRGB(x, y, rgb(k * (16 + 14 * neb), k * (18 + 8 * neb2), k * (40 + 20 * neb + 10 * neb2)));
            }
        }
        for (int i = 0; i < 9; i++) {
            int x = r.nextInt(S), y = r.nextInt(S);
            double br = k * (95 + r.nextInt(70));
            img.setRGB(x, y, rgb(br * 0.9, br * 0.95, br));
        }
        return img;
    }

    /** Бок — та же «ночь», чуть темнее: ступеньки сливаются в одну глыбу. */
    static BufferedImage side() {
        return nightShade(0.85, 13);
    }
}
