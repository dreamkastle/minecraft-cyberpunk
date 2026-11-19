package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер -> клиент: сколько тиков ещё работает чип и сколько тиков он перезаряжается. */
public record ChipStatePayload(int active, int cooldown) implements CustomPacketPayload {
    public static final Type<ChipStatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "chip_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChipStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChipStatePayload::active,
            ByteBufCodecs.VAR_INT, ChipStatePayload::cooldown,
            ChipStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
