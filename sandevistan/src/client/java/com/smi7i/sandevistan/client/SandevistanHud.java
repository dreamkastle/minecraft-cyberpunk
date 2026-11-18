package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Зелёный мир, как цветокоррекция в Роблоксе: каждый канал экрана умножается на свой множитель (до 2×).
 *
 * Обычно фильтр накладывает {@link SandevistanWorldPass} сразу после мира, а игрок и послеобразы
 * рисуются поверх него в своих цветах. Здесь — запасной вариант для включённого шейдерпака Iris:
 * фильтр поверх всего экрана (под интерфейсом), тогда игрок тоже зеленеет.
 */
public class SandevistanHud {
    /** Множители каналов на полной силе: красный, зелёный, синий. 1.0 = без изменений, 0..2. */
    public static final float TINT_R = 0.50F;
    public static final float TINT_G = 1.15F;
    public static final float TINT_B = 0.58F;
    /** Множители каналов красного экрана при перегреве (на полной силе). */
    public static final float RED_R = 1.35F;
    public static final float RED_G = 0.60F;
    public static final float RED_B = 0.55F;
    /** Лёгкое зелёное свечение поверх, чтобы тени отдавали зелёным, а не были просто чёрными. 0x000000 = выключить. */
    public static final int GLOW_RGB = 0x061A0A;

    /** Умножение с запасом до 2×: итог = src*dst + dst*src = экран * (2 * цвет). Серый 0x808080 ничего не меняет. */
    public static final BlendFunction MULTIPLY_2X =
            new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE);

    private static final RenderPipeline GUI_MULTIPLY_2X = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("sandevistan", "pipeline/gui_multiply_2x"))
                    .withColorTargetState(new ColorTargetState(MULTIPLY_2X))
                    .build());

    public static void init() {
        HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath("sandevistan", "green_tint"),
                SandevistanHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!IrisCompat.shadersActive()) return; // без шейдерпака фильтр накладывает SandevistanWorldPass

        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
        if (!ClientSandevistanState.anyFilter(partial)) return;
        float t = ClientSandevistanState.tintForFrame(partial);
        float red = ClientSandevistanState.overheatForFrame(partial);

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        graphics.fill(GUI_MULTIPLY_2X, 0, 0, w, h, multiplyColor(t, red));

        int glow = glowColor(t);
        if ((glow & 0xFFFFFF) != 0) {
            graphics.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, 0, 0, w, h, glow); // сложение цветов
        }
    }

    /** Цвет заливки для умножения 2×: зелёный с силой t и красный (перегрев) с силой red, оба 0..1. */
    public static int multiplyColor(float t, float red) {
        int r = half((1.0F + (TINT_R - 1.0F) * t) * (1.0F + (RED_R - 1.0F) * red));
        int g = half((1.0F + (TINT_G - 1.0F) * t) * (1.0F + (RED_G - 1.0F) * red));
        int b = half((1.0F + (TINT_B - 1.0F) * t) * (1.0F + (RED_B - 1.0F) * red));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Цвет свечения (для сложения) при силе t. */
    public static int glowColor(float t) {
        int r = Math.round(((GLOW_RGB >> 16) & 0xFF) * t);
        int g = Math.round(((GLOW_RGB >> 8) & 0xFF) * t);
        int b = Math.round((GLOW_RGB & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Множитель 0..2 -> байт цвета заливки 0..255. */
    private static int half(float multiplier) {
        return Math.max(0, Math.min(255, Math.round(multiplier * 0.5F * 255.0F)));
    }
}
