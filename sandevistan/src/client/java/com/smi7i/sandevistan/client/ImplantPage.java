package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.HandImplantSlot;
import com.smi7i.sandevistan.HeadImplantSlot;
import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.NeuroChipSlot;
import com.smi7i.sandevistan.mixin.client.AbstractContainerScreenAccessor;
import com.smi7i.sandevistan.mixin.client.ScreenInvoker;
import com.smi7i.sandevistan.mixin.client.SlotAccessor;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Страница «Импланты» в обычном инвентаре. Вкладка-кнопка справа от окна переключает страницу: обычные слоты
 * (броня, крафт, левая рука) уходят за экран, а четыре слота имплантов (голова, спина, две руки) встают на силуэт
 * тела. Всё это происходит на том же экране и том же меню, поэтому сервер ничего не знает о страницах.
 */
public final class ImplantPage {
    public static boolean open;

    private static Screen lastScreen;
    private static final int OFF = -2000;

    /** Слоты обычной страницы, которые прячем на странице имплантов: результат и сетка крафта, броня, левая рука. */
    private static final int[] VANILLA_HIDDEN = {0, 1, 2, 3, 4, 5, 6, 7, 8, 45};
    private static final Map<Integer, int[]> ORIGINAL = new HashMap<>();

    // Места слотов на странице (левый верхний угол предмета 16x16 относительно окна инвентаря).
    private static final int HEAD_X = 80, HEAD_Y = 12;
    private static final int SPINE_X = 80, SPINE_Y = 40;
    private static final int LEFT_X = 48, LEFT_Y = 40;
    private static final int RIGHT_X = 112, RIGHT_Y = 40;
    private static final int CHIP_X = 126, CHIP_Y = 12;

    private ImplantPage() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen inventory)) return;
            if (inventory != lastScreen) { // новое открытие инвентаря — начинаем с обычной страницы
                lastScreen = inventory;
                open = false;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            AbstractContainerMenu menu = mc.player.inventoryMenu;
            apply(menu, open);

            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) (Object) inventory;
            Button tab = Button.builder(label(), b -> {
                open = !open;
                apply(menu, open);
                b.setMessage(label());
                setVanillaWidgetsHidden(inventory, b, open);
            }).bounds(acc.sandevistan$getLeftPos() + 176 + 4, acc.sandevistan$getTopPos() + 6, 76, 20).build();
            ((ScreenInvoker) (Object) inventory).sandevistan$addRenderableWidget(tab);
            setVanillaWidgetsHidden(inventory, tab, open);
        });
    }

    private static Component label() {
        return Component.literal(open ? "\u2190 Инвентарь" : "Импланты \u2192");
    }

    /** Кнопку рецептов и прочие виджеты обычной страницы прячем, пока открыта страница имплантов. */
    private static void setVanillaWidgetsHidden(Screen screen, AbstractWidget keep, boolean hidden) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != keep) widget.visible = !hidden;
        }
    }

    /** Расставляет слоты под выбранную страницу. */
    private static void apply(AbstractContainerMenu menu, boolean page) {
        for (int index : VANILLA_HIDDEN) {
            if (index >= menu.slots.size()) continue;
            Slot slot = menu.getSlot(index);
            if (slot.x > -1000) ORIGINAL.computeIfAbsent(index, i -> new int[]{slot.x, slot.y});
            int[] original = ORIGINAL.get(index);
            if (original == null) continue;
            move(slot, page ? OFF : original[0], page ? OFF : original[1]);
        }
        place(menu, HeadImplantSlot.MENU_INDEX, page, HEAD_X, HEAD_Y);
        place(menu, ImplantSlot.MENU_INDEX, page, SPINE_X, SPINE_Y);
        place(menu, HandImplantSlot.MENU_INDEX_1, page, LEFT_X, LEFT_Y);
        place(menu, HandImplantSlot.MENU_INDEX_2, page, RIGHT_X, RIGHT_Y);
        place(menu, NeuroChipSlot.MENU_INDEX, page, CHIP_X, CHIP_Y);
    }

    private static void place(AbstractContainerMenu menu, int index, boolean page, int x, int y) {
        if (index >= menu.slots.size()) return;
        move(menu.getSlot(index), page ? x : OFF, page ? y : OFF);
    }

    private static void move(Slot slot, int x, int y) {
        SlotAccessor accessor = (SlotAccessor) slot;
        accessor.sandevistan$setX(x);
        accessor.sandevistan$setY(y);
    }

    /** Рисует страницу имплантов поверх верхней части обычного инвентаря (до слотов и предметов). */
    public static void drawPage(GuiGraphicsExtractor g, int left, int top) {
        Minecraft mc = Minecraft.getInstance();
        long t = System.currentTimeMillis();
        int x0 = left + 7, y0 = top + 7, x1 = left + 169, y1 = top + 82;

        g.fill(x0, y0, x1, y1, 0xFF0B1514);
        HoloUi.panel(g, x0, y0, x1, y1, HoloTheme.accent(), 0.9F);

        // Силуэт тела: голова, шея, туловище, две руки.
        int line = HoloUi.argb(0.35F, HoloTheme.accent());
        outline(g, left + 74, top + 8, left + 102, top + 32, line);     // голова
        g.fill(left + 86, top + 32, left + 90, top + 36, line);          // шея
        outline(g, left + 70, top + 36, left + 106, top + 76, line);     // туловище
        outline(g, left + 42, top + 36, left + 68, top + 70, line);      // левая рука
        outline(g, left + 108, top + 36, left + 134, top + 70, line);    // правая рука

        g.fill(left + 102, top + 20, left + CHIP_X - 1, top + 21, line); // связь головы с чипом

        // Подписи.
        if (mc.font != null) {
            HoloUi.text(g, mc.font, "ГОЛОВА", left + 12, top + 17, HoloTheme.accent(), 0.85F);
            HoloUi.centeredText(g, mc.font, "ЧИП", left + CHIP_X + 8, top + CHIP_Y + 20, HoloTheme.accent(), 0.85F);
            HoloUi.centeredText(g, mc.font, "СПИНА", left + 88, top + 62, HoloTheme.accent(), 0.85F);
            HoloUi.centeredText(g, mc.font, "ЛЕВ.", left + 55, top + 62, HoloTheme.accent(), 0.85F);
            HoloUi.centeredText(g, mc.font, "ПРАВ.", left + 121, top + 62, HoloTheme.accent(), 0.85F);
        }

        // Окошки слотов (предметы рисует сама игра, потому что слоты стоят на этих местах).
        AbstractContainerMenu menu = mc.player != null ? mc.player.inventoryMenu : null;
        frame(g, menu, HeadImplantSlot.MENU_INDEX, left + HEAD_X - 1, top + HEAD_Y - 1, 2, t);
        frame(g, menu, NeuroChipSlot.MENU_INDEX, left + CHIP_X - 1, top + CHIP_Y - 1, 3, t);
        frame(g, menu, ImplantSlot.MENU_INDEX, left + SPINE_X - 1, top + SPINE_Y - 1, 0, t);
        frame(g, menu, HandImplantSlot.MENU_INDEX_1, left + LEFT_X - 1, top + LEFT_Y - 1, 1, t);
        frame(g, menu, HandImplantSlot.MENU_INDEX_2, left + RIGHT_X - 1, top + RIGHT_Y - 1, 1, t);
    }

    private static void frame(GuiGraphicsExtractor g, AbstractContainerMenu menu, int index, int x, int y, int kind, long t) {
        boolean filled = menu != null && menu.slots.size() > index && menu.getSlot(index).hasItem();
        ImplantSlotUi.draw(g, x, y, filled, t, kind);
    }

    private static void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }
}
