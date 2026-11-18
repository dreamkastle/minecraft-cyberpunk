package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.ImplantContainer;
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

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sandevistan$addImplantSlot(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        ((AbstractContainerMenuInvoker) (Object) this)
                .sandevistan$addSlot(new ImplantSlot(new ImplantContainer(owner), 77, 44));
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void sandevistan$quickMove(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        AbstractContainerMenuInvoker invoker = (AbstractContainerMenuInvoker) (Object) this;

        Slot slot = menu.getSlot(index);
        if (!slot.hasItem()) return;
        ItemStack stack = slot.getItem();

        boolean fromImplant = index == IMPLANT_SLOT;
        boolean toImplant = !fromImplant && ImplantSlot.isImplant(stack) && !menu.getSlot(IMPLANT_SLOT).hasItem();
        if (!fromImplant && !toImplant) return;

        ItemStack original = stack.copy();
        boolean moved = fromImplant
                ? invoker.sandevistan$moveItemStackTo(stack, 9, 45, false)
                : invoker.sandevistan$moveItemStackTo(stack, IMPLANT_SLOT, IMPLANT_SLOT + 1, false);
        if (!moved) {
            if (fromImplant) cir.setReturnValue(ItemStack.EMPTY);
            return;
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, original); else slot.setChanged();
        slot.onTake(player, stack);
        cir.setReturnValue(original);
    }
}
