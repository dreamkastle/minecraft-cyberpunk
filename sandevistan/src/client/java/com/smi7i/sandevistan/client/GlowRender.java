package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Светящиеся эффекты мода (кольца, молнии, свечение, волны) — сложение света.
 * Без шейдеров — ванильный тип «молния» (просто цвет). С шейдерпаком Iris тип «молния» рисуется шейдером как
 * настоящая молния — БЕЛЫМ (Photon и др.), поэтому тогда берём тип «светящиеся глаза» (eyes) с белой текстурой:
 * он тоже складывается со светом, но цвет вершины сохраняется.
 * Вершины всегда пишутся полностью (цвет, текстура, наложение, свет, нормаль) — лишнее игра для «молнии» пропускает.
 */
public final class GlowRender {
    private static final int FULL_BRIGHT = 15728880;
    private static RenderType eyes;
    /** С шейдерпаком рисовать свечение через «глаза» (цвет сохраняется) вместо «молнии» (белая у Photon). */
    public static final boolean USE_EYES_WITH_SHADERS = false;

    private GlowRender() {}

    public static RenderType type() {
        // Пользователь: «верни как было» — всегда «молния» (в ванилле красивее; с шейдерпаком Photon она белая).
        // Чтобы снова включить цветной режим для шейдеров: USE_EYES_WITH_SHADERS = true.
        return USE_EYES_WITH_SHADERS && IrisCompat.shadersActive() ? eyesType() : RenderTypes.lightning();
    }

    private static RenderType eyesType() {
        if (eyes == null) {
            Identifier id = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/glow_white");
            NativeImage img = new NativeImage(4, 4, false);
            for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) img.setPixel(x, y, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sandevistan glow white", img));
            eyes = RenderTypes.eyes(id);
        }
        return eyes;
    }

    public static void vertex(VertexConsumer b, PoseStack.Pose pose, float x, float y, float z, int argb) {
        b.addVertex(pose, x, y, z).setColor(argb).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
