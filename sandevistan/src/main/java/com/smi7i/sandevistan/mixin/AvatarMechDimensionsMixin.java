package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.ModAttachments;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Игрок в костюме-мехе выше и шире: голова и тело сидят в костюме на ~0.8 блока выше обычного. */
@Mixin(Avatar.class)
public abstract class AvatarMechDimensionsMixin {
    private static final EntityDimensions MECH = EntityDimensions.scalable(1.4F, 2.7F).withEyeHeight(2.325F); // на 2 пикселя (2/16 блока) ниже прежних 2.45
    private static final EntityDimensions MECH_CROUCH = EntityDimensions.scalable(1.4F, 2.5F).withEyeHeight(2.25F);

    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void sandevistan$mechSize(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (pose == Pose.SLEEPING || pose == Pose.DYING) return;
        if (ModAttachments.hasMech((Avatar) (Object) this)) {
            cir.setReturnValue(pose == Pose.CROUCHING ? MECH_CROUCH : MECH);
        }
    }
}
