package com.smi7i.sandevistan.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;

/**
 * Общий «голографический» стиль интерфейса мода: панель слегка повёрнута (будто висит в воздухе под углом),
 * полупрозрачный фон, тонкая рамка с уголками, еле заметные полосы развёртки и лёгкое свечение (сложение света).
 */
public final class HoloUi {
    /**
     * Название клавиши всегда латиницей (игра берёт буквы из раскладки — на русской вышло бы «К» вместо R).
     * Буквы и цифры — по коду клавиши, кнопки мыши — LMB/RMB/MMB, остальное — как в игре.
     */
    public static String key(net.minecraft.client.KeyMapping mapping) {
        com.mojang.blaze3d.platform.InputConstants.Key k =
                net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(mapping);
        int v = k.getValue();
        switch (k.getType()) {
            case KEYSYM -> {
                if ((v >= 65 && v <= 90) || (v >= 48 && v <= 57)) return String.valueOf((char) v);
                if (v == -1) return "—";
            }
            case MOUSE -> {
                return switch (v) {
                    case 0 -> "LMB";
                    case 1 -> "RMB";
                    case 2 -> "MMB";
                    default -> "MOUSE" + (v + 1);
                };
            }
            default -> { }
        }
        return mapping.getTranslatedKeyMessage().getString().toUpperCase(java.util.Locale.ROOT);
    }

    // ===== Настройки стиля =====
    /** Наклон голограммы: правый край выше левого (как панель, повёрнутая к игроку). */
    public static final float TILT = -0.06F;
    /** Сила свечения вокруг элементов (0..1) — «только чуть-чуть». */
    public static final float GLOW = 0.22F;
    /** Яркость полос развёртки (0..1). */
    public static final float SCANLINES = 0.05F;

    private HoloUi() {}

    /** Начинает голограмму: поворот вокруг точки (px, py). Закончить — graphics.pose().popMatrix(). */
    public static void begin(GuiGraphicsExtractor g, float px, float py, float tilt) {
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().mul(new Matrix3x2f(1.0F, tilt, 0.0F, 1.0F, 0.0F, 0.0F));
        g.pose().translate(-px, -py);
    }

    /** Лёгкое мерцание голограммы: почти всегда 1, изредка короткий провал яркости. */
    public static float flicker(long timeMs, int salt) {
        long slice = timeMs / 60 + salt * 31L;
        long h = (slice * 2654435761L) >>> 8;
        return (h & 0x3F) == 0 ? 0.55F : 0.92F + 0.08F * Mth.sin(timeMs / 180.0F + salt);
    }

    /** Панель: полупрозрачный тёмный фон с оттенком цвета, рамка, уголки, развёртка и свечение по контуру. */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int rgb, float alpha) {
        glow(g, x0, y0, x1, y1, rgb, GLOW * 0.6F * alpha, 4);
        g.fill(x0, y0, x1, y1, argb(0.55F * alpha, darker(rgb)));
        scanlines(g, x0, y0, x1, y1, rgb, alpha);
        int border = argb(0.55F * alpha, rgb);
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
        int corner = argb(alpha, rgb);
        int c = 5;
        g.fill(x0 - 1, y0 - 1, x0 + c, y0 + 1, corner);
        g.fill(x0 - 1, y0 - 1, x0 + 1, y0 + c, corner);
        g.fill(x1 - c, y1 - 1, x1 + 1, y1 + 1, corner);
        g.fill(x1 - 1, y1 - c, x1 + 1, y1 + 1, corner);
    }

    /** Мягкое свечение вокруг прямоугольника: несколько расширяющихся слоёв со сложением света. */
    public static void glow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int rgb, float strength, int radius) {
        if (strength <= 0.005F) return;
        for (int i = 1; i <= radius; i++) {
            float k = strength * (1.0F - (float) (i - 1) / radius) / radius;
            g.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x0 - i, y0 - i, x1 + i, y1 + i, 0xFF000000 | scale(rgb, k));
        }
    }

    /** Еле заметные горизонтальные полосы развёртки. */
    public static void scanlines(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int rgb, float alpha) {
        int line = 0xFF000000 | scale(rgb, SCANLINES * alpha);
        for (int y = y0 + 1; y < y1 - 1; y += 2) {
            g.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x0 + 1, y, x1 - 1, y + 1, line);
        }
    }

    /** Текст со слабым свечением (цветная «дымка» вокруг букв). */
    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int rgb, float alpha) {
        int haze = argb(0.18F * alpha, rgb);
        g.text(font, text, x - 1, y, haze, false);
        g.text(font, text, x + 1, y, haze, false);
        g.text(font, text, x, y - 1, haze, false);
        g.text(font, text, x, y + 1, haze, false);
        g.text(font, text, x, y, argb(alpha, brighter(rgb)), false);
    }

    /** То же, по центру. */
    public static void centeredText(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int rgb, float alpha) {
        text(g, font, text, cx - font.width(text) / 2, y, rgb, alpha);
    }

    public static int argb(float alpha, int rgb) {
        return (Mth.clamp(Math.round(alpha * 255.0F), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static int scale(int rgb, float k) {
        k = Mth.clamp(k, 0.0F, 1.0F);
        return (Math.round(((rgb >> 16) & 0xFF) * k) << 16) | (Math.round(((rgb >> 8) & 0xFF) * k) << 8) | Math.round((rgb & 0xFF) * k);
    }

    private static int darker(int rgb) {
        return (rgb >> 3) & 0x1F1F1F;
    }

    private static int brighter(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((rgb >> 8) & 0xFF) + 40);
        int b = Math.min(255, (rgb & 0xFF) + 40);
        return (r << 16) | (g << 8) | b;
    }
}
