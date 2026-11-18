package com.smi7i.sandevistan.client;

import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 3D-голограмма ошибки «ИМПЛАНТ ОСТЫВАЕТ», если нажать V, пока имплант остывает.
 * Висит перед глазами чуть ниже прицела, показывает оставшееся время и полоску остывания, ~1.6 с.
 */
public final class SandevistanCooldownPopup {
    // ===== Настройки =====
    /** Сколько висит окошко, в тиках. */
    private static final int LIFETIME = 32;
    private static final int RED = 0xFF3A4A;
    private static final int WIDTH = 170;
    private static final int HEIGHT = 40;

    private static Holo3D.Panel panel;

    private SandevistanCooldownPopup() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Остыл — окошко сразу убираем.
            if (panel != null && !ClientSandevistanState.recharging()) {
                panel.remove();
                panel = null;
            }
        });
    }

    /** Показать окошко (повторное нажатие — показать заново). */
    public static void show() {
        if (panel != null) panel.remove();
        panel = Holo3D.add(0.0F, -0.32F, 1.3F, 0.0F, 12.0F, 0.0F, WIDTH, HEIGHT, LIFETIME,
                SandevistanCooldownPopup::draw);
    }

    private static void draw(Holo3D.Canvas c, float alpha, long timeMs) {
        c.panel(WIDTH, HEIGHT, RED, alpha);
        c.centeredText("!! ИМПЛАНТ ОСТЫВАЕТ !!", WIDTH / 2.0F, 6, RED, alpha, 1.0F);
        String time = String.format(Locale.ROOT, "ГОТОВ ЧЕРЕЗ %.1f с", ClientSandevistanState.cooldownSecondsLeft());
        c.centeredText(time, WIDTH / 2.0F, 17, 0xFFB0B0, 0.85F * alpha, 1.0F);

        // Полоска остывания.
        float bx0 = 10, bx1 = WIDTH - 10, by = HEIGHT - 9;
        c.fill(bx0, by, bx1, by + 3, HoloUi.argb(0.4F * alpha, 0x401010));
        float fill = (bx1 - bx0) * ClientSandevistanState.cooldownProgress();
        c.glow(bx0, by, bx0 + fill, by + 3, RED, 0.25F * alpha, 3);
        c.fill(bx0, by, bx0 + fill, by + 3, HoloUi.argb(alpha, RED));
    }
}
