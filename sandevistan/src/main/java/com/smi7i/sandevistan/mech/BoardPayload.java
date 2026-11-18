package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер -> игрок: началась кат-сцена посадки в костюм suitId (start — игровое время ПКМ). */
public record BoardPayload(int suitId, long start) implements CustomPacketPayload {
    public static final Type<BoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_board"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoardPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BoardPayload::suitId,
            ByteBufCodecs.VAR_LONG, BoardPayload::start,
            BoardPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
