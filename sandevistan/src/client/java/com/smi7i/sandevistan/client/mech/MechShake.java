package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Тряска камеры от тяжёлых шагов костюма-меха: у самого пилота и у всех, кто стоит рядом с идущим костюмом
 * (чем дальше — тем слабее). Короткий толчок: резко дёргается и быстро затухает.
 */
public final class MechShake {
    // ===== Настройки =====
    /** Сила толчка от своего шага (градусы). */
    public static final float OWN_STEP = 0.9F;
    /** Сила толчка от чужого костюма вплотную и на каком расстоянии он уже не чувствуется (блоки). */
    public static final float NEAR_STEP = 1.1F, FAR_DISTANCE = 18.0F;
    /** Как быстро затухает (доля в секунду: больше — короче). */
    private static final float DECAY = 9.0F;

    private static float strength;
    private static long lastMs;
    private static float seed;

    private MechShake() {}

    /** Шаг костюма игрока p: толчок у своего игрока, если он рядом (или это он сам). */
    public static void onStep(Player p, float weight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // С Сандевистаном шаги не трясут: ни свои, ни чужого костюма, у которого он работает.
        if (p == mc.player ? com.smi7i.sandevistan.client.ClientSandevistanState.active
                || com.smi7i.sandevistan.client.ClientSandevistanState.psychosis()
                : com.smi7i.sandevistan.client.ClientZones.isActive(p.getId())) return;
        float add;
        if (p == mc.player) {
            add = OWN_STEP * weight;
        } else {
            double dist = p.distanceTo(mc.player);
            if (dist >= FAR_DISTANCE) return;
            float k = 1.0F - (float) (dist / FAR_DISTANCE);
            add = NEAR_STEP * weight * k * k;
        }
        strength = Math.min(2.5F, Math.max(strength, add) + add * 0.3F);
        seed = (float) (Math.random() * 100);
    }

    /** Долгий тяжёлый гул после удара: медленная тряска, затухает за seconds секунд. */
    public static void rumble(float amount, float seconds) {
        rumbleAmp = Math.max(rumbleAmp, amount);
        rumbleStart = net.minecraft.util.Util.getMillis();
        rumbleMs = (long) (seconds * 1000);
    }

    private static float rumbleAmp;
    private static long rumbleStart, rumbleMs;

    /** Резкий толчок камеры заданной силы (градусы). */
    public static void kick(float amount) {
        strength = Math.min(4.0F, strength + amount);
        seed = (float) (Math.random() * 100);
    }

    /** Взрыв: сильная тряска рядом, слабее с расстоянием (до 40 блоков). */
    public static void explosion(net.minecraft.world.phys.Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double dist = mc.player.position().distanceTo(at);
        if (dist > 40) return;
        float k = 1.0F - (float) (dist / 40);
        strength = Math.min(4.0F, strength + 3.5F * k * k);
        seed = (float) (Math.random() * 100);
    }

    /** Камера: короткие быстрые колебания, затухают. Вызывается из GameRendererMixin (bobHurt). */
    public static void apply(PoseStack poseStack) {
        long now = net.minecraft.util.Util.getMillis();
        float dt = lastMs == 0 ? 0 : Math.min(0.1F, (now - lastMs) / 1000.0F);
        lastMs = now;
        strength *= (float) Math.exp(-DECAY * dt);
        float t = now / 1000.0F;
        if (rumbleMs > 0 && now - rumbleStart < rumbleMs) {
            float k = 1.0F - (now - rumbleStart) / (float) rumbleMs;
            float a = rumbleAmp * k * k;
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.sin(t * 17.0F) * a + Mth.sin(t * 29.0F) * a * 0.4F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 13.0F + 1.0F) * a * 0.8F));
            poseStack.mulPose(Axis.YP.rotationDegrees(Mth.sin(t * 11.0F + 2.0F) * a * 0.4F));
        }
        if (strength < 0.01F) return;
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.sin(t * 47.0F + seed) * strength));       // вверх-вниз сильнее
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 39.0F + seed * 2) * strength * 0.6F));
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.sin(t * 31.0F + seed * 3) * strength * 0.3F));
    }
}
