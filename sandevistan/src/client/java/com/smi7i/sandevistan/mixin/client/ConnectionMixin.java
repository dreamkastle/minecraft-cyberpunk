package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.PlayerFreeze;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Пока игрок «заморожен» Сандевистаном другого игрока — входящие пакеты откладываются (см. PlayerFreeze). */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow public abstract PacketFlow getReceiving();

    @Shadow public abstract PacketListener getPacketListener();

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void sandevistan$bufferWhileFrozen(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (getReceiving() != PacketFlow.CLIENTBOUND) return;
        if (PlayerFreeze.shouldBuffer(packet, getPacketListener())) ci.cancel();
    }
}
