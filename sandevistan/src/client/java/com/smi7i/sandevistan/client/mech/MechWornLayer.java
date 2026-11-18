package com.smi7i.sandevistan.client.mech;

import com.smi7i.sandevistan.mech.MechManager;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Костюм-мех на игроке: рисуется вокруг его модели. Голова и тело игрока подняты на место «пилота»
 * (PlayerModelMechMixin), руки и ноги скрыты — подходит любой скин.
 */
public class MechWornLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** Игрок в костюме (кладётся в состояние отрисовки в AvatarRendererExtractMixin). */
    public static final RenderStateDataKey<Boolean> IN_MECH = RenderStateDataKey.create(() -> "sandevistan:in_mech");
    /** Уровень жидкости в шприцах костюма, 0..1 (уколов осталось / 5). */
    public static final RenderStateDataKey<Float> LIQUID = RenderStateDataKey.create(() -> "sandevistan:mech_liquid");
    /** Время анимации ходьбы (секунды, назад — уменьшается), из MechMotion. */
    public static final RenderStateDataKey<Float> WALK_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_walk_time");
    /** Вес ходьбы 0..1 (стоит / идёт / переступает на развороте), из MechMotion. */
    public static final RenderStateDataKey<Float> WALK_WEIGHT = RenderStateDataKey.create(() -> "sandevistan:mech_walk_weight");
    /** Шаг вбок: время и вес анимации strafe. */
    /** «Магнит»: секунды с начала (-1 — нет). */
    /** Доля здоровья костюма (для мигания огней при повреждениях). */
    /** Крен в повороте (градусы). */
    public static final RenderStateDataKey<Float> BANK = RenderStateDataKey.create(() -> "sandevistan:mech_bank");
    public static final RenderStateDataKey<Float> HP = RenderStateDataKey.create(() -> "sandevistan:mech_hp");
    /** Сколько стадий повреждений пройдено (отвалившиеся части не рисуются) и id игрока (для обломков). */
    public static final RenderStateDataKey<Integer> LOST = RenderStateDataKey.create(() -> "sandevistan:mech_lost");
    public static final RenderStateDataKey<Integer> OWNER_ID = RenderStateDataKey.create(() -> "sandevistan:mech_owner_id");
    public static final RenderStateDataKey<Float> PUSH_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_push_time");
    public static final RenderStateDataKey<Float> STRAFE_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_strafe_time");
    public static final RenderStateDataKey<Float> STRAFE_WEIGHT = RenderStateDataKey.create(() -> "sandevistan:mech_strafe_weight");
    /** Время атаки костюма (секунды с начала), -1 — не атакует. */
    public static final RenderStateDataKey<Float> ATTACK_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_attack_time");
    /** Насколько (пиксели модели) голова и тело игрока выше обычного — по силуэту в модели костюма. */
    public static final float PILOT_LIFT = 13.0F;

    public MechWornLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    /** Время посадки в костюм (секунды с ПКМ), -1 — не садится. */
    public static final RenderStateDataKey<Float> EQUIP_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_equip_time");
    /** Своё тело, нарисованное для вида от первого лица (голова скрыта). */
    public static final RenderStateDataKey<Boolean> FIRST_PERSON = RenderStateDataKey.create(() -> "sandevistan:mech_first_person");

    /** Персонаж нарисован для кат-сцены (в интерфейсе) — моды анимаций (EMF / Fresh Animations) позу не трогают. */
    public static final RenderStateDataKey<Boolean> STAGE = RenderStateDataKey.create(() -> "sandevistan:stage");
    /** «Последний бой»: скользит по земле (анимация glide вместо шагов). */
    public static final RenderStateDataKey<Boolean> GLIDE = RenderStateDataKey.create(() -> "sandevistan:mech_glide");
    /** Кат-сцена на Луне: сидит, руки упираются в грунт позади. */
    public static final RenderStateDataKey<Boolean> STAGE_SIT = RenderStateDataKey.create(() -> "sandevistan:stage_sit");
    /** «Последний бой»: пилот смотрит кат-сцену — со стороны у него трясётся голова. */
    public static final RenderStateDataKey<Boolean> SHOWDOWN_GLITCH = RenderStateDataKey.create(() -> "sandevistan:mech_showdown_glitch");
    /** Гибель после «Последнего боя»: секунды с начала анимации death, -1 — нет. */
    public static final RenderStateDataKey<Float> DEATH_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_death_time");
    /** «Вихрь»: секунды с начала, -1 — нет. */
    public static final RenderStateDataKey<Float> SKILL_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_skill_time");
    /** Кат-сцена посадки: тики с ПКМ, -1 — нет. */
    public static final RenderStateDataKey<Float> BOARD_TIME = RenderStateDataKey.create(() -> "sandevistan:mech_board_time");

    public static float boardTime(AvatarRenderState state) {
        Float t = state.getData(BOARD_TIME);
        return t == null ? -1 : t;
    }

    public static boolean inMech(AvatarRenderState state) {
        return Boolean.TRUE.equals(state.getData(IN_MECH));
    }

    public static boolean firstPerson(AvatarRenderState state) {
        return Boolean.TRUE.equals(state.getData(FIRST_PERSON));
    }

    /** Насколько пилот забрался в костюм при посадке: 0 — стоит на земле, 1 — сидит на месте (и вне посадки). */
    public static float climb(AvatarRenderState state) {
        Float t = state.getData(EQUIP_TIME);
        return t == null || t < 0 ? 1.0F : MechPoses.climb(t);
    }

    public static void register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                @SuppressWarnings("unchecked")
                RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
                helper.register(new MechWornLayer(parent));
            }
        });
    }

    /**
     * Огни костюма при гибели: отказ систем — резко мигают, потом гаснут с последними вспышками, к 3.2 с темно.
     */
    public static float deathGlow(float t) {
        if (t >= 3.2F) return 0.0F;
        long slot = (long) (t * 14);
        float rnd = (float) ((Math.sin(slot * 12.9898) * 43758.5453) % 1.0 + 1.0) % 1.0F;
        if (t < 1.3F) return rnd < 0.35F ? 0.1F : 1.2F;
        float fade = 1.0F - (t - 1.3F) / 1.9F;
        return rnd < 0.5F ? 0.0F : fade * fade;
    }

    /** Опора корпуса костюма над землёй (пиксели модели) — вокруг неё поворачивается пилот. */
    private static final float TORSO_PIVOT = 24.0F;

    /**
     * Перенос модели игрока на место пилота (подъём + поворот вместе с корпусом костюма) или точная отмена (inverse).
     * Система координат — после масштаба модели игрока: начало у ступней, оси X и Y перевёрнуты (scale -1,-1,1),
     * поэтому «вверх» — минус, а повороты вокруг X и Y — с обратным знаком.
     */
    public static void pilotTransform(PoseStack poseStack, AvatarRenderState state, boolean inverse) {
        MechModel.Pose pose = pose(state);
        // При посадке пилот поднимается с земли на своё место (с небольшим подскоком).
        float k = climb(state);
        float lift = k * (PILOT_LIFT + pose.posY("torso")) + MechPoses.climbHop(k);
        float[] t = pose.rot("torso");
        if (k < 1.0F) t = new float[]{t[0] * k, t[1] * k, t[2] * k};
        boolean rotate = t[0] != 0 || t[1] != 0 || t[2] != 0;
        // Опора корпуса костюма относительно поднятой модели. Корпус в позе опущен на posY, и его опора — тоже:
        // она на высоте TORSO_PIVOT + posY над ступнями. Раньше posY здесь не учитывался — при сильном наклоне
        // (анимация гибели: корпус опущен на 11 px) пилот поворачивался вокруг точки выше и «отрывался» от костюма.
        float pivot = (lift - TORSO_PIVOT - pose.posY("torso")) / 16.0F;
        // Сдвиг корпуса вперёд/вбок (в анимации гибели корпус падает вперёд — поворот вокруг колен даёт сдвиг по Z).
        // Без него пилот поворачивался на месте и «вываливался» из падающего костюма. Ось X в системе игрока перевёрнута.
        float[] tp = pose.pos("torso");
        float sx = -tp[0] * k / 16.0F, sz = tp[2] * k / 16.0F;
        if (!inverse) {
            poseStack.translate(sx, -lift / 16.0F, sz);
            if (rotate) {
                poseStack.translate(0.0F, pivot, 0.0F);
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(t[2]));
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-t[1]));
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-t[0]));
                poseStack.translate(0.0F, -pivot, 0.0F);
            }
        } else {
            if (rotate) {
                poseStack.translate(0.0F, pivot, 0.0F);
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(t[0]));
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(t[1]));
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-t[2]));
                poseStack.translate(0.0F, -pivot, 0.0F);
            }
            poseStack.translate(-sx, lift / 16.0F, -sz);
        }
    }

    /** Насколько (пиксели модели) поднята вся модель игрока: место пилота + проседание корпуса костюма. */
    public static float pilotLift(AvatarRenderState state) {
        float k = climb(state);
        return k * (PILOT_LIFT + pose(state).posY("torso")) + MechPoses.climbHop(k);
    }

    /** Поза костюма для этого кадра — из движения игрока (или посадка). */
    public static MechModel.Pose pose(AvatarRenderState state) {
        Float equip = state.getData(EQUIP_TIME);
        if (equip != null && equip >= 0 && equip < MechPoses.EQUIP_SECONDS) {
            MechModel.Pose p = MechPoses.equip(equip, state.ageInTicks);
            Float liquid = state.getData(LIQUID);
            if (liquid != null) p.liquid = liquid;
            return p;
        }
        Float walkTime = state.getData(WALK_TIME);
        Float weight = state.getData(WALK_WEIGHT);
        Float attack = state.getData(ATTACK_TIME);
        Float strafeTime = state.getData(STRAFE_TIME);
        Float strafeWeight = state.getData(STRAFE_WEIGHT);
        MechModel.Pose pose = MechPoses.worn(walkTime != null ? walkTime : 0, weight != null ? weight : 0,
                strafeTime != null ? strafeTime : 0, strafeWeight != null ? strafeWeight : 0, state.ageInTicks,
                attack != null ? attack : -1);
        if (Boolean.TRUE.equals(state.getData(GLIDE))) {
            MechModel.Anim glide = MechModel.get().anim("glide");
            if (glide != null) pose.blend(glide, state.ageInTicks / 20.0F, 1.0F); // скользит, а не шагает
        }
        Float skill = state.getData(SKILL_TIME);
        MechModel.Anim skillAnim = MechModel.get().anim("skill");
        if (skill != null && skill >= 0 && skillAnim != null && skill < skillAnim.length()) {
            // «Вихрь»: поверх всего, плавный вход и выход (анимация начинается и кончается стойкой).
            float w = Math.max(0, Math.min(1, Math.min(skill / 0.15F, (skillAnim.length() - skill) / 0.2F)));
            pose.blend(skillAnim, skill, w);
        }
        // Повреждённый костюм: огни мигают, почти разбитый — гаснут надолго.
        Float hp = state.getData(HP);
        if (hp != null && hp < 0.4F) {
            long slot = (long) (state.ageInTicks * 0.7F);
            float rnd = (float) (((Math.sin(slot * 12.9898 + 4.1) * 43758.5453) % 1.0 + 1.0) % 1.0);
            float off = hp < 0.15F ? 0.55F : hp < MechManager.LIMP_HP ? 0.35F : 0.15F;
            if (rnd < off) pose.glow *= 0.1F;
        }
        Float bank = state.getData(BANK);
        if (bank != null && Math.abs(bank) > 0.05F) {
            pose.rot("torso")[2] += bank;
            pose.rot("left_arm_carrier")[2] += bank * 0.9F;
            pose.rot("right_arm_carrier")[2] += bank * 0.9F;
        }
        Float pushT = state.getData(PUSH_TIME);
        MechModel.Anim pushAnim = MechModel.get().anim("push");
        if (pushT != null && pushT >= 0 && pushAnim != null && pushT < pushAnim.length()) {
            float w = Math.max(0, Math.min(1, Math.min(pushT / 0.12F, (pushAnim.length() - pushT) / 0.25F)));
            pose.blend(pushAnim, pushT, w);
        }
        Float death = state.getData(DEATH_TIME);
        MechModel.Anim deathAnim = MechModel.get().anim("death");
        if (death != null && death >= 0 && deathAnim != null) {
            // Гибель: костюм падает лицом в землю и так и остаётся (последний кадр держится).
            float w = Math.max(0, Math.min(1, death / 0.15F));
            pose.blend(deathAnim, Math.min(death, deathAnim.length()), w);
            pose.glow = deathGlow(death);
        }
        Float liquid = state.getData(LIQUID);
        if (liquid != null) pose.liquid = liquid;
        Integer lost = state.getData(LOST);
        if (lost != null) pose.lost = lost;
        return pose;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        if (!inMech(state) || state.isInvisible) return; // «метеор»: костюм исчез
        poseStack.pushPose();
        MechModel.Pose pose = pose(state);
        // Из системы модели игрока (начало на 1.501 над ногами) — к ступням; отменяем перенос пилота (костюм — на землю);
        // дальше — система Blockbench: Y вверх, земля = 0.
        poseStack.translate(0.0F, 1.501F, 0.0F);
        pilotTransform(poseStack, state, true);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
        if (state instanceof com.smi7i.sandevistan.client.AfterimageRenderState afterimage) {
            // Послеобраз: костюм полупрозрачный, светится сам, цвет и прозрачность — как у послеобраза.
            MechModel.get().submit(pose, poseStack, collector, light,
                    net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucentEmissive(MechModel.TEXTURE), afterimage.afterimageColor);
        } else {
            MechModel.get().submit(pose, poseStack, collector, light);
            Integer owner = state.getData(OWNER_ID);
            if (owner != null) MechWreck.remember(owner, pose); // поза — чтобы отвалившаяся часть начала падать оттуда, где была
        }
        poseStack.popPose();
    }
}
