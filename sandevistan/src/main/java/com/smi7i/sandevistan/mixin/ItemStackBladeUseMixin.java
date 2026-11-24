package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.MantisBlades;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Пока клинки достаны, предмет в заблокированном слоте (основная рука) нельзя использовать: есть, ставить, бросать и т.п. */
@Mixin(ItemStack.class)
public abstract class ItemStackBladeUseMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noUseWhileBlades(Level level, Player player, InteractionHand hand,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        if (hand == InteractionHand.MAIN_HAND && MantisBlades.isExtended(player)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noUseOnWhileBlades(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Player player = context.getPlayer();
        if (player != null && context.getHand() == InteractionHand.MAIN_HAND && MantisBlades.isExtended(player)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
