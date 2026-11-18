package com.smi7i.sandevistan.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;

/**
 * Окошко слота импланта в инвентаре (обычном и творческом), нарисованное кодом в голографическом стиле мода.
 * Плавно «вырастает» из серого фона инвентаря: мягкий ореол к краям, внутри тёмное «стекло»,
 * тонкая светящаяся рамка, яркие уголки, бегущая полоса развёртки. Пустой слот — бледный силуэт позвоночника,
 * со вставленным имплантом — зелёное пульсирующее свечение.
 */
public final class ImplantSlotUi {
    // ===== Настройки =====
    /** Цвет рамки и ореола (бирюзовый), цвет свечения со вставленным имплантом (зелёный). */
    private static final int TEAL = 0x2AE0C0;
    private static final int GREEN = 0x39FF6A;
    /** Ширина плавного перехода от инвентаря к окошку, в пикселях. */
    private static final int HALO = 6;

    private ImplantSlotUi() {}

    /** x, y — левый верхний угол стандартной клетки слота 18×18 (предмет рисуется в x+1, y+1). Слот позвоночника. */
    public static void draw(GuiGraphicsExtractor g, int x, int y, boolean filled, long timeMs) {
        draw(g, x, y, filled, timeMs, false);
    }

    /** Тот же стиль; hand = true — пустой слот показывает силуэт руки (слоты имплантов рук). */
    public static void draw(GuiGraphicsExtractor g, int x, int y, boolean filled, long timeMs, boolean hand) {
        draw(g, x, y, filled, timeMs, hand ? 1 : 0);
    }

    /** kind: 0 — позвоночник, 1 — рука, 2 — голова (силуэт в пустом слоте). */
    public static void draw(GuiGraphicsExtractor g, int x, int y, boolean filled, long timeMs, int kind) {
        float pulse = 0.5F + 0.5F * Mth.sin(timeMs / 380.0F);
        int accent = filled ? GREEN : TEAL;
        int x1 = x + 18, y1 = y + 18;

        // 1. Плавный переход: мягкая тень и ореол, которые растворяются в фоне инвентаря.
        for (int i = HALO; i >= 1; i--) {
            float k = 1.0F - (float) (i - 1) / HALO;
            g.fill(x - i, y - i, x1 + i, y1 + i, HoloUi.argb(0.09F * k * k, 0x0A1414));
        }
        float glow = filled ? 0.10F + 0.10F * pulse : 0.06F;
        for (int i = 4; i >= 1; i--) {
            float k = 1.0F - (float) (i - 1) / 4;
            g.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x - i, y - i, x1 + i, y1 + i,
                    0xFF000000 | HoloUi.scale(accent, glow * k * k));
        }

        // 2. Тёмное «стекло» с лёгким градиентом.
        g.fillGradient(x, y, x1, y1, 0xF0142220, 0xF00A1312);
        // Внутренний блик сверху.
        g.fill(x + 1, y + 1, x1 - 1, y + 2, HoloUi.argb(0.18F, accent));

        // 3. Пустой слот — бледный силуэт позвоночника (подсказка, что сюда вставлять).
        if (!filled && kind == 2) {
            int ghost = HoloUi.argb(0.22F, TEAL);
            g.fill(x + 5, y + 2, x + 13, y + 10, ghost);            // голова
            g.fill(x + 8, y + 10, x + 10, y + 13, ghost);           // шея
            g.fill(x + 3, y + 13, x + 15, y + 16, ghost);           // плечи
        } else if (!filled && kind == 1) {
            int ghost = HoloUi.argb(0.22F, TEAL);
            g.fill(x + 7, y + 12, x + 11, y + 16, ghost);          // предплечье
            g.fill(x + 5, y + 7, x + 13, y + 12, ghost);            // ладонь
            g.fill(x + 5, y + 3, x + 6, y + 7, ghost);              // пальцы
            g.fill(x + 7, y + 2, x + 8, y + 7, ghost);
            g.fill(x + 9, y + 2, x + 10, y + 7, ghost);
            g.fill(x + 11, y + 3, x + 12, y + 7, ghost);
            g.fill(x + 3, y + 8, x + 5, y + 10, ghost);             // большой палец
        } else if (!filled) {
            int ghost = HoloUi.argb(0.22F, TEAL);
            int cx = x + 9;
            for (int v = 0; v < 5; v++) {
                int vy = y + 3 + v * 3;
                g.fill(cx - 2, vy, cx + 2, vy + 2, ghost);
                g.fill(cx - 4, vy, cx - 3, vy + 1, ghost);
                g.fill(cx + 3, vy, cx + 4, vy + 1, ghost);
            }
        }

        // 4. Бегущая полоса развёртки.
        int scanY = y + 1 + (int) ((timeMs / 45) % 16);
        g.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x + 1, scanY, x1 - 1, scanY + 1,
                0xFF000000 | HoloUi.scale(accent, filled ? 0.25F : 0.15F));

        // 5. Тонкая рамка и яркие уголки.
        int border = HoloUi.argb(filled ? 0.55F + 0.35F * pulse : 0.6F, accent);
        g.fill(x, y, x1, y + 1, border);
        g.fill(x, y1 - 1, x1, y1, border);
        g.fill(x, y, x + 1, y1, border);
        g.fill(x1 - 1, y, x1, y1, border);
        int corner = HoloUi.argb(1.0F, accent);
        int c = 4;
        g.fill(x - 1, y - 1, x + c, y, corner);
        g.fill(x - 1, y - 1, x, y + c, corner);
        g.fill(x1 - c, y1, x1 + 1, y1 + 1, corner);
        g.fill(x1, y1 - c, x1 + 1, y1 + 1, corner);
        g.fill(x1 - c, y - 1, x1 + 1, y, corner);
        g.fill(x1, y - 1, x1 + 1, y + c, corner);
        g.fill(x - 1, y1, x + c, y1 + 1, corner);
        g.fill(x - 1, y1 - c, x, y1 + 1, corner);
    }
}
