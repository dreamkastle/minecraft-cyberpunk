package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Настоящие 3D-голограммы: панели висят в пространстве перед камерой, повёрнуты под настоящим углом
 * (с перспективой), мягко «плывут» следом за взглядом. Видны сквозь блоки, текст светится сам.
 * Без шейдерпака рисуются в SandevistanWorldPass (поверх цветного фильтра), с Iris — через событие Fabric.
 */
public final class Holo3D {
    // ===== Настройки =====
    /** Размер одного «пикселя» панели в блоках. */
    public static final float PIXEL = 0.0055F;
    /** Насколько быстро голограмма догоняет взгляд (доля за тик): меньше — сильнее «плывёт». */
    public static final float FOLLOW = 0.35F;
    /** За сколько тиков панель «разворачивается» при появлении. */
    public static final float APPEAR_TICKS = 3.0F;
    private static final int FULL_BRIGHT = 15728880;
    /** Порядок отрисовки ярких плашек: больше — позже (поверх всего, что в обычном порядке 0). */
    private static final int ORDER_PANEL = 50, ORDER_TEXT = 51;

    /** Что нарисовать на панели (вызывается каждый кадр — можно показывать меняющиеся данные). */
    public interface Content {
        void draw(Canvas canvas, float alpha, long timeMs);
    }

    /** Одна голограмма. Положение задаётся относительно взгляда: вправо, вверх, вперёд (в блоках). */
    public static final class Panel {
        public float right, up, forward;
        /** Собственный наклон панели в градусах: поворот влево-вправо, вверх-вниз, крен. */
        public float yaw, pitch, roll;
        public int width, height;
        public int life;
        public Content content;
        public int salt;
        /**
         * Панель «прикреплена к экрану» (как интерфейс): каждый кадр ставится в угол того, что видно —
         * по настоящей матрице проекции (FOV и пропорции окна), без отставания при повороте головы.
         * hudX/hudY — угол: -1 — левый/нижний, 1 — правый/верхний; hudMargin — отступ (доля половины экрана).
         */
        public boolean hud;
        public float hudX = -1, hudY = 1, hudMargin = 0.06F;
        long born;
        Vec3 pos, prevPos;
        boolean removed;

        public void remove() {
            removed = true;
        }

        public float ageTicks(float partial) {
            return (ticks - born) + partial;
        }
    }

    private static final List<Panel> PANELS = new ArrayList<>();
    private static long ticks;

    private Holo3D() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PANELS.clear());
        // С шейдерпаком Iris рисуем вместе с миром (наш отдельный проход тогда выключен).
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!IrisCompat.shadersActive() || !hasAny()) return; // с шейдерами — только когда есть что рисовать
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submit(context.poseStack(), context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
    }

    public static Panel add(float right, float up, float forward, float yaw, float pitch, float roll,
                            int width, int height, int life, Content content) {
        Panel p = new Panel();
        p.right = right;
        p.up = up;
        p.forward = forward;
        p.yaw = yaw;
        p.pitch = pitch;
        p.roll = roll;
        p.width = width;
        p.height = height;
        p.life = life;
        p.content = content;
        p.salt = (int) (System.nanoTime() & 0xFFFF);
        p.born = ticks;
        PANELS.add(p);
        return p;
    }

    public static boolean hasAny() {
        return !PANELS.isEmpty();
    }

    public static int count() {
        return PANELS.size();
    }

    private static void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        Quaternionf rot = mc.gameRenderer.mainCamera().rotation();
        for (Iterator<Panel> it = PANELS.iterator(); it.hasNext(); ) {
            Panel p = it.next();
            if (p.removed || ticks - p.born > p.life) {
                it.remove();
                continue;
            }
            Vec3 desired = cam.add(toWorld(rot, p.right, p.up, -p.forward));
            if (p.pos == null) {
                p.pos = p.prevPos = desired;
            } else {
                p.prevPos = p.pos;
                p.pos = p.pos.lerp(desired, FOLLOW); // мягко «плывёт» за взглядом
            }
        }
    }

    private static Vec3 toWorld(Quaternionf rotation, float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z).rotate(rotation);
        return new Vec3(v.x, v.y, v.z);
    }

    /** Отправляет все голограммы на отрисовку. poseStack — в координатах относительно камеры. */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, float partial) {
        if (PANELS.isEmpty()) return;
        long now = System.currentTimeMillis();
        Vec3 cam = camera.pos;
        for (Panel p : PANELS) {
            if (p.pos == null && !p.hud) continue;
            float age = p.ageTicks(partial);
            float progress = Mth.clamp(age / p.life, 0.0F, 1.0F);
            float appear = Mth.clamp(age / APPEAR_TICKS, 0.0F, 1.0F);
            float fade = progress < 0.75F ? 1.0F : 1.0F - (progress - 0.75F) / 0.25F;
            float alpha = fade * HoloUi.flicker(now, p.salt);
            if (alpha <= 0.02F) continue;

            poseStack.pushPose();
            if (p.hud) {
                // В системе камеры: смотрит в -Z, X вправо, Y вверх. Видимая половина экрана на расстоянии d — d / m00, d / m11.
                poseStack.mulPose(camera.orientation);
                float d = p.forward;
                float halfW = d / camera.projectionMatrix.m00(), halfH = d / camera.projectionMatrix.m11();
                float w2 = p.width * PIXEL / 2, h2 = p.height * PIXEL / 2;
                // Наклонённая панель ближним краем кажется больше — берём с запасом.
                float yawK = (float) Math.cos(Math.toRadians(p.yaw)) * (1.0F + (float) Math.abs(Math.sin(Math.toRadians(p.yaw))) * w2 / d);
                float cx = p.hudX * Math.max(0, halfW * (1 - p.hudMargin) - w2 * yawK * 1.05F);
                float cy = p.hudY * Math.max(0, halfH * (1 - p.hudMargin) - h2 * 1.1F);
                poseStack.translate(cx, cy, -d);
            } else {
                Vec3 pos = p.prevPos.lerp(p.pos, partial).subtract(cam);
                poseStack.translate(pos.x, pos.y, pos.z);
                poseStack.mulPose(camera.orientation);      // лицом к камере...
            }
            poseStack.mulPose(Axis.YP.rotationDegrees(p.yaw));   // ...и собственный наклон в 3D
            poseStack.mulPose(Axis.XP.rotationDegrees(p.pitch));
            poseStack.mulPose(Axis.ZP.rotationDegrees(p.roll));
            poseStack.scale(PIXEL, -PIXEL * (0.15F + 0.85F * appear), PIXEL);
            poseStack.translate(-p.width / 2.0F, -p.height / 2.0F, 0.0F);

            Canvas canvas = new Canvas(poseStack, collector);
            p.content.draw(canvas, alpha, now);
            canvas.flush();
            poseStack.popPose();
        }
    }

    /** «Холст» панели: прямоугольники и текст в пикселях панели (0,0 — левый верхний угол). */
    public static final class Canvas {
        private final PoseStack poseStack;
        private final SubmitNodeCollector collector;
        private final List<float[]> quads = new ArrayList<>();
        private float layer;
        public final Font font = Minecraft.getInstance().font;

        Canvas(PoseStack poseStack, SubmitNodeCollector collector) {
            this.poseStack = poseStack;
            this.collector = collector;
        }

        /** Прямоугольник цвета ARGB. Каждый следующий чуть ближе к глазам (рисуется поверх предыдущих). */
        public void fill(float x0, float y0, float x1, float y1, int argb) {
            if (((argb >>> 24) & 0xFF) < 2) return;
            layer += 0.02F;
            quads.add(new float[]{x0, y0, x1, y1, Float.intBitsToFloat(argb), layer});
        }

        /** Мягкое свечение вокруг прямоугольника: несколько расширяющихся слоёв. */
        public void glow(float x0, float y0, float x1, float y1, int rgb, float strength, int radius) {
            for (int i = radius; i >= 1; i--) {
                float a = strength * (1.0F - (float) (i - 1) / radius) / radius;
                fill(x0 - i, y0 - i, x1 + i, y1 + i, HoloUi.argb(a, rgb));
            }
        }

        /** Рамка с яркими уголками. */
        public void frame(float x0, float y0, float x1, float y1, int rgb, float alpha) {
            int border = HoloUi.argb(0.6F * alpha, rgb);
            fill(x0, y0, x1, y0 + 1, border);
            fill(x0, y1 - 1, x1, y1, border);
            fill(x0, y0, x0 + 1, y1, border);
            fill(x1 - 1, y0, x1, y1, border);
            int corner = HoloUi.argb(alpha, rgb);
            float c = 7;
            fill(x0 - 1, y0 - 1, x0 + c, y0 + 1, corner);
            fill(x0 - 1, y0 - 1, x0 + 1, y0 + c, corner);
            fill(x1 - c, y1 - 1, x1 + 1, y1 + 1, corner);
            fill(x1 - 1, y1 - c, x1 + 1, y1 + 1, corner);
        }

        /** Голографическая панель целиком: свечение, фон, развёртка, рамка. */
        public void panel(float w, float h, int rgb, float alpha) {
            glow(0, 0, w, h, rgb, 0.25F * alpha, 5);
            fill(0, 0, w, h, HoloUi.argb(0.42F * alpha, (rgb >> 3) & 0x1F1F1F));
            for (float y = 1; y < h - 1; y += 2) {
                fill(1, y, w - 1, y + 1, HoloUi.argb(0.07F * alpha, rgb));
            }
            frame(0, 0, w, h, rgb, alpha);
        }

        /** Текст (светится сам). scale — увеличение. */
        public void text(String text, float x, float y, int rgb, float alpha, float scale) {
            layer += 0.05F;
            poseStack.pushPose();
            poseStack.translate(x, y, layer); // ближе к глазам, чем фон (после разворота к камере +Z смотрит на неё)
            poseStack.scale(scale, scale, 1.0F);
            if (brightText) {
                // Яркий текст: рисуется отдельным, более поздним «слоем» (order) — после фона панели и после всего мира
                // (дыма, пыли), без проверки глубины: его ничто не закрывает.
                collector.order(ORDER_TEXT).submitText(poseStack, 0.0F, 0.0F, Component.literal(text).getVisualOrderText(), true,
                        Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, HoloUi.argb(Math.min(1.0F, alpha * 1.2F), rgb), 0, 0);
            } else {
                collector.submitText(poseStack, 0.0F, 0.0F, Component.literal(text).getVisualOrderText(), false,
                        Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, HoloUi.argb(alpha, rgb), 0, 0);
            }
            poseStack.popPose();
        }

        /** Яркая плашка-интерфейс: фон и текст рисуются последними, поверх мира и эффектов (текст — поверх фона). */
        public boolean brightText;

        public void centeredText(String text, float cx, float y, int rgb, float alpha, float scale) {
            text(text, cx - font.width(text) * scale / 2.0F, y, rgb, alpha, scale);
        }

        void flush() {
            if (quads.isEmpty()) return;
            List<float[]> list = new ArrayList<>(quads);
            // «Сквозной» фон рисуется без проверки глубины и игра выводит его ПОСЛЕ текста — он ложился тёмной плёнкой
            // поверх букв. Для яркого текста фон — обычный (с глубиной): тогда текст, который ближе к глазам, всегда сверху.
            // Для яркой плашки фон — свой поздний «слой» (order): поверх мира и эффектов, но раньше текста.
            boolean depth = false;
            OrderedSubmitNodeCollector target = brightText ? collector.order(ORDER_PANEL) : collector;
            target.submitCustomGeometry(poseStack, RenderTypes.textBackgroundSeeThrough(),
                    (pose, buffer) -> {
                for (float[] q : list) {
                    int argb = Float.floatToRawIntBits(q[4]);
                    float z = q[5];
                    // С обеих сторон, чтобы панель была видна под любым углом.
                    float[][] v = {{q[0], q[1]}, {q[0], q[3]}, {q[2], q[3]}, {q[2], q[1]}};
                    int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
                    for (int k : order) {
                        var c = buffer.addVertex(pose, v[k][0], v[k][1], z).setColor(argb);
                        if (depth) c.setLight(FULL_BRIGHT);
                    }
                }
            });
        }
    }
}
