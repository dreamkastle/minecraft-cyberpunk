package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.MantisBladeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Удар ЛКМ: вместо вечного взмаха основной рукой клинки бьют по очереди — правый, левый, правый...
 * Выбранная рука уходит и в анимацию, и в пакет взмаха на сервер (другие игроки видят то же самое).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftBladeSwingMixin {
    @ModifyArg(method = "startAttack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"),
            index = 0)
    private InteractionHand sandevistan$alternateBlades(InteractionHand hand) {
        Minecraft mc = (Minecraft) (Object) this;
        return mc.player == null ? hand : MantisBladeClient.nextSwingHand(mc.player);
    }
}
