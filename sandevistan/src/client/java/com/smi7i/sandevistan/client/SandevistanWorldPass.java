package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Отдельный проход сразу после мира (до руки и интерфейса), пока в кадре ещё есть глубина мира:
 *   1) зелёный фильтр на весь экран;
 *   2) поверх него — свой игрок и послеобразы в настоящих цветах (стены их по-прежнему закрывают).
 * Вызывается из GameRendererMixin. При включённом шейдерпаке Iris не используется (см. IrisCompat).
 */
public final class SandevistanWorldPass {
    /** Игрок и послеобразы, отложенные из обычной отрисовки мира на этот кадр. */
    static final List<EntityRenderState> PENDING = new ArrayList<>();

    private static final RenderType FULLSCREEN_MULTIPLY = fullscreenType("fullscreen_multiply_2x", SandevistanHud.MULTIPLY_2X);
    private static final RenderType FULLSCREEN_ADD = fullscreenType("fullscreen_add", BlendFunction.ADDITIVE);

    private SandevistanWorldPass() {}

    private static RenderType fullscreenType(String name, BlendFunction blend) {
        RenderPipeline pipeline = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("sandevistan", "pipeline/" + name))
                        .withColorTargetState(new ColorTargetState(blend))
                        .withCull(false)
                        .build());
        return RenderType.create("sandevistan_" + name, RenderSetup.builder(pipeline).createRenderSetup());
    }

    public static void render(Minecraft mc, CameraRenderState camera, DeltaTracker deltaTracker,
                              FeatureRenderDispatcher features, SubmitNodeStorage storage) {
        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
        float t = ClientSandevistanState.tintForFrame(partial);
        float red = ClientSandevistanState.overheatForFrame(partial);
        boolean filter = ClientSandevistanState.anyFilter(partial) && !IrisCompat.shadersActive();
        boolean holograms = Holo3D.hasAny() && !IrisCompat.shadersActive();
        boolean bursts = HitBurst.hasAny() && !IrisCompat.shadersActive();
        boolean mechFx = com.smi7i.sandevistan.client.mech.MechFx.hasAny() && !IrisCompat.shadersActive();
        boolean stage = com.smi7i.sandevistan.client.mech.MechShowdownClient.hasStage() && !IrisCompat.shadersActive();
        boolean sing = com.smi7i.sandevistan.client.mech.MechSingularityClient.hasAny() && !IrisCompat.shadersActive();
        boolean sky = com.smi7i.sandevistan.client.mech.SkyShatterClient.hasAny() && !IrisCompat.shadersActive();
        boolean push = com.smi7i.sandevistan.client.mech.MechPushClient.hasAny() && !IrisCompat.shadersActive();
        if (!filter && PENDING.isEmpty() && !holograms && !bursts && !mechFx && !stage && !sing && !sky && !push) return;
        // «Сингулярность» / «Магнит»: искажение света — до всех наших эффектов.
        if (sing || push) com.smi7i.sandevistan.client.mech.MechSingularityClient.lensPass(mc, camera, partial);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().mul(camera.viewRotationMatrix);
        try {
            if (filter) {
                // Прямоугольник прямо перед камерой, больше экрана. Координаты в пространстве камеры.
                PoseStack screen = new PoseStack();
                screen.mulPose(new Matrix4f(camera.viewRotationMatrix).invert());
                int multiply = SandevistanHud.multiplyColor(t, red);
                int glow = SandevistanHud.glowColor(t);
                storage.submitCustomGeometry(screen, FULLSCREEN_MULTIPLY, (pose, buffer) -> quad(pose, buffer, multiply));
                if ((glow & 0xFFFFFF) != 0) {
                    storage.submitCustomGeometry(screen, FULLSCREEN_ADD, (pose, buffer) -> quad(pose, buffer, glow));
                }
                features.renderAllFeatures(storage);
            }

            if (!PENDING.isEmpty()) {
                EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
                Vec3 cam = camera.pos;
                PoseStack poseStack = new PoseStack();
                for (EntityRenderState state : PENDING) {
                    dispatcher.submit(state, camera, state.x - cam.x(), state.y - cam.y(), state.z - cam.z(), poseStack, storage);
                }
                features.renderAllFeatures(storage);
            }

            if (stage) {
                // Кат-сцена «Последнего боя»: Луна, Земля, космос, две сидящие фигуры.
                com.smi7i.sandevistan.client.mech.MechShowdownClient.submitStage(storage, camera, partial);
                features.renderAllFeatures(storage);
            }

            if (mechFx) {
                // Дым, пыль, куски блоков и красный круг костюма-меха.
                com.smi7i.sandevistan.client.mech.MechFx.submit(storage, camera, partial);
                features.renderAllFeatures(storage);
            }

            if (push) {
                // «Магнит»: кольца у ладоней, дуги, шар, ударная дуга.
                com.smi7i.sandevistan.client.mech.MechPushClient.submit(storage, camera, partial);
                features.renderAllFeatures(storage);
            }

            if (sky) {
                // Расколотое небо: дыры в глитчевый космос, трещины, падающие куски.
                com.smi7i.sandevistan.client.mech.SkyShatterClient.submit(storage, camera, partial);
                features.renderAllFeatures(storage);
            }

            if (sing) {
                // Горизонт событий (чёрное) — раньше, потом светящееся: диск, кольца, молнии, сверхновая.
                com.smi7i.sandevistan.client.mech.MechSingularityClient.submitDark(storage, camera, partial);
                features.renderAllFeatures(storage);
                com.smi7i.sandevistan.client.mech.MechSingularityClient.submitGlow(storage, camera, partial);
                features.renderAllFeatures(storage);
            }

            if (bursts) {
                // Вспышки ударов — после фильтра, чтобы остались белыми; стены их закрывают.
                HitBurst.submit(storage, camera, partial);
                features.renderAllFeatures(storage);
            }

            if (holograms) {
                // 3D-голограммы — последними, поверх всего мира и без цветного фильтра.
                Holo3D.submit(new PoseStack(), storage, camera, partial);
                features.renderAllFeatures(storage);
            }
        } finally {
            modelView.popMatrix();
            PENDING.clear();
        }
    }

    private static void quad(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, int color) {
        float s = 50.0F;
        float z = -1.0F;
        buffer.addVertex(pose, -s, -s, z).setColor(color);
        buffer.addVertex(pose, s, -s, z).setColor(color);
        buffer.addVertex(pose, s, s, z).setColor(color);
        buffer.addVertex(pose, -s, s, z).setColor(color);
    }
}
