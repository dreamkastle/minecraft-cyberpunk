package com.smi7i.sandevistan;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class ImplantInit implements ModInitializer {
    @Override
    public void onInitialize() {
        ModAttachments.init();

        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StatePayload.TYPE, StatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE,
                (payload, context) -> SandevistanManager.onAction(context.player(), payload.action()));

        SandevistanManager.init();
    }
}
