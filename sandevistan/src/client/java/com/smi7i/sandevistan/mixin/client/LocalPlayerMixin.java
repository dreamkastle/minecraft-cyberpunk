package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.PsychosisSoul;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Игра применяет нажатия движения к игроку и отправляет его позицию на сервер, только если камера в нём.
 * Пока «душа» вне тела, камера в душе — но тело должно двигаться (автопилот) и сообщать серверу, где оно.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    /** Заморожен чужим Сандевистаном — игрок вообще не «живёт» (не падает, не двигается), пока не отпустит. */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sandevistan$frozenByOtherSandevistan(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (com.smi7i.sandevistan.client.PlayerFreeze.frozen()) ci.cancel();
    }

    @Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
    private void sandevistan$bodyStillControlled(CallbackInfoReturnable<Boolean> cir) {
        if (PsychosisSoul.active()) {
            cir.setReturnValue(true);
        }
    }
}
