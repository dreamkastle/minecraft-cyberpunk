package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.ClientZones;
import com.smi7i.sandevistan.client.SlowMotionClient;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Пока игрок сам в зоне Сандевистана, частицы (взрывы, дым, искры) живут в 20 раз медленнее. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sandevistan$slowParticles(CallbackInfo ci) {
        if (SlowMotionClient.isSlowMotion() && !ClientZones.isStepTick()) ci.cancel();
    }
}
