package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.client.mech.MechClient;
import com.smi7i.sandevistan.client.mech.MechShowdownClient;
import com.smi7i.sandevistan.mech.ParryPayload;
import com.smi7i.sandevistan.mech.ProjectileParry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Отбивание снарядов с работающим Сандевистаном (и в костюме): если рядом перед тобой летит снаряд,
 * ЛКМ отбивает его туда, куда смотришь, ПКМ — отражает назад в стрелка. Нажатие тогда не уходит игре
 * (не бьёт воздух и не ест еду). Нет снаряда — кнопки работают как обычно.
 */
public final class ParryClient {
    private ParryClient() {}

    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(ParryClient::tick);
    }

    private static boolean enabled(Minecraft mc) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.gui.screen() != null) return false;
        if (ClientSandevistanState.psychosis() || MechShowdownClient.locked()) return false;
        return ClientSandevistanState.active || MechClient.localInMech();
    }

    private static void tick(Minecraft mc) {
        if (!enabled(mc)) return;
        Projectile target = find(mc);
        if (target == null) return;
        boolean bat = false, reflect = false;
        while (mc.options.keyAttack.consumeClick()) bat = true;
        while (mc.options.keyUse.consumeClick()) reflect = true;
        if (!bat && !reflect) return;
        ClientPlayNetworking.send(new ParryPayload(target.getId(), reflect));
        // Сразу искры у снаряда — отклик без ожидания сервера.
        Vec3 eye = mc.player.getEyePosition();
        HitBurst.spawn(target.position(), target.position().subtract(eye).normalize());
    }

    /** Ближайший к прицелу летящий снаряд в пределах досягаемости перед игроком (свои отбитые — не трогаем). */
    private static Projectile find(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        double reach = ProjectileParry.REACH;
        Projectile best = null;
        double bestScore = Double.MAX_VALUE;
        for (Projectile p : mc.level.getEntitiesOfClass(Projectile.class, new AABB(eye, eye).inflate(reach))) {
            if (!p.isAlive() || p.getOwner() == mc.player) continue;
            Vec3 v = p.getDeltaMovement();
            if (v.lengthSqr() < 0.0025) continue; // воткнулась или лежит
            Vec3 to = p.position().subtract(eye);
            double d = to.length();
            if (d > reach) continue;
            double dot = d < 1.5 ? 1.0 : to.normalize().dot(look);
            if (dot < 0.55) continue; // только то, что перед тобой
            double score = d * (1.6 - dot);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }
}
