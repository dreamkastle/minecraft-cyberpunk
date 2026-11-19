package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер -> клиент: список целей, строки «ник\\tмаска_имплантов» через перевод строки. */
public record HackListPayload(String data) implements CustomPacketPayload {
    public static final Type<HackListPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "hack_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HackListPayload> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, HackListPayload::data, HackListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
