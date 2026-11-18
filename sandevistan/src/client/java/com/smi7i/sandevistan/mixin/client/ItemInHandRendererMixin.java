package com.smi7i.sandevistan.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * В костюме-мехе рука «от первого лица» (и предмет в ней) не рисуется — видно руки самого костюма.
 * Отдельно от GameRendererMixin: с шейдерами Iris рисует руку своим путём, но через этот же метод.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noHandInMech(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector,
                                         LocalPlayer player, int lightCoords, CallbackInfo ci) {
        if (com.smi7i.sandevistan.ModAttachments.hasMech(player)) ci.cancel();
    }
}
