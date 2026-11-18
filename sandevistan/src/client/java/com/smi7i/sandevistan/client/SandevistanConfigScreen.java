package com.smi7i.sandevistan.client;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Экран настроек мода (из ModMenu или по своей клавише). Голографический фон в стиле мода. */
public class SandevistanConfigScreen extends Screen {
    private static final int TEAL = 0x2AE0C0;
    private final Screen parent;

    public SandevistanConfigScreen(Screen parent) {
        super(Component.literal("Настройки Сандевистана"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SandevistanConfig c = SandevistanConfig.get();
        int w = 220, h = 20, x = width / 2 - w / 2;
        int y = 46, step = 24;

        addRenderableWidget(slider(x, y, w, h, c.filterStrength / 1.5, v -> c.filterStrength = (float) (v * 1.5),
                v -> String.format(Locale.ROOT, "Сила зелёного фильтра: %d%%", Math.round(v * 150))));
        y += step;
        addRenderableWidget(slider(x, y, w, h, c.soundVolume / 1.5, v -> c.soundVolume = (float) (v * 1.5),
                v -> String.format(Locale.ROOT, "Громкость звуков мода: %d%%", Math.round(v * 150))));
        y += step;
        addRenderableWidget(slider(x, y, w, h, (c.afterimageSeconds - 0.5) / 4.5, v -> c.afterimageSeconds = (float) (0.5 + v * 4.5),
                v -> String.format(Locale.ROOT, "Длина шлейфа: %.1f с", 0.5 + v * 4.5)));
        y += step;
        addRenderableWidget(slider(x, y, w, h, (c.afterimageCount - 10) / 110.0, v -> c.afterimageCount = (int) Math.round(10 + v * 110),
                v -> "Послеобразов максимум: " + Math.round(10 + v * 110)));
        y += step;
        addRenderableWidget(slider(x, y, w, h, (c.glowStrength - 0.25) / 1.75, v -> c.glowStrength = (float) (0.25 + v * 1.75),
                v -> String.format(Locale.ROOT, "Яркость импланта: %d%%", Math.round((0.25 + v * 1.75) * 100))));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.soulMode).create(x, y, w, h,
                Component.literal("«Душа» при киберпсихозе"), (b, v) -> c.soulMode = v));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.hitEffects).create(x, y, w, h,
                Component.literal("Искры от ударов"), (b, v) -> c.hitEffects = v));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.slowWorldSounds).create(x, y, w, h,
                Component.literal("Низкий звук мира в слоумо"), (b, v) -> c.slowWorldSounds = v));
        y += step + 8;
        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose()).bounds(x, y, w, h).build());
    }

    private static AbstractSliderButton slider(int x, int y, int w, int h, double value, DoubleConsumer apply,
                                               DoubleFunction<String> label) {
        double start = Math.max(0, Math.min(1, value));
        return new AbstractSliderButton(x, y, w, h, Component.literal(label.apply(start)), start) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label.apply(this.value)));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
            }
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0xD0050A0A);
        int grid = HoloUi.argb(0.05F, TEAL);
        for (int x = 0; x < width; x += 16) g.fill(x, 0, x + 1, height, grid);
        for (int y = 0; y < height; y += 16) g.fill(0, y, width, y + 1, grid);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        HoloUi.centeredText(g, font, "НАСТРОЙКИ САНДЕВИСТАНА", width / 2, 18, TEAL, HoloUi.flicker(System.currentTimeMillis(), 5));
    }

    @Override
    public void onClose() {
        SandevistanConfig.save();
        minecraft.gui.setScreen(parent);
    }
}
