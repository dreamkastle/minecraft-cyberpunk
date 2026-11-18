package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.ActionPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Мини-игра «Дефрагментация памяти»: голографические карточки-воспоминания нужно мышью
 * перетащить в правильные папки (ЛЮДИ / МЕСТА / ВЕЩИ). Ошибка — карточка «отскакивает» обратно с помехами.
 * Всё разложено правильно — очищается одна ячейка киберпсихоза (ActionPayload 2, сервер проверяет таймер).
 */
public class MemoryDefragScreen extends Screen {
    // ===== Настройки =====
    private static final String[] FOLDERS = {"ЛЮДИ", "МЕСТА", "ВЕЩИ"};
    private static final String[][] MEMORIES = {
            {"Мамин голос", "Лучший друг", "Первый учитель", "Соседка снизу", "Старший брат", "Отец"},
            {"Родной дом", "Крыша на закате", "Школьный двор", "Ночной неон", "Старый вокзал", "Берег реки"},
            {"Старые часы", "Детская игрушка", "Куртка отца", "Первый имплант", "Разбитый телефон", "Фото в рамке"},
    };
    /** Сколько карточек из каждой папки. */
    private static final int PER_FOLDER = 2;
    private static final int CARD_W = 104, CARD_H = 20;
    private static final int FOLDER_W = 118, FOLDER_H = 52;
    private static final int TEAL = 0x2AE0C0, GREEN = 0x39FF6A, RED = 0xFF3A4A;

    private static class Card {
        String text;
        int folder;
        float homeX, homeY, x, y;
        boolean done;
        int shakeTicks;
    }

    private final List<Card> cards = new ArrayList<>();
    private final float[] folderFlash = new float[FOLDERS.length];
    private final int[] folderFlashColor = new int[FOLDERS.length];
    private final Random random = new Random();
    private Card dragging;
    private float grabX, grabY;
    private int mistakes;
    private int successTicks = -1;
    private int ageTicks;

    public MemoryDefragScreen() {
        super(Component.literal("Дефрагментация памяти"));
    }

    @Override
    protected void init() {
        if (!cards.isEmpty()) {
            layoutHomes();
            return;
        }
        for (int f = 0; f < FOLDERS.length; f++) {
            List<String> pool = new ArrayList<>(List.of(MEMORIES[f]));
            Collections.shuffle(pool, random);
            for (int i = 0; i < PER_FOLDER; i++) {
                Card c = new Card();
                c.text = pool.get(i);
                c.folder = f;
                cards.add(c);
            }
        }
        Collections.shuffle(cards, random);
        layoutHomes();
        for (Card c : cards) {
            c.x = c.homeX;
            c.y = c.homeY;
        }
    }

    /** Карточки — россыпью в верхней половине экрана (сетка 3×2 со смещениями). */
    private void layoutHomes() {
        int cols = 3;
        float areaTop = 48, areaH = Math.max(60, height / 2.0F - 40);
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            int col = i % cols, row = i / cols;
            float cellW = width / (float) cols;
            c.homeX = col * cellW + (cellW - CARD_W) / 2 + (float) ((i * 37 % 17) - 8);
            c.homeY = areaTop + row * (areaH / 2) + (float) ((i * 53 % 13) - 6);
            if (c != dragging && !c.done) {
                c.x = c.homeX;
                c.y = c.homeY;
            }
        }
    }

    private float folderX(int f) {
        float gap = (width - FOLDERS.length * FOLDER_W) / (FOLDERS.length + 1.0F);
        return gap + f * (FOLDER_W + gap);
    }

    private float folderY() {
        return height - FOLDER_H - 26;
    }

    @Override
    public void tick() {
        ageTicks++;
        for (int f = 0; f < folderFlash.length; f++) folderFlash[f] = Math.max(0, folderFlash[f] - 0.08F);
        for (Card c : cards) {
            if (c.shakeTicks > 0) c.shakeTicks--;
            if (c != dragging && !c.done) {
                // Отпущенная карточка плавно возвращается на место.
                c.x += (c.homeX - c.x) * 0.35F;
                c.y += (c.homeY - c.y) * 0.35F;
            }
        }
        if (successTicks >= 0 && ++successTicks > 30) onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (successTicks >= 0 || event.button() != 0) return super.mouseClicked(event, doubleClick);
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (!c.done && event.x() >= c.x && event.x() <= c.x + CARD_W && event.y() >= c.y && event.y() <= c.y + CARD_H) {
                dragging = c;
                grabX = (float) event.x() - c.x;
                grabY = (float) event.y() - c.y;
                cards.remove(i);
                cards.add(c); // поверх остальных
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.x = (float) event.x() - grabX;
            dragging.y = (float) event.y() - grabY;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging == null) return super.mouseReleased(event);
        Card c = dragging;
        dragging = null;
        float cx = c.x + CARD_W / 2.0F, cy = c.y + CARD_H / 2.0F;
        for (int f = 0; f < FOLDERS.length; f++) {
            float fx = folderX(f), fy = folderY();
            if (cx >= fx && cx <= fx + FOLDER_W && cy >= fy && cy <= fy + FOLDER_H) {
                if (f == c.folder) {
                    c.done = true;
                    folderFlash[f] = 1.0F;
                    folderFlashColor[f] = GREEN;
                    checkSuccess();
                } else {
                    mistakes++;
                    c.shakeTicks = 10;
                    folderFlash[f] = 1.0F;
                    folderFlashColor[f] = RED;
                }
                return true;
            }
        }
        return true;
    }

    /** Без паузы: сервер сразу получает «пройдено», а таймеры клиента и сервера не расходятся. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void checkSuccess() {
        for (Card c : cards) if (!c.done) return;
        successTicks = 0;
        ClientPlayNetworking.send(new ActionPayload(2)); // очистить одну ячейку киберпсихоза
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0xD0050A0A);
        // Еле заметная сетка «кибер-пространства».
        int grid = HoloUi.argb(0.05F, TEAL);
        for (int x = 0; x < width; x += 16) g.fill(x, 0, x + 1, height, grid);
        for (int y = 0; y < height; y += 16) g.fill(0, y, width, y + 1, grid);
        int scan = (int) ((System.currentTimeMillis() / 12) % Math.max(1, height));
        g.fill(RenderPipelines.GUI_TEXT_HIGHLIGHT, 0, scan, width, scan + 2, 0xFF000000 | HoloUi.scale(TEAL, 0.12F));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBackground(g, mouseX, mouseY, a);
        long now = System.currentTimeMillis();
        float alpha = HoloUi.flicker(now, 11);

        HoloUi.centeredText(g, font, "ДЕФРАГМЕНТАЦИЯ ПАМЯТИ", width / 2, 12, TEAL, alpha);
        String hint = successTicks >= 0 ? "ПАМЯТЬ ВОССТАНОВЛЕНА — ОДНА ЯЧЕЙКА ОЧИЩЕНА"
                : "Разложи воспоминания по папкам" + (mistakes > 0 ? "   ОШИБОК: " + mistakes : "");
        HoloUi.centeredText(g, font, hint, width / 2, 26, successTicks >= 0 ? GREEN : 0xB0D8D0, alpha);

        // Папки.
        for (int f = 0; f < FOLDERS.length; f++) {
            int fx = Math.round(folderX(f)), fy = Math.round(folderY());
            int color = folderFlash[f] > 0 ? folderFlashColor[f] : TEAL;
            HoloUi.panel(g, fx, fy, fx + FOLDER_W, fy + FOLDER_H, color, alpha * (0.8F + 0.2F * folderFlash[f]));
            // «Ушко» папки.
            g.fill(fx + 6, fy - 6, fx + 46, fy, HoloUi.argb(0.6F * alpha, color));
            HoloUi.centeredText(g, font, FOLDERS[f], fx + FOLDER_W / 2, fy + 10, color, alpha);
            int inside = 0;
            for (Card c : cards) if (c.done && c.folder == f) inside++;
            HoloUi.centeredText(g, font, inside + " / " + PER_FOLDER, fx + FOLDER_W / 2, fy + 30, 0xB0D8D0, alpha * 0.8F);
        }

        // Карточки-воспоминания.
        Random glitch = new Random(now / 90);
        for (Card c : cards) {
            if (c.done) continue;
            float jx = c == dragging ? 0 : (glitch.nextFloat() < 0.04F ? glitch.nextInt(5) - 2 : 0);
            if (c.shakeTicks > 0) jx += Mth.sin(c.shakeTicks * 2.5F) * 4;
            int x0 = Math.round(c.x + jx), y0 = Math.round(c.y);
            int color = c.shakeTicks > 0 ? RED : (c == dragging ? GREEN : TEAL);
            HoloUi.panel(g, x0, y0, x0 + CARD_W, y0 + CARD_H, color, alpha);
            String text = glitch.nextFloat() < 0.05F ? corrupt(c.text, glitch) : c.text;
            HoloUi.centeredText(g, font, text, x0 + CARD_W / 2, y0 + 6, 0xE0FFF8, alpha);
        }
    }

    /** «Повреждённая» строка — иногда буквы подменяются мусором. */
    private static String corrupt(String s, Random r) {
        char[] chars = s.toCharArray();
        String junk = "#%@$&*01";
        for (int i = 0; i < chars.length; i++) {
            if (r.nextFloat() < 0.3F && chars[i] != ' ') chars[i] = junk.charAt(r.nextInt(junk.length()));
        }
        return new String(chars);
    }
}
