package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: способность «шприц» — уколоть жидкость из баллонов костюма (+30 секунд). */
public record InjectPayload() implements CustomPacketPayload {
    public static final Type<InjectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_inject"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InjectPayload> CODEC = StreamCodec.unit(new InjectPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
