package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.client.ImplantSlotUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Голографическое окошко слота импланта в обычном инвентаре (слот 46, позиция 77, 44). */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void sandevistan$frame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        boolean filled = mc.player != null && mc.player.inventoryMenu.slots.size() > ImplantSlot.MENU_INDEX
                && mc.player.inventoryMenu.getSlot(ImplantSlot.MENU_INDEX).hasItem();
        ImplantSlotUi.draw(graphics, acc.sandevistan$getLeftPos() + 76, acc.sandevistan$getTopPos() + 43,
                filled, System.currentTimeMillis());
    }
}
