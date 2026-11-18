package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: способность «атака» костюма (комбо из трёх ударов). */
public record MechAttackPayload() implements CustomPacketPayload {
    public static final Type<MechAttackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_attack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MechAttackPayload> CODEC = StreamCodec.unit(new MechAttackPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
