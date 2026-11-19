package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер -> клиент: на тебя была попытка взлома (провал). */
public record HackWarningPayload() implements CustomPacketPayload {
    public static final Type<HackWarningPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "hack_warning"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HackWarningPayload> CODEC = StreamCodec.unit(new HackWarningPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
