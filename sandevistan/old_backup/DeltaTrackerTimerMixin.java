package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.SlowMotionClient;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Общие часы игры. Всё, что подчиняется заморозке мира (частицы, сущности, анимации блоков, погода),
 * спрашивает у них «долю между тиками» с ignoreFrozenGame = false. Пока мир в слоумо, отдаём плавную долю
 * между шагами мира (по настоящему времени) — так ВЕСЬ замедленный мир двигается плавно, а не прыжками раз в секунду.
 * Игрок и камера спрашивают с ignoreFrozenGame = true — их это не касается.
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
    private void sandevistan$smoothSlowMotion(boolean ignoreFrozenGame, CallbackInfoReturnable<Float> cir) {
        if (ignoreFrozenGame) return;
        Float slow = SlowMotionClient.worldPartial();
        if (slow != null) cir.setReturnValue(slow);
    }
}
