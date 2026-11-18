package com.smi7i.sandevistan.client.mech;

import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.mech.MechManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Кабина пилота (вид от первого лица в костюме): каркас по углам, стекло, козырёк, радар, приборы, шкала наклона,
 * рамка прицела. Повреждения: трещины на стекле (больше — чем меньше здоровья), аварийные лампы и надпись,
 * искры по стеклу при каждом попадании.
 */
public final class MechCockpitHud {
    private static final int CYAN = 0x5FE8FF, GREEN = 0x39FF6A, RED = 0xFF3048, AMBER = 0xFFB040;
    private static final float[] CRACK_AT = {0.8F, 0.66F, 0.52F, 0.4F, 0.28F, 0.17F};
    private static float[][][] cracks;
    private static float lastHp = -1;
    private static long hitFlashAt;

    private static final class Spark {
        float x, y, vx, vy;
        long born;
        int life;
    }

    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final Random R = new Random();

    private MechCockpitHud() {}

    private static boolean visible(Minecraft mc) {
        return mc.player != null && ModAttachments.hasMech(mc.player) && mc.options.getCameraType().isFirstPerson()
                && mc.getCameraEntity() == mc.player && !MechShowdownClient.hideHud() && !MechCutscene.active()
                && !MechManager.isEquipping(mc.player) && !mc.player.isInvisible();
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) {
            lastHp = -1;
            return;
        }
        long now = System.currentTimeMillis();
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        Player p = mc.player;
        float hp = p.getHealth() / Math.max(1, p.getMaxHealth());
        if (lastHp >= 0 && hp < lastHp - 0.001F) onHit(w, h, now);
        lastHp = hp;

        // Стекло: лёгкий холодный оттенок и козырёк сверху.
        g.fill(0, 0, w, h, HoloUi.argb(0.05F, 0x08303A));
        for (int i = 0; i < 10; i++) g.fill(0, i * 2, w, i * 2 + 2, HoloUi.argb(0.05F * (10 - i), 0x02060A));
        // Каркас: тёмные стойки по углам с подсвеченной кромкой.
        float strut = Math.max(18, h * 0.05F);
        // Стойки только в самых углах — не заходят на радар, панели и шкалы.
        strut(g, -10, h * 0.16F, w * 0.08F, -10, strut);
        strut(g, w + 10, h * 0.16F, w * 0.92F, -10, strut);
        strut(g, -10, h * 0.88F, w * 0.05F, h + 10, strut);
        strut(g, w + 10, h * 0.88F, w * 0.95F, h + 10, strut);
        // Рамка прицела и шкала наклона.
        int cx = w / 2, cy = h / 2;
        int b = 16, l = 5, rc = HoloUi.argb(0.55F, CYAN);
        g.fill(cx - b, cy - b, cx - b + l, cy - b + 1, rc); g.fill(cx - b, cy - b, cx - b + 1, cy - b + l, rc);
        g.fill(cx + b - l, cy - b, cx + b, cy - b + 1, rc); g.fill(cx + b - 1, cy - b, cx + b, cy - b + l, rc);
        g.fill(cx - b, cy + b - 1, cx - b + l, cy + b, rc); g.fill(cx - b, cy + b - l, cx - b + 1, cy + b, rc);
        g.fill(cx + b - l, cy + b - 1, cx + b, cy + b, rc); g.fill(cx + b - 1, cy + b - l, cx + b, cy + b, rc);
        float pitch = p.getXRot();
        for (int k = -3; k <= 3; k++) {
            int ang = (int) (Math.round(pitch / 10.0) * 10) + k * 10;
            float y = cy + (pitch - ang) * -3.0F;
            if (y < cy - 70 || y > cy + 70) continue;
            float a = 0.45F * (1 - Math.abs(y - cy) / 75F);
            int col = HoloUi.argb(a, CYAN);
            g.fill(cx - 78, (int) y, cx - 52, (int) y + 1, col);
            g.fill(cx + 52, (int) y, cx + 78, (int) y + 1, col);
            HoloUi.text(g, mc.font, String.valueOf(-ang), cx + 82, (int) y - 4, CYAN, a);
        }
        // Радар справа сверху, компактный: снизу место под панель способностей и шкалу ульты.
        radar(g, mc, p, w - 50, 46, 30, now);
        gauges(g, mc, hp, 10, (int) (h * 0.40F), now);

        // Повреждения: трещины на стекле, аварийные лампы и надпись.
        cracks(g, w, h, hp);
        if (hp < 0.35F) {
            boolean on = (now / 260) % 2 == 0;
            int lamp = HoloUi.argb(on ? 0.9F : 0.25F, RED);
            g.fill(w / 2 - 150, 4, w / 2 - 138, 10, lamp);
            g.fill(w / 2 + 138, 4, w / 2 + 150, 10, lamp);
            if (on) {
                HoloUi.glow(g, w / 2 - 150, 4, w / 2 - 138, 10, RED, 0.4F, 4);
                HoloUi.glow(g, w / 2 + 138, 4, w / 2 + 150, 10, RED, 0.4F, 4);
                g.fill(0, 0, w, h, HoloUi.argb(0.06F, RED));
            }
            HoloUi.centeredText(g, mc.font, String.format(Locale.ROOT, "⚠ ЦЕЛОСТНОСТЬ КОРПУСА %d%%", Math.round(hp * 100)), w / 2, 48,
                    RED, on ? 0.95F : 0.55F);
        }
        // Вспышка удара по стеклу и искры.
        long since = now - hitFlashAt;
        if (since < 120) g.fill(0, 0, w, h, HoloUi.argb(0.12F * (1 - since / 120F), 0xFFFFFF));
        for (Iterator<Spark> it = SPARKS.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            float t = (now - s.born) / 1000F;
            if (t * 1000 > s.life) {
                it.remove();
                continue;
            }
            float x = s.x + s.vx * t, y = s.y + s.vy * t + 180 * t * t;
            float a = 1 - t * 1000 / s.life;
            line(g, x, y, x - s.vx * 0.02F, y - (s.vy + 360 * t) * 0.02F, 1.2F, HoloUi.argb(a, t < 0.08F ? 0xFFFFFF : AMBER));
        }
    }

    private static void onHit(int w, int h, long now) {
        hitFlashAt = now;
        for (int i = 0; i < 14; i++) {
            Spark s = new Spark();
            boolean left = R.nextBoolean();
            s.x = left ? R.nextFloat() * w * 0.25F : w - R.nextFloat() * w * 0.25F;
            s.y = R.nextFloat() * h * 0.5F;
            s.vx = (left ? 1 : -1) * (60 + R.nextFloat() * 160);
            s.vy = -40 + R.nextFloat() * 80;
            s.born = now;
            s.life = 300 + R.nextInt(300);
            SPARKS.add(s);
        }
    }

    /** Наклонная стойка каркаса: тёмная полоса с подсвеченной кромкой. */
    private static void strut(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float thick) {
        line(g, x0, y0, x1, y1, thick, HoloUi.argb(0.82F, 0x05090E));
        line(g, x0, y0, x1, y1, thick * 0.55F, HoloUi.argb(0.9F, 0x0B141C));
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = -dy / len, ny = dx / len;
        float sx = nx * thick * 0.5F, sy = ny * thick * 0.5F;
        // Кромка — на стороне, обращённой к центру экрана.
        Minecraft mc = Minecraft.getInstance();
        float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        float toCx = mc.getWindow().getGuiScaledWidth() / 2F - mx, toCy = mc.getWindow().getGuiScaledHeight() / 2F - my;
        if (sx * toCx + sy * toCy < 0) { sx = -sx; sy = -sy; }
        line(g, x0 + sx, y0 + sy, x1 + sx, y1 + sy, 1.0F, HoloUi.argb(0.55F, CYAN));
    }

    /** Радар: кольца, вращающийся луч, точки существ в 40 блоках (повёрнуты по взгляду). */
    private static void radar(GuiGraphicsExtractor g, Minecraft mc, Player p, int cx, int cy, int r, long now) {
        int ring = HoloUi.argb(0.45F, CYAN);
        for (int i = 0; i < 72; i++) {
            double a = 2 * Math.PI * i / 72;
            g.fill(cx + (int) Math.round(Math.cos(a) * r), cy + (int) Math.round(Math.sin(a) * r),
                    cx + (int) Math.round(Math.cos(a) * r) + 1, cy + (int) Math.round(Math.sin(a) * r) + 1, ring);
            if (i % 2 == 0) g.fill(cx + (int) Math.round(Math.cos(a) * r / 2), cy + (int) Math.round(Math.sin(a) * r / 2),
                    cx + (int) Math.round(Math.cos(a) * r / 2) + 1, cy + (int) Math.round(Math.sin(a) * r / 2) + 1, HoloUi.argb(0.25F, CYAN));
        }
        g.fill(cx - r, cy, cx + r, cy + 1, HoloUi.argb(0.18F, CYAN));
        g.fill(cx, cy - r, cx + 1, cy + r, HoloUi.argb(0.18F, CYAN));
        float sweep = (now % 2400) / 2400F * (float) Math.PI * 2;
        for (int k = 0; k < 10; k++) {
            float a = sweep - k * 0.06F;
            line(g, cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, 1.0F, HoloUi.argb(0.4F * (1 - k / 10F), GREEN));
        }
        double yaw = Math.toRadians(p.getYRot());
        double range = 40;
        for (Entity e : mc.level.getEntities(p, new AABB(p.position(), p.position()).inflate(range), e -> e instanceof LivingEntity && e.isAlive())) {
            double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
            double fwd = dx * -Math.sin(yaw) + dz * Math.cos(yaw), right = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > range) continue;
            int x = cx + (int) Math.round(right / range * r), y = cy - (int) Math.round(fwd / range * r);
            int col = e instanceof Player ? AMBER : e instanceof Enemy ? RED : GREEN;
            g.fill(x - 1, y - 1, x + 2, y + 2, HoloUi.argb(0.9F, col));
        }
        g.fill(cx - 1, cy - 2, cx + 2, cy + 2, HoloUi.argb(0.9F, 0xFFFFFF));
        HoloUi.centeredText(g, mc.font, "РАДАР 40м", cx, cy + r + 4, CYAN, 0.6F);
    }

    /** Приборы слева: «реактор» (живая шкала) и целостность корпуса. */
    private static void gauges(GuiGraphicsExtractor g, Minecraft mc, float hp, int x, int y, long now) {
        float reactor = 0.9F + 0.06F * (float) Math.sin(now / 700.0) + 0.03F * (float) Math.sin(now / 173.0);
        HoloUi.text(g, mc.font, "РЕАКТОР", x, y, CYAN, 0.7F);
        bar(g, x, y + 10, 64, reactor, CYAN);
        HoloUi.text(g, mc.font, String.format(Locale.ROOT, "%d%%", Math.round(reactor * 100)), x + 68, y + 8, CYAN, 0.6F);
        int col = hp > 0.6F ? GREEN : hp > 0.3F ? AMBER : RED;
        HoloUi.text(g, mc.font, "КОРПУС", x, y + 22, col, 0.7F);
        bar(g, x, y + 32, 64, hp, col);
        HoloUi.text(g, mc.font, String.format(Locale.ROOT, "%d%%", Math.round(hp * 100)), x + 68, y + 30, col, 0.6F);
        // Осциллограмма — «пульс» систем.
        for (int i = 0; i < 64; i++) {
            float v = (float) (Math.sin(now / 90.0 + i * 0.45) * 0.5 + Math.sin(now / 37.0 + i * 1.3) * 0.3);
            g.fill(x + i, y + 48 + Math.round(v * 4), x + i + 1, y + 49 + Math.round(v * 4), HoloUi.argb(0.5F, col));
        }
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, float k, int col) {
        g.fill(x, y, x + w, y + 3, HoloUi.argb(0.35F, 0x102020));
        int segs = 16;
        for (int i = 0; i < segs; i++) {
            if (i >= Math.round(segs * Mth.clamp(k, 0, 1))) break;
            int sx = x + i * w / segs;
            g.fill(sx, y, sx + w / segs - 1, y + 3, HoloUi.argb(0.85F, col));
        }
    }

    /** Трещины на стекле: появляются по одной при падении здоровья. */
    private static void cracks(GuiGraphicsExtractor g, int w, int h, float hp) {
        if (cracks == null) {
            Random r = new Random(4242);
            cracks = new float[CRACK_AT.length][][];
            for (int c = 0; c < CRACK_AT.length; c++) {
                List<float[]> segs = new ArrayList<>();
                // Старт у края стекла, ветвится к центру.
                float sx = r.nextBoolean() ? 0.05F + r.nextFloat() * 0.25F : 0.7F + r.nextFloat() * 0.25F;
                float sy = 0.12F + r.nextFloat() * 0.75F;
                float dir = (float) Math.atan2(0.5F - sy, 0.5F - sx) + (r.nextFloat() - 0.5F) * 1.2F;
                grow(segs, r, sx, sy, dir, 0.07F, 7, 2);
                // «Звезда» в точке удара.
                for (int k = 0; k < 6; k++) {
                    float a = (float) (Math.PI * 2 * k / 6 + r.nextFloat() * 0.5F), len = 0.012F + r.nextFloat() * 0.02F;
                    segs.add(new float[]{sx, sy, sx + (float) Math.cos(a) * len, sy + (float) Math.sin(a) * len * 1.6F});
                }
                cracks[c] = segs.toArray(new float[0][]);
            }
        }
        for (int c = 0; c < CRACK_AT.length; c++) {
            if (hp >= CRACK_AT[c]) continue;
            for (float[] s : cracks[c]) {
                float x0 = s[0] * w, y0 = s[1] * h, x1 = s[2] * w, y1 = s[3] * h;
                line(g, x0 + 1, y0 + 1, x1 + 1, y1 + 1, 1.4F, HoloUi.argb(0.35F, 0x000000));
                line(g, x0, y0, x1, y1, 1.0F, HoloUi.argb(0.6F, 0xE8F4FF));
            }
        }
    }

    private static void grow(List<float[]> out, Random r, float x, float y, float dir, float len, int steps, int depth) {
        for (int i = 0; i < steps; i++) {
            dir += (r.nextFloat() - 0.5F) * 0.7F;
            float nx = x + (float) Math.cos(dir) * len, ny = y + (float) Math.sin(dir) * len * 1.6F;
            out.add(new float[]{x, y, nx, ny});
            if (depth > 0 && r.nextFloat() < 0.3F) grow(out, r, nx, ny, dir + (r.nextBoolean() ? 0.9F : -0.9F), len * 0.6F, 3, depth - 1);
            x = nx;
            y = ny;
            len *= 0.85F;
        }
    }

    /** Линия любой толщины: повёрнутый прямоугольник. */
    private static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float thick, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5F) return;
        g.pose().pushMatrix();
        g.pose().translate(x0, y0);
        g.pose().rotate((float) Math.atan2(dy, dx));
        int t = Math.max(1, Math.round(thick));
        g.fill(0, -t / 2, Math.round(len), -t / 2 + t, color);
        g.pose().popMatrix();
    }
}
