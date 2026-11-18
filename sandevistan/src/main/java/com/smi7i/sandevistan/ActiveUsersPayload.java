package com.smi7i.sandevistan;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> всем клиентам: у каких игроков (номера сущностей) сейчас работает Сандевистан
 * и у кого из них приступ киберпсихоза. По этому клиенты строят зоны замедления (30 блоков),
 * рисуют чужие послеобразы, яркое свечение импланта и звуки.
 */
public record ActiveUsersPayload(List<Integer> activeIds, List<Integer> psychosisIds) implements CustomPacketPayload {
    public static final Type<ActiveUsersPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "active_users"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActiveUsersPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ActiveUsersPayload::activeIds,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ActiveUsersPayload::psychosisIds,
            ActiveUsersPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
