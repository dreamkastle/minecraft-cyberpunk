package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок -> сервер: способность «Магнит». */
public record PushPayload() implements CustomPacketPayload {
    public static final Type<PushPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_push"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PushPayload> CODEC = StreamCodec.unit(new PushPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
