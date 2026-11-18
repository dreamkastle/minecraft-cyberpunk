package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.mech.MechWornLayer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Игрок в костюме-мехе: руки и ноги скрыты (вместе со вторым слоем скина), голова и тело подняты на место «пилота»
 * и вместе с корпусом костюма проседают/поднимаются. Работает с любым скином.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMechMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void sandevistan$mechPilot(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel self = (PlayerModel) (Object) this;
        // При посадке руки и ноги видны, пока пилот забирается; пропадают, когда он сел на место.
        boolean mech = MechWornLayer.inMech(state) && MechWornLayer.climb(state) > 0.92F;
        self.leftArm.visible = !mech;
        self.rightArm.visible = !mech;
        self.leftLeg.visible = !mech;
        self.rightLeg.visible = !mech;
        // Своё тело от первого лица — без головы (камера внутри неё).
        self.head.visible = !MechWornLayer.firstPerson(state);
        // Кат-сцена на Луне: сидят, руки отведены назад и упираются в грунт (как в кадре из аниме).
        if (Boolean.TRUE.equals(state.getData(MechWornLayer.STAGE_SIT))) {
            // Корпус откинут назад на 20° (наклон всей фигуры) — руки прямые, чуть назад и в стороны, ладони на грунте.
            self.rightArm.xRot = 0.12F;
            self.rightArm.yRot = 0.0F;
            self.rightArm.zRot = 0.2F;
            self.leftArm.xRot = 0.12F;
            self.leftArm.yRot = 0.0F;
            self.leftArm.zRot = -0.2F;
            // Ноги вытянуты вперёд и лежат на грунте (с учётом наклона назад), чуть разведены.
            self.rightLeg.xRot = -1.07F;
            self.rightLeg.yRot = 0.12F;
            self.rightLeg.zRot = 0.0F;
            self.leftLeg.xRot = -1.07F;
            self.leftLeg.yRot = -0.12F;
            self.leftLeg.zRot = 0.0F;
        }
        // Скольжение: корпус костюма (а с ним и пилот) наклонён вперёд на 16° — голову поднимаем обратно, смотрит вперёд.
        if (Boolean.TRUE.equals(state.getData(MechWornLayer.GLIDE))) self.head.xRot -= 0.28F;
        // «Последний бой» (вид со стороны): голова пилота мелко и резко трясётся.
        if (Boolean.TRUE.equals(state.getData(MechWornLayer.SHOWDOWN_GLITCH))) {
            float t = state.ageInTicks;
            self.head.xRot += 0.18F * net.minecraft.util.Mth.sin(t * 9.1F) + 0.1F * net.minecraft.util.Mth.sin(t * 23.7F);
            self.head.yRot += 0.22F * net.minecraft.util.Mth.sin(t * 7.3F + 1.0F) + 0.12F * net.minecraft.util.Mth.sin(t * 19.1F);
            self.head.zRot += 0.12F * net.minecraft.util.Mth.sin(t * 11.3F + 2.0F);
        }
        // Гибель в костюме: голову отбрасывает назад при отказе систем, потом она бессильно падает на грудь.
        Float death = state.getData(MechWornLayer.DEATH_TIME);
        if (death != null && death >= 0 && MechWornLayer.inMech(state)) {
            float t = death;
            float jerk = (float) Math.exp(-Math.pow((t - 0.28) / 0.14, 2));
            float shake = t < 1.3F ? 0.08F * net.minecraft.util.Mth.sin(t * 41.0F) : 0;
            float slump = smooth((t - 2.4F) / 1.8F);
            self.head.xRot = net.minecraft.util.Mth.lerp(slump, self.head.xRot - 0.55F * jerk + shake, 0.95F);
            self.head.yRot = net.minecraft.util.Mth.lerp(slump, self.head.yRot, 0.25F);
            self.head.zRot = net.minecraft.util.Mth.lerp(smooth((t - 3.3F) / 1.5F), self.head.zRot, 0.3F);
            self.body.xRot = net.minecraft.util.Mth.lerp(slump, self.body.xRot, 0.25F);
        }
        // Кат-сцена посадки: рука поднимается к броне и дважды стучит костяшками.
        float board = MechWornLayer.boardTime(state);
        if (board >= 0) com.smi7i.sandevistan.client.mech.MechCutscene.knockPose(self, board);
        // Подъём головы и тела делается сдвигом всей модели (AvatarRendererMechLiftMixin) — его не затирают моды анимаций.
    }

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }
}
