package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.SlowMotionHitEffects;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Удар игрока (и автопилота киберпсихоза) — эффекты удара в слоумо. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("TAIL"))
    private void sandevistan$hitEffects(Player player, Entity target, CallbackInfo ci) {
        if (com.smi7i.sandevistan.client.SandevistanConfig.get().hitEffects) SlowMotionHitEffects.onAttack(player, target);
    }
}
