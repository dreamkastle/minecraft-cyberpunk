package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: нажата клавиша-способность «вызвать костюм». */
public record SummonMechPayload() implements CustomPacketPayload {
    public static final Type<SummonMechPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "summon_mech"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SummonMechPayload> CODEC = StreamCodec.unit(new SummonMechPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
