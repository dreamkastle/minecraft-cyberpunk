package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер -> клиент: состояние импланта. */
public record StatePayload(boolean active, int ticksLeft, int streak) implements CustomPacketPayload {
    public static final Type<StatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StatePayload::active,
            ByteBufCodecs.VAR_INT, StatePayload::ticksLeft,
            ByteBufCodecs.VAR_INT, StatePayload::streak,
            StatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
