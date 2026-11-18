package com.smi7i.sandevistan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.math.Axis;
import com.smi7i.sandevistan.mixin.client.ItemStackRenderStateAccessor;
import com.smi7i.sandevistan.mixin.client.LayerRenderStateInvoker;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Надетый имплант виден на спине игрока, вдоль позвоночника. Двигается вместе с телом.
 * Свечение — по текстуре-маске пользователя sandevistan_glow.png (модель item/sandevistan_glow):
 * её грани рисуются поверх импланта «светящимся» способом (как глаза паука), с регулируемой силой.
 * Модели кладёт в состояние отрисовки AvatarRendererExtractMixin.
 */
public class ImplantBackLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** Модель импланта для этого игрока (пустая/нет — имплант не надет). */
    public static final RenderStateDataKey<ItemStackRenderState> IMPLANT_ITEM =
            RenderStateDataKey.create(() -> "sandevistan:implant_item");
    /** Модель маски свечения (та же модель, текстура sandevistan_glow). */
    public static final RenderStateDataKey<ItemStackRenderState> IMPLANT_GLOW =
            RenderStateDataKey.create(() -> "sandevistan:implant_glow");

    // ===== Настройки положения (в блоках, относительно тела) =====
    /** Сдвиг вниз от шеи: 0.25 — модель стоит по центру спины. */
    private static final float OFFSET_DOWN = 0.25F;
    /** Насколько отступить от спины без нагрудника и с нагрудником. */
    private static final float OFFSET_BACK = 0.13F;
    private static final float OFFSET_BACK_ARMOR = 0.2F;

    // ===== Настройки свечения =====
    /** Сила свечения в покое. 1 = как текстура при полном свете; больше — ярче (рисуется в несколько слоёв). */
    private static final float GLOW_IDLE = 0.35F;
    /** Сила свечения во время работы импланта/приступа (на пике пульсации). */
    private static final float GLOW_ACTIVE = 4.5F;
    /** Глубина пульсации при работе: 0.5 — яркость гуляет от половины до максимума. */
    private static final float PULSE_DEPTH = 0.5F;
    /** Скорость пульсации (больше — быстрее). */
    private static final float PULSE_SPEED = 11.0F;
    /** Сила свечения импланта на послеобразах (умножается на их прозрачность). */
    private static final float GLOW_AFTERIMAGE = 2.0F;
    private static final int FULL_BRIGHT = 15728880;

    public ImplantBackLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    public static void init() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                @SuppressWarnings("unchecked")
                RenderLayerParent<AvatarRenderState, PlayerModel> parent =
                        (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
                helper.register(new ImplantBackLayer(parent));
            }
        });
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
                       float yRot, float xRot) {
        ItemStackRenderState item = state.getData(IMPLANT_ITEM);
        if (item == null || item.isEmpty() || (state.isInvisible && !(state instanceof AfterimageRenderState))) return;

        boolean armor = !state.chestEquipment.isEmpty();
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack); // следуем за телом (наклоны, присед)
        poseStack.translate(0.0F, OFFSET_DOWN, armor ? OFFSET_BACK_ARMOR : OFFSET_BACK);
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F)); // модель предмета «вверх ногами» относительно тела

        ItemStackRenderState glow = state.getData(IMPLANT_GLOW);
        if (state instanceof AfterimageRenderState afterimage) {
            // На послеобразе — только светящаяся маска (сам имплант непрозрачный, на силуэте смотрелся бы плохо).
            // Послеобраз полупрозрачный — точки на спине просвечивали бы насквозь, когда он стоит лицом к камере.
            // Поэтому свечение видно, только если смотреть ему в спину.
            float alpha = ((afterimage.afterimageColor >>> 24) & 0xFF) / 255.0F;
            float fromBehind = backFacingCamera(afterimage);
            if (glow != null && !glow.isEmpty() && fromBehind > 0.01F) {
                submitGlow(poseStack, collector, glow, alpha * GLOW_AFTERIMAGE * fromBehind);
            }
        } else {
            item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            if (glow != null && !glow.isEmpty()) submitGlow(poseStack, collector, glow, glowStrength(state));
        }
        poseStack.popPose();
    }

    /** 1 — камера сзади послеобраза (спина видна), 0 — спереди или сбоку. Плавно между ними. */
    private static float backFacingCamera(AvatarRenderState state) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
        double dx = cam.x - state.x;
        double dz = cam.z - state.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) return 0.0F;
        float rad = state.bodyRot * Mth.DEG_TO_RAD;
        // Направление спины: противоположно тому, куда смотрит тело.
        double backX = Mth.sin(rad);
        double backZ = -Mth.cos(rad);
        float dot = (float) ((dx * backX + dz * backZ) / len);
        float k = Mth.clamp((dot - 0.15F) / 0.45F, 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    /** Сила свечения: своему игроку — по состоянию импланта, другим — по данным сервера (работает ли у них Сандевистан). */
    private static float glowStrength(AvatarRenderState state) {
        return glowStrengthRaw(state) * SandevistanConfig.get().glowStrength; // яркость из настроек
    }

    private static float glowStrengthRaw(AvatarRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.id != mc.player.getId()) {
            if (!ClientZones.isActive(state.id)) return GLOW_IDLE;
            float wave = 0.5F + 0.5F * Mth.sin(System.currentTimeMillis() / 1000.0F * PULSE_SPEED);
            return GLOW_ACTIVE * (1.0F - PULSE_DEPTH + PULSE_DEPTH * wave);
        }
        float g = ClientSandevistanState.glowForFrame(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        float k = Mth.clamp((g - ClientSandevistanState.IDLE_GLOW) / (1.0F - ClientSandevistanState.IDLE_GLOW), 0.0F, 1.0F);
        float strength = GLOW_IDLE + (GLOW_ACTIVE - GLOW_IDLE) * k;
        if (ClientSandevistanState.active || ClientSandevistanState.psychosis()) {
            float wave = 0.5F + 0.5F * Mth.sin(System.currentTimeMillis() / 1000.0F * PULSE_SPEED);
            strength *= 1.0F - PULSE_DEPTH + PULSE_DEPTH * wave; // сильная пульсация при работе
        }
        return strength;
    }

    /** Рисует грани маски свечения со сложением света. Сила больше 1 — несколько слоёв друг на друге. */
    private static void submitGlow(PoseStack poseStack, SubmitNodeCollector collector, ItemStackRenderState glow, float strength) {
        ItemStackRenderStateAccessor access = (ItemStackRenderStateAccessor) glow;
        ItemStackRenderState.LayerRenderState[] layers = access.sandevistan$layers();
        int count = access.sandevistan$activeLayerCount();

        for (int i = 0; i < count; i++) {
            ItemStackRenderState.LayerRenderState layer = layers[i];
            List<BakedQuad> quads = layer.prepareQuadList();
            if (quads.isEmpty()) continue;

            poseStack.pushPose();
            ((LayerRenderStateInvoker) layer).sandevistan$applyTransform(poseStack.last());
            var renderType = RenderTypes.eyes(quads.get(0).materialInfo().sprite().atlasLocation());
            for (float left = strength; left > 0.01F; left -= 1.0F) {
                int c = Math.round(Mth.clamp(left, 0.0F, 1.0F) * 255.0F);
                QuadInstance instance = new QuadInstance();
                instance.setColor(0xFF000000 | (c << 16) | (c << 8) | c);
                instance.setLightCoords(FULL_BRIGHT);
                instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
                collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
                    for (BakedQuad quad : quads) {
                        buffer.putBakedQuad(pose, quad, instance);
                    }
                });
            }
            poseStack.popPose();
        }
    }
}
