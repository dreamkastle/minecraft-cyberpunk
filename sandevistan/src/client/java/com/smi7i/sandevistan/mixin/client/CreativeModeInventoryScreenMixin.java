package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.client.ImplantSlotUi;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Слот импланта на вкладке инвентаря в творческом режиме. Игра раскладывает слоты игрока сама,
 * и наш (46-й) попадал на хотбар — переставляем его справа от брони и рисуем голографическое окошко.
 * Сохранение на сервере — ServerCreativeImplantMixin.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin {
    @Inject(method = "selectTab", at = @At("TAIL"))
    private void sandevistan$placeImplantSlot(CreativeModeTab tab, CallbackInfo ci) {
        CreativeModeInventoryScreen self = (CreativeModeInventoryScreen) (Object) this;
        if (!self.isInventoryOpen()) return;
        // Вкладка инвентаря кладёт слоты игрока по порядку: 46-й в списке — обёртка нашего слота.
        List<Slot> slots = self.getMenu().slots;
        if (slots.size() <= ImplantSlot.MENU_INDEX) return;
        SlotAccessor slot = (SlotAccessor) slots.get(ImplantSlot.MENU_INDEX);
        slot.sandevistan$setX(ImplantSlot.CREATIVE_X);
        slot.sandevistan$setY(ImplantSlot.CREATIVE_Y);
    }

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void sandevistan$implantFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        CreativeModeInventoryScreen self = (CreativeModeInventoryScreen) (Object) this;
        if (!self.isInventoryOpen()) return;
        List<Slot> slots = self.getMenu().slots;
        boolean filled = slots.size() > ImplantSlot.MENU_INDEX && slots.get(ImplantSlot.MENU_INDEX).hasItem();
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) (Object) this;
        ImplantSlotUi.draw(graphics, acc.sandevistan$getLeftPos() + ImplantSlot.CREATIVE_X - 1,
                acc.sandevistan$getTopPos() + ImplantSlot.CREATIVE_Y - 1, filled, System.currentTimeMillis());
    }
}
