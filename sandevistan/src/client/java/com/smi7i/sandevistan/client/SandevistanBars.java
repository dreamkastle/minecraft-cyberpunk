package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.SandevistanManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Две шкалы в левом нижнем углу:
 *  - SANDEVISTAN — зелёный заряд: убывает за 10 секунд работы, восстанавливается при остывании;
 *  - КИБЕРПСИХОЗ — красная, из трёх делений (перегревы подряд); во время приступа горит и убывает.
 * При перегреве дёргаются вместе с остальным HUD.
 */
public class SandevistanBars {
    // ===== Настройки =====
    private static final int MARGIN_X = 8;
    private static final int MARGIN_BOTTOM = 10;
    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 5;
    private static final int GREEN = 0x39FF6A;
    private static final int GREEN_DARK = 0x158A35;
    private static final int RED = 0xFF3040;
    private static final int RED_DARK = 0x8A1020;
    /** Цвет голографической панели под шкалами. */
    private static final int PANEL_COLOR = 0x2AE0C0;

    public static void init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "bars"), SandevistanBars::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (com.smi7i.sandevistan.client.mech.MechShowdownClient.hideHud()) return; // «Последний бой» — интерфейс скрыт
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean equipped = ImplantSlot.isImplant(mc.player.getAttachedOrCreate(ModAttachments.IMPLANT));
        boolean psychosis = ClientSandevistanState.psychosis();
        if (!equipped && !psychosis && !ClientSandevistanState.active) return;

        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
        float glitch = SandevistanGlitchHud.strength(partial);
        graphics.pose().pushMatrix();
        if (glitch > 0.02F) {
            SandevistanHudDistortion.distort(graphics, glitch, 0x5A4D);
        }
        try {
            Font font = mc.font;
            int h = mc.getWindow().getGuiScaledHeight();
            long time = System.currentTimeMillis();
            // Строка про очистку ячеек киберпсихоза — только если есть что очищать.
            String defrag = defragLine();
            int extra = defrag != null ? 11 : 0;
            int psyY = h - MARGIN_BOTTOM - BAR_HEIGHT - extra;
            int sandY = psyY - BAR_HEIGHT - 16;
            float alpha = HoloUi.flicker(time, 3);
            // Ширина панели — по самой длинной надписи, чтобы текст не вылезал за рамку.
            int contentW = Math.max(BAR_WIDTH, Math.max(font.width("SANDEVISTAN // COOLING"), font.width("!! КИБЕРПСИХОЗ !!")));
            if (defrag != null) contentW = Math.max(contentW, font.width(defrag));

            // Голограмма: общая панель, слегка повёрнутая (правый край выше).
            HoloUi.begin(graphics, MARGIN_X, psyY, HoloUi.TILT);
            try {
                HoloUi.panel(graphics, MARGIN_X - 6, sandY - 15, MARGIN_X + contentW + 6, psyY + BAR_HEIGHT + 6 + extra,
                        PANEL_COLOR, alpha * 0.8F);
                drawSandevistan(graphics, font, MARGIN_X, sandY, partial, time, alpha);
                drawPsychosis(graphics, font, MARGIN_X, psyY, time, alpha);
                if (defrag != null) {
                    boolean ready = ClientSandevistanState.defragReady();
                    float blink = ready && (time / 400) % 2 == 0 ? 1.0F : 0.75F;
                    HoloUi.text(graphics, font, defrag, MARGIN_X, psyY + BAR_HEIGHT + 3, ready ? 0x39FF6A : 0x9AB8B0, alpha * blink);
                }
            } finally {
                graphics.pose().popMatrix();
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** «ОЧИСТКА ЧЕРЕЗ 2:31» / «[N] ОЧИСТИТЬ ПАМЯТЬ», или null, если ячейки пустые или идёт приступ. */
    private static String defragLine() {
        if (ClientSandevistanState.streak <= 0 || ClientSandevistanState.psychosis()) return null;
        if (ClientSandevistanState.defragReady()) {
            return "[" + com.smi7i.sandevistan.client.HoloUi.key(SandevistanInputClient.DEFRAG) + "] ОЧИСТИТЬ ПАМЯТЬ";
        }
        int s = (ClientSandevistanState.defragTicks + 19) / 20;
        return String.format("ОЧИСТКА ЧЕРЕЗ %d:%02d", s / 60, s % 60);
    }

    private static void drawSandevistan(GuiGraphicsExtractor g, Font font, int x, int y, float partial, long time, float alpha) {
        float energy = Mth.clamp(ClientSandevistanState.energyForFrame(partial), 0.0F, 1.0F);
        boolean active = ClientSandevistanState.active;
        boolean charging = ClientSandevistanState.holdTicks > 0;
        boolean recharging = ClientSandevistanState.recharging();

        String label = active ? "SANDEVISTAN " + (int) Math.ceil(energy * 10) + "s"
                : charging ? "SANDEVISTAN ..." : recharging ? "SANDEVISTAN // COOLING" : "SANDEVISTAN // READY";
        HoloUi.text(g, font, label, x, y - 10, recharging ? GREEN_DARK : GREEN, alpha);

        frame(g, x, y, recharging ? GREEN_DARK : GREEN, alpha);
        int fillW = Math.round((BAR_WIDTH - 2) * energy);
        if (fillW > 0) {
            float brightness = 1.0F;
            if (charging) brightness = 0.6F + 0.4F * ClientSandevistanState.chargeProgress();
            if (active && energy < 0.25F) brightness = 0.55F + 0.45F * (0.5F + 0.5F * Mth.sin(time / 70.0F)); // мигает на исходе
            int top = scale(recharging ? GREEN_DARK : GREEN, brightness);
            int bottom = scale(GREEN_DARK, brightness);
            HoloUi.glow(g, x + 1, y + 1, x + 1 + fillW, y + BAR_HEIGHT - 1, top, HoloUi.GLOW * alpha, 3);
            g.fillGradient(x + 1, y + 1, x + 1 + fillW, y + BAR_HEIGHT - 1,
                    HoloUi.argb(0.85F * alpha, top), HoloUi.argb(0.85F * alpha, bottom));
            g.fill(x + 1, y + 1, x + 1 + fillW, y + 2, 0x50FFFFFF); // блик сверху
        }
    }

    private static void drawPsychosis(GuiGraphicsExtractor g, Font font, int x, int y, long time, float alpha) {
        boolean psychosis = ClientSandevistanState.psychosis();
        int streak = Math.min(ClientSandevistanState.streak, SandevistanManager.PSYCHOSIS_STREAK);

        String label = psychosis ? "!! КИБЕРПСИХОЗ !!" : "КИБЕРПСИХОЗ " + streak + "/" + SandevistanManager.PSYCHOSIS_STREAK;
        int labelColor = psychosis && (time / 150) % 2 == 0 ? 0xFFFFFF : RED;
        HoloUi.text(g, font, label, x, y - 10, labelColor, alpha);

        frame(g, x, y, RED, alpha);
        int inner = BAR_WIDTH - 2;
        if (psychosis) {
            // Во время приступа — сплошная полоса, пульсирует и убывает до конца приступа.
            float left = ClientSandevistanState.psychosisTicks / (float) SandevistanManager.PSYCHOSIS_TICKS;
            float pulse = 0.65F + 0.35F * (0.5F + 0.5F * Mth.sin(time / 90.0F));
            int fillW = Math.round(inner * Mth.clamp(left, 0.0F, 1.0F));
            HoloUi.glow(g, x + 1, y + 1, x + 1 + fillW, y + BAR_HEIGHT - 1, RED, HoloUi.GLOW * alpha * pulse, 3);
            g.fillGradient(x + 1, y + 1, x + 1 + fillW, y + BAR_HEIGHT - 1,
                    HoloUi.argb(0.85F * alpha, scale(RED, pulse)), HoloUi.argb(0.85F * alpha, scale(RED_DARK, pulse)));
        } else {
            // Три деления: каждое — один перегрев подряд.
            int segments = SandevistanManager.PSYCHOSIS_STREAK;
            int gap = 2;
            int segW = (inner - gap * (segments - 1)) / segments;
            for (int i = 0; i < segments; i++) {
                int sx = x + 1 + i * (segW + gap);
                int color = i < streak ? RED : 0x301010;
                int dark = i < streak ? RED_DARK : 0x200808;
                if (i < streak) HoloUi.glow(g, sx, y + 1, sx + segW, y + BAR_HEIGHT - 1, RED, HoloUi.GLOW * alpha, 3);
                g.fillGradient(sx, y + 1, sx + segW, y + BAR_HEIGHT - 1, HoloUi.argb(0.85F * alpha, color), HoloUi.argb(0.85F * alpha, dark));
            }
        }
    }

    /** Полупрозрачный фон и тонкая светящаяся рамка шкалы. */
    private static void frame(GuiGraphicsExtractor g, int x, int y, int rgb, float alpha) {
        g.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, HoloUi.argb(0.45F * alpha, 0x000000));
        int border = HoloUi.argb(0.6F * alpha, rgb);
        g.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y, border);
        g.fill(x - 1, y + BAR_HEIGHT, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, border);
        g.fill(x - 1, y, x, y + BAR_HEIGHT, border);
        g.fill(x + BAR_WIDTH, y, x + BAR_WIDTH + 1, y + BAR_HEIGHT, border);
    }

    private static int scale(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * k));
        int gr = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((rgb & 0xFF) * k));
        return (r << 16) | (gr << 8) | b;
    }
}
