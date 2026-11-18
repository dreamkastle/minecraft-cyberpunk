package com.smi7i.sandevistan.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.smi7i.sandevistan.client.mech.MechWornLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Игрок в костюме-мехе: вся модель игрока поднимается на место «пилота» и поворачивается вместе с корпусом костюма.
 * Сдвигаем всю модель, а не отдельные части, — так подъём не затирают моды анимаций (Fresh Animations / EMF).
 * Слой костюма точно отменяет это преобразование (MechWornLayer), чтобы костюм стоял на земле.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMechLiftMixin {
    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("TAIL"))
    private void sandevistan$liftPilot(AvatarRenderState state, PoseStack poseStack, CallbackInfo ci) {
        if (!MechWornLayer.inMech(state)) return;
        MechWornLayer.pilotTransform(poseStack, state, false);
    }
}
