package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.ClientSandevistanState;
import com.smi7i.sandevistan.client.PsychosisSoul;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private Entity entity;
    @Shadow private boolean detached;

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow protected abstract void setPosition(Vec3 position);

    @Shadow protected abstract void move(float forwards, float up, float right);

    @Shadow protected abstract float getMaxZoom(float cameraDist);

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void sandevistan$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        Float cine = com.smi7i.sandevistan.client.mech.MechCutscene.fov(); // кат-сцена: узкий «киношный» угол
        if (cine == null) cine = com.smi7i.sandevistan.client.mech.MechShowdownClient.cutsceneFov();
        if (cine != null) {
            cir.setReturnValue(cine);
            return;
        }
        if (com.smi7i.sandevistan.client.mech.MechShowdownClient.running(net.minecraft.client.Minecraft.getInstance().player)) {
            cir.setReturnValue(cir.getReturnValue() + com.smi7i.sandevistan.client.mech.MechShowdownClient.RUN_FOV_BOOST);
            return;
        }
        float boost = ClientSandevistanState.fovBoost();
        if (boost > 0.0F) {
            cir.setReturnValue(cir.getReturnValue() + boost);
        }
    }

    /**
     * Камера души: положение и поворот задаём сами — плавно и без служебных полей сущности
     * (с ними камеру души трясло). В виде от третьего лица — так же, как игра: разворот для вида спереди
     * и отъезд назад с проверкой стен.
     */
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void sandevistan$soulCamera(float partialTicks, CallbackInfo ci) {
        // Кат-сцена посадки: кинокамера — низко, по диагонали, медленно облетает костюм.
        double[] cut = com.smi7i.sandevistan.client.mech.MechCutscene.camera(partialTicks);
        if (cut == null) cut = com.smi7i.sandevistan.client.mech.MechShowdownClient.cutsceneCamera(partialTicks); // «Последний бой»: кат-сцена
        if (cut == null) cut = com.smi7i.sandevistan.client.mech.MechShowdownClient.runCamera(partialTicks); // «Последний бой»: полёт и гибель
        if (cut == null) cut = com.smi7i.sandevistan.client.mech.MechSingularityClient.riseCamera(net.minecraft.client.Minecraft.getInstance(), partialTicks); // «Сингулярность»: взлёт
        if (cut != null) {
            setRotation((float) cut[3], (float) cut[4]);
            setPosition(new Vec3(cut[0], cut[1], cut[2]));
            return;
        }
        // Посадка в костюм от первого лица: глаза поднимаются вместе с пилотом, а не сразу на высоту костюма.
        if (!detached && entity instanceof net.minecraft.world.entity.player.Player player
                && com.smi7i.sandevistan.mech.MechManager.isEquipping(player)) {
            long start = player.getAttachedOrElse(com.smi7i.sandevistan.ModAttachments.MECH_EQUIP_START, 0L);
            float t = (player.level().getGameTime() - start + partialTicks) / 20.0F;
            float k = com.smi7i.sandevistan.client.mech.MechPoses.climb(t);
            float hop = com.smi7i.sandevistan.client.mech.MechPoses.climbHop(k) / 16.0F * 0.9375F;
            setPosition(((Camera) (Object) this).position().add(0, -(1.0F - k) * EQUIP_EYE_DROP + hop, 0));
        }
        if (!PsychosisSoul.active() || entity != PsychosisSoul.soul()) return;
        float yaw = PsychosisSoul.yaw();
        float pitch = PsychosisSoul.pitch();
        CameraType type = Minecraft.getInstance().options.getCameraType();
        if (detached && type.isMirrored()) {
            yaw += 180.0F;
            pitch = -pitch;
        }
        setRotation(yaw, pitch);
        setPosition(PsychosisSoul.eyePosition(partialTicks));
        if (detached) {
            move(-getMaxZoom(THIRD_PERSON_DISTANCE), 0.0F, 0.0F);
        }
    }

    /** Насколько камера отъезжает от души в виде от третьего лица (как у игрока: 4 блока). */
    private static final float THIRD_PERSON_DISTANCE = 4.0F;
    /** Насколько глаза ниже в начале посадки: высота глаз в костюме (2.325) минус обычная (1.62). */
    private static final float EQUIP_EYE_DROP = 0.7F;
}
