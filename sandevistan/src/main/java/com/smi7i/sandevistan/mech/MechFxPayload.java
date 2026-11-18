package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> игроки рядом: нарисовать эффект костюма (свой дым, пыль, крутящиеся куски блоков — client/mech/MechFx).
 * kind — что случилось, (x, y, z) — точка, yaw — куда направлен удар, power — сила (1..3),
 * ground — блок под точкой (Block.getId), blocks/states — выбитые блоки (BlockPos.asLong и Block.getId).
 */
public record MechFxPayload(int kind, double x, double y, double z, float yaw, int power, int ground,
                            long[] blocks, int[] states) implements CustomPacketPayload {
    public static final int LAND = 0, HIT = 1, SLAM = 2, WAVE = 3, EJECT = 4, DASH = 5, DASH_END = 6, SUIT_EXPLODE = 7, LEVITATE = 8;
    /** «Сингулярность»: блоки, которые поглотила чёрная дыра (power — id игрока). */
    public static final int ABSORB = 9;
    /** «Магнит»: блоки, копии которых притягиваются к рукам (power — id игрока). */
    public static final int PULL = 10;

    public static final Type<MechFxPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MechFxPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.kind);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.yaw);
                buf.writeByte(p.power);
                buf.writeVarInt(p.ground);
                buf.writeVarInt(p.blocks.length);
                for (int i = 0; i < p.blocks.length; i++) {
                    buf.writeLong(p.blocks[i]);
                    buf.writeVarInt(p.states[i]);
                }
            },
            buf -> {
                int kind = buf.readByte();
                double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                float yaw = buf.readFloat();
                int power = buf.readByte();
                int ground = buf.readVarInt();
                int n = Math.min(buf.readVarInt(), 4096);
                long[] blocks = new long[n];
                int[] states = new int[n];
                for (int i = 0; i < n; i++) {
                    blocks[i] = buf.readLong();
                    states[i] = buf.readVarInt();
                }
                return new MechFxPayload(kind, x, y, z, yaw, power, ground, blocks, states);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
