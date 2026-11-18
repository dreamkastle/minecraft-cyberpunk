package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: способность костюма «вихрь». */
public record VortexPayload() implements CustomPacketPayload {
    public static final Type<VortexPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_vortex"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VortexPayload> CODEC = StreamCodec.unit(new VortexPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
