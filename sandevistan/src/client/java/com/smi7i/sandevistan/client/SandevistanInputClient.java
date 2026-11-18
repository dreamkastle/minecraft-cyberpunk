package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.ActionPayload;
import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class SandevistanInputClient implements ClientModInitializer {
    public static KeyMapping TOGGLE;
    /** Клавиша «Дефрагментации памяти» (очистка ячейки киберпсихоза), по умолчанию N. */
    public static KeyMapping DEFRAG;
    /** Открыть настройки мода (по умолчанию не назначена; ещё есть кнопка в ModMenu). */
    public static KeyMapping SETTINGS;
    private boolean prevDown;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sandevistan", "main"));
        TOGGLE = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        DEFRAG = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.defrag", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
        com.smi7i.sandevistan.client.mech.MechClient.init(category); // костюм-мех: клавиша B, отрисовка, подсказка
        SETTINGS = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.settings", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE,
                (payload, context) -> ClientSandevistanState.update(payload.active(), payload.ticksLeft(), payload.streak(),
                        payload.overheatTicks(), payload.psychosisTicks(), payload.defragTicks()));

        SandevistanHud.init();
        PsychosisController.init();
        ImplantBackLayer.init();
        SandevistanBars.init();
        SlowMotionClient.init();
        ClientZones.init();
        PlayerFreeze.init();
        PsychosisSoul.init();
        SandevistanSounds.init();
        SandevistanFlash.init();
        SandevistanCooldownPopup.init();
        Holo3D.init();
        HitBurst.init();
        com.smi7i.sandevistan.client.compat.EmfCompat.init();
        SandevistanAfterimages.init();
        SandevistanGlitchHud.init();
        SandevistanHudDistortion.init();

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientSandevistanState.reset());
    }

    private void tick(Minecraft client) {
        if (client.player == null) {
            ClientSandevistanState.holdTicks = 0;
            prevDown = false;
            ClientSandevistanState.tickVisuals();
            return;
        }

        boolean hasImplant = ImplantSlot.isImplant(client.player.getAttachedOrCreate(ModAttachments.IMPLANT));
        boolean down = TOGGLE.isDown() && client.gui.screen() == null;

        if (ClientSandevistanState.psychosis()) {
            // Во время приступа имплант не слушается.
            ClientSandevistanState.holdTicks = 0;
        } else if (!ClientSandevistanState.active && ClientSandevistanState.recharging()) {
            // Остывает: ничего не заряжается и не зеленеет — только окошко ошибки при нажатии.
            ClientSandevistanState.holdTicks = 0;
            if (down && !prevDown && hasImplant) {
                SandevistanCooldownPopup.show();
            }
        } else if (ClientSandevistanState.active) {
            // Уже работает: новое нажатие V выключает имплант.
            ClientSandevistanState.holdTicks = 0;
            if (down && !prevDown) {
                ClientPlayNetworking.send(new ActionPayload(1));
            }
        } else if (down && hasImplant) {
            ClientSandevistanState.holdTicks++;
            if (ClientSandevistanState.holdTicks == ClientSandevistanState.HOLD_TICKS) {
                ClientPlayNetworking.send(new ActionPayload(0));
            }
        } else {
            ClientSandevistanState.holdTicks = 0;
        }
        prevDown = down;

        while (SETTINGS.consumeClick()) {
            client.gui.setScreen(new SandevistanConfigScreen(null));
        }
        while (DEFRAG.consumeClick()) {
            if (ClientSandevistanState.defragReady()) {
                client.gui.setScreen(new MemoryDefragScreen());
            } else if (ClientSandevistanState.streak <= 0) {
                HoloToast.show("ПАМЯТЬ В ПОРЯДКЕ", () -> "нечего очищать", 0x2AE0C0);
            } else {
                HoloToast.show("ДЕФРАГМЕНТАЦИЯ НЕДОСТУПНА", () -> {
                    int s = (ClientSandevistanState.defragTicks + 19) / 20;
                    return String.format("доступна через %d:%02d", s / 60, s % 60);
                }, 0xFF3A4A);
            }
        }

        ClientSandevistanState.tickVisuals();
    }
}
