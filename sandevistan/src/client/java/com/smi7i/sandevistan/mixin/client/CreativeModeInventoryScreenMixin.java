package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.HandImplantSlot;
import com.smi7i.sandevistan.HeadImplantSlot;
import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.NeuroChipSlot;
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
        // Слоты имплантов рук: в столбик под слотом Сандевистана.
        if (slots.size() > HandImplantSlot.MENU_INDEX_2) {
            SlotAccessor h1 = (SlotAccessor) slots.get(HandImplantSlot.MENU_INDEX_1);
            h1.sandevistan$setX(HandImplantSlot.CREATIVE_X);
            h1.sandevistan$setY(HandImplantSlot.CREATIVE_Y_1);
            SlotAccessor h2 = (SlotAccessor) slots.get(HandImplantSlot.MENU_INDEX_2);
            h2.sandevistan$setX(HandImplantSlot.CREATIVE_X);
            h2.sandevistan$setY(HandImplantSlot.CREATIVE_Y_2);
        }
        // Слот импланта головы: ниже слотов рук.
        if (slots.size() > HeadImplantSlot.MENU_INDEX) {
            SlotAccessor head = (SlotAccessor) slots.get(HeadImplantSlot.MENU_INDEX);
            head.sandevistan$setX(HeadImplantSlot.CREATIVE_X);
            head.sandevistan$setY(HeadImplantSlot.CREATIVE_Y);
        }
        // Слот чипа: под слотом головы.
        if (slots.size() > NeuroChipSlot.MENU_INDEX) {
            SlotAccessor chip = (SlotAccessor) slots.get(NeuroChipSlot.MENU_INDEX);
            chip.sandevistan$setX(NeuroChipSlot.CREATIVE_X);
            chip.sandevistan$setY(NeuroChipSlot.CREATIVE_Y);
        }
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
        int[] handIndex = {HandImplantSlot.MENU_INDEX_1, HandImplantSlot.MENU_INDEX_2};
        int[] handY = {HandImplantSlot.CREATIVE_Y_1, HandImplantSlot.CREATIVE_Y_2};
        for (int i = 0; i < 2; i++) {
            boolean handFilled = slots.size() > handIndex[i] && slots.get(handIndex[i]).hasItem();
            ImplantSlotUi.draw(graphics, acc.sandevistan$getLeftPos() + HandImplantSlot.CREATIVE_X - 1,
                    acc.sandevistan$getTopPos() + handY[i] - 1, handFilled, System.currentTimeMillis(), true);
        }
        boolean headFilled = slots.size() > HeadImplantSlot.MENU_INDEX && slots.get(HeadImplantSlot.MENU_INDEX).hasItem();
        ImplantSlotUi.draw(graphics, acc.sandevistan$getLeftPos() + HeadImplantSlot.CREATIVE_X - 1,
                acc.sandevistan$getTopPos() + HeadImplantSlot.CREATIVE_Y - 1, headFilled, System.currentTimeMillis(), 2);
        boolean chipFilled = slots.size() > NeuroChipSlot.MENU_INDEX && slots.get(NeuroChipSlot.MENU_INDEX).hasItem();
        ImplantSlotUi.draw(graphics, acc.sandevistan$getLeftPos() + NeuroChipSlot.CREATIVE_X - 1,
                acc.sandevistan$getTopPos() + NeuroChipSlot.CREATIVE_Y - 1, chipFilled, System.currentTimeMillis(), 3);
    }
}
