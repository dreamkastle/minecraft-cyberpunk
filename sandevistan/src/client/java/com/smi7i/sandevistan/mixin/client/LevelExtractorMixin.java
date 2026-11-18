package com.smi7i.sandevistan.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.smi7i.sandevistan.client.SlowMotionClient;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Для замороженных сущностей подменяет долю между тиками — плавное слоумо вместо рывков раз в секунду. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    // require = 0: если другой мод (например, Sodium) переписал это место, игра не упадёт — просто не будет плавности.
    @WrapOperation(method = "extractVisibleEntities", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState sandevistan$slowMotion(LevelExtractor self, Entity entity, float partial,
                                                     Operation<EntityRenderState> original) {
        return original.call(self, entity, SlowMotionClient.partialFor(entity, partial));
    }

    /** Частицы в слоумо тикают раз в 20 тиков — рисуем их плавно между шагами. */
    @WrapOperation(method = "extract", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V"))
    private void sandevistan$slowParticles(ParticleEngine engine, ParticlesRenderState state, Frustum frustum, Camera camera,
                                           float partial, Operation<Void> original) {
        Float slow = SlowMotionClient.isSlowMotion() ? SlowMotionClient.worldPartial() : null;
        original.call(engine, state, frustum, camera, slow != null ? slow : partial);
    }
}
