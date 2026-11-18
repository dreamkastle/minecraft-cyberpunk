package com.smi7i.sandevistan.client;

import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;

/**
 * При перегреве интерфейс игрока «разъезжается»: каждый элемент HUD (хотбар, сердечки, голод и т.д.)
 * рывками сдвигается и иногда перекашивается, плюс лёгкое плавное покачивание.
 * Обёртка через Fabric HudElementRegistry.replaceElement — сама отрисовка элементов не меняется.
 */
public class SandevistanHudDistortion {
    /** Максимальный рывок в сторону, в пикселях интерфейса. */
    private static final float MAX_SHIFT = 9.0F;
    /** Максимальный перекос (0.3 = заметный наклон). */
    private static final float MAX_SHEAR = 0.3F;
    /** Плавное покачивание интерфейса, в пикселях. */
    private static final float SWAY = 3.0F;
    /** Как часто меняются рывки, в миллисекундах. */
    private static final long FRAME_MS = 70;

    private static final Identifier[] DISTORTED = {
            VanillaHudElements.CROSSHAIR,
            VanillaHudElements.HOTBAR,
            VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH,
            VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.HELD_ITEM_TOOLTIP,
            VanillaHudElements.MOB_EFFECTS,
            VanillaHudElements.OVERLAY_MESSAGE,
    };

    public static void init() {
        for (Identifier id : DISTORTED) {
            int salt = id.hashCode();
            HudElementRegistry.replaceElement(id, original -> (graphics, deltaTracker) -> {
                float strength = SandevistanGlitchHud.strength(deltaTracker.getGameTimeDeltaPartialTick(true));
                if (strength <= 0.02F) {
                    original.extractRenderState(graphics, deltaTracker);
                    return;
                }
                graphics.pose().pushMatrix();
                distort(graphics, strength, salt);
                try {
                    original.extractRenderState(graphics, deltaTracker);
                } finally {
                    graphics.pose().popMatrix();
                }
            });
        }
    }

    /** Сдвигает/перекашивает всё, что рисуется дальше (до popMatrix). salt — чтобы элементы дёргались по-разному. */
    static void distort(GuiGraphicsExtractor graphics, float strength, int salt) {
        Minecraft mc = Minecraft.getInstance();
        float w = mc.getWindow().getGuiScaledWidth();
        float h = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();
        Random random = new Random((now / FRAME_MS) * 1_000_003L + salt);

        // Плавное покачивание.
        float time = (now % 1_000_000L) / 1000.0F;
        float dx = Mth.sin(time * 2.3F + salt) * SWAY * strength;
        float dy = Mth.sin(time * 1.7F + salt * 0.5F) * SWAY * 0.5F * strength;

        // Рывки: не каждый «кадр», чтобы было рвано.
        if (random.nextFloat() < 0.25F + 0.6F * strength) {
            dx += (random.nextFloat() * 2.0F - 1.0F) * MAX_SHIFT * strength;
            dy += (random.nextFloat() * 2.0F - 1.0F) * MAX_SHIFT * 0.4F * strength;
        }
        graphics.pose().translate(dx, dy);

        // Иногда перекос вокруг нижней середины экрана (там хотбар).
        if (random.nextFloat() < 0.35F * strength) {
            float shear = (random.nextFloat() * 2.0F - 1.0F) * MAX_SHEAR * strength;
            graphics.pose().translate(w / 2.0F, h);
            graphics.pose().mul(new Matrix3x2f(1.0F, 0.0F, shear, 1.0F, 0.0F, 0.0F));
            graphics.pose().translate(-w / 2.0F, -h);
        }
    }
}
