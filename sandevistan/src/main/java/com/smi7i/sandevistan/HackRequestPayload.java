package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: чип «Взлом» нажат, пришли список целей. */
public record HackRequestPayload() implements CustomPacketPayload {
    public static final Type<HackRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "hack_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HackRequestPayload> CODEC = StreamCodec.unit(new HackRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
