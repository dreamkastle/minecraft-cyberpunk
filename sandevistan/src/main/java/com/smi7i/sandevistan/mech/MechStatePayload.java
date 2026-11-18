package com.smi7i.sandevistan.mech;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> игрок: состояние костюма. ticks — сколько тиков жизни в костюме осталось, doses — уколов в шприцах,
 * attackCooldown — перезарядка атаки (тики), summonCooldown — заряд вызова костюма (тики до готовности),
 * dashCooldown — перезарядка рывка, skillCooldown — перезарядка «вихря» (тики).
 */
public record MechStatePayload(int ticks, int doses, int attackCooldown, int summonCooldown, int dashCooldown, int skillCooldown,
                               int meteorCooldown, int singularityCooldown, int pushCooldown)
        implements CustomPacketPayload {
    public static final Type<MechStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sandevistan", "mech_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MechStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MechStatePayload::ticks,
            ByteBufCodecs.VAR_INT, MechStatePayload::doses,
            ByteBufCodecs.VAR_INT, MechStatePayload::attackCooldown,
            ByteBufCodecs.VAR_INT, MechStatePayload::summonCooldown,
            ByteBufCodecs.VAR_INT, MechStatePayload::dashCooldown,
            ByteBufCodecs.VAR_INT, MechStatePayload::skillCooldown,
            ByteBufCodecs.VAR_INT, MechStatePayload::meteorCooldown,
            ByteBufCodecs.VAR_INT, MechStatePayload::singularityCooldown,
            ByteBufCodecs.VAR_INT, MechStatePayload::pushCooldown,
            MechStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
