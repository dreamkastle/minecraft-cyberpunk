package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.TimeDilation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Обычно клиент «догоняет» новую позицию моба с сервера за 3 его тика (треть пути за тик).
 * В зоне слоумо тик моба — это ~секунда, и движение шло рывками разной длины.
 * В зоне догоняем за 1 шаг: моб встаёт ровно в новую точку, а плавность между шагами даёт SlowMotionClient.
 */
@Mixin(InterpolationHandler.class)
public abstract class InterpolationHandlerMixin {
    @Shadow @Final private Entity entity;
    @Shadow private int interpolationSteps;

    @Unique private int sandevistan$savedSteps = -1;

    @Inject(method = "interpolateTo", at = @At("HEAD"))
    private void sandevistan$oneStepInZone(Vec3 position, float yRot, float xRot, CallbackInfo ci) {
        if (interpolationSteps > 1 && entity.level().isClientSide() && TimeDilation.clientInZone.test(entity)) {
            sandevistan$savedSteps = interpolationSteps;
            interpolationSteps = 1;
        }
    }

    @Inject(method = "interpolateTo", at = @At("RETURN"))
    private void sandevistan$restoreSteps(Vec3 position, float yRot, float xRot, CallbackInfo ci) {
        if (sandevistan$savedSteps >= 0) {
            interpolationSteps = sandevistan$savedSteps;
            sandevistan$savedSteps = -1;
        }
    }
}
