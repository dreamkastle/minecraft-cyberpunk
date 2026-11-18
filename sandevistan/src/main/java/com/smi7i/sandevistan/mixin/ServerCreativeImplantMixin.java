package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.ImplantSlot;
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
        if (packet.slotNum() != ImplantSlot.MENU_INDEX) return;
        ci.cancel();
        if (!player.hasInfiniteMaterials()) return;
        ItemStack stack = packet.itemStack();
        if (!stack.isEmpty() && (!ImplantSlot.isImplant(stack) || stack.getCount() > 1)) return;
        if (player.inventoryMenu.slots.size() <= ImplantSlot.MENU_INDEX) return;
        player.inventoryMenu.getSlot(ImplantSlot.MENU_INDEX).setByPlayer(stack);
        player.inventoryMenu.setRemoteSlot(ImplantSlot.MENU_INDEX, stack);
        player.inventoryMenu.broadcastChanges();
    }
}
