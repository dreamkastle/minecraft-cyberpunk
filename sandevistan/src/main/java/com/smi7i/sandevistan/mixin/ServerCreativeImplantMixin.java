package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.BodyImplantSlot;
import com.smi7i.sandevistan.SoftwareSlot;
import com.smi7i.sandevistan.HandImplantSlot;
import com.smi7i.sandevistan.HeadImplantSlot;
import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.NeuroChipSlot;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * В творческом режиме игра сообщает серверу об изменениях инвентаря, но сервер принимает только слоты 1..45.
 * Слот импланта — 46: принимаем и его, но только если это имплант (или пусто, когда имплант вынули).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerCreativeImplantMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void sandevistan$creativeImplantSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        int index = packet.slotNum();
        boolean main = index == ImplantSlot.MENU_INDEX;
        boolean hand = index == HandImplantSlot.MENU_INDEX_1 || index == HandImplantSlot.MENU_INDEX_2;
        boolean head = index == HeadImplantSlot.MENU_INDEX;
        boolean chip = index == NeuroChipSlot.MENU_INDEX;
        boolean body = index == BodyImplantSlot.MENU_INDEX;
        boolean software = index == SoftwareSlot.MENU_INDEX;
        if (!main && !hand && !head && !chip && !body && !software) return;
        ci.cancel();
        if (!player.hasInfiniteMaterials()) return;
        ItemStack stack = packet.itemStack();
        boolean valid = main ? ImplantSlot.isImplant(stack)
                : head ? HeadImplantSlot.isHeadImplant(stack)
                : chip ? NeuroChipSlot.isChip(stack)
                : body ? BodyImplantSlot.isBodyImplant(stack)
                : software ? SoftwareSlot.isSoftware(stack) : HandImplantSlot.accepts(index, stack);
        if (!stack.isEmpty() && (!valid || stack.getCount() > 1)) return;
        if (player.inventoryMenu.slots.size() <= index) return;
        player.inventoryMenu.getSlot(index).setByPlayer(stack);
        player.inventoryMenu.setRemoteSlot(index, stack);
        player.inventoryMenu.broadcastChanges();
    }
}
