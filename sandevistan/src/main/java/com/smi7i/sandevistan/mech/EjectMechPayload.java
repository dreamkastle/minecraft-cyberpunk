package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: снять костюм-мех. Пилот этого не переживает — игрок погибает. */
public record EjectMechPayload() implements CustomPacketPayload {
    public static final Type<EjectMechPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_eject"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EjectMechPayload> CODEC = StreamCodec.unit(new EjectMechPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
