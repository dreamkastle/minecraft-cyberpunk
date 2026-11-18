package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер. action: 0 = включить, 1 = выключить. */
public record ActionPayload(int action) implements CustomPacketPayload {
    public static final Type<ActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, ActionPayload::action, ActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
