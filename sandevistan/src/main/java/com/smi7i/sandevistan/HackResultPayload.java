package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: мини-игра окончена, успех или провал. */
public record HackResultPayload(boolean success) implements CustomPacketPayload {
    public static final Type<HackResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "hack_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HackResultPayload> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, HackResultPayload::success, HackResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
