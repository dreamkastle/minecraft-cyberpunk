package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> клиент: состояние импланта. overheatTicks — сколько ещё длится перегрев (0 = нет),
 * psychosisTicks — сколько ещё длится приступ киберпсихоза (0 = нет),
 * defragTicks — сколько ещё ждать, чтобы очистить одну ячейку киберпсихоза мини-игрой (0 = можно, если есть что чистить).
 */
public record StatePayload(boolean active, int ticksLeft, int streak, int overheatTicks, int psychosisTicks,
                           int defragTicks)
        implements CustomPacketPayload {
    public static final Type<StatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StatePayload::active,
            ByteBufCodecs.VAR_INT, StatePayload::ticksLeft,
            ByteBufCodecs.VAR_INT, StatePayload::streak,
            ByteBufCodecs.VAR_INT, StatePayload::overheatTicks,
            ByteBufCodecs.VAR_INT, StatePayload::psychosisTicks,
            ByteBufCodecs.VAR_INT, StatePayload::defragTicks,
            StatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
