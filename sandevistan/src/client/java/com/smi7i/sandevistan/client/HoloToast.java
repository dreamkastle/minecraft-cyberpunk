package com.smi7i.sandevistan.client;

import java.util.function.Supplier;

/** Короткое 3D-голографическое уведомление перед глазами (заголовок + строка, ~1.6 с). */
public final class HoloToast {
    private static final int WIDTH = 190;
    private static final int HEIGHT = 32;
    private static final int LIFETIME = 34;

    private static Holo3D.Panel panel;

    private HoloToast() {}

    /** subtitle — функция, чтобы строка могла меняться (например, обратный отсчёт). */
    public static void show(String title, Supplier<String> subtitle, int rgb) {
        if (panel != null) panel.remove();
        panel = Holo3D.add(0.0F, -0.32F, 1.3F, 0.0F, 12.0F, 0.0F, WIDTH, HEIGHT, LIFETIME, (c, alpha, timeMs) -> {
            c.panel(WIDTH, HEIGHT, rgb, alpha);
            c.centeredText(title, WIDTH / 2.0F, 6, rgb, alpha, 1.0F);
            c.centeredText(subtitle.get(), WIDTH / 2.0F, 18, 0xD0E8E0, 0.85F * alpha, 1.0F);
        });
    }
}
