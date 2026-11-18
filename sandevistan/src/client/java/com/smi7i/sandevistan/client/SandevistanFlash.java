package com.smi7i.sandevistan.client;

import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Вспышка в момент включения Сандевистана: экран ярко вспыхивает бело-голубым,
 * за полсекунды цвет уходит через бирюзовый в зелёный и гаснет; яркие засветы по краям и пара полос-помех.
 * Свет складывается с картинкой (как у ламп), поэтому мир «засвечивается», а не закрашивается.
 */
public final class SandevistanFlash {
    // ===== Настройки =====
    /** Сколько длится вспышка, в тиках (20 = 1 секунда). */
    private static final int DURATION = 16;
    /** Цвета: начало (бело-голубой), середина (бирюзовый), конец (зелёный). */
    private static final int COLOR_START = 0xE0FFFF;
    private static final int COLOR_MID = 0x30F0FF;
    private static final int COLOR_END = 0x20FF60;
    /** Яркость засвета на всём экране и по краям на пике (0..1). */
    private static final float SCREEN_PEAK = 0.85F;
    private static final float EDGE_PEAK = 1.0F;

    /** Своя картинка-рентген поверх вспышки (необязательна): положить PNG по этому пути. */
    private static final Identifier XRAY = Identifier.fromNamespaceAndPath("sandevistan", "textures/gui/activation_xray.png");
    /** Насколько непрозрачна картинка-рентген на пике вспышки (0..1). */
    private static final float XRAY_PEAK = 0.95F;
    /** Какую часть вспышки позвоночник держится полностью (дальше тает). */
    private static final float XRAY_HOLD = 0.35F;
    /** Насколько картинка приближается за время вспышки (0.08 = на 8%). */
    private static final float XRAY_ZOOM = 0.08F;
    private static int xrayW, xrayH; // 0 — картинки нет

    private static int ticksLeft;
    private static boolean prevActive;
    private static long seed;

    private SandevistanFlash() {}

    public static void init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR,
                Identifier.fromNamespaceAndPath("sandevistan", "activation_flash"), SandevistanFlash::render);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            boolean active = ClientSandevistanState.active;
            if (active && !prevActive) {
                ticksLeft = DURATION; // включился — вспышка
                seed = System.nanoTime();
                checkXray(mc);
            } else if (ticksLeft > 0) {
                ticksLeft--;
            }
            prevActive = active;
        });
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (ticksLeft <= 0) return;
        float age = (DURATION - ticksLeft) + deltaTracker.getGameTimeDeltaPartialTick(true);
        float t = Mth.clamp(age / DURATION, 0.0F, 1.0F);
        // Мгновенный пик и быстрое затухание.
        float intensity = (float) Math.pow(1.0F - t, 2.2);

        int rgb = t < 0.35F ? lerpRgb(COLOR_START, COLOR_MID, t / 0.35F)
                : lerpRgb(COLOR_MID, COLOR_END, (t - 0.35F) / 0.65F);

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        var additive = RenderPipelines.GUI_TEXT_HIGHLIGHT; // сложение света

        // Рентген-позвоночник (картинка пользователя): на весь экран, держится XRAY_HOLD, потом тает,
        // слегка «наезжая» на камеру. Рисуется первым — вспышка засвечивает его сверху.
        if (xrayW > 0 && t < 1.0F) {
            float fade = t < XRAY_HOLD ? 1.0F : 1.0F - (t - XRAY_HOLD) / (1.0F - XRAY_HOLD);
            fade = fade * fade * (3.0F - 2.0F * fade);
            int alpha = Math.round(255 * Mth.clamp(fade * XRAY_PEAK, 0.0F, 1.0F));
            if (alpha > 2) {
                float zoom = 1.0F + XRAY_ZOOM * t;
                float cover = Math.max(w / (float) xrayW, h / (float) xrayH) * zoom; // заполнить экран целиком
                int dw = Math.round(xrayW * cover), dh = Math.round(xrayH * cover);
                graphics.blit(RenderPipelines.GUI_TEXTURED, XRAY, (w - dw) / 2, (h - dh) / 2, 0, 0, dw, dh,
                        xrayW, xrayH, xrayW, xrayH, (alpha << 24) | 0xFFFFFF);
            }
        }

        // Засвет всего экрана.
        graphics.fill(additive, 0, 0, w, h, 0xFF000000 | scale(rgb, intensity * SCREEN_PEAK));

        // Яркие края: градиенты от краёв к центру.
        int edge = 0xFF000000 | scale(rgb, intensity * EDGE_PEAK);
        int band = Math.max(8, w / 6);
        edgeGlow(graphics, 0, 0, w, band, edge, true, false);          // верх
        edgeGlow(graphics, 0, h - band, w, h, edge, false, false);     // низ
        edgeGlow(graphics, 0, 0, band, h, edge, true, true);           // лево
        edgeGlow(graphics, w - band, 0, w, h, edge, false, true);      // право

        // Несколько полос-помех в первой половине вспышки.
        if (t < 0.5F) {
            Random random = new Random(seed + (long) (age * 2));
            for (int i = 0; i < 4; i++) {
                int y = random.nextInt(Math.max(1, h));
                int height = 1 + random.nextInt(4);
                graphics.fill(additive, 0, y, w, Math.min(h, y + height), 0xFF000000 | scale(COLOR_START, intensity));
            }
        }
    }

    /**
     * Засвет у края: полосы от яркой (у края) к тёмной (к центру), со сложением света.
     * brightFirst — яркая сторона в начале (верх/лево); vertical — полосы вертикальные (для левого/правого края).
     */
    private static void edgeGlow(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int edgeColor,
                                 boolean brightFirst, boolean vertical) {
        int size = vertical ? x1 - x0 : y1 - y0;
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            float k = 1.0F - (float) i / steps;
            int a = brightFirst ? size * i / steps : size - size * (i + 1) / steps;
            int b = brightFirst ? size * (i + 1) / steps : size - size * i / steps;
            int color = 0xFF000000 | scale(edgeColor & 0xFFFFFF, k * k / 1.5F);
            if (vertical) {
                graphics.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x0 + a, y0, x0 + b, y1, color);
            } else {
                graphics.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, x0, y0 + a, x1, y0 + b, color);
            }
        }
    }

    /** Есть ли картинка-рентген и какого она размера (проверяем при каждом включении — вдруг ресурс-пак сменили). */
    private static void checkXray(Minecraft mc) {
        xrayW = xrayH = 0;
        var resource = mc.getResourceManager().getResource(XRAY);
        if (resource.isEmpty()) return;
        try (var in = resource.get().open(); var image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
            xrayW = image.getWidth();
            xrayH = image.getHeight();
        } catch (Exception ignored) {
            xrayW = xrayH = 0;
        }
    }

    private static int lerpRgb(int a, int b, float k) {
        k = Mth.clamp(k, 0.0F, 1.0F);
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int rgb, float k) {
        k = Mth.clamp(k, 0.0F, 1.0F);
        int r = Math.round(((rgb >> 16) & 0xFF) * k);
        int g = Math.round(((rgb >> 8) & 0xFF) * k);
        int b = Math.round((rgb & 0xFF) * k);
        return (r << 16) | (g << 8) | b;
    }
}
