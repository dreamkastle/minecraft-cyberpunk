package com.smi7i.sandevistan.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuInvoker {
    @Invoker("addSlot")
    Slot sandevistan$addSlot(Slot slot);

    @Invoker("moveItemStackTo")
    boolean sandevistan$moveItemStackTo(ItemStack stack, int start, int end, boolean reverse);
}
