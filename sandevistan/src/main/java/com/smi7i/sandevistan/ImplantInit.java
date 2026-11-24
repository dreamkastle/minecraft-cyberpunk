package com.smi7i.sandevistan;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class ImplantInit implements ModInitializer {
    @Override
    public void onInitialize() {
        ModAttachments.init();

        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ChipStatePayload.TYPE, ChipStatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HackRequestPayload.TYPE, HackRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HackListPayload.TYPE, HackListPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HackStartPayload.TYPE, HackStartPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HackResultPayload.TYPE, HackResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HackWarningPayload.TYPE, HackWarningPayload.CODEC);
        HackManager.init();
        PayloadTypeRegistry.serverboundPlay().register(BladeSwingPayload.TYPE, BladeSwingPayload.CODEC);
        MantisBlades.init();
        PayloadTypeRegistry.clientboundPlay().register(ActiveUsersPayload.TYPE, ActiveUsersPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FreezePayload.TYPE, FreezePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE,
                (payload, context) -> SandevistanManager.onAction(context.player(), payload.action()));

        HandImplants.init(); // импланты рук: бонус урона и скорость
        BodyImplants.init(); // имплант тела: подкожная броня
        SandevistanManager.init();
        com.smi7i.sandevistan.mech.MechManager.init(); // костюм-мех: вызов клавишей и посадка по ПКМ
    }
}
