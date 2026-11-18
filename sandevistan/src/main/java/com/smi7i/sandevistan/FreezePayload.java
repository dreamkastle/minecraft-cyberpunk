package com.smi7i.sandevistan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> игроку без Сандевистана рядом с тем, у кого он работает (ближе 30 блоков):
 * frozen = true — «замри» (экран на паузе, всё происходящее копится), false — «отомри» (показать накопленное ×10).
 */
public record FreezePayload(boolean frozen) implements CustomPacketPayload {
    public static final Type<FreezePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "freeze"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FreezePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, FreezePayload::frozen, FreezePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
