package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.ModAttachments;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Игрока в костюме-мехе нельзя толкнуть: мобы, игроки и толпа не сдвигают его при столкновении
 * (сам костюм по-прежнему расталкивает других). Флаг костюма синхронизирован всем, поэтому работает
 * и на сервере, и на клиенте (свой игрок двигается на клиенте).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPushMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void sandevistan$mechNotPushable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Player p && ModAttachments.hasMech(p)) cir.setReturnValue(false);
    }
}
