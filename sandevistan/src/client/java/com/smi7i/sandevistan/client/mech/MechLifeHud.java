package com.smi7i.sandevistan.client.mech;

import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.mech.MechManager;
import com.smi7i.sandevistan.mech.MechStatePayload;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Табло жизни в костюме-мехе (левый верхний угол, голограмма): сколько осталось и сколько уколов в шприцах.
 * Когда время вышло — красное мигающее «СИСТЕМЫ ЖИЗНЕОБЕСПЕЧЕНИЯ ОТКАЗАЛИ».
 */
public final class MechLifeHud {
    private static final int GREEN = 0x39FF6A, CYAN = 0x5BE6FF, RED = 0xFF2A2A;
    /** Сколько тиков жизни осталось (сервер присылает раз в секунду, между — считаем сами). */
    static int ticks;
    static int doses;
    /** Перезарядка атаки и заряд вызова (тики до готовности). */
    static int attackCd, summonCd, dashCd, skillCd, meteorCd;
    /** Сколько рывков доступно подряд (из MechManager.DASH_CHARGES); dashCd — тиков до восстановления ближайшего. */
    static int dashCharges = MechManager.DASH_CHARGES;
    /** Когда сделали укол (мс) — короткая зелёная вспышка на весь экран, как в Roblox. */
    static long injectFlashAt;
    private static long flashMs = 900;

    /** Зелёная вспышка экрана на durationMs (укол — 900 мс, рывок — короче). */
    public static void greenFlash(long durationMs) {
        colorFlash(0x35E85A, durationMs);
    }

    /** Вспышка экрана любым цветом (вихрь — голубая). */
    public static void colorFlash(int rgb, long durationMs) {
        injectFlashAt = System.currentTimeMillis();
        flashMs = durationMs;
        flashColor = rgb;
    }

    private static int flashColor = 0x35E85A;

    private MechLifeHud() {}

    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(MechStatePayload.TYPE, (payload, context) -> {
            ticks = payload.ticks();
            // Уколов стало меньше — значит, только что укололись: зелёная вспышка.
            if (payload.doses() < doses && MechClient.localInMech()) {
                greenFlash(900);
                lastInjectAt = System.currentTimeMillis();
            }
            doses = payload.doses();
            dashCharges = payload.dashCooldown() / 10000;
            dashCd = payload.dashCooldown() % 10000;
            skillCd = payload.skillCooldown();
            meteorCd = payload.meteorCooldown();
            MechSingularityClient.cooldown = payload.singularityCooldown();
            MechPushClient.cooldown = payload.pushCooldown();
            attackCd = payload.attackCooldown();
            // Заряд вызова резко вырос, а игрок жив и не в костюме — значит, костюм только что вызвали: «входящий звонок».
            Minecraft mc = Minecraft.getInstance();
            if (payload.summonCooldown() > summonCd + 100 && mc.player != null && mc.player.isAlive()
                    && !MechClient.localInMech()) {
                showDrop();
            }
            summonCd = payload.summonCooldown();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.isPaused()) return;
            if (ticks > 0 && MechClient.localInMech()) ticks--;
            if (attackCd > 0) attackCd--;
            if (summonCd > 0) summonCd--;
            if (dashCd > 0) dashCd--;
            if (skillCd > 0) skillCd--;
            if (meteorCd > 0) meteorCd--;
            if (voiceDelay > 0 && --voiceDelay == 0) {
                com.smi7i.sandevistan.client.SandevistanSounds.play(mc, com.smi7i.sandevistan.ModSounds.MECH_CALL_VOICE, 1.0F);
            }
        });
    }

    /** Когда показали голограмму «костюм падает» (мс), 0 — не показываем. */
    static long dropShownAt;
    /** Сколько висит голограмма падения (мс) и через сколько костюм касается земли. */
    private static final long DROP_SHOW_MS = 11000, DROP_FALL_MS = com.smi7i.sandevistan.mech.MechSuitEntity.LAND_TICK * 50L;
    private static final int ORANGE = 0xFF6A2A;
    /** Размер панели «звонка» (пиксели голограммы) и как далеко она висит перед глазами (блоки). */
    private static final int DROP_W = 230, DROP_H = 84;
    /** Ближе к глазам — крупнее; отступ от краёв — доля половины экрана; небольшой наклон для объёма. */
    private static final float DROP_DIST = 1.45F, DROP_YAW = 8.0F, DROP_MARGIN = 0.16F;
    /** Через сколько тиков после звонка начинается голосовое сообщение. */
    private static final int VOICE_DELAY = 22;
    private static int voiceDelay;
    private static com.smi7i.sandevistan.client.Holo3D.Panel dropPanel;

    /** Вызов костюма: показать 3D-голограмму «входящего звонка» в левом верхнем углу взгляда. */
    static void showDrop() {
        dropShownAt = System.currentTimeMillis();
        if (dropPanel != null) dropPanel.remove();
        dropPanel = com.smi7i.sandevistan.client.Holo3D.add(0, 0, DROP_DIST, DROP_YAW, 4.0F, 0.0F, DROP_W, DROP_H,
                (int) (DROP_SHOW_MS / 50), MechLifeHud::drawDrop);
        dropPanel.hudMargin = DROP_MARGIN;
        // Звонок, потом голосовое сообщение (оба «в голове» — слышит только вызвавший).
        com.smi7i.sandevistan.client.SandevistanSounds.play(Minecraft.getInstance(), com.smi7i.sandevistan.ModSounds.MECH_CALL_RING, 0.9F);
        voiceDelay = VOICE_DELAY;
        // Прикреплена к левому верхнему углу экрана (по настоящей проекции — не уходит за край, не отстаёт).
        dropPanel.hud = true;
        dropPanel.hudX = -1;
        dropPanel.hudY = 1;
    }

    /**
     * 3D-голограмма вызова — как «входящий звонок» в Roblox: неизвестный абонент, сообщение печатается по буквам,
     * красная кнопка CALL; ниже — сколько осталось до земли, потом «костюм на месте».
     */
    private static void drawDrop(com.smi7i.sandevistan.client.Holo3D.Canvas c, float alpha, long now) {
        long t = now - dropShownAt;
        if ((MechClient.localInMech() || MechCutscene.active()) && dropPanel != null) {
            dropPanel.remove();
            dropPanel = null;
        }
        Minecraft mc = Minecraft.getInstance();
        // Яркая плашка: плотная тёмная подложка (читается на небе), сильное свечение, светлый текст.
        float a = Math.min(1.0F, alpha * 1.15F);
        c.brightText = true;
        c.glow(0, 0, DROP_W, DROP_H, CYAN, 0.7F * a, 8);
        c.fill(0, 0, DROP_W, DROP_H, HoloUi.argb(0.82F * a, 0x02121C));
        c.panel(DROP_W, DROP_H, CYAN, a);
        c.fill(0, 0, DROP_W, 15, HoloUi.argb(0.35F * a, CYAN)); // полоса заголовка
        // Кнопка CALL: оранжево-красный квадрат, пульсирует.
        float bs = 42, bx = DROP_W - bs - 7, by = 20;
        float pulse = 0.75F + 0.25F * (float) Math.abs(Math.sin(now / 180.0));
        c.glow(bx, by, bx + bs, by + bs, ORANGE, 0.6F * a * pulse, 6);
        c.fill(bx, by, bx + bs, by + bs, HoloUi.argb(0.95F * a * pulse, 0xD04A1C));
        c.fill(bx + 3, by + 3, bx + bs - 3, by + 6, HoloUi.argb(a, 0xFF5A5A));
        c.fill(bx + 3, by + bs - 6, bx + bs - 3, by + bs - 3, HoloUi.argb(a, 0xFF5A5A));
        c.centeredText("CALL", bx + bs / 2, by + bs / 2 - 4, 0xFFFFFF, a, 1.0F);

        c.text("◆ ВХОДЯЩИЙ ВЫЗОВ: НЕИЗВЕСТНЫЙ", 6, 4, 0xFFFFFF, a, 1.0F);
        String[] lines = {"Чум, костюм уже летит.", "Залезай внутрь — лучше", "момента не будет."};
        int chars = (int) (t / 28); // текст «печатается» по буквам
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            int show = Math.max(0, Math.min(s.length(), chars));
            chars -= s.length();
            if (show > 0) c.text(s.substring(0, show), 6, 21 + i * 11, 0xE6FDFF, a, 1.0F);
        }
        float sy = DROP_H - 17;
        if (t < DROP_FALL_MS) {
            float p = t / (float) DROP_FALL_MS;
            int meters = Math.round(25 * (1 - p) * (1 - p));
            c.text("▼ КОСТЮМ ПАДАЕТ  " + meters + " м", 6, sy, 0xFF5050, a, 1.0F);
            c.fill(6, sy + 10, DROP_W - 6, sy + 13, HoloUi.argb(0.4F * a, 0x401010));
            c.glow(6, sy + 10, 6 + (DROP_W - 12) * p, sy + 13, RED, 0.5F * a, 3);
            c.fill(6, sy + 10, 6 + (DROP_W - 12) * p, sy + 13, HoloUi.argb(a, 0xFF4040));
        } else {
            c.text("✔ КОСТЮМ НА МЕСТЕ  [" + HoloUi.key(mc.options.keyUse) + "]", 6, sy, 0x6BFF8C, a, 1.0F);
        }
    }
    /** Здоровье костюма сверху по центру: голографическая шкала из делений, число HP; мигает красным, когда мало. */
    static void renderHealth(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !MechClient.localInMech() || MechShowdownClient.hideHud()) return;
        long now = System.currentTimeMillis();
        float hp = mc.player.getHealth(), max = Math.max(1, mc.player.getMaxHealth());
        float frac = Math.max(0, Math.min(1, hp / max));
        int sw = mc.getWindow().getGuiScaledWidth();
        int w = 200, h = 24, x0 = (sw - w) / 2, y0 = 16;
        boolean low = frac < 0.25F;
        int color = low ? RED : frac < 0.5F ? 0xFFB040 : GREEN;
        float alpha = HoloUi.flicker(now, 41) * (low ? 0.6F + 0.4F * (float) Math.abs(Math.sin(now / 150.0)) : 1.0F);
        HoloUi.begin(g, x0, y0, 0.0F);
        HoloUi.panel(g, x0, y0, x0 + w, y0 + h, color, alpha);
        HoloUi.text(g, mc.font, "КОСТЮМ", x0 + 5, y0 + 3, color, alpha);
        String num = Math.round(hp) + " / " + Math.round(max);
        HoloUi.text(g, mc.font, num, x0 + w - 5 - mc.font.width(num), y0 + 3, color, alpha);
        int segs = 25, bx = x0 + 5, by = y0 + 14, bw = w - 10, gap = 1;
        float segW = (bw - gap * (segs - 1)) / (float) segs;
        int lit = (int) Math.ceil(segs * frac);
        for (int i = 0; i < segs; i++) {
            int sx = bx + Math.round(i * (segW + gap)), ex = bx + Math.round(i * (segW + gap) + segW);
            boolean on = i < lit;
            g.fill(sx, by, ex, by + 5, HoloUi.argb(on ? 0.9F * alpha : 0.22F * alpha, on ? color : 0x1A2A2A));
        }
        if (lit > 0) HoloUi.glow(g, bx, by, bx + Math.round(bw * frac), by + 5, color, 0.22F * alpha, 2);
        g.pose().popMatrix();
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || MechShowdownClient.hideHud()) return;
        if (!MechClient.localInMech()) return;
        long now = System.currentTimeMillis();
        boolean dying = ticks <= 0;
        // Подальше от края (наклон панели выносит угол наружу), шире — надпись «[H] +30с» внутри.
        int x0 = 20, y0 = 18, w = 172, h = dying ? 44 : 34;
        int color = dying ? RED : GREEN;
        float alpha = HoloUi.flicker(now, 11) * (dying ? 0.6F + 0.4F * (float) Math.abs(Math.sin(now / 160.0)) : 1.0F);

        HoloUi.begin(g, x0, y0, HoloUi.TILT);
        HoloUi.panel(g, x0, y0, x0 + w, y0 + h, color, alpha);
        int secs = (ticks + 19) / 20;
        String time = dying ? "0:00" : String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
        HoloUi.text(g, mc.font, "КОСТЮМ  " + time, x0 + 5, y0 + 4, color, alpha);

        // Шприцы: 5 делений — сколько уколов осталось.
        int bx = x0 + 5, by = y0 + 16, cell = 18;
        for (int i = 0; i < MechManager.DOSES; i++) {
            boolean full = i < doses;
            int c = full ? CYAN : 0x203040;
            g.fill(bx + i * (cell + 3), by, bx + i * (cell + 3) + cell, by + 6, HoloUi.argb(full ? 0.9F * alpha : 0.5F * alpha, c));
            if (full) HoloUi.glow(g, bx + i * (cell + 3), by, bx + i * (cell + 3) + cell, by + 6, CYAN, 0.25F * alpha, 2);
        }
        String key = com.smi7i.sandevistan.client.HoloUi.key(MechClient.INJECT);
        HoloUi.text(g, mc.font, "[" + key + "] +0:45", bx + MechManager.DOSES * (cell + 3) + 2, by - 1, CYAN, 0.8F * alpha);
        if (dying) HoloUi.text(g, mc.font, "ЖИЗНЕОБЕСПЕЧЕНИЕ ОТКАЗАЛО", x0 + 5, y0 + 30, RED, alpha);
        g.pose().popMatrix();
        renderEject(g, mc, now);
    }

    // ===== Последствия уколов =====
    /** С каждым уколом экран чуть зеленее (сила на один укол) и сколько длятся глитчи после укола (мс). */
    private static final float TINT_PER_DOSE = 0.055F;
    private static final long GLITCH_MS = 750;
    private static final java.util.Random GR = new java.util.Random();

    /** Сколько уколов уже сделано в этом костюме. */
    static int dosesUsed() {
        return Math.max(0, MechManager.DOSES - doses);
    }

    /**
     * Постоянный зеленоватый оттенок (растёт с каждым уколом), глитчи сразу после укола
     * и вертикальная шкала справа (растёт с уколами; для чего — пользователь объяснит позже).
     */
    private static void renderSyringeEffects(GuiGraphicsExtractor g, Minecraft mc) {
        if (!MechClient.localInMech() || MechShowdownClient.hideHud()) return;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();
        int used = dosesUsed();
        if (used > 0) {
            float tint = TINT_PER_DOSE * used * (0.92F + 0.08F * (float) Math.sin(now / 700.0));
            g.fill(0, 0, sw, sh, HoloUi.argb(tint, 0x2BE05A));
        }
        // Глитчи: рваные полосы, сдвинутые куски, «битые» надписи — сильнее с каждым уколом.
        long t = now - lastInjectAt;
        if (lastInjectAt > 0 && t < GLITCH_MS) {
            float k = 1.0F - t / (float) GLITCH_MS;
            GR.setSeed(now / 45);
            int strips = 6 + 3 * used;
            for (int i = 0; i < strips; i++) {
                if (GR.nextFloat() > 0.55F + 0.4F * k) continue;
                int y = GR.nextInt(sh), h = 1 + GR.nextInt(6), x = GR.nextInt(sw / 2) - sw / 4, w = sw / 3 + GR.nextInt(sw);
                int col = GR.nextInt(3) == 0 ? 0xFFFFFF : (GR.nextBoolean() ? 0x39FF6A : 0x1FFFD0);
                g.fill(x, y, x + w, y + h, HoloUi.argb((0.15F + 0.35F * GR.nextFloat()) * k, col));
            }
            if (GR.nextFloat() < 0.7F * k) {
                String[] words = {"DOSE_INJECTED", "NEURO.SYNC ++", "0x" + Integer.toHexString(GR.nextInt(0xFFFFF)).toUpperCase(),
                        "BLOOD.CHEM ▲", "СИНХРОНИЗАЦИЯ " + used + "/" + MechManager.DOSES};
                String s = words[GR.nextInt(words.length)];
                HoloUi.text(g, mc.font, s, GR.nextInt(Math.max(1, sw - 120)), GR.nextInt(Math.max(1, sh - 20)), 0x39FF6A, 0.8F * k);
            }
        }
        // Шкала полная (все 5 уколов) — экран глитчит постоянно и сильно, иногда — тяжёлый срыв.
        boolean showdownSpent = Boolean.TRUE.equals(mc.player.getAttached(com.smi7i.sandevistan.ModAttachments.MECH_SHOWDOWN_DONE));
        if (used >= MechManager.DOSES && !showdownSpent) {
            GR.setSeed(now / 55);
            for (int i = 0; i < 4 + GR.nextInt(5); i++) {
                if (GR.nextFloat() > 0.45F) continue;
                int y = GR.nextInt(sh), hh = 1 + GR.nextInt(4), x = GR.nextInt(sw) - sw / 3, ww = sw / 4 + GR.nextInt(sw / 2);
                g.fill(x, y, x + ww, y + hh, HoloUi.argb(0.12F + 0.25F * GR.nextFloat(), GR.nextBoolean() ? 0x39FF6A : 0xFF2A50));
            }
            long cycle = now % 2600;
            if (cycle < 260) {
                // Срыв: цветные сдвинутые полосы через весь экран, «битые» надписи, мигание.
                float k = 1.0F - cycle / 260.0F;
                GR.setSeed(now / 35);
                for (int i = 0; i < 14; i++) {
                    int y = GR.nextInt(sh), hh = 2 + GR.nextInt(14);
                    int col = new int[]{0xFF2040, 0x20E0FF, 0x39FF6A, 0xFFFFFF}[GR.nextInt(4)];
                    g.fill(0, y, sw, y + hh, HoloUi.argb((0.15F + 0.3F * GR.nextFloat()) * k, col));
                }
                g.fill(0, 0, sw, sh, HoloUi.argb(0.12F * k, GR.nextBoolean() ? 0xFF0030 : 0x00FF60));
                String[] err = {"NEURAL OVERLOAD", "SYNC 100%", "ONE LAST SHOWDOWN", "ПОСЛЕДНИЙ БОЙ ДОСТУПЕН", "[K]"};
                for (int i = 0; i < 3; i++) {
                    HoloUi.text(g, mc.font, err[GR.nextInt(err.length)], GR.nextInt(Math.max(1, sw - 140)), GR.nextInt(Math.max(1, sh - 20)),
                            GR.nextBoolean() ? 0xFF3050 : 0x39FF6A, 0.85F * k);
                }
            }
        }
        if (showdownSpent) used = 0; // способность потрачена — шкала пустая
        // Вертикальная шкала: деления снизу вверх. Стоит слева от панели способностей (её ширина 150, отступ 10),
        // низом вровень с ней — так её не закрывают ни радар (справа сверху), ни панель, ни стойки кабины.
        int segs = 20, segH = 4, gap = 2, bw = 10;
        int bh = segs * (segH + gap);
        int bx = sw - 150 - 10 - 14 - bw, by = sh - 10 - bh;
        if (by < 20) by = 20;
        float fill = used / (float) MechManager.DOSES;
        int lit = Math.round(segs * fill);
        float pulse = 0.8F + 0.2F * (float) Math.sin(now / 300.0);
        HoloUi.glow(g, bx - 1, by - 1, bx + bw + 1, by + bh + 1, CYAN, 0.12F, 3);
        for (int i = 0; i < segs; i++) {
            int y1 = by + bh - (i + 1) * (segH + gap);
            boolean on = i < lit;
            g.fill(bx, y1, bx + bw, y1 + segH, HoloUi.argb(on ? 0.85F * pulse : 0.2F, on ? CYAN : 0x0E3A40));
            if (on) HoloUi.glow(g, bx, y1, bx + bw, y1 + segH, CYAN, 0.18F * pulse, 2);
        }
    }

    /** Когда был последний укол (мс) — для глитчей. */
    static long lastInjectAt;

    /** Укол: экран ненадолго становится светло-зелёным (быстро вспыхивает, плавно уходит). */
    static void renderInjectFlash(GuiGraphicsExtractor g) {
        renderSyringeEffects(g, Minecraft.getInstance());
        if (injectFlashAt == 0) return;
        long t = System.currentTimeMillis() - injectFlashAt;
        if (t > flashMs) {
            injectFlashAt = 0;
            return;
        }
        float k = t < 80 ? t / 80.0F : 1.0F - (t - 80) / (float) (flashMs - 80);
        k = k * k;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        g.fill(0, 0, sw, sh, HoloUi.argb(0.55F * k, flashColor));
        // По краям гуще — как свечение.
        int edge = sh / 5;
        for (int i = 0; i < 6; i++) {
            float a = 0.12F * k * (6 - i) / 6.0F;
            int e = edge * (i + 1) / 6;
            g.fill(0, 0, sw, e, HoloUi.argb(a, flashColor));
            g.fill(0, sh - e, sw, sh, HoloUi.argb(a, flashColor));
        }
    }

    /** Зажата клавиша снятия костюма — красное предупреждение по центру с полоской. */
    private static void renderEject(GuiGraphicsExtractor g, Minecraft mc, long now) {
        if (MechClient.ejectHeld <= 0) return;
        float p = Math.min(1.0F, MechClient.ejectHeld / (float) MechClient.EJECT_HOLD);
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int w = 170, h = 34, x0 = (sw - w) / 2, y0 = sh / 2 + 24;
        float alpha = 0.75F + 0.25F * (float) Math.abs(Math.sin(now / 90.0));
        HoloUi.begin(g, x0, y0, HoloUi.TILT);
        HoloUi.panel(g, x0, y0, x0 + w, y0 + h, RED, alpha);
        HoloUi.text(g, mc.font, "КОСТЮМ СНИМАЕТСЯ...", x0 + 5, y0 + 4, RED, alpha);
        HoloUi.text(g, mc.font, "ПИЛОТ НЕ ВЫЖИВЕТ", x0 + 5, y0 + 15, 0xFFB0B0, 0.8F * alpha);
        int by = y0 + h - 7;
        g.fill(x0 + 5, by, x0 + w - 5, by + 3, HoloUi.argb(0.35F * alpha, 0x200808));
        int fw = Math.round((w - 10) * p);
        if (fw > 0) {
            g.fill(x0 + 5, by, x0 + 5 + fw, by + 3, HoloUi.argb(0.95F * alpha, RED));
            HoloUi.glow(g, x0 + 5, by, x0 + 5 + fw, by + 3, RED, 0.3F * alpha, 2);
        }
        g.pose().popMatrix();
    }

    /**
     * Панель справа внизу. В костюме — способности: [R] атака (полоска перезарядки) и [H] шприц (уколы).
     * Без костюма — [B] вызов костюма со шкалой заряда.
     */
    static void renderAbilities(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || MechShowdownClient.hideHud()) return;
        long now = System.currentTimeMillis();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        float alpha = HoloUi.flicker(now, 23);
        int w = 150;
        int x0 = sw - w - 10;
        if (MechClient.localInMech()) {
            int h = 136, y0 = sh - h - 10;
            HoloUi.begin(g, x0, y0, -HoloUi.TILT);
            HoloUi.panel(g, x0, y0, x0 + w, y0 + h, GREEN, alpha);
            HoloUi.text(g, mc.font, "КОСТЮМ: СПОСОБНОСТИ", x0 + 5, y0 + 4, GREEN, alpha);
            boolean attacking = MechClient.localAttacking();
            float atk = attacking ? 0 : 1.0F - attackCd / (float) com.smi7i.sandevistan.mech.MechManager.ATTACK_COOLDOWN;
            String atkState = attacking ? "БЬЁТ" : attackCd > 0 ? String.format(Locale.ROOT, "%dс", (attackCd + 19) / 20) : "ГОТОВА";
            ability(g, mc, x0 + 5, y0 + 16, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.ATTACK) + "] АТАКА",
                    atkState, atk, attacking ? RED : GREEN, alpha);
            float dose = doses / (float) MechManager.DOSES;
            ability(g, mc, x0 + 5, y0 + 31, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.INJECT) + "] ШПРИЦ",
                    doses + "/" + MechManager.DOSES, dose, CYAN, alpha);
            // Заряды рывка: полоска — сколько зарядов (+ доля восстанавливающегося), справа — время до следующего.
            float dash = (dashCharges + (dashCharges < MechManager.DASH_CHARGES && dashCd > 0
                    ? 1.0F - dashCd / (float) MechManager.DASH_COOLDOWN : 0)) / MechManager.DASH_CHARGES;
            String dashState = dashCharges >= MechManager.DASH_CHARGES ? "ГОТОВ"
                    : String.format(Locale.ROOT, "%.1fс", dashCd / 20.0F);
            ability(g, mc, x0 + 5, y0 + 46, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.DASH) + "] РЫВОК ("
                    + dashCharges + ")", dashState, dash, dashCharges > 0 ? GREEN : RED, alpha);
            boolean vortex = com.smi7i.sandevistan.mech.MechVortex.isActive(mc.player);
            float sk = vortex ? 0 : 1.0F - skillCd / (float) com.smi7i.sandevistan.mech.MechVortex.COOLDOWN;
            ability(g, mc, x0 + 5, y0 + 61, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.VORTEX) + "] ВИХРЬ",
                    vortex ? "АКТИВЕН" : skillCd > 0 ? String.format(Locale.ROOT, "%dс", (skillCd + 19) / 20) : "ГОТОВ", sk,
                    vortex ? RED : CYAN, alpha);
            boolean meteor = com.smi7i.sandevistan.mech.MechMeteor.isActive(mc.player);
            float mt = meteor ? 0 : 1.0F - meteorCd / (float) com.smi7i.sandevistan.mech.MechMeteor.COOLDOWN;
            ability(g, mc, x0 + 5, y0 + 76, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.METEOR) + "] МЕТЕОР (держать)",
                    meteor ? "АКТИВЕН" : meteorCd > 0 ? String.format(Locale.ROOT, "%dс", (meteorCd + 19) / 20) : "ГОТОВ", mt,
                    meteor ? RED : CYAN, alpha);
            boolean sing = com.smi7i.sandevistan.mech.MechSingularity.isActive(mc.player);
            int sCd = MechSingularityClient.cooldown;
            float sf = sing ? 0 : 1.0F - sCd / (float) com.smi7i.sandevistan.mech.MechSingularity.COOLDOWN;
            ability(g, mc, x0 + 5, y0 + 91, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechSingularityClient.KEY) + "] СИНГУЛЯРНОСТЬ",
                    sing ? "!!!" : sCd > 0 ? String.format(Locale.ROOT, "%dс", (sCd + 19) / 20) : "ГОТОВА", sf,
                    sing ? 0xC060FF : sCd > 0 ? 0x6A7A80 : 0xB070FF, alpha);
            // «Последний бой»: открывается полной шкалой уколов, один раз на костюм.
            boolean done = Boolean.TRUE.equals(mc.player.getAttached(com.smi7i.sandevistan.ModAttachments.MECH_SHOWDOWN_DONE));
            boolean ready = com.smi7i.sandevistan.mech.MechShowdown.available(mc.player);
            boolean live = com.smi7i.sandevistan.mech.MechShowdown.isActive(mc.player);
            float pulseK = 0.6F + 0.4F * (float) Math.abs(Math.sin(now / 220.0));
            boolean pushing = com.smi7i.sandevistan.mech.MechPush.isActive(mc.player);
            int pCd = MechPushClient.cooldown;
            ability(g, mc, x0 + 5, y0 + 106, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechPushClient.KEY) + "] МАГНИТ",
                    pushing ? "ТЯНЕТ" : pCd > 0 ? String.format(Locale.ROOT, "%dс", (pCd + 19) / 20) : "ГОТОВ",
                    pushing ? 0 : 1.0F - pCd / (float) com.smi7i.sandevistan.mech.MechPush.COOLDOWN, pushing ? RED : 0xFF6070, alpha);
            ability(g, mc, x0 + 5, y0 + 121, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.SHOWDOWN) + "] ПОСЛЕДНИЙ БОЙ",
                    live ? "!!!" : done ? "ПОТРАЧЕН" : ready ? "ГОТОВ" : dosesUsed() + "/" + MechManager.DOSES,
                    done ? 0 : dosesUsed() / (float) MechManager.DOSES, ready || live ? 0xFFC83A : 0x6A7A80,
                    alpha * (ready ? pulseK : 1.0F));
            g.pose().popMatrix();
        } else {
            int h = 30, y0 = sh - h - 10;
            // Без Сандевистана о костюме ничего не напоминает — панели вызова нет.
            if (!com.smi7i.sandevistan.ImplantSlot.isImplant(mc.player.getAttachedOrCreate(com.smi7i.sandevistan.ModAttachments.IMPLANT))) return;
            boolean ready = summonCd <= 0;
            int color = ready ? GREEN : CYAN;
            HoloUi.begin(g, x0, y0, -HoloUi.TILT);
            HoloUi.panel(g, x0, y0, x0 + w, y0 + h, color, alpha * (ready ? 0.7F + 0.3F * (float) Math.abs(Math.sin(now / 400.0)) : 1.0F));
            float charge = 1.0F - summonCd / (float) MechManager.SUMMON_COOLDOWN;
            ability(g, mc, x0 + 5, y0 + 6, w - 10, "[" + com.smi7i.sandevistan.client.HoloUi.key(MechClient.SUMMON) + "] ВЫЗОВ КОСТЮМА",
                    ready ? "ГОТОВ" : String.format(Locale.ROOT, "%dс", (summonCd + 19) / 20), charge, color, alpha);
            g.pose().popMatrix();
        }
    }

    /**
     * «Метеор», полёт невидимым: экран маскировки — зелёные края, бегущая развёртка, полосы скорости по краям,
     * скобки прицела, внизу «МАСКИРОВКА» и шкала оставшегося времени полёта.
     */
    static void renderMeteorHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !com.smi7i.sandevistan.mech.MechMeteor.isHidden(mc.player)) return;
        long now = System.currentTimeMillis();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        long s = com.smi7i.sandevistan.mech.MechMeteor.startTime(mc.player);
        float age = mc.level.getGameTime() - s - com.smi7i.sandevistan.mech.MechMeteor.LAUNCH;
        float in = Math.min(1, age / 8.0F);
        int green = 0x39FF6A;
        // Края: мягкое затемнение с зелёным отливом.
        for (int i = 0; i < 14; i++) {
            float a = in * 0.05F * (14 - i) / 14.0F;
            int c = HoloUi.argb(a, 0x03200C);
            int m = i * 3;
            g.fill(0, m, sw, m + 3, c);
            g.fill(0, sh - m - 3, sw, sh - m, c);
            g.fill(m, 0, m + 3, sh, c);
            g.fill(sw - m - 3, 0, sw - m, sh, c);
        }
        // Развёртка: тонкие линии и одна яркая полоса бежит сверху вниз.
        for (int y = 0; y < sh; y += 4) g.fill(0, y, sw, y + 1, HoloUi.argb(0.035F * in, green));
        int sweep = (int) ((now / 6) % (sh + 40)) - 20;
        g.fill(0, sweep, sw, sweep + 2, HoloUi.argb(0.08F * in, green));
        // Полосы скорости: короткие штрихи летят от центра к краям.
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 26; i++) {
            float ang = r.nextFloat() * (float) Math.PI * 2, speed = 0.6F + r.nextFloat() * 0.8F;
            float ph = ((now / 1000.0F) * speed + r.nextFloat()) % 1.0F;
            float d0 = 0.55F + 0.5F * ph, d1 = d0 + 0.08F + 0.06F * speed;
            float cx = sw / 2.0F, cy = sh / 2.0F, rx = sw * 0.5F, ry = sh * 0.5F;
            int x0 = (int) (cx + Math.cos(ang) * rx * d0), y0 = (int) (cy + Math.sin(ang) * ry * d0);
            int x1 = (int) (cx + Math.cos(ang) * rx * d1), y1 = (int) (cy + Math.sin(ang) * ry * d1);
            int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            int col = HoloUi.argb(0.35F * in * (1 - ph), 0xC8FFD8);
            for (int k = 0; k <= steps; k += 2) {
                int x = x0 + (x1 - x0) * k / Math.max(1, steps), y = y0 + (y1 - y0) * k / Math.max(1, steps);
                g.fill(x, y, x + 1, y + 1, col);
            }
        }
        // Скобки прицела.
        int cx = sw / 2, cy = sh / 2, b = 14, l = 6;
        int bc = HoloUi.argb(0.7F * in, green);
        g.fill(cx - b, cy - b, cx - b + l, cy - b + 1, bc); g.fill(cx - b, cy - b, cx - b + 1, cy - b + l, bc);
        g.fill(cx + b - l, cy - b, cx + b, cy - b + 1, bc); g.fill(cx + b - 1, cy - b, cx + b, cy - b + l, bc);
        g.fill(cx - b, cy + b - 1, cx - b + l, cy + b, bc); g.fill(cx - b, cy + b - l, cx - b + 1, cy + b, bc);
        g.fill(cx + b - l, cy + b - 1, cx + b, cy + b, bc); g.fill(cx + b - 1, cy + b - l, cx + b, cy + b, bc);
        // Надпись и шкала времени полёта.
        float left = 1.0F - Math.max(0, age) / com.smi7i.sandevistan.mech.MechMeteor.MAX_FLY;
        int w = 180, x0 = (sw - w) / 2, y0 = sh - 78;
        float fl = HoloUi.flicker(now, 41) * in;
        HoloUi.centeredText(g, mc.font, "◇ МАСКИРОВКА ◇", sw / 2, y0, green, fl);
        HoloUi.centeredText(g, mc.font, "отпусти [" + HoloUi.key(MechClient.METEOR) + "] — удар", sw / 2, y0 + 11, 0xB8FFC8, 0.75F * fl);
        g.fill(x0, y0 + 24, x0 + w, y0 + 27, HoloUi.argb(0.35F * in, 0x0A2010));
        int fw = Math.round(w * Math.max(0, left));
        if (fw > 0) {
            int col = left < 0.25F && (now / 150) % 2 == 0 ? 0xFF3048 : green;
            g.fill(x0, y0 + 24, x0 + fw, y0 + 27, HoloUi.argb(0.9F * in, col));
            HoloUi.glow(g, x0, y0 + 24, x0 + fw, y0 + 27, col, 0.3F * in, 2);
        }
    }
    /** Строка способности: название, состояние справа и полоска под ними. */
    private static void ability(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int w, String name, String state,
                                float fill, int color, float alpha) {
        HoloUi.text(g, mc.font, name, x, y, color, alpha);
        HoloUi.text(g, mc.font, state, x + w - mc.font.width(state), y, color, alpha);
        int by = y + 10;
        g.fill(x, by, x + w, by + 3, HoloUi.argb(0.35F * alpha, 0x102020));
        int fw = Math.round(w * Math.max(0, Math.min(1, fill)));
        if (fw > 0) {
            g.fill(x, by, x + fw, by + 3, HoloUi.argb(0.9F * alpha, color));
            HoloUi.glow(g, x, by, x + fw, by + 3, color, 0.25F * alpha, 2);
        }
    }
}