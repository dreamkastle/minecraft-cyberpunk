package com.smi7i.sandevistan.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.smi7i.sandevistan.client.ClientSandevistanState;
import com.smi7i.sandevistan.client.SandevistanWorldPass;
import com.smi7i.sandevistan.client.PsychosisSoul;
import org.joml.Matrix4fc;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Сразу после отрисовки мира (до очистки глубины и руки) запускает наш проход: фильтр + игрок + послеобразы. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow private Minecraft minecraft;
    @Shadow private GameRenderState gameRenderState;
    @Shadow private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow private SubmitNodeStorage handAndScreenSubmitNodeStorage;

    /** Покачивание экрана при перегреве. bobHurt наклоняет камеру при уроне; добавляем свой наклон туда же. */
    @Inject(method = "bobHurt", at = @At("RETURN"))
    private void sandevistan$overheatSway(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (PsychosisSoul.active()) return;
        com.smi7i.sandevistan.client.mech.MechShake.apply(poseStack); // тряска от шагов костюма-меха
        float amount = ClientSandevistanState.overheat * (0.35F + 0.65F * ClientSandevistanState.overheatFreshness());
        if (amount <= 0.002F) return;
        float time = (Util.getMillis() % 1_000_000L) / 1000.0F;
        // Медленное «плавание» в разные стороны с разной частотой, чтобы не было ровного маятника.
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(time * 1.9F) * SWAY_ROLL * amount));
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.sin(time * 1.3F + 1.0F) * SWAY_PITCH * amount));
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.sin(time * 0.9F + 2.0F) * SWAY_YAW * amount));
    }

    /** Пока камера в душе — никаких покачиваний картинки (ходьба, урон), чтобы ничего не тряслось. */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noBobForSoul(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (PsychosisSoul.active()) ci.cancel();
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noHurtBobForSoul(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (PsychosisSoul.active()) ci.cancel();
    }

    /** Пока душа вне тела, руки «от первого лица» не рисуются. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noHandsForSoul(CameraRenderState cameraState, float partialTick, Matrix4fc modelView, CallbackInfo ci) {
        // Пока душа вне тела — и в костюме-мехе (у игрока нет рук) — руки «от первого лица» не рисуются.
        if (PsychosisSoul.active() || com.smi7i.sandevistan.client.mech.MechClient.localInMech()) ci.cancel();
    }

    /** Сила покачивания в градусах: крен, кивок, поворот. */
    private static final float SWAY_ROLL = 6.0F;
    private static final float SWAY_PITCH = 2.5F;
    private static final float SWAY_YAW = 2.5F;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"))
    private void sandevistan$afterWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        SandevistanWorldPass.render(minecraft, gameRenderState.levelRenderState.cameraRenderState, deltaTracker,
                featureRenderDispatcher, handAndScreenSubmitNodeStorage);
    }
}
