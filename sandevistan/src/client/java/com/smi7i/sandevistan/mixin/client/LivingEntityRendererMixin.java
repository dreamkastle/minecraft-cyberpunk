package com.smi7i.sandevistan.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.smi7i.sandevistan.client.AfterimageRenderState;
import com.smi7i.sandevistan.client.ImplantBackLayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Послеобразы рисуются полупрозрачными и в своём цвете. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow public abstract Identifier getTextureLocation(LivingEntityRenderState state);

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void sandevistan$afterimageRenderType(LivingEntityRenderState state, boolean isBodyVisible, boolean forceTransparent,
                                                  boolean appearGlowing, CallbackInfoReturnable<RenderType> cir) {
        if (state instanceof AfterimageRenderState) {
            // Светится сам, не зависит от освещения — яркие цвета, как в аниме.
            cir.setReturnValue(RenderTypes.entityTranslucentEmissive(getTextureLocation(state)));
        }
    }

    /** У послеобразов рисуется только слой импланта на спине, остальные слои (броня, предметы, плащ) — нет. */
    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"))
    private void sandevistan$onlyImplantOnAfterimage(RenderLayer<?, ?> layer, PoseStack poseStack, SubmitNodeCollector collector,
                                                     int light, EntityRenderState state, float yRot, float xRot,
                                                     Operation<Void> original) {
        if (state instanceof AfterimageRenderState && !(layer instanceof ImplantBackLayer
                || layer instanceof com.smi7i.sandevistan.client.mech.MechWornLayer)) return; // у послеобраза — имплант и костюм-мех
        // Игрок в костюме-мехе: броня, предметы в руках, плащ и т.п. не рисуются (рук и ног нет) — только костюм, имплант и голова.
        if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar
                && com.smi7i.sandevistan.client.mech.MechWornLayer.inMech(avatar)
                && !(layer instanceof com.smi7i.sandevistan.client.mech.MechWornLayer || layer instanceof ImplantBackLayer
                || layer instanceof net.minecraft.client.renderer.entity.layers.CustomHeadLayer<?, ?>)) return;
        // Своё тело от первого лица (в костюме): без головы — и без того, что на ней надето.
        if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar
                && com.smi7i.sandevistan.client.mech.MechWornLayer.firstPerson(avatar)
                && layer instanceof net.minecraft.client.renderer.entity.layers.CustomHeadLayer<?, ?>) return;
        original.call(layer, poseStack, collector, light, state, yRot, xRot);
    }

    @Inject(method = "getModelTint", at = @At("HEAD"), cancellable = true)
    private void sandevistan$afterimageTint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (state instanceof AfterimageRenderState afterimage) {
            cir.setReturnValue(afterimage.afterimageColor);
        }
    }
}
