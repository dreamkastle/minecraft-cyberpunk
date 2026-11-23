package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.MantisBlades;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пока клинки достаны, в инвентаре нельзя ничего класть в заблокированный (выбранный) слот хотбара и брать из него:
 * ни кликом, ни шифтом, ни перетаскиванием, ни обменом по цифре (цифра этого слота над любым другим слотом).
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuBladeLockMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void sandevistan$lockBladeSlot(int slotId, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (!MantisBlades.isExtended(player)) return;
        int locked = player.getInventory().getSelectedSlot();
        if (input == ContainerInput.SWAP && button == locked) {
            ci.cancel();
            return;
        }
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (slotId < 0 || slotId >= menu.slots.size()) return;
        Slot slot = menu.slots.get(slotId);
        if (slot.container == player.getInventory() && slot.getContainerSlot() == locked) ci.cancel();
    }
}
