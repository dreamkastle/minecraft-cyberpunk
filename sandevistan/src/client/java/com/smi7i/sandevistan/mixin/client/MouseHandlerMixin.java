package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.PsychosisController;
import com.smi7i.sandevistan.client.PsychosisSoul;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Во время киберпсихоза мышь не крутит тело — головой тела управляет автопилот (каждый кадр, плавно).
 * Если душа вышла из тела — мышь крутит взгляд души.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow private Minecraft minecraft;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void sandevistan$psychosisLook(double frameSeconds, CallbackInfo ci) {
        if (com.smi7i.sandevistan.client.PlayerFreeze.frozen() || com.smi7i.sandevistan.client.mech.MechCutscene.active()
                || com.smi7i.sandevistan.client.mech.MechShowdownClient.locked()) {
            ci.cancel(); // заморожен чужим Сандевистаном — взгляд не двигается
            return;
        }
        if (!PsychosisController.isControlling()) return;
        PsychosisController.turnFrame(frameSeconds);
        if (PsychosisSoul.active()) {
            // Та же чувствительность, что у обычной мыши в игре.
            double ss = minecraft.options.sensitivity().get() * 0.6 + 0.2;
            double sens = ss * ss * ss * 8.0;
            double dx = accumulatedDX * sens;
            double dy = accumulatedDY * sens;
            PsychosisSoul.turn(minecraft.options.invertMouseX().get() ? -dx : dx,
                    minecraft.options.invertMouseY().get() ? -dy : dy);
        }
        ci.cancel();
    }
}
