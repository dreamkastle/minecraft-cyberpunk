package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> игроки рядом: «Сингулярность» выстрелила. player — кто, x/y/z — центр будущей чёрной дыры,
 * fireTime — игровое время выстрела (по нему клиент считает слияние молний, рост дыры и сверхновую).
 */
public record SingularityFxPayload(int player, double x, double y, double z, long fireTime) implements CustomPacketPayload {
    public static final Type<SingularityFxPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_singularity_fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SingularityFxPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SingularityFxPayload::player,
            ByteBufCodecs.DOUBLE, SingularityFxPayload::x,
            ByteBufCodecs.DOUBLE, SingularityFxPayload::y,
            ByteBufCodecs.DOUBLE, SingularityFxPayload::z,
            ByteBufCodecs.VAR_LONG, SingularityFxPayload::fireTime,
            SingularityFxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
