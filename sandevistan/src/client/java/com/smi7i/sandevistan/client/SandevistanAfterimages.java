package com.smi7i.sandevistan.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Послеобразы: пока имплант работает, игрок оставляет за собой полупрозрачные цветные копии. Только клиент. */
public class SandevistanAfterimages {
    // ===== Настройки =====
    /** Раз в сколько тиков делать снимок (20 тиков = 1 секунда). */
    public static final int INTERVAL_TICKS = 2;
    /** Сколько тиков живёт один послеобраз (50 = 2.5 секунды). */
    public static int lifetimeTicks() { return Math.max(10, Math.round(SandevistanConfig.get().afterimageSeconds * 20)); } // из настроек (по умолчанию 50 = 2.5 с)
    /** Какую часть жизни послеобраз держится плотным, прежде чем начать таять (0.6 = 60%). */
    public static final float HOLD_PART = 0.6F;
    /** Максимум послеобразов одновременно. */
    public static int maxAfterimages() { return Math.max(10, SandevistanConfig.get().afterimageCount); } // на всех игроков вместе, из настроек
    /** Минимальный сдвиг игрока (в блоках) для нового снимка: стоя на месте, копии не появляются. */
    public static final double MIN_MOVE = 0.35;
    /** Прозрачность послеобраза, пока он не начал таять: 0..1 (1 = совсем непрозрачный). */
    public static final float START_ALPHA = 0.8F;
    /** Насколько меняется оттенок у каждого следующего послеобраза (доля круга: 0.03 = 11 градусов). */
    public static final float HUE_STEP = 0.03F;
    /** Начальный оттенок: 0.333 = зелёный. Дальше по кругу: голубой -> синий -> фиолетовый -> красный -> жёлтый -> зелёный. */
    public static final float START_HUE = 0.333F;
    /** Ближе этого расстояния к камере (в блоках) послеобраз полностью скрыт, чтобы не закрывать обзор. */
    public static final double HIDE_NEAR = 1.0;
    /** С этого расстояния послеобраз виден полностью; между HIDE_NEAR и SHOW_FAR плавно проявляется. */
    public static final double SHOW_FAR = 2.5;
    /** Киберпсихоз: насколько оттенок красных послеобразов уходит в малиновый/оранжевый (доля круга). */
    public static final float PSYCHOSIS_HUE_SWING = 0.035F;
    /** Цвет двойника-галлюцинации (RRGGBB). */
    public static final int HALLUCINATION_RGB = 0xE01020;
    /** Насыщенность и яркость цвета: 0..1. */
    public static final float SATURATION = 1.0F;
    public static final float BRIGHTNESS = 1.0F;
    /** Полная яркость (не зависят от освещения, как в аниме). */
    private static final int FULL_BRIGHT = 15728880;

    private static final Deque<AfterimageRenderState> AFTERIMAGES = new ArrayDeque<>();
    private static long clientTicks;
    private static int nextIndex;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SandevistanAfterimages::tick);
        LevelExtractionEvents.END_EXTRACTION.register(SandevistanAfterimages::addToFrame);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    private static void clear() {
        AFTERIMAGES.clear();
        LAST_POS.clear();
        nextIndex = 0;
    }

    private static void tick(Minecraft mc) {
        clientTicks++;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            clear();
            return;
        }

        // Старые послеобразы исчезают.
        while (!AFTERIMAGES.isEmpty() && clientTicks - AFTERIMAGES.peekFirst().bornTick >= lifetimeTicks()) {
            AFTERIMAGES.pollFirst();
        }

        // Послеобразы костюма-меха тяжёлые (270 кубиков) — у игрока в костюме они появляются вдвое реже.
        int interval = mc.player != null && com.smi7i.sandevistan.ModAttachments.hasMech(mc.player) ? INTERVAL_TICKS * 2 : INTERVAL_TICKS;
        if (clientTicks % interval != 0) return;
        // Свой игрок и все остальные игроки с работающим Сандевистаном (о них сообщает сервер).
        for (AbstractClientPlayer p : mc.level.players()) {
            boolean self = p == player;
            boolean on = self ? (ClientSandevistanState.active || ClientSandevistanState.psychosis())
                    : ClientZones.isActive(p.getId());
            boolean psychosis = self ? ClientSandevistanState.psychosis() : ClientZones.isPsychosis(p.getId());
            trySnapshot(mc, p, on, psychosis);
        }
        LAST_POS.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        while (AFTERIMAGES.size() > maxAfterimages()) {
            AFTERIMAGES.pollFirst();
        }
    }

    /** Снимок игрока, если у него работает Сандевистан и он сдвинулся достаточно (стоя на месте — нет копий). */
    private static void trySnapshot(Minecraft mc, AbstractClientPlayer p, boolean on, boolean psychosis) {
        if (!on) {
            LAST_POS.remove(p.getId());
            return;
        }
        Vec3 pos = p.position();
        Vec3 last = LAST_POS.get(p.getId());
        if (last == null) {
            // Первый снимок после включения не делаем на месте игрока, только запоминаем точку.
            LAST_POS.put(p.getId(), pos);
            return;
        }
        if (pos.distanceTo(last) < MIN_MOVE) return;
        LAST_POS.put(p.getId(), pos);
        AFTERIMAGES.addLast(snapshot(mc, p, psychosis));
    }

    /** Последнее место снимка для каждого игрока (по номеру сущности). */
    private static final java.util.Map<Integer, Vec3> LAST_POS = new java.util.HashMap<>();

    /** Добавить готовый снимок (клоны «Последнего боя» — их послеобразы живут как обычные). */
    public static void addExternal(AfterimageRenderState state) {
        AFTERIMAGES.addLast(state);
    }

    public static AfterimageRenderState snapshot(Minecraft mc, AbstractClientPlayer player) {
        return snapshot(mc, player, ClientSandevistanState.psychosis());
    }

    /** Снимает позу игрока тем же кодом, которым игра готовит его к отрисовке. */
    public static AfterimageRenderState snapshot(Minecraft mc, AbstractClientPlayer player, boolean psychosis) {
        AvatarRenderer<AbstractClientPlayer> renderer = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
        AfterimageRenderState state = new AfterimageRenderState();
        renderer.extractRenderState(player, state, 1.0F);

        // Убираем всё лишнее: имя, тень, огонь, поводки, подсветку, красный оттенок урона.
        state.nameTag = null;
        state.scoreText = null;
        state.leashStates = null;
        state.shadowPieces.clear();
        state.shadowRadius = 0.0F;
        state.displayFireAnimation = false;
        state.outlineColor = 0;
        state.hasRedOverlay = false;
        state.lightCoords = FULL_BRIGHT;

        state.bornTick = clientTicks;
        state.index = nextIndex++;
        state.psychosis = psychosis;
        return state;
    }

    /** 0 — послеобраз вплотную к камере (скрыт), 1 — далеко (виден полностью). */
    private static float nearCameraFade(AfterimageRenderState state, Vec3 cam) {
        // Расстояние от камеры до ближайшей точки «столбика» тела: от ног до макушки.
        double top = state.y + state.boundingBoxHeight;
        double nearestY = Mth.clamp(cam.y, state.y, top);
        double dx = cam.x - state.x;
        double dy = cam.y - nearestY;
        double dz = cam.z - state.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float k = (float) Mth.clamp((dist - HIDE_NEAR) / (SHOW_FAR - HIDE_NEAR), 0.0, 1.0);
        return k * k * (3.0F - 2.0F * k);
    }

    /**
     * Каждый кадр решает, где рисовать послеобразы и своего игрока.
     * Обычно — в отдельном проходе поверх зелёного фильтра (SandevistanWorldPass), чтобы фильтр их не красил.
     * С шейдерпаком Iris — как обычные сущности мира.
     */
    private static void addToFrame(LevelExtractionContext context) {
        SandevistanWorldPass.PENDING.clear();
        boolean ownPass = !IrisCompat.shadersActive();
        List<EntityRenderState> worldList = context.levelState().entityRenderStates;
        float partial = context.deltaTracker().getGameTimeDeltaPartialTick(true); // true: не зависит от замедления мира

        // Свой игрок (в виде от третьего лица): пока виден фильтр, рисуем его поверх фильтра.
        Minecraft mc = Minecraft.getInstance();
        if (ownPass && mc.player != null && ClientSandevistanState.anyFilter(partial)) {
            int playerId = mc.player.getId();
            for (Iterator<EntityRenderState> it = worldList.iterator(); it.hasNext(); ) {
                EntityRenderState state = it.next();
                if (state instanceof AvatarRenderState avatar && !(state instanceof AfterimageRenderState) && avatar.id == playerId) {
                    it.remove();
                    SandevistanWorldPass.PENDING.add(state);
                    break;
                }
            }
        }

        // Душа вне тела: игра не рисует своего игрока, если камера не в нём, — добавляем тело сами.
        if (PsychosisSoul.active() && mc.player != null) {
            EntityRenderState body = mc.getEntityRenderDispatcher().extractEntity(mc.player, partial);
            if (ownPass && ClientSandevistanState.anyFilter(partial)) {
                SandevistanWorldPass.PENDING.add(body);
            } else {
                worldList.add(body);
            }
        }

        // «Последний бой»: клоны рядом с летящим костюмом и «глитч-копии» у того, кто смотрит кат-сцену.
        com.smi7i.sandevistan.client.mech.MechShowdownClient.addToFrame(mc, partial,
                ownPass && ClientSandevistanState.anyFilter(partial) ? SandevistanWorldPass.PENDING : worldList, worldList);

        // Костюм-мех от первого лица: игра не рисует своего игрока — добавляем тело (без головы) и костюм сами.
        EntityRenderState fpBody = com.smi7i.sandevistan.client.mech.MechClient.firstPersonBody(mc, partial);
        if (fpBody != null) {
            if (ownPass && ClientSandevistanState.anyFilter(partial)) {
                SandevistanWorldPass.PENDING.add(fpBody);
            } else {
                worldList.add(fpBody);
            }
        }

        Vec3 camPos = context.camera().position();
        // От самого дальнего к самому ближнему: с шейдерами Iris полупрозрачные копии рисуются в порядке передачи,
        // и ближняя, нарисованная раньше, «закрывала» дальние — они пропадали.
        List<AfterimageRenderState> sorted = new ArrayList<>(AFTERIMAGES);
        sorted.sort(Comparator.comparingDouble((AfterimageRenderState s) ->
                (s.x - camPos.x) * (s.x - camPos.x) + (s.y - camPos.y) * (s.y - camPos.y) + (s.z - camPos.z) * (s.z - camPos.z))
                .reversed());
        for (AfterimageRenderState state : sorted) {
            float age = (clientTicks - state.bornTick) + partial;
            float progress = Mth.clamp(age / lifetimeTicks(), 0.0F, 1.0F);
            // Держится плотным, потом плавно тает.
            float fade = progress <= HOLD_PART ? 1.0F : 1.0F - (progress - HOLD_PART) / (1.0F - HOLD_PART);
            float alpha = START_ALPHA * fade * fade * (3.0F - 2.0F * fade) * nearCameraFade(state, camPos);
            if (alpha <= 0.01F) continue;

            int rgb;
            if (state.psychosis) {
                // Киберпсихоз: красные, с лёгким колебанием в малиновый и оранжевый.
                float hue = (1.0F + Mth.sin(state.index * 0.5F) * PSYCHOSIS_HUE_SWING) % 1.0F;
                rgb = Mth.hsvToRgb(hue, SATURATION, BRIGHTNESS);
            } else {
                float hue = (START_HUE + state.index * HUE_STEP) % 1.0F;
                rgb = Mth.hsvToRgb(hue, SATURATION, BRIGHTNESS);
            }
            state.afterimageColor = ARGB.color(alpha, rgb);
            if (ownPass) {
                SandevistanWorldPass.PENDING.add(state);
            } else {
                worldList.add(state);
            }
        }

        // Двойник-галлюцинация при киберпсихозе: тёмно-красный, мерцает.
        AfterimageRenderState ghost = PsychosisController.hallucination;
        if (ghost != null) {
            long slice = System.currentTimeMillis() / 90;
            float flicker = (float) ((slice * 2654435761L >>> 16) & 0xFF) / 255.0F;
            if (flicker > 0.12F) {
                float alpha = (0.45F + 0.45F * flicker) * nearCameraFade(ghost, camPos);
                ghost.afterimageColor = ARGB.color(alpha, HALLUCINATION_RGB);
                if (alpha > 0.01F) {
                    if (ownPass) {
                        SandevistanWorldPass.PENDING.add(ghost);
                    } else {
                        worldList.add(ghost);
                    }
                }
            }
        }
    }
}
