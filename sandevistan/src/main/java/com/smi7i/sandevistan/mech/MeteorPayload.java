package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок -> сервер: «метеор» костюма. action 0 — зажал клавишу (взлёт), 1 — отпустил (появиться и ударить). */
public record MeteorPayload(int action) implements CustomPacketPayload {
    public static final Type<MeteorPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_meteor"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MeteorPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MeteorPayload::action, MeteorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
