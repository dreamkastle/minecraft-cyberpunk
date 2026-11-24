package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.HackState;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.ModItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Продвинутый нейро интерфейс (клавиша K): экран на весь экран со всеми существами в радиусе 100 блоков.
 * Вкладки «Мобы» и «Игроки». Не поместившиеся в список строки уходят на следующую страницу (стрелки внизу).
 * Если мобов много (больше GROUP_THRESHOLD), они показываются не по одному, а по видам: «Зомби 12».
 * Клик по строке игрока раскрывает её: видно, какой у него нейро интерфейс. Данные берутся из тех существ,
 * что загружены у клиента, — обычно это весь радиус 100 блоков.
 */
public class AdvancedNeuroScreen extends Screen {
    private static final double RADIUS = 100.0;
    /** Если мобов больше этого числа — показываем их сгруппированно по видам. */
    private static final int GROUP_THRESHOLD = 15;
    private static final int PANEL_W = 400;
    private static final int ROW_H = 20;
    private static final int DETAIL_H = 14;

    private record MobRow(String name, int count, float hp, float max, int dist) {}

    private record PlayerRow(String name, int dist, float hp, float max, String neuro, boolean hacked) {}

    private final List<MobRow> mobs = new ArrayList<>();
    private final List<PlayerRow> players = new ArrayList<>();
    private int mobTotal;
    private boolean playersTab;
    private int page;
    private final Set<String> expanded = new HashSet<>();
    private int ageTicks;

    // Области для кликов: заполняются при рисовании.
    private int tabMobsX0, tabPlayersX0, tabY0, tabW, tabH;
    private int prevX0, nextX0, pagerY0;
    private final List<int[]> playerHits = new ArrayList<>(); // y0, y1, индекс игрока

    public AdvancedNeuroScreen() {
        super(Component.literal("Продвинутый нейро интерфейс"));
        scan();
    }

    // ---------------------------------------------------------------- данные

    private void scan() {
        Minecraft mc = Minecraft.getInstance();
        mobs.clear();
        players.clear();
        mobTotal = 0;
        if (mc.player == null || mc.level == null) return;
        Player self = mc.player;
        AABB box = self.getBoundingBox().inflate(RADIUS);
        List<LivingEntity> found = new ArrayList<>(mc.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != self && e.isAlive() && !(e instanceof ArmorStand) && !e.isSpectator()));
        found.removeIf(e -> e.distanceTo(self) > RADIUS);
        found.sort(Comparator.comparingDouble(e -> e.distanceTo(self)));

        List<LivingEntity> mobList = new ArrayList<>();
        for (LivingEntity e : found) {
            if (e instanceof Player p) {
                players.add(new PlayerRow(p.getName().getString(), Math.round(p.distanceTo(self)), p.getHealth(),
                        p.getMaxHealth(), neuroName(p), HackState.isHacked(p)));
            } else if (e instanceof Mob) {
                mobList.add(e);
            }
        }
        mobTotal = mobList.size();
        if (mobList.size() > GROUP_THRESHOLD) {
            // Много мобов: «Зомби 12» (ближайший из вида — его расстояние).
            Map<String, int[]> groups = new LinkedHashMap<>(); // имя -> {число, ближайшее расстояние}
            for (LivingEntity e : mobList) { // список уже по возрастанию расстояния
                String name = e.getType().getDescription().getString();
                int dist = Math.round(e.distanceTo(self));
                int[] g = groups.get(name);
                if (g == null) groups.put(name, new int[]{1, dist}); else g[0]++;
            }
            for (Map.Entry<String, int[]> g : groups.entrySet()) {
                mobs.add(new MobRow(g.getKey(), g.getValue()[0], 0, 0, g.getValue()[1]));
            }
        } else {
            for (LivingEntity e : mobList) {
                mobs.add(new MobRow(e.getName().getString(), 1, e.getHealth(), e.getMaxHealth(), Math.round(e.distanceTo(self))));
            }
        }
    }

    private static String neuroName(Player p) {
        ItemStack head = p.getAttachedOrCreate(ModAttachments.HEAD_IMPLANT);
        if (head.is(ModItems.ADVANCED_NEURO_INTERFACE)) return "Продвинутый нейро интерфейс";
        if (head.is(ModItems.NEURO_INTERFACE)) return "Нейро интерфейс";
        return "нет";
    }

    private int rowsPerPage() {
        return Math.max(4, Math.min(12, (height - 150) / ROW_H));
    }

    private int pageCount() {
        int total = playersTab ? players.size() : mobs.size();
        return Math.max(1, (total + rowsPerPage() - 1) / rowsPerPage());
    }

    // ---------------------------------------------------------------- события

    @Override
    public void tick() {
        ageTicks++;
        if (ageTicks % 10 == 0) scan();
        page = Math.min(page, pageCount() - 1);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        if (my >= tabY0 && my <= tabY0 + tabH) {
            if (mx >= tabMobsX0 && mx <= tabMobsX0 + tabW) { switchTab(false); return true; }
            if (mx >= tabPlayersX0 && mx <= tabPlayersX0 + tabW) { switchTab(true); return true; }
        }
        if (my >= pagerY0 && my <= pagerY0 + 14) {
            if (mx >= prevX0 && mx <= prevX0 + 22 && page > 0) { page--; return true; }
            if (mx >= nextX0 && mx <= nextX0 + 22 && page < pageCount() - 1) { page++; return true; }
        }
        if (playersTab) {
            for (int[] hit : playerHits) {
                if (my >= hit[0] && my <= hit[1] && mx >= panelX() + 4 && mx <= panelX() + PANEL_W - 4) {
                    String name = players.get(hit[2]).name();
                    if (!expanded.remove(name)) expanded.add(name);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void switchTab(boolean players) {
        if (playersTab != players) {
            playersTab = players;
            page = 0;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- рисование

    private int panelX() {
        return width / 2 - PANEL_W / 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0xD0050A0A);
        int grid = HoloUi.argb(0.05F, HoloTheme.accent());
        for (int x = 0; x < width; x += 16) g.fill(x, 0, x + 1, height, grid);
        for (int y = 0; y < height; y += 16) g.fill(0, y, width, y + 1, grid);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBackground(g, mouseX, mouseY, a);
        int accent = HoloTheme.accent();
        int rows = rowsPerPage();
        int px = panelX();
        int ph = 62 + rows * ROW_H + DETAIL_H + 26;
        int py = Math.max(6, height / 2 - ph / 2);

        g.fill(px, py, px + PANEL_W, py + ph, 0xF00B1514);
        HoloUi.panel(g, px, py, px + PANEL_W, py + ph, accent, 0.9F);
        HoloUi.text(g, font, "ПРОДВИНУТЫЙ НЕЙРО ИНТЕРФЕЙС // СКАН 100 М", px + 10, py + 9, accent, 1.0F);
        String summary = "мобов " + mobTotal + "   игроков " + players.size();
        g.text(font, summary, px + PANEL_W - 10 - font.width(summary), py + 9, 0xFFB8FFF0, false);
        g.fill(px + 8, py + 22, px + PANEL_W - 8, py + 23, HoloUi.argb(0.4F, accent));

        // Вкладки-фильтры.
        tabY0 = py + 28;
        tabH = 16;
        tabW = 100;
        tabMobsX0 = px + 10;
        tabPlayersX0 = px + 10 + tabW + 6;
        drawTab(g, tabMobsX0, "МОБЫ", !playersTab, accent);
        drawTab(g, tabPlayersX0, "ИГРОКИ", playersTab, accent);

        // Список.
        playerHits.clear();
        int listTop = py + 52;
        int from = page * rows;
        if (playersTab) {
            drawPlayers(g, px, listTop, from, rows, mouseY);
        } else {
            drawMobs(g, px, listTop, from, rows);
        }

        // Листание страниц.
        pagerY0 = py + ph - 20;
        prevX0 = px + PANEL_W / 2 - 60;
        nextX0 = px + PANEL_W / 2 + 38;
        drawArrow(g, prevX0, pagerY0, "<", page > 0, accent);
        drawArrow(g, nextX0, pagerY0, ">", page < pageCount() - 1, accent);
        HoloUi.centeredText(g, font, "стр. " + (page + 1) + " / " + pageCount(), px + PANEL_W / 2, pagerY0 + 3, 0xB8FFF0, 1.0F);
        String hint = playersTab ? "ЛКМ по игроку — подробнее" : mobTotal > GROUP_THRESHOLD ? "мобов много — показаны по видам" : "";
        g.text(font, hint, px + 10, pagerY0 + 3, 0xFF7FA8A0, false);
    }

    private void drawTab(GuiGraphicsExtractor g, int x, String label, boolean active, int accent) {
        g.fill(x, tabY0, x + tabW, tabY0 + tabH, active ? HoloUi.argb(0.35F, accent) : HoloUi.argb(0.10F, accent));
        HoloUi.panel(g, x, tabY0, x + tabW, tabY0 + tabH, accent, active ? 0.9F : 0.4F);
        HoloUi.centeredText(g, font, label, x + tabW / 2, tabY0 + 4, active ? 0xFFFFFF : accent, 1.0F);
    }

    private void drawArrow(GuiGraphicsExtractor g, int x, int y, String label, boolean enabled, int accent) {
        g.fill(x, y, x + 22, y + 14, HoloUi.argb(enabled ? 0.25F : 0.07F, accent));
        HoloUi.centeredText(g, font, label, x + 11, y + 3, enabled ? 0xFFFFFF : 0x557770, 1.0F);
    }

    private void drawMobs(GuiGraphicsExtractor g, int px, int top, int from, int rows) {
        if (mobs.isEmpty()) {
            HoloUi.centeredText(g, font, "Мобов в радиусе 100 м нет", px + PANEL_W / 2, top + 14, 0x7FA8A0, 1.0F);
            return;
        }
        for (int i = 0; i < rows && from + i < mobs.size(); i++) {
            MobRow m = mobs.get(from + i);
            int ry = top + i * ROW_H;
            String title = m.count() > 1 ? m.name() + " " + m.count() : m.name();
            g.text(font, font.plainSubstrByWidth(title, 190), px + 12, ry + 5, 0xFFE8FFFA, false);
            if (m.count() == 1) {
                float ratio = m.max() <= 0 ? 0 : Math.max(0, Math.min(1, m.hp() / m.max()));
                int color = ratio > 0.5F ? 0x39FF6A : ratio > 0.25F ? 0xFFD23C : 0xFF4650;
                int bx = px + 214;
                g.fill(bx, ry + 7, bx + 60, ry + 12, 0xFF10201E);
                g.fill(bx, ry + 7, bx + Math.round(60 * ratio), ry + 12, 0xFF000000 | color);
                g.text(font, Math.round(m.hp()) + "/" + Math.round(m.max()), px + 282, ry + 5, 0xFF000000 | color, false);
            }
            String d = m.dist() + " м";
            g.text(font, d, px + PANEL_W - 12 - font.width(d), ry + 5, 0xFF9FD8CF, false);
            g.fill(px + 8, ry + ROW_H - 2, px + PANEL_W - 8, ry + ROW_H - 1, HoloUi.argb(0.12F, HoloTheme.accent()));
        }
    }

    private void drawPlayers(GuiGraphicsExtractor g, int px, int top, int from, int rows, int mouseY) {
        if (players.isEmpty()) {
            HoloUi.centeredText(g, font, "Игроков в радиусе 100 м нет", px + PANEL_W / 2, top + 14, 0x7FA8A0, 1.0F);
            return;
        }
        int accent = HoloTheme.accent();
        int ry = top;
        for (int i = 0; i < rows && from + i < players.size(); i++) {
            PlayerRow p = players.get(from + i);
            boolean open = expanded.contains(p.name());
            int h = ROW_H + (open ? DETAIL_H : 0);
            playerHits.add(new int[]{ry, ry + h, from + i});
            if (open) g.fill(px + 4, ry, px + PANEL_W - 4, ry + h, HoloUi.argb(0.10F, accent));
            g.text(font, open ? "v" : ">", px + 12, ry + 5, accent | 0xFF000000, false);
            g.text(font, font.plainSubstrByWidth(p.name(), 190), px + 24, ry + 5, 0xFFE8FFFA, false);
            float ratio = p.max() <= 0 ? 0 : Math.max(0, Math.min(1, p.hp() / p.max()));
            int color = ratio > 0.5F ? 0x39FF6A : ratio > 0.25F ? 0xFFD23C : 0xFF4650;
            int bx = px + 214;
            g.fill(bx, ry + 7, bx + 60, ry + 12, 0xFF10201E);
            g.fill(bx, ry + 7, bx + Math.round(60 * ratio), ry + 12, 0xFF000000 | color);
            g.text(font, Math.round(p.hp()) + "/" + Math.round(p.max()), px + 282, ry + 5, 0xFF000000 | color, false);
            String d = p.dist() + " м";
            g.text(font, d, px + PANEL_W - 12 - font.width(d), ry + 5, 0xFF9FD8CF, false);
            if (open) {
                String info = "Нейро интерфейс: " + p.neuro() + (p.hacked() ? "  (отключён взломом)" : "");
                g.text(font, font.plainSubstrByWidth(info, PANEL_W - 40), px + 24, ry + ROW_H - 2, 0xFFB8FFF0, false);
            }
            g.fill(px + 8, ry + h - 2, px + PANEL_W - 8, ry + h - 1, HoloUi.argb(0.12F, accent));
            ry += h;
        }
    }
}
