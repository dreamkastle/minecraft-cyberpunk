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
    /**
     * Заморожен чужим Сандевистаном: управление отключено, а сам игрок «живёт» как моб в зоне — делает один тик
     * из 20. Падает он медленно, но не стоит в воздухе (клавиши в это время не нажимаются, см. KeyboardInputMixin).
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sandevistan$frozenByOtherSandevistan(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (com.smi7i.sandevistan.client.PlayerFreeze.frozen() && !com.smi7i.sandevistan.client.ClientZones.isStepTick()) {
            ci.cancel();
        }
    }

    @Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
    private void sandevistan$bodyStillControlled(CallbackInfoReturnable<Boolean> cir) {
        if (PsychosisSoul.active()) {
            cir.setReturnValue(true);
        }
    }
}
