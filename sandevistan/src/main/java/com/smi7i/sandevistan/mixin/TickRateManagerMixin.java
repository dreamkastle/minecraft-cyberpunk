package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.TimeDilation;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Игра спрашивает «заморожена ли сущность» перед каждым её тиком (на сервере и на клиенте).
 * Сущности в зоне Сандевистана отвечаем «да» — кроме одного тика из 20 (шаг): так они живут в 20 раз медленнее.
 */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerMixin {
    @Inject(method = "isEntityFrozen", at = @At("RETURN"), cancellable = true)
    private void sandevistan$zoneSlowdown(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && TimeDilation.isZoneFrozen(entity)) {
            cir.setReturnValue(true);
        }
    }
}
