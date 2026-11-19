package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: игрок выбрал цель и начал взлом (с этого момента идёт кулдаун чипа). */
public record HackStartPayload(String target) implements CustomPacketPayload {
    public static final Type<HackStartPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "hack_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HackStartPayload> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, HackStartPayload::target, HackStartPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
