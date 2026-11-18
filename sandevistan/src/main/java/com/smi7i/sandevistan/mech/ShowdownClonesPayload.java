package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> игроки рядом: где сейчас два клона «Последнего боя» игрока playerId.
 * data — по клону: x, y, z, поворот (рыскание в градусах).
 */
public record ShowdownClonesPayload(int playerId, double[] data) implements CustomPacketPayload {
    public static final Type<ShowdownClonesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_showdown_clones"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShowdownClonesPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.playerId);
                buf.writeVarInt(p.data.length);
                for (double d : p.data) buf.writeFloat((float) d);
            },
            buf -> {
                int id = buf.readVarInt();
                int n = Math.min(buf.readVarInt(), 64);
                double[] data = new double[n];
                for (int i = 0; i < n; i++) data[i] = buf.readFloat();
                return new ShowdownClonesPayload(id, data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
