package com.smi7i.sandevistan;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ImplantContainer implements Container {
    private final Player player;

    public ImplantContainer(Player player) {
        this.player = player;
    }

    @Override public int getContainerSize() { return 1; }
    @Override public boolean isEmpty() { return getItem(0).isEmpty(); }
    @Override public ItemStack getItem(int slot) { return player.getAttachedOrCreate(ModAttachments.IMPLANT); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = getItem(0);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stack.split(amount);
        setItem(0, stack);
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = getItem(0);
        setItem(0, ItemStack.EMPTY);
        return stack;
    }

    @Override public void setItem(int slot, ItemStack stack) { player.setAttached(ModAttachments.IMPLANT, stack); }
    @Override public void setChanged() { }
    @Override public boolean stillValid(Player p) { return true; }
    @Override public void clearContent() { setItem(0, ItemStack.EMPTY); }
}
