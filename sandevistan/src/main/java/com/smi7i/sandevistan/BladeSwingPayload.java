package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент -> сервер: игрок взмахнул достанными клинками и не навёл прицел на существо (промах). */
public record BladeSwingPayload() implements CustomPacketPayload {
    public static final Type<BladeSwingPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "blade_swing"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BladeSwingPayload> CODEC =
            StreamCodec.unit(new BladeSwingPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
