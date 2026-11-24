package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.ActionPayload;
import com.smi7i.sandevistan.BladeSwingPayload;
import com.smi7i.sandevistan.MantisBlades;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/** Клинки богомола: клавиша «достать / убрать» (по умолчанию M) и слой отрисовки на игроке. */
public final class MantisBladeClient {
    private static KeyMapping toggle;
    private static boolean attackWasDown;
    /** Каким клинком бить следующим, когда достаны оба (по очереди: правый, левый, правый, ...). */
    private static boolean leftNext;

    private MantisBladeClient() {}

    /**
     * Какой рукой взмахнуть при ударе. Клинки достаны оба — бьют по очереди (левый и правый), достан один —
     * бьёт он; клинки убраны — как обычно, основной рукой. Левый клинок — это левая рука игрока.
     */
    public static InteractionHand nextSwingHand(Player player) {
        if (!MantisBlades.isExtended(player)) return InteractionHand.MAIN_HAND;
        boolean left = MantisBlades.hasLeft(player);
        boolean right = MantisBlades.hasRight(player);
        boolean useLeft;
        if (left && right) {
            useLeft = leftNext;
            leftNext = !leftNext;
        } else {
            useLeft = left;
        }
        boolean leftIsMain = player.getMainArm() == HumanoidArm.LEFT;
        return useLeft == leftIsMain ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

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
