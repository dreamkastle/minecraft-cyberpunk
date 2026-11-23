package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.HackManager;
import com.smi7i.sandevistan.HackResultPayload;
import com.smi7i.sandevistan.HackStartPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Чип «Взлом». Сначала список игроков с нейро интерфейсом в радиусе 500 блоков (ник и их импланты, слева череп),
 * ЛКМ по черепу запускает мини-бой с боссом: нужно зажать ЛКМ на кружочке и уворачиваться от пуль босса, пока
 * шкала взлома не заполнится. У цели есть программа Л.Е.Д (3 уровня): чем выше уровень, тем больше пуль на экране
 * (20 / 40 / 60 против 12 без неё) и тем медленнее заполняется шкала взлома. Красная стена не пускает кружок к боссу.
 * Три попадания пули — провал: цель получит предупреждение.
 */
public class HackScreen extends Screen {
    public record Entry(String name, int mask, int led) {}

    private enum Phase { LIST, READY, FIGHT, FAILED, SUCCESS }

    private static final int RED = 0xFF4650;
    private static final int ROW_H = 26;
    private static final int PANEL_W = 330;
    private static final int MAX_ROWS = 8;
    private static final int R = 5;        // радиус кружка игрока
    private static final float HIT_R = 2.5F;   // радиус попадания игрока (меньше кружка, как в «буллет хелл»)
    private static final float BULLET_R = 3.0F;
    private static final float MAX_STEP = 9.0F; // кружок догоняет мышь не быстрее, чем на столько пикселей за тик
    private static final int LIVES = 3;
    /** Сколько тиков зажатой кнопки нужно для взлома без Л.Е.Д (25 секунд). */
    private static final int BASE_FIGHT_TICKS = 500;
    // Индекс — уровень Л.Е.Д цели (0 — программы нет).
    private static final int[] MAX_BULLETS = {12, 20, 40, 60};
    private static final float[] BULLET_SPEED = {1.5F, 1.7F, 2.0F, 2.3F};
    /** Л.Е.Д замедляет прогресс взлома. */
    private static final float[] PROGRESS_RATE = {1.0F, 0.8F, 0.6F, 0.45F};
    private static final int[] BOSS_TINT = {0xFFFFFFFF, 0xFFB8FFF0, 0xFFFFE070, 0xFFFF7080};
    private static final int[] BULLET_COLORS = {0x33E8FF, 0xFF4650, 0xFF5CE1, 0xFFD23C};
    private static final Identifier BOSS_TEXTURE = Identifier.fromNamespaceAndPath("sandevistan", "textures/gui/hack_boss.png");

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

    // Бой с боссом.
    private final List<float[]> bullets = new ArrayList<>(); // x, y, vx, vy, цвет
    private final Random rng = new Random();
    private float px, py;
    private int mouseXi, mouseYi;
    /** ЛКМ сейчас зажата (в экране игра сама не обновляет состояние мыши, поэтому следим за нажатием и отпусканием). */
    private boolean leftDown, grabbed;
    private int lives, invuln, fightTicks, level;
    private float progress;
    private double spiral;

    public HackScreen(List<Entry> entries) {
        super(Component.literal("Взлом"));
        this.entries = entries;
    }

    /** Разбор строки «ник\tмаска\tуровень Л.Е.Д» через перевод строки, как её присылает сервер. */
    public static List<Entry> parse(String data) {
        List<Entry> list = new ArrayList<>();
        if (data == null || data.isEmpty()) return list;
        for (String line : data.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            try {
                int led = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                list.add(new Entry(parts[0], Integer.parseInt(parts[1]), Math.max(0, Math.min(3, led))));
            } catch (NumberFormatException ignored) {
                // битую строку пропускаем
            }
        }
        return list;
    }

    private static List<String> implantNames(int mask, int led) {
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
        if (led > 0) names.add("Л.Е.Д ур. " + led);
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

    // ---------------------------------------------------------------- арена

    private int arenaW() { return Math.min(width - 60, 440); }
    private int arenaH() { return Math.min(height - 120, 300); }
    private int arenaLeft() { return (width - arenaW()) / 2; }
    private int arenaTop() { return (height - arenaH()) / 2 + 14; }
    private int midY() { return arenaTop() + arenaH() / 2; }
    /** Босс чуть выше середины экрана. */
    private int bossY() { return midY() - 36; }
    private float bossX() { return width / 2.0F + (phase == Phase.FIGHT ? 70.0F * (float) Math.sin(fightTicks / 70.0) : 0.0F); }
    /** Красная стенка под боссом: кружок не может её пересечь, поэтому подойти к боссу нельзя. */
    private int wallY() { return bossY() + 32; }

    private void startAttempt(Entry entry) {
        target = entry;
        started = true;
        level = Math.max(0, Math.min(3, entry.led()));
        ClientPlayNetworking.send(new HackStartPayload(entry.name())); // с этого момента идёт кулдаун чипа
        bullets.clear();
        lives = LIVES;
        invuln = 0;
        fightTicks = 0;
        progress = 0.0F;
        spiral = 0.0;
        px = width / 2.0F;
        py = midY() + 34;
        grabbed = false;
        leftDown = true; // кнопка ещё зажата после клика по черепу; бой начнётся только с нового нажатия на кружке
        phase = Phase.READY;
    }

    private boolean overPlayer(double mx, double my) {
        return Math.hypot(mx - px, my - py) <= R + 10;
    }

    private void stepFight() {
        if (!leftDown) grabbed = false; // отпустил — бой на паузе
        if (!grabbed) return;

        fightTicks++;
        if (invuln > 0) invuln--;

        // Кружок догоняет мышь с ограничением скорости.
        float dx = mouseXi - px, dy = mouseYi - py;
        float dist = (float) Math.hypot(dx, dy);
        if (dist > 0.01F) {
            float move = Math.min(dist, MAX_STEP);
            px += dx / dist * move;
            py += dy / dist * move;
        }
        px = Math.max(arenaLeft() + R, Math.min(arenaLeft() + arenaW() - R, px));
        py = Math.max(wallY() + 3 + R, Math.min(arenaTop() + arenaH() - R, py)); // выше стенки не пускаем

        spawnBullets();
        updateBullets();
        if (phase != Phase.FIGHT) return;

        progress += PROGRESS_RATE[level] / BASE_FIGHT_TICKS;
        if (progress >= 1.0F) success();
    }

    private void add(float x, float y, double vx, double vy, int color) {
        bullets.add(new float[]{x, y, (float) vx, (float) vy, color});
    }

    /** Атаки босса сменяются каждые 7 секунд; число пуль на экране ограничено уровнем Л.Е.Д. */
    private void spawnBullets() {
        int room = MAX_BULLETS[level] - bullets.size();
        if (room <= 0) return;
        float sp = BULLET_SPEED[level];
        float bx = bossX(), by = bossY() + 14;
        switch ((fightTicks / 140) % 4) {
            case 0 -> { // кольцо
                int period = Math.max(26, 44 - level * 5);
                if (fightTicks % period == 0) {
                    int n = Math.min(room, 10 + level * 4);
                    double off = rng.nextDouble() * Math.PI * 2;
                    for (int i = 0; i < n; i++) {
                        double a = off + Math.PI * 2 * i / n;
                        add(bx, by, Math.cos(a) * sp * 0.9, Math.sin(a) * sp * 0.9, 0);
                    }
                }
            }
            case 1 -> { // веер в игрока
                int period = Math.max(16, 28 - level * 3);
                if (fightTicks % period == 0) {
                    int n = Math.min(room, 3 + level);
                    double base = Math.atan2(py - by, px - bx);
                    for (int i = 0; i < n; i++) {
                        double a = base + (i - (n - 1) / 2.0) * 0.22;
                        add(bx, by, Math.cos(a) * sp * 1.1, Math.sin(a) * sp * 1.1, 1);
                    }
                }
            }
            case 2 -> { // спираль
                int every = Math.max(2, 5 - level);
                if (fightTicks % every == 0) {
                    spiral += 0.42;
                    int arms = level >= 2 ? 2 : 1;
                    for (int k = 0; k < arms && room > 0; k++, room--) {
                        double a = spiral + k * Math.PI;
                        add(bx, by, Math.cos(a) * sp, Math.sin(a) * sp, 2);
                    }
                }
            }
            default -> { // дождь сверху
                int every = Math.max(3, 8 - level * 2);
                if (fightTicks % every == 0) {
                    float x = arenaLeft() + 6 + rng.nextFloat() * (arenaW() - 12);
                    add(x, arenaTop() + 2, (rng.nextFloat() - 0.5F) * 0.6F, sp, 3);
                }
            }
        }
    }

    private void updateBullets() {
        int l = arenaLeft() - 8, r = arenaLeft() + arenaW() + 8, t = arenaTop() - 8, b = arenaTop() + arenaH() + 8;
        for (int i = bullets.size() - 1; i >= 0; i--) {
            float[] bl = bullets.get(i);
            bl[0] += bl[2];
            bl[1] += bl[3];
            if (bl[0] < l || bl[0] > r || bl[1] < t || bl[1] > b) {
                bullets.remove(i);
                continue;
            }
            if (invuln <= 0 && Math.hypot(bl[0] - px, bl[1] - py) <= HIT_R + BULLET_R) {
                bullets.remove(i);
                lives--;
                invuln = 35;
                if (lives <= 0) {
                    fail();
                    return;
                }
            }
        }
    }

    private void fail() {
        if (phase != Phase.FIGHT) return;
        phase = Phase.FAILED;
        endTicks = 0;
        sendResult(false);
    }

    private void success() {
        if (phase != Phase.FIGHT) return;
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
        if (phase == Phase.FIGHT) stepFight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        leftDown = true;
        // Бой начинается, когда ЛКМ зажата на кружке игрока; после паузы тоже зажимаем на кружке.
        if (phase == Phase.READY && overPlayer(event.x(), event.y())) {
            phase = Phase.FIGHT;
            grabbed = true;
            return true;
        }
        if (phase == Phase.FIGHT && !grabbed && overPlayer(event.x(), event.y())) {
            grabbed = true;
            return true;
        }
        if (phase == Phase.LIST) {
            int rows = Math.min(entries.size(), MAX_ROWS);
            for (int i = 0; i < rows; i++) {
                if (overSkull(i, event.x(), event.y())) {
                    startAttempt(entries.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            leftDown = false;
            grabbed = false; // отпустил ЛКМ — бой на паузе до нового зажатия на кружке
        }
        return super.mouseReleased(event);
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
        mouseXi = mouseX;
        mouseYi = mouseY;
        extractBackground(g, mouseX, mouseY, a);
        if (phase == Phase.LIST) {
            drawList(g, mouseX, mouseY);
        } else {
            drawFight(g);
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
            String implants = String.join(" · ", implantNames(e.mask(), e.led()));
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

    private void drawFight(GuiGraphicsExtractor g) {
        int accent = HoloTheme.accent();
        int left = arenaLeft(), top = arenaTop(), aw = arenaW(), ah = arenaH();

        String ice = level > 0 ? "Л.Е.Д ур. " + level : "Л.Е.Д нет";
        HoloUi.centeredText(g, font, "ВЗЛОМ: " + (target == null ? "" : target.name()) + "   " + ice, width / 2, 8, accent, 1.0F);
        String hint = switch (phase) {
            case READY -> "Зажми ЛКМ на кружке и уворачивайся от пуль. Красная стена не пускает к боссу.";
            case FIGHT -> grabbed ? "Не отпускай ЛКМ. Три попадания — провал." : "ПАУЗА: снова зажми ЛКМ на кружке";
            case FAILED -> "ВЗЛОМ ПРОВАЛЕН";
            case SUCCESS -> "ВЗЛОМ УСПЕШЕН: ИМПЛАНТЫ ЦЕЛИ ОТКЛЮЧЕНЫ НА 40 С";
            default -> "";
        };
        int hintColor = phase == Phase.FAILED ? RED : phase == Phase.SUCCESS ? HoloTheme.glow() : 0xB8FFF0;
        HoloUi.centeredText(g, font, hint, width / 2, 20, hintColor, 1.0F);

        // Шкала взлома над ареной: Л.Е.Д замедляет её заполнение.
        int by0 = top - 12;
        g.fill(left, by0, left + aw, by0 + 6, 0xFF0A1716);
        g.fill(left, by0, left + Math.round(aw * Math.min(1.0F, progress)), by0 + 6, 0xFF000000 | HoloTheme.glow());
        HoloUi.panel(g, left, by0, left + aw, by0 + 6, accent, 0.5F);
        String pct = Math.round(Math.min(1.0F, progress) * 100) + "%";
        g.text(font, pct, left + aw - font.width(pct), by0 - 10, 0xFFB8FFF0, false);

        // Арена.
        g.fill(left, top, left + aw, top + ah, 0xE0081210);
        HoloUi.panel(g, left, top, left + aw, top + ah, phase == Phase.FAILED ? RED : accent, 0.7F);

        // Босс.
        int tint = BOSS_TINT[level];
        float bx = bossX();
        g.blit(RenderPipelines.GUI_TEXTURED, BOSS_TEXTURE, Math.round(bx) - 24, bossY() - 24, 0.0F, 0.0F, 48, 48, 64, 64, 64, 64, tint);

        // Красная стенка.
        int wy = wallY();
        g.fill(left + 1, wy - 1, left + aw - 1, wy + 3, HoloUi.argb(0.25F, RED));
        g.fill(left + 1, wy, left + aw - 1, wy + 2, 0xFF000000 | RED);

        // Пули.
        for (float[] bl : bullets) {
            circle(g, Math.round(bl[0]), Math.round(bl[1]), 3, 0xFF000000 | BULLET_COLORS[(int) bl[4]], 1.0F);
        }

        // Кружок игрока (мигает после попадания).
        if (invuln <= 0 || (invuln / 3) % 2 == 0) {
            int c = phase == Phase.FAILED ? RED : phase == Phase.SUCCESS ? HoloTheme.glow() : 0xFFFFFF;
            circle(g, Math.round(px), Math.round(py), R, 0xFF000000 | c, 1.0F);
            if (phase == Phase.READY) {
                int pulse = 8 + (int) (3 * Math.sin(ageTicks / 4.0));
                for (int i = 0; i < 360; i += 15) {
                    int ox = (int) Math.round(Math.cos(Math.toRadians(i)) * pulse), oy = (int) Math.round(Math.sin(Math.toRadians(i)) * pulse);
                    g.fill(Math.round(px) + ox, Math.round(py) + oy, Math.round(px) + ox + 1, Math.round(py) + oy + 1, 0xFF000000 | HoloTheme.glow());
                }
            }
        }

        // Жизни и число пуль.
        g.text(font, "ЖИЗНИ", left + 6, top + ah - 14, 0xFF7FA8A0, false);
        for (int i = 0; i < LIVES; i++) {
            circle(g, left + 52 + i * 12, top + ah - 10, 4, i < lives ? 0xFF000000 | HoloTheme.glow() : 0xFF1A2A28, 1.0F);
        }
        String cap = "пуль " + bullets.size() + " / " + MAX_BULLETS[level];
        g.text(font, cap, left + aw - 6 - font.width(cap), top + ah - 14, 0xFF7FA8A0, false);
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
