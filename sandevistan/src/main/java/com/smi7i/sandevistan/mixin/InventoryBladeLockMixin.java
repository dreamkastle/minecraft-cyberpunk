package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.MantisBlades;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Пока клинки богомола достаны, выбранный слот хотбара заблокирован:
 *  - слот нельзя сменить (колесо мыши, цифры 1-9, любые другие способы) — выбранный слот и есть заблокированный;
 *  - предмет из него нельзя выбросить (Q);
 *  - подбираемые с земли предметы не попадают в этот слот.
 * Миксин общий (и сервер, и клиент): флаг клинков синхронизирован, поэтому обе стороны решают одинаково.
 */
@Mixin(Inventory.class)
public abstract class InventoryBladeLockMixin {
    @Shadow @Final public Player player;

    @Shadow public abstract int getSelectedSlot();

    @Shadow public abstract ItemStack getItem(int slot);

    @Inject(method = "setSelectedSlot", at = @At("HEAD"), cancellable = true)
    private void sandevistan$lockSelectedSlot(int slot, CallbackInfo ci) {
        if (MantisBlades.isExtended(player)) ci.cancel();
    }

    @Inject(method = "removeFromSelected", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noDrop(boolean all, CallbackInfoReturnable<ItemStack> cir) {
        if (MantisBlades.isExtended(player)) cir.setReturnValue(ItemStack.EMPTY);
    }

    /** Подбор предмета: свободный слот ищем, минуя заблокированный. */
    @Inject(method = "getFreeSlot", at = @At("RETURN"), cancellable = true)
    private void sandevistan$skipLockedFreeSlot(CallbackInfoReturnable<Integer> cir) {
        if (!MantisBlades.isExtended(player)) return;
        int locked = getSelectedSlot();
        if (cir.getReturnValueI() != locked) return;
        for (int i = 0; i < 36; i++) {
            if (i != locked && getItem(i).isEmpty()) {
                cir.setReturnValue(i);
                return;
            }
        }
        cir.setReturnValue(-1);
    }

    /** Подбор предмета: в стопку из заблокированного слота ничего не добавляем. */
    @Inject(method = "getSlotWithRemainingSpace", at = @At("RETURN"), cancellable = true)
    private void sandevistan$skipLockedStack(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (MantisBlades.isExtended(player) && cir.getReturnValueI() == getSelectedSlot()) cir.setReturnValue(-1);
    }
}
