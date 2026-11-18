package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.ActionPayload;
import com.smi7i.sandevistan.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class SandevistanInputClient implements ClientModInitializer {
    public static KeyMapping TOGGLE;
    private boolean prevDown;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sandevistan", "main"));
        TOGGLE = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.sandevistan.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE,
                (payload, context) -> ClientSandevistanState.update(payload.active(), payload.ticksLeft(), payload.streak()));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientSandevistanState.reset());
    }

    private void tick(Minecraft client) {
        if (client.player == null) {
            ClientSandevistanState.holdTicks = 0;
            prevDown = false;
            return;
        }

        boolean down = TOGGLE.isDown() && client.screen == null;

        if (ClientSandevistanState.active) {
            // Уже работает: новое нажатие V выключает имплант.
            ClientSandevistanState.holdTicks = 0;
            if (down && !prevDown) {
                ClientPlayNetworking.send(new ActionPayload(1));
            }
        } else if (down) {
            ClientSandevistanState.holdTicks++;
            if (ClientSandevistanState.holdTicks == ClientSandevistanState.HOLD_TICKS) {
                ClientPlayNetworking.send(new ActionPayload(0));
            }
        } else {
            ClientSandevistanState.holdTicks = 0;
        }
        prevDown = down;
    }
}
