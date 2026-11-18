package com.smi7i.sandevistan.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Глитчи при перегреве, в зелёном и красном:
 *  - на экране («на стекле»): мерцающие полосы и короткие надписи-ошибки;
 *  - в пространстве: большие 3D-голограммы (Holo3D) с надписью «ОШИБКА», строчками данных или пустой рамкой —
 *    висят перед глазами под настоящим углом и мягко плывут за взглядом.
 * Чем свежее перегрев, тем больше глитчей.
 */
public class SandevistanGlitchHud {
    // ===== Настройки =====
    /** Как часто меняется рисунок полос/мерцания, в миллисекундах. */
    private static final long GLITCH_FRAME_MS = 70;
    /** Сколько максимум полос за один «кадр» глитча. */
    private static final int MAX_BARS = 8;
    /** Вероятность короткой надписи-ошибки за «кадр» глитча на полной силе. */
    private static final float TEXT_CHANCE = 0.45F;
    /** Максимум голограмм одновременно. */
    private static final int MAX_PANELS = 3;
    /** Шанс появления новой голограммы за тик (на полной силе). */
    private static final float PANEL_CHANCE = 0.12F;
    /** Сколько живёт голограмма, в тиках: от и до. */
    private static final int PANEL_LIFE_MIN = 14;
    private static final int PANEL_LIFE_MAX = 36;

    /** Цвета глитчей (RRGGBB). */
    public static final int RED = 0xFF3040;
    public static final int GREEN = 0x39FF6A;

    private static final String[] ERRORS = {
            "SYSTEM OVERHEAT", "ERROR 0x5D3V", "CRITICAL TEMPERATURE", "NEURAL LINK UNSTABLE",
            "SANDEVISTAN // FAULT", "MEMORY CORRUPTED", "!! WARNING !!", "SYNC LOST", "REBOOTING...",
            "ERR_KIROSHI_0x3F",
    };
    private static final String[] PANEL_TITLES = {"ОШИБКА", "ПЕРЕГРЕВ", "СБОЙ", "ERROR"};
    private static final String[] PANEL_HEADERS = {
            "SYS://SANDEVISTAN", "MILITECH.KERNEL", "NEURO_BUS 07", "THERMAL.CTRL", "KIROSHI.OPT",
    };

    private static final List<Holo3D.Panel> PANELS = new ArrayList<>();
    private static final Random SPAWN_RANDOM = new Random();

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "overheat_glitch"),
                SandevistanGlitchHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }

    /** Сила глитчей 0..1 для текущего кадра. Её же использует искажение HUD. */
    public static float strength(float partialTick) {
        return ClientSandevistanState.overheatForFrame(partialTick)
                * (0.25F + 0.75F * ClientSandevistanState.overheatFreshness());
    }

    private static void tick() {
        PANELS.removeIf(p -> p.ageTicks(0) > p.life);
        float s = strength(1.0F);
        if (s <= 0.15F) {
            if (s <= 0.02F) {
                PANELS.forEach(Holo3D.Panel::remove);
                PANELS.clear();
            }
            return;
        }
        if (PANELS.size() < MAX_PANELS && SPAWN_RANDOM.nextFloat() < PANEL_CHANCE * s) {
            PANELS.add(spawnHologram(SPAWN_RANDOM));
        }
    }

    /** Новая голограмма в случайном месте перед глазами, повёрнутая под случайным углом. */
    private static Holo3D.Panel spawnHologram(Random r) {
        float right = (r.nextFloat() * 2 - 1) * 0.8F;
        float up = -0.25F + r.nextFloat() * 0.7F;
        float forward = 1.4F + r.nextFloat() * 0.5F;
        // Боковые панели развёрнуты к центру, как мониторы вокруг.
        float yaw = -right * 30.0F + (r.nextFloat() * 2 - 1) * 10.0F;
        float pitch = -up * 15.0F + (r.nextFloat() * 2 - 1) * 6.0F;
        float roll = (r.nextFloat() * 2 - 1) * 5.0F;
        int width = 150 + r.nextInt(90);
        int height = Math.round(width * (0.5F + r.nextFloat() * 0.2F));
        int life = PANEL_LIFE_MIN + r.nextInt(PANEL_LIFE_MAX - PANEL_LIFE_MIN + 1);

        int color = r.nextFloat() < 0.6F ? RED : GREEN;
        int type = r.nextInt(3);
        String title = PANEL_TITLES[r.nextInt(PANEL_TITLES.length)];
        String header = PANEL_HEADERS[r.nextInt(PANEL_HEADERS.length)];
        String[] lines = new String[4 + r.nextInt(3)];
        for (int i = 0; i < lines.length; i++) lines[i] = fakeDataLine(r);
        long seed = r.nextLong();

        return Holo3D.add(right, up, forward, yaw, pitch, roll, width, height, life,
                (canvas, alpha, timeMs) -> drawPanel(canvas, alpha, timeMs, width, height, color, type, title, header, lines, seed));
    }

    private static void drawPanel(Holo3D.Canvas c, float alpha, long timeMs, int w, int h, int color, int type,
                                  String title, String header, String[] lines, long seed) {
        Random glitch = new Random(timeMs / GLITCH_FRAME_MS * 7919L + seed);
        int other = color == RED ? GREEN : RED;

        c.panel(w, h, color, alpha);
        // Шапка.
        c.fill(1, 1, w - 1, 13, HoloUi.argb(0.5F * alpha, color));
        c.text(header, 5, 3, 0x101010, Math.max(0.4F, alpha), 1.0F);

        if (type == 0) {
            // Большая светящаяся надпись с цветным «расслоением».
            float scale = Math.min(3.0F, (w * 0.8F) / Math.max(1, c.font.width(title)));
            float y = 13 + (h - 13) / 2.0F - 4.5F * scale;
            float split = glitch.nextFloat() < 0.35F ? 2.0F : 1.0F;
            c.glow(w * 0.15F, y - 2, w * 0.85F, y + 9 * scale + 2, color, 0.18F * alpha, 4);
            c.centeredText(title, w / 2.0F - split, y, other, 0.55F * alpha, scale);
            c.centeredText(title, w / 2.0F + split, y, color, alpha, scale);
        } else if (type == 1) {
            // Строчки «данных».
            float y = 17;
            for (String line : lines) {
                if (y + 9 > h - 3) break;
                c.text(line, 6, y, glitch.nextFloat() < 0.15F ? other : color, alpha, 1.0F);
                y += 10;
            }
        } else {
            // Пустая рамка с рваными полосами и «NO SIGNAL».
            Random bars = new Random(seed + timeMs / (GLITCH_FRAME_MS * 2));
            for (int i = 0; i < 5; i++) {
                float y = 17 + bars.nextInt(Math.max(1, h - 30));
                float x0 = 5 + bars.nextInt(Math.max(1, w / 2));
                float x1 = Math.min(w - 5, x0 + 12 + bars.nextInt(Math.max(1, w / 2)));
                c.fill(x0, y, x1, y + 2 + bars.nextInt(3), HoloUi.argb(0.6F * alpha, bars.nextBoolean() ? RED : GREEN));
            }
            c.centeredText("NO SIGNAL", w / 2.0F, h - 13, color, 0.75F * alpha, 1.0F);
        }
    }

    private static String fakeDataLine(Random r) {
        String[] words = {"TEMP", "CORE", "SYNC", "BUS", "MEM", "NRV", "CLK", "VOLT"};
        return String.format("%s_%02d  0x%04X  %3d%%  %s", words[r.nextInt(words.length)], r.nextInt(100),
                r.nextInt(0x10000), 80 + r.nextInt(70), r.nextBoolean() ? "FAIL" : "!!");
    }

    /** Помехи «на стекле»: полосы и короткие надписи прямо на экране. */
    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
        float strength = strength(partial);
        if (strength <= 0.02F) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        long frame = System.currentTimeMillis() / GLITCH_FRAME_MS;

        Random random = new Random(frame * 31L + 17L);
        // Иногда «кадр» глитча пустой — так мерцание выглядит рвано.
        if (random.nextFloat() > 0.35F + 0.65F * strength) return;

        // Горизонтальные полосы, красные и зелёные.
        int bars = Math.round(MAX_BARS * strength * random.nextFloat());
        for (int i = 0; i < bars; i++) {
            int y = random.nextInt(Math.max(1, h));
            int height = 1 + random.nextInt(random.nextFloat() < 0.2F ? 10 : 3);
            int x0 = random.nextFloat() < 0.5F ? 0 : random.nextInt(Math.max(1, w / 2));
            int x1 = x0 + w / 3 + random.nextInt(Math.max(1, w));
            int alpha = 60 + random.nextInt(110);
            int rgb = random.nextBoolean() ? RED : GREEN;
            graphics.fill(x0, y, Math.min(w, x1), Math.min(h, y + height), (alpha << 24) | rgb);
        }

        // Короткие надписи-ошибки с «расслоением» цвета: зелёная тень, красный текст поверх со сдвигом.
        if (random.nextFloat() < TEXT_CHANCE * strength) {
            String text = ERRORS[random.nextInt(ERRORS.length)];
            int textW = font.width(text);
            int x = random.nextInt(Math.max(1, w - textW));
            int y = 10 + random.nextInt(Math.max(1, h - 50));
            int shift = 1 + random.nextInt(2);
            graphics.fill(x - 3, y - 2, x + textW + 3, y + 10, 0x90100000);
            graphics.text(font, text, x - shift, y, 0xC0000000 | GREEN, false);
            graphics.text(font, text, x + shift, y, 0xFF000000 | RED, false);
        }
    }
}
