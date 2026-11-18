package com.smi7i.sandevistan.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Положение слоя модели предмета (то же, что игра применяет перед отрисовкой граней). */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateInvoker {
    @Invoker("applyTransform")
    void sandevistan$applyTransform(PoseStack.Pose pose);
}
