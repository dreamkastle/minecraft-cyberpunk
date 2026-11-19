package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.HeadImplantSlot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

/**
 * Нейро интерфейс (имплант головы). Клавиша K включает список в углу экрана: все живые существа в радиусе 20 блоков
 * с именем, здоровьем и дистанцией, а у игроков с таким же интерфейсом — значок NI. Заготовка под будущие нейро импланты.
 */
public final class NeuroHud {
    private static final double RADIUS = 20.0;
    private static final int MAX_ROWS = 12;

    private static KeyMapping toggle;
    private static boolean on;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static int totalFound;
    private static int neuroCount;

    private record Entry(String name, float hp, float max, int dist, boolean neuro) {}

    private NeuroHud() {}

    public static void init(KeyMapping.Category category) {
        toggle = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.neuro", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        ClientTickEvents.END_CLIENT_TICK.register(NeuroHud::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "neuro_interface"), NeuroHud::render);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            on = false;
            ENTRIES.clear();
            return;
        }
        boolean has = HeadImplantSlot.hasNeuroInterface(mc.player);
        while (toggle.consumeClick()) {
            if (has) on = !on;
        }
        if (!has) on = false; // сняли имплант — интерфейс гаснет
        if (!on) {
            ENTRIES.clear();
            return;
        }
        if (mc.player.tickCount % 4 != 0) return;

        Player self = mc.player;
        AABB box = self.getBoundingBox().inflate(RADIUS);
        List<LivingEntity> found = new ArrayList<>(mc.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != self && e.isAlive() && !(e instanceof ArmorStand) && !e.isSpectator()));
        found.removeIf(e -> e.distanceTo(self) > RADIUS);
        found.sort(Comparator.comparingDouble(e -> e.distanceTo(self)));

        ENTRIES.clear();
        totalFound = found.size();
        neuroCount = 0;
        for (LivingEntity e : found) {
            boolean neuro = e instanceof Player p && HeadImplantSlot.hasNeuroInterface(p);
            if (neuro) neuroCount++;
            if (ENTRIES.size() < MAX_ROWS) {
                ENTRIES.add(new Entry(e.getName().getString(), e.getHealth(), e.getMaxHealth(),
                        Math.round(e.distanceTo(self)), neuro));
            }
        }
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        if (!on) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int x = 8, y = 8, w = 232;
        int rows = Math.max(1, ENTRIES.size());
        int h = 20 + rows * 11 + 4;

        HoloUi.panel(g, x, y, x + w, y + h, HoloTheme.accent(), 0.9F);
        HoloUi.text(g, font, "НЕЙРО ИНТЕРФЕЙС // СКАН", x + 5, y + 4, HoloTheme.accent(), 1.0F);
        String summary = "цели " + totalFound + "  NI " + neuroCount;
        g.text(font, summary, x + w - 5 - font.width(summary), y + 4, 0xFFB8FFF0, false);

        if (ENTRIES.isEmpty()) {
            g.text(font, "никого рядом", x + 5, y + 17, 0xFF7FA8A0, false);
            return;
        }
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry e = ENTRIES.get(i);
            int ry = y + 17 + i * 11;
            g.text(font, font.plainSubstrByWidth(e.name(), 68), x + 5, ry, 0xFFE8FFFA, false);

            // Полоска здоровья.
            float ratio = e.max() <= 0 ? 0 : Math.max(0, Math.min(1, e.hp() / e.max()));
            int color = ratio > 0.5F ? 0x39FF6A : ratio > 0.25F ? 0xFFD23C : 0xFF4650;
            int bx = x + 78;
            g.fill(bx, ry + 2, bx + 44, ry + 7, 0xFF10201E);
            g.fill(bx, ry + 2, bx + Math.round(44 * ratio), ry + 7, 0xFF000000 | color);

            g.text(font, Math.round(e.hp()) + "/" + Math.round(e.max()), x + 128, ry, 0xFF000000 | color, false);
            g.text(font, e.dist() + " м", x + 178, ry, 0xFF9FD8CF, false);
            if (e.neuro()) {
                g.fill(x + 206, ry - 1, x + w - 5, ry + 9, HoloUi.argb(0.35F, HoloTheme.accent()));
                g.text(font, "NI", x + 210, ry, 0xFFB8FFF0, false);
            }
        }
    }
}
