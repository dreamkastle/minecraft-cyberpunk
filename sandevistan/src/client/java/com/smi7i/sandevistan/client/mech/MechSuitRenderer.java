package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.smi7i.sandevistan.mech.MechSuitEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;

/** Отрисовка стоящего (пустого) костюма: падение с неба, потом стойка. */
public class MechSuitRenderer extends EntityRenderer<MechSuitEntity, MechSuitRenderer.State> {
    public static class State extends EntityRenderState {
        public float yaw;
        public float age; // секунды с вызова
        public float boardAge = -1; // тики с начала посадки, -1 — нет
    }

    public MechSuitRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 1.0F;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(MechSuitEntity entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.yaw = entity.getYRot();
        state.age = (entity.level().getGameTime() - entity.getSpawnTime() + partialTicks) / 20.0F;
        long board = entity.getBoardTime();
        state.boardAge = board > 0 ? entity.level().getGameTime() - board + partialTicks : -1;
    }

    /**
     * Кат-сцена посадки: в конце костюм резко хватает игрока двумя руками (анимация grab из Blockbench):
     * руки смыкаются ровно в момент, когда у садящегося экран чернеет.
     */
    private static MechModel.Pose pose(State state) {
        MechModel.Pose p = MechPoses.standing(state.age);
        float grabStart = com.smi7i.sandevistan.mech.MechManager.BOARD_PULL - com.smi7i.sandevistan.mech.MechManager.BOARD_STRIKE;
        if (state.boardAge >= grabStart - 3) {
            MechModel.Anim grab = MechModel.get().anim("grab");
            if (grab != null) {
                float time = Math.max(0, Math.min(grab.length(), (state.boardAge - grabStart) / 20.0F));
                float w = Math.max(0, Math.min(1, (state.boardAge - (grabStart - 3)) / 3.0F));
                p.blend(grab, time, w);
            }
        }
        return p;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yaw));
        poseStack.scale(MechPoses.SCALE, MechPoses.SCALE, MechPoses.SCALE);
        MechModel.get().submit(pose(state), poseStack, collector, state.lightCoords);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /** Пока костюм падает с неба, рисуем его, даже если сама точка падения не в кадре. */
    @Override
    protected AABB getBoundingBoxForCulling(MechSuitEntity entity) {
        AABB box = super.getBoundingBoxForCulling(entity);
        long age = entity.level().getGameTime() - entity.getSpawnTime();
        return age < MechSuitEntity.LAND_TICK + 10 ? box.expandTowards(0, 26, 0) : box;
    }
}
