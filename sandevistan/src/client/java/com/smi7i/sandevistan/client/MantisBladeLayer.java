package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Достанные клинки богомола на руках игрока (вид от третьего лица). Клинок рисуется так же, как предмет в руке,
 * по настройкам display модели (thirdperson_lefthand / thirdperson_righthand). Модели кладёт в состояние
 * отрисовки AvatarRendererBladeMixin.
 */
public class MantisBladeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public static final RenderStateDataKey<ItemStackRenderState> BLADE_LEFT =
            RenderStateDataKey.create(() -> "sandevistan:blade_left");
    public static final RenderStateDataKey<ItemStackRenderState> BLADE_RIGHT =
            RenderStateDataKey.create(() -> "sandevistan:blade_right");

    public MantisBladeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    public static void init() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                @SuppressWarnings("unchecked")
                RenderLayerParent<AvatarRenderState, PlayerModel> parent =
                        (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
                helper.register(new MantisBladeLayer(parent));
            }
        });
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
                       float yRot, float xRot) {
        if (state.isInvisible || state instanceof AfterimageRenderState) return;
        submitBlade(poseStack, collector, light, state, state.getData(BLADE_LEFT), true);
        submitBlade(poseStack, collector, light, state, state.getData(BLADE_RIGHT), false);
    }

    private void submitBlade(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
                             ItemStackRenderState item, boolean left) {
        if (item == null || item.isEmpty()) return;
        ModelPart arm = left ? getParentModel().leftArm : getParentModel().rightArm;
        poseStack.pushPose();
        arm.translateAndRotate(poseStack);                 // следуем за рукой (взмах, присед)
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F)); // так же, как игра ставит предмет в руку
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate((left ? -1 : 1) / 16.0F, 0.125F, -0.625F);
        item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}
