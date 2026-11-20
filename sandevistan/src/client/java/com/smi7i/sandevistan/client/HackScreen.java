package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.HackManager;
import com.smi7i.sandevistan.HackResultPayload;
import com.smi7i.sandevistan.HackStartPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Чип «Взлом». Сначала список игроков с нейро интерфейсом в радиусе 500 блоков (ник и их импланты, слева череп),
 * ЛКМ по черепу запускает мини-игру: провести кружок по узким зигзагообразным коридорам до финиша, не коснувшись
 * стен. Чем больше у цели имплантов, тем уже и длиннее лабиринт.
 */
public class HackScreen extends Screen {
    public record Entry(String name, int mask) {}

    private enum Phase { LIST, READY, RUNNING, FAILED, SUCCESS }

    private static final int RED = 0xFF4650;
    private static final int ROW_H = 26;
    private static final int PANEL_W = 330;
    private static final int MAX_ROWS = 8;
    private static final int R = 4; // радиус кружка

    /** Череп 11x11 (X — пиксель), рисуется в масштабе 2. */
    private static final String[] SKULL = {
            "...XXXXX...",
            "..XXXXXXX..",
            ".XXXXXXXXX.",
            ".XXXXXXXXX.",
            ".X..XXX..X.",
            ".X..XXX..X.",
            ".XXXXXXXXX.",
            "..XXX.XXX..",
            "...XXXXX...",
            "...X.X.X...",
            "...X.X.X..."};

    private final List<Entry> entries;
    private Phase phase = Phase.LIST;
    private Entry target;
    private boolean started;
    private boolean resultSent;
    private int endTicks;
    private int ageTicks;

    // Лабиринт.
    private final List<int[]> segments = new ArrayList<>(); // x1, y1, x2, y2
    private int halfWidth;
    private int startX, startY, endX, endY;
    private int prevX, prevY;

    public HackScreen(List<Entry> entries) {
        super(Component.literal("Взлом"));
        this.entries = entries;
    }

    /** Разбор строки «ник\tмаска» через перевод строки, как её присылает сервер. */
    public static List<Entry> parse(String data) {
        List<Entry> list = new ArrayList<>();
        if (data == null || data.isEmpty()) return list;
        for (String line : data.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            try {
                list.add(new Entry(parts[0], Integer.parseInt(parts[1])));
            } catch (NumberFormatException ignored) {
                // битую строку пропускаем
            }
        }
        return list;
    }

    private static List<String> implantNames(int mask) {
        List<String> names = new ArrayList<>();
        if ((mask & HackManager.BIT_SANDEVISTAN) != 0) names.add("Сандевистан");
        int hands = ((mask & HackManager.BIT_HAND_LEFT) != 0 ? 1 : 0) + ((mask & HackManager.BIT_HAND_RIGHT) != 0 ? 1 : 0);
        if (hands == 1) names.add("Имплант руки");
        if (hands == 2) names.add("Импланты рук ×2");
        if ((mask & HackManager.BIT_BLADE_LEFT) != 0) names.add("Левый клинок богомола");
        if ((mask & HackManager.BIT_BLADE_RIGHT) != 0) names.add("Правый клинок богомола");
        if ((mask & HackManager.BIT_NEURO) != 0) names.add("Нейро интерфейс");
        if ((mask & HackManager.BIT_CHIP_OVERTIME) != 0) names.add("Чип: Овертайм");
        if ((mask & HackManager.BIT_CHIP_HACK) != 0) names.add("Чип: Взлом");
        return names;
    }

    // ---------------------------------------------------------------- список

    private int panelHeight() {
        return 44 + Math.max(1, Math.min(entries.size(), MAX_ROWS)) * ROW_H + (entries.size() > MAX_ROWS ? 12 : 4);
    }

    private int panelX() {
        return width / 2 - PANEL_W / 2;
    }

    private int panelY() {
        return height / 2 - panelHeight() / 2;
    }

    private int rowY(int i) {
        return panelY() + 34 + i * ROW_H;
    }

    private boolean overSkull(int i, double mx, double my) {
        int sx = panelX() + 8, sy = rowY(i);
        return mx >= sx - 2 && mx <= sx + 24 && my >= sy - 1 && my <= sy + 24;
    }

    // ---------------------------------------------------------------- лабиринт

    private void startAttempt(Entry entry) {
        target = entry;
        started = true;
        ClientPlayNetworking.send(new HackStartPayload(entry.name())); // с этого момента идёт кулдаун чипа
        buildMaze(Math.max(1, Math.min(6, Integer.bitCount(entry.mask()))));
        phase = Phase.READY;
    }

    private void buildMaze(int difficulty) {
        segments.clear();
        int mw = Math.min(width - 80, 460);
        int mh = Math.min(height - 130, 240);
        int left = width / 2 - mw / 2;
        int top = height / 2 - mh / 2 + 12;
        halfWidth = Math.max(8, 20 - difficulty * 2);
        int columns = 3 + difficulty;
        int dx = (mw - 2 * halfWidth) / (columns - 1);
        int yTop = top + halfWidth;
        int yBottom = top + mh - halfWidth;
        int lastX = 0, lastY = 0;
        for (int i = 0; i < columns; i++) {
            int x = left + halfWidth + i * dx;
            int fromY = i % 2 == 0 ? yTop : yBottom;
            int toY = i % 2 == 0 ? yBottom : yTop;
            if (i == 0) {
                startX = x;
                startY = fromY;
            }
            segments.add(new int[]{x, fromY, x, toY});
            lastX = x;
            lastY = toY;
            if (i < columns - 1) {
                segments.add(new int[]{x, toY, x + dx, toY});
            }
        }
        endX = lastX;
        endY = lastY;
    }

    /** Центр кружка не должен выходить за коридор (с запасом на радиус). */
    private boolean insideCorridor(int px, int py) {
        int margin = halfWidth - R;
        for (int[] s : segments) {
            if (px >= Math.min(s[0], s[2]) - margin && px <= Math.max(s[0], s[2]) + margin
                    && py >= Math.min(s[1], s[3]) - margin && py <= Math.max(s[1], s[3]) + margin) {
                return true;
            }
        }
        return false;
    }

    private void fail() {
        if (phase != Phase.RUNNING) return;
        phase = Phase.FAILED;
        endTicks = 0;
        sendResult(false);
    }

    private void success() {
        if (phase != Phase.RUNNING) return;
        phase = Phase.SUCCESS;
        endTicks = 0;
        sendResult(true);
    }

    private void sendResult(boolean ok) {
        if (resultSent) return;
        resultSent = true;
        ClientPlayNetworking.send(new HackResultPayload(ok));
    }

    // ---------------------------------------------------------------- события

    @Override
    public void tick() {
        ageTicks++;
        if ((phase == Phase.FAILED || phase == Phase.SUCCESS) && ++endTicks > 28) onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        if (phase == Phase.LIST) {
            int rows = Math.min(entries.size(), MAX_ROWS);
            for (int i = 0; i < rows; i++) {
                if (overSkull(i, event.x(), event.y())) {
                    startAttempt(entries.get(i));
                    return true;
                }
            }
        } else if (phase == Phase.READY) {
            int mx = (int) event.x(), my = (int) event.y();
            if (Math.hypot(mx - startX, my - startY) <= halfWidth + 6 && insideCorridor(mx, my)) {
                phase = Phase.RUNNING;
                prevX = mx;
                prevY = my;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Закрыли экран посреди попытки (Esc) — это провал: цель получит предупреждение. */
    @Override
    public void onClose() {
        if (started && !resultSent) sendResult(false);
        minecraft.gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- рисование

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
        if (phase == Phase.LIST) {
            drawList(g, mouseX, mouseY);
        } else {
            drawMaze(g, mouseX, mouseY);
        }
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int accent = HoloTheme.accent();
        int px = panelX(), py = panelY(), ph = panelHeight();
        g.fill(px, py, px + PANEL_W, py + ph, 0xF00B1514);
        HoloUi.panel(g, px, py, px + PANEL_W, py + ph, accent, 0.9F);
        HoloUi.text(g, font, "ВЗЛОМ // ЦЕЛИ В РАДИУСЕ 500 М", px + 10, py + 9, accent, 1.0F);
        String hint = "ЛКМ по черепу";
        g.text(font, hint, px + PANEL_W - 10 - font.width(hint), py + 9, 0xFF7FA8A0, false);
        g.fill(px + 8, py + 22, px + PANEL_W - 8, py + 23, HoloUi.argb(0.4F, accent));

        if (entries.isEmpty()) {
            HoloUi.centeredText(g, font, "Целей с нейро интерфейсом поблизости нет", px + PANEL_W / 2, py + 40, 0x7FA8A0, 1.0F);
            return;
        }
        int rows = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            Entry e = entries.get(i);
            int ry = rowY(i);
            boolean hover = overSkull(i, mouseX, mouseY);
            if (hover) g.fill(px + 4, ry - 2, px + PANEL_W - 4, ry + ROW_H - 3, HoloUi.argb(0.14F, accent));
            drawSkull(g, px + 8, ry, hover ? RED : accent);
            g.text(font, font.plainSubstrByWidth(e.name(), PANEL_W - 60), px + 40, ry + 3, 0xFFE8FFFA, false);
            String implants = String.join(" · ", implantNames(e.mask()));
            g.text(font, font.plainSubstrByWidth(implants, PANEL_W - 60), px + 40, ry + 14, 0xFF7FB8AE, false);
        }
        if (entries.size() > MAX_ROWS) {
            g.text(font, "ещё " + (entries.size() - MAX_ROWS) + " вне списка", px + 10, py + ph - 12, 0xFF7FA8A0, false);
        }
    }

    private static void drawSkull(GuiGraphicsExtractor g, int x, int y, int rgb) {
        int color = 0xFF000000 | rgb;
        for (int row = 0; row < SKULL.length; row++) {
            for (int col = 0; col < SKULL[row].length(); col++) {
                if (SKULL[row].charAt(col) == 'X') g.fill(x + col * 2, y + row * 2, x + col * 2 + 2, y + row * 2 + 2, color);
            }
        }
    }

    private void drawMaze(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int accent = HoloTheme.accent();

        // Проход мыши по лабиринту (по точкам между кадрами, чтобы нельзя было «перепрыгнуть» стену).
        if (phase == Phase.RUNNING) {
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(mouseX - prevX, mouseY - prevY) / 2.0));
            for (int i = 1; i <= steps && phase == Phase.RUNNING; i++) {
                int px = prevX + (mouseX - prevX) * i / steps;
                int py = prevY + (mouseY - prevY) * i / steps;
                if (!insideCorridor(px, py)) {
                    fail();
                } else if (Math.hypot(px - endX, py - endY) <= halfWidth - 1) {
                    success();
                }
            }
            prevX = mouseX;
            prevY = mouseY;
        }

        String title = "ВЗЛОМ: " + (target == null ? "" : target.name())
                + "   ИМПЛАНТОВ: " + (target == null ? 0 : Integer.bitCount(target.mask()));
        HoloUi.centeredText(g, font, title, width / 2, 14, accent, 1.0F);
        String hint = switch (phase) {
            case READY -> "Наведи кружок на СТАРТ и нажми ЛКМ. Не касайся стен.";
            case RUNNING -> "Веди кружок до ФИНИША, не касаясь стен.";
            case FAILED -> "ВЗЛОМ ПРОВАЛЕН";
            case SUCCESS -> "ВЗЛОМ УСПЕШЕН: ИМПЛАНТЫ ЦЕЛИ ОТКЛЮЧЕНЫ НА 40 С";
            default -> "";
        };
        int hintColor = phase == Phase.FAILED ? RED : phase == Phase.SUCCESS ? HoloTheme.glow() : 0xB8FFF0;
        HoloUi.centeredText(g, font, hint, width / 2, 28, hintColor, 1.0F);

        // Коридоры: сначала светлая окантовка всего объединения, потом тёмная заливка поверх.
        int edge = phase == Phase.FAILED ? RED : accent;
        for (int[] s : segments) {
            g.fill(Math.min(s[0], s[2]) - halfWidth - 1, Math.min(s[1], s[3]) - halfWidth - 1,
                    Math.max(s[0], s[2]) + halfWidth + 1, Math.max(s[1], s[3]) + halfWidth + 1, 0xFF000000 | edge);
        }
        for (int[] s : segments) {
            g.fill(Math.min(s[0], s[2]) - halfWidth, Math.min(s[1], s[3]) - halfWidth,
                    Math.max(s[0], s[2]) + halfWidth, Math.max(s[1], s[3]) + halfWidth, 0xFF0A1716);
        }

        // Старт и финиш.
        int pulse = 120 + (int) (60 * Math.sin(ageTicks / 4.0));
        circle(g, startX, startY, halfWidth - 2, 0xFF000000 | HoloTheme.glow(), 0.35F + pulse / 800.0F);
        circle(g, endX, endY, halfWidth - 2, 0xFF000000 | RED, 0.35F + pulse / 800.0F);
        HoloUi.centeredText(g, font, "СТАРТ", startX, startY - 4, HoloTheme.glow(), 1.0F);
        HoloUi.centeredText(g, font, "ФИНИШ", endX, endY - 4, RED, 1.0F);

        // Кружок игрока.
        int cursor = phase == Phase.FAILED ? RED : phase == Phase.SUCCESS ? HoloTheme.glow() : 0xFFFFFF;
        circle(g, mouseX, mouseY, R, 0xFF000000 | cursor, 1.0F);
    }

    /** Заполненный круг из горизонтальных полосок; alpha < 1 — полупрозрачный. */
    private static void circle(GuiGraphicsExtractor g, int cx, int cy, int radius, int argb, float alpha) {
        int color = alpha >= 1.0F ? argb : ((Math.max(0, Math.min(255, (int) (alpha * 255))) << 24) | (argb & 0xFFFFFF));
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.floor(Math.sqrt((double) radius * radius - (double) dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }
}
