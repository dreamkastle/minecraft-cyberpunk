package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.PsychosisController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Во время киберпсихоза клавиши (атака, предметы, инвентарь, хотбар, чат) не работают. ESC остаётся. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void sandevistan$blockKeys(CallbackInfo ci) {
        if (PsychosisController.isControlling() || com.smi7i.sandevistan.client.PlayerFreeze.frozen()
                || com.smi7i.sandevistan.client.mech.MechCutscene.active()
                || com.smi7i.sandevistan.client.mech.MechShowdownClient.locked()) {
            PsychosisController.swallowKeyPresses();
            ci.cancel();
        }
    }
}
