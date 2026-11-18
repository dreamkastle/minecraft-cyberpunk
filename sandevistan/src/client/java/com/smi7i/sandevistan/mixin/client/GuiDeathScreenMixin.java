package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.mech.MechShowdownClient;
import com.smi7i.sandevistan.client.mech.ShowdownDeathScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Гибель в «Последнем бою»: вместо ванильного экрана смерти СРАЗУ открывается свой (ShowdownDeathScreen) —
 * раньше ванильный успевал мелькнуть на кадр, а потом подменялся.
 */
@Mixin(Gui.class)
public abstract class GuiDeathScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void sandevistan$showdownDeathScreen(Screen screen, CallbackInfo ci) {
        if (!MechShowdownClient.customDeathScreen()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean death = screen instanceof DeathScreen || (screen == null && mc.player != null && mc.player.isDeadOrDying());
        if (!death) return;
        ci.cancel();
        // Свой экран уже открыт — ничего не трогаем (иначе анимация надписей начиналась бы заново).
        if (mc.gui.screen() instanceof ShowdownDeathScreen) return;
        ((Gui) (Object) this).setScreen(new ShowdownDeathScreen());
    }
}
