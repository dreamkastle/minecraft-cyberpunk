package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: отбить (ЛКМ, reflect = false) или отразить назад (ПКМ, reflect = true) снаряд entityId. */
public record ParryPayload(int entityId, boolean reflect) implements CustomPacketPayload {
    public static final Type<ParryPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "parry"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ParryPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ParryPayload::entityId, ByteBufCodecs.BOOL, ParryPayload::reflect, ParryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
