package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.ImplantPage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Страница «Импланты»: рисуем голографическую панель с силуэтом поверх верхней части инвентаря. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void sandevistan$implantPage(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (!ImplantPage.open) return;
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) (Object) this;
        ImplantPage.drawPage(graphics, acc.sandevistan$getLeftPos(), acc.sandevistan$getTopPos());
    }
}
