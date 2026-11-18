package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.PsychosisController;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Во время киберпсихоза «нажатия» клавиш движения берутся у автопилота, а не с клавиатуры. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void sandevistan$psychosisInput(CallbackInfo ci) {
        ClientInput self = (ClientInput) (Object) this;
        // Кат-сцена посадки в костюм: персонаж сам подходит к костюму, игрок им не управляет.
        Input cut = com.smi7i.sandevistan.client.mech.MechCutscene.forcedInput();
        if (cut == null && com.smi7i.sandevistan.client.mech.MechShowdownClient.locked()) {
            cut = new Input(false, false, false, false, false, false, false); // кат-сцена / гибель «Последнего боя»
        }
        if (cut != null) {
            self.keyPresses = cut;
            ((ClientInputAccessor) self).sandevistan$setMoveVector(new Vec2(0.0F, com.smi7i.sandevistan.client.mech.MechCutscene.forwardAmount()));
            return;
        }
        Input forced = PsychosisController.forcedInput();
        if (forced == null && com.smi7i.sandevistan.client.mech.MechClient.localInMech()) {
            Input in = self.keyPresses;
            if (com.smi7i.sandevistan.client.mech.MechClient.localAttacking()
                    || com.smi7i.sandevistan.mech.MechManager.isEquipping(net.minecraft.client.Minecraft.getInstance().player)
                    || com.smi7i.sandevistan.mech.MechVortex.isActive(net.minecraft.client.Minecraft.getInstance().player)
                    || com.smi7i.sandevistan.mech.MechSingularity.isActive(net.minecraft.client.Minecraft.getInstance().player)
                    || com.smi7i.sandevistan.mech.MechPush.isActive(net.minecraft.client.Minecraft.getInstance().player)) {
                // Во время атаки и посадки костюм стоит на месте.
                self.keyPresses = new Input(false, false, false, false, false, false, false);
                ((ClientInputAccessor) self).sandevistan$setMoveVector(Vec2.ZERO);
            } else if (in.shift() || in.jump()) {
                // В костюме нельзя приседать и прыгать (на блок вверх костюм перешагивает сам).
                self.keyPresses = new Input(in.forward(), in.backward(), in.left(), in.right(), false, false, in.sprint());
            }
            return;
        }
        if (forced == null) return;
        self.keyPresses = forced;
        float forward = forced.forward() == forced.backward() ? 0.0F : (forced.forward() ? 1.0F : -1.0F);
        float left = forced.left() == forced.right() ? 0.0F : (forced.left() ? 1.0F : -1.0F);
        ((ClientInputAccessor) self).sandevistan$setMoveVector(new Vec2(left, forward).normalized());
    }
}
