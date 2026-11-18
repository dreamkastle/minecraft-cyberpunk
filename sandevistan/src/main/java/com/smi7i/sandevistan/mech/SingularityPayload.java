package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок -> сервер: «Сингулярность». action 0 — начать, 1 — выстрелить раньше (во время наведения). */
public record SingularityPayload(int action) implements CustomPacketPayload {
    public static final Type<SingularityPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_singularity"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SingularityPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SingularityPayload::action, SingularityPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
