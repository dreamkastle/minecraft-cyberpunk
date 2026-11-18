package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: способность «рывок» костюма. yaw — куда рванул (сам рывок двигает клиент). */
public record DashPayload(float yaw) implements CustomPacketPayload {
    public static final Type<DashPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_dash"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DashPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, DashPayload::yaw, DashPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
