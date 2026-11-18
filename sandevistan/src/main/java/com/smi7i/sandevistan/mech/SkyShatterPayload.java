package com.smi7i.sandevistan.mech;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> игроки рядом: после сверхновой раскалывается небо. start — игровое время взрыва, cx/cz — центр над игроком,
 * skyY — высота «неба», groundY — земля в центре. shards — по SkyShatter.FLOATS чисел на кусок (см. SkyShatter.Shard).
 */
public record SkyShatterPayload(long start, double cx, double cz, double skyY, double groundY, List<Float> shards)
        implements CustomPacketPayload {
    public static final Type<SkyShatterPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "sky_shatter"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkyShatterPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SkyShatterPayload::start,
            ByteBufCodecs.DOUBLE, SkyShatterPayload::cx,
            ByteBufCodecs.DOUBLE, SkyShatterPayload::cz,
            ByteBufCodecs.DOUBLE, SkyShatterPayload::skyY,
            ByteBufCodecs.DOUBLE, SkyShatterPayload::groundY,
            ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), SkyShatterPayload::shards,
            SkyShatterPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
