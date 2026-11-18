package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.AfterimageRenderState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * У послеобразов слои включены, но LivingEntityRendererMixin пропускает все, кроме ImplantBackLayer:
 * без брони, предметов, плаща — только силуэт и светящийся имплант на спине.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(method = "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Z",
            at = @At("HEAD"), cancellable = true)
    private void sandevistan$layersOnAfterimage(AvatarRenderState state, CallbackInfoReturnable<Boolean> cir) {
        if (state instanceof AfterimageRenderState) {
            cir.setReturnValue(true);
        }
    }
}
