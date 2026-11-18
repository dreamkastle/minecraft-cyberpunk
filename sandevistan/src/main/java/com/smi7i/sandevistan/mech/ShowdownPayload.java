package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: способность «Последний бой» (когда шкала уколов полная). */
public record ShowdownPayload() implements CustomPacketPayload {
    public static final Type<ShowdownPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_showdown"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShowdownPayload> CODEC = StreamCodec.unit(new ShowdownPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
