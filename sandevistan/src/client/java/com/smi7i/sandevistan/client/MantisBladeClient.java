package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.ActionPayload;
import com.smi7i.sandevistan.BladeSwingPayload;
import com.smi7i.sandevistan.MantisBlades;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/** Клинки богомола: клавиша «достать / убрать» (по умолчанию M) и слой отрисовки на игроке. */
public final class MantisBladeClient {
    private static KeyMapping toggle;
    private static boolean attackWasDown;

    private MantisBladeClient() {}

    public static void init(KeyMapping.Category category) {
        toggle = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.blades", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            while (toggle.consumeClick()) {
                ClientPlayNetworking.send(new ActionPayload(4)); // сервер сам проверит клинки, приступ, костюм
            }
            // Взмах клинков мимо: ЛКМ нажата, а на прицеле нет существа. Звук попадания играет сервер при ударе.
            boolean down = mc.options.keyAttack.isDown();
            if (down && !attackWasDown && mc.gui.screen() == null && MantisBlades.isExtended(mc.player)
                    && !(mc.hitResult instanceof EntityHitResult)) {
                ClientPlayNetworking.send(new BladeSwingPayload());
            }
            attackWasDown = down;
        });
        MantisBladeLayer.init();
    }
}
