package com.smi7i.sandevistan.client.mech;

import com.smi7i.sandevistan.client.HoloUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Финал «Последнего боя» вместо обычного экрана смерти: чёрный экран, медленно проявляются надписи,
 * потом — голографическая кнопка «ПЕРЕРОДИТЬСЯ» (светится, пульсирует, при наведении ярче).
 */
public class ShowdownDeathScreen extends Screen {
    private static final int RED = 0xFF3048, GREEN = 0x39FF6A;
    private final long opened = System.currentTimeMillis();
    private int bx, by, bw = 180, bh = 26;
    /** Запрос на возрождение уже отправлен — второй раз не шлём. */
    private boolean respawning;

    public ShowdownDeathScreen() {
        super(Component.literal("Последний бой"));
    }

    @Override
    protected void init() {
        bx = width / 2 - bw / 2;
        by = height / 2 + 34;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Кнопка появляется не сразу — сначала надписи. */
    private float buttonAlpha() {
        return Mth.clamp((System.currentTimeMillis() - opened - 2600) / 900.0F, 0, 1);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0xFF000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        long t = System.currentTimeMillis() - opened;
        // Полосы кино и тонкая красная линия горизонта.
        int bar = Math.round(height * 0.09F);
        g.fill(0, 0, width, bar, 0xFF000000);
        g.fill(0, height - bar, width, height, 0xFF000000);
        float title = Mth.clamp((t - 300) / 1200.0F, 0, 1);
        float sub = Mth.clamp((t - 1500) / 1000.0F, 0, 1);
        float flick = HoloUi.flicker(System.currentTimeMillis(), 61);
        int cx = width / 2, cy = height / 2;
        if (title > 0) {
            g.fill(cx - 140, cy - 20, cx + 140, cy - 19, HoloUi.argb(0.5F * title, RED));
            g.pose().pushMatrix();
            g.pose().translate(cx, cy - 34);
            g.pose().scale(2.0F, 2.0F);
            HoloUi.centeredText(g, font, MechShowdownClient.lastDeathShowdown ? "ПОСЛЕДНИЙ БОЙ ОКОНЧЕН" : "КОСТЮМ УНИЧТОЖЕН", 0, 0, RED, title * flick);
            g.pose().popMatrix();
        }
        if (sub > 0) {
            HoloUi.centeredText(g, font, "пилот не пережил синхронизацию", cx, cy - 8, 0xB0B8C0, 0.8F * sub);
            HoloUi.centeredText(g, font, MechShowdownClient.lastDeathShowdown ? "но он дошёл до конца" : "связь с пилотом потеряна", cx, cy + 4, 0x7A8590, 0.7F * sub);
        }
        float ba = buttonAlpha();
        if (ba > 0) {
            boolean hover = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + bh;
            float pulse = 0.75F + 0.25F * (float) Math.abs(Math.sin(t / 420.0));
            float k = ba * (hover ? 1.0F : pulse);
            HoloUi.glow(g, bx, by, bx + bw, by + bh, GREEN, (hover ? 0.55F : 0.3F) * k, 6);
            HoloUi.panel(g, bx, by, bx + bw, by + bh, GREEN, k);
            if (hover) g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, HoloUi.argb(0.18F * ba, GREEN));
            HoloUi.centeredText(g, font, "▶  ПЕРЕРОДИТЬСЯ  ◀", cx, by + bh / 2 - 4, hover ? 0xFFFFFF : GREEN, ba);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && buttonAlpha() > 0.5F && event.x() >= bx && event.x() <= bx + bw
                && event.y() >= by && event.y() <= by + bh) {
            // Как у ванильного экрана: только запрос на возрождение; экран закроется, когда игрок появится.
            if (!respawning && minecraft.player != null) minecraft.player.respawn();
            respawning = true;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
