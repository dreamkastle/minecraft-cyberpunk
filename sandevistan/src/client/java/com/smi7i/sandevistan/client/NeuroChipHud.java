package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.ActionPayload;
import com.smi7i.sandevistan.ChipStatePayload;
import com.smi7i.sandevistan.HackListPayload;
import com.smi7i.sandevistan.HackRequestPayload;
import com.smi7i.sandevistan.HackState;
import com.smi7i.sandevistan.HackWarningPayload;
import com.smi7i.sandevistan.HeadImplantSlot;
import com.smi7i.sandevistan.ModItems;
import com.smi7i.sandevistan.NeuroChips;
import com.smi7i.sandevistan.SandevistanManager;
import com.smi7i.sandevistan.client.mech.MechClient;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Чипы нейро интерфейса: клавиша активации (по умолчанию I), мини-панель справа внизу (какой чип установлен,
 * работает ли он, перезарядка), открытие списка чипа «Взлом», предупреждение о попытке взлома и надпись,
 * пока твои импланты отключены чужим взломом.
 */
public final class NeuroChipHud {
    private static final int RED = 0xFF4650;

    private static KeyMapping activate;
    /** Тики до конца работы чипа и до конца перезарядки (приходят с сервера и тикают на клиенте). */
    private static int active;
    private static int cooldown;

    private NeuroChipHud() {}

    public static void init(KeyMapping.Category category) {
        activate = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.chip", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, category));
        ClientPlayNetworking.registerGlobalReceiver(ChipStatePayload.TYPE, (payload, context) -> {
            active = payload.active();
            cooldown = payload.cooldown();
        });
        ClientPlayNetworking.registerGlobalReceiver(HackListPayload.TYPE, (payload, context) ->
                Minecraft.getInstance().gui.setScreen(new HackScreen(HackScreen.parse(payload.data()))));
        ClientPlayNetworking.registerGlobalReceiver(HackWarningPayload.TYPE, (payload, context) ->
                HoloToast.show("!! ВЗЛОМ !!", () -> "Обнаружена попытка взлома ваших имплантов", RED));
        ClientTickEvents.END_CLIENT_TICK.register(NeuroChipHud::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "neuro_chip"), NeuroChipHud::render);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "hacked_banner"), NeuroChipHud::renderHacked);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) {
            active = 0;
            cooldown = 0;
            return;
        }
        if (active > 0) active--;
        if (cooldown > 0) cooldown--;
        int chip = NeuroChips.activeChip(mc.player);
        while (activate.consumeClick()) {
            if (chip == NeuroChips.OVERTIME && active <= 0 && cooldown <= 0) {
                ClientPlayNetworking.send(new ActionPayload(3));
            } else if (chip == NeuroChips.HACK && cooldown <= 0 && mc.gui.screen() == null) {
                ClientPlayNetworking.send(new HackRequestPayload()); // сервер пришлёт список целей
            }
        }
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !HeadImplantSlot.hasNeuroInterface(mc.player)) return;
        Font font = mc.font;
        int chip = NeuroChips.activeChip(mc.player);
        int accent = HoloTheme.accent();
        long now = System.currentTimeMillis();

        int w = 140, h = 30;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = sw - w - 10;
        // Над панелью костюма / пульта, чтобы не накладываться.
        int y = sh - 10 - h - panelAboveOffset(mc);

        float alpha = HoloUi.flicker(now, 31);
        HoloUi.panel(g, x, y, x + w, y + h, accent, alpha);

        if (chip == NeuroChips.NONE) {
            HoloUi.text(g, font, "ЧИП НЕ УСТАНОВЛЕН", x + 6, y + 11, 0x7FA8A0, alpha);
            return;
        }

        HoloUi.text(g, font, chip == NeuroChips.HACK ? "ЧИП 2 // ВЗЛОМ" : "ЧИП 1 // ОВЕРТАЙМ", x + 6, y + 4, accent, alpha);

        float fraction;
        int color;
        String status;
        boolean overtime = chip == NeuroChips.OVERTIME && active > 0
                && ClientSandevistanState.active && ClientSandevistanState.ticksLeft <= 0;
        int total = chip == NeuroChips.HACK ? SandevistanManager.HACK_COOLDOWN_TICKS : SandevistanManager.CHIP_COOLDOWN_TICKS;
        if (active > 0) {
            fraction = active / (float) SandevistanManager.CHIP_ACTIVE_TICKS;
            color = overtime ? RED : HoloTheme.glow();
            boolean blink = (now / 250) % 2 == 0;
            status = overtime ? (blink ? "ОВЕРТАЙМ!" : "") : "АКТИВЕН";
            status += String.format(Locale.ROOT, " %.1fс", active / 20.0F);
        } else if (cooldown > 0) {
            fraction = 1.0F - cooldown / (float) total;
            color = 0x9FB8B0;
            int sec = (cooldown + 19) / 20;
            status = String.format(Locale.ROOT, "ЗАРЯДКА %d:%02d", sec / 60, sec % 60);
        } else {
            fraction = 1.0F;
            color = HoloTheme.glow();
            status = "ГОТОВ [" + HoloUi.key(activate) + "]";
        }

        int bx = x + 6, by = y + 16, bw = w - 12;
        g.fill(bx, by, bx + bw, by + 4, 0xFF10201E);
        g.fill(bx, by, bx + Math.round(bw * Math.max(0, Math.min(1, fraction))), by + 4, 0xFF000000 | color);
        HoloUi.text(g, font, status, bx, by + 5, color, alpha);
    }

    /** Пока твои импланты отключены чужим взломом — надпись сверху по центру с обратным отсчётом. */
    private static void renderHacked(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !HackState.isHacked(mc.player)) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int w = 220, h = 26;
        int x = sw / 2 - w / 2, y = 10;
        float alpha = HoloUi.flicker(System.currentTimeMillis(), 17);
        HoloUi.panel(g, x, y, x + w, y + h, RED, alpha);
        HoloUi.centeredText(g, mc.font, "ИМПЛАНТЫ ОТКЛЮЧЕНЫ // ВЗЛОМ", x + w / 2, y + 5, RED, alpha);
        HoloUi.centeredText(g, mc.font, String.format(Locale.ROOT, "восстановление через %.0f с",
                HackState.remainingTicks(mc.player) / 20.0F), x + w / 2, y + 15, 0xE8B8B8, 0.9F * alpha);
    }

    /** Насколько поднять мини-панель, если справа внизу уже стоит панель костюма или вызова пультом. */
    private static int panelAboveOffset(Minecraft mc) {
        if (MechClient.localInMech()) return 136 + 6;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).is(ModItems.MECH_REMOTE)) return 30 + 6;
        }
        return 0;
    }
}
