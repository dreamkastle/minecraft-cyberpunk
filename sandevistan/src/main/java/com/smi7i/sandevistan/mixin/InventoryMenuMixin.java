package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.BodyImplantSlot;
import com.smi7i.sandevistan.SoftwareSlot;
import com.smi7i.sandevistan.HandImplantContainer;
import com.smi7i.sandevistan.HandImplantSlot;
import com.smi7i.sandevistan.HeadImplantSlot;
import com.smi7i.sandevistan.NeuroChipSlot;
import com.smi7i.sandevistan.ImplantContainer;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.ImplantSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {
    @Unique private static final int IMPLANT_SLOT = 46;
    @Unique private static final int OFFSCREEN = -2000;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sandevistan$addImplantSlot(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new ImplantSlot(new ImplantContainer(owner), OFFSCREEN, OFFSCREEN));
        // Слоты имплантов живут на отдельной странице «Импланты»; на обычной странице инвентаря они убраны за экран,
        // а клиентский код (ImplantPage) ставит их на место при переключении страницы.
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new HandImplantSlot(new HandImplantContainer(owner, ModAttachments.HAND_IMPLANT_1), OFFSCREEN, OFFSCREEN, true));
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new HandImplantSlot(new HandImplantContainer(owner, ModAttachments.HAND_IMPLANT_2), OFFSCREEN, OFFSCREEN, false));
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new HeadImplantSlot(new HandImplantContainer(owner, ModAttachments.HEAD_IMPLANT), OFFSCREEN, OFFSCREEN));
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new NeuroChipSlot(new HandImplantContainer(owner, ModAttachments.CHIP), OFFSCREEN, OFFSCREEN));
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new BodyImplantSlot(new HandImplantContainer(owner, ModAttachments.BODY_IMPLANT), OFFSCREEN, OFFSCREEN));
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new SoftwareSlot(new HandImplantContainer(owner, ModAttachments.SOFTWARE), OFFSCREEN, OFFSCREEN));
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void sandevistan$quickMove(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        AbstractContainerMenuInvoker invoker = (AbstractContainerMenuInvoker) (Object) this;

        Slot slot = menu.getSlot(index);
        if (!slot.hasItem()) return;
        ItemStack stack = slot.getItem();

        boolean fromImplant = index == IMPLANT_SLOT
                || index == HandImplantSlot.MENU_INDEX_1 || index == HandImplantSlot.MENU_INDEX_2
                || index == HeadImplantSlot.MENU_INDEX || index == NeuroChipSlot.MENU_INDEX
                || index == BodyImplantSlot.MENU_INDEX || index == SoftwareSlot.MENU_INDEX;
        boolean toImplant = !fromImplant && ImplantSlot.isImplant(stack) && !menu.getSlot(IMPLANT_SLOT).hasItem();
        boolean toHand = !fromImplant && HandImplantSlot.isAnyHandItem(stack)
                && (!menu.getSlot(HandImplantSlot.MENU_INDEX_1).hasItem() || !menu.getSlot(HandImplantSlot.MENU_INDEX_2).hasItem());
        boolean toHead = !fromImplant && HeadImplantSlot.isHeadImplant(stack)
                && !menu.getSlot(HeadImplantSlot.MENU_INDEX).hasItem();
        boolean toChip = !fromImplant && NeuroChipSlot.isChip(stack)
                && !menu.getSlot(NeuroChipSlot.MENU_INDEX).hasItem();
        boolean toBody = !fromImplant && BodyImplantSlot.isBodyImplant(stack)
                && !menu.getSlot(BodyImplantSlot.MENU_INDEX).hasItem();
        boolean toSoftware = !fromImplant && SoftwareSlot.isSoftware(stack)
                && !menu.getSlot(SoftwareSlot.MENU_INDEX).hasItem();
        if (!fromImplant && !toImplant && !toHand && !toHead && !toChip && !toBody && !toSoftware) return;

        ItemStack original = stack.copy();
        boolean moved;
        if (fromImplant) {
            moved = invoker.sandevistan$moveItemStackTo(stack, 9, 45, false);
        } else if (toImplant) {
            moved = invoker.sandevistan$moveItemStackTo(stack, IMPLANT_SLOT, IMPLANT_SLOT + 1, false);
        } else if (toHand) {
            moved = invoker.sandevistan$moveItemStackTo(stack, HandImplantSlot.MENU_INDEX_1, HandImplantSlot.MENU_INDEX_2 + 1, false);
        } else if (toHead) {
            moved = invoker.sandevistan$moveItemStackTo(stack, HeadImplantSlot.MENU_INDEX, HeadImplantSlot.MENU_INDEX + 1, false);
        } else if (toSoftware) {
            moved = invoker.sandevistan$moveItemStackTo(stack, SoftwareSlot.MENU_INDEX, SoftwareSlot.MENU_INDEX + 1, false);
        } else if (toBody) {
            moved = invoker.sandevistan$moveItemStackTo(stack, BodyImplantSlot.MENU_INDEX, BodyImplantSlot.MENU_INDEX + 1, false);
        } else {
            moved = invoker.sandevistan$moveItemStackTo(stack, NeuroChipSlot.MENU_INDEX, NeuroChipSlot.MENU_INDEX + 1, false);
        }
        if (!moved) {
            if (fromImplant) cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original); else slot.setChanged();
        slot.onTake(player, stack);
        cir.setReturnValue(original);
    }
}
