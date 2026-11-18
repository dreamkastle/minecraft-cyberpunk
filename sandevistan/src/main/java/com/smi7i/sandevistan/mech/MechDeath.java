package com.smi7i.sandevistan.mech;

import com.smi7i.sandevistan.ModAttachments;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;

/**
 * Любая гибель в костюме — кинематографичная (как в «Последнем бою»): смерть откладывается, пилот держится на 1 хп
 * и неуязвим, DEATH тиков играет кат-сцена гибели (колени, оседание, гаснущие огни, кардиограмма), потом — смерть.
 * Исключение — падение в пустоту (там ждать нечего).
 */
public final class MechDeath {
    /** Длина кат-сцены — как гибель в «Последнем бою»; удары коленей и рук — те же тики. */
    public static final int DEATH = MechShowdown.DEATH;

    private MechDeath() {}

    public static long startTime(net.minecraft.world.entity.player.Player p) {
        return p.getAttachedOrElse(ModAttachments.MECH_DEATH_START, 0L);
    }

    public static boolean isDying(net.minecraft.world.entity.player.Player p) {
        return startTime(p) > 0;
    }

    static void init() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p)) return true;
            long s = startTime(p);
            if (s > 0) return p.level().getGameTime() - s >= DEATH; // кат-сцена идёт — не умирает, пока не доиграет
            if (!ModAttachments.hasMech(p) || MechShowdown.isActive(p) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) return true;
            // Отложить смерть: кат-сцена гибели.
            p.setHealth(1.0F);
            p.setAttached(ModAttachments.MECH_DEATH_START, p.level().getGameTime());
            return false;
        });
    }

    static void tick(ServerPlayer player) {
        long s = startTime(player);
        if (s <= 0) return;
        long a = player.level().getGameTime() - s;
        player.fallDistance = 0;
        if (player.isAlive() && player.getHealth() < 1.0F) player.setHealth(1.0F);
        if (a == MechShowdown.DEATH_HIT || a == MechShowdown.DEATH_KNEE2) {
            MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_SLAM, 2.2F, a == MechShowdown.DEATH_HIT ? 0.62F : 0.55F);
            MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_LAND, 1.8F, 0.75F);
        }
        if (a == MechShowdown.DEATH_ARMS) MechManager.sound(player.level(), player.position(), com.smi7i.sandevistan.ModSounds.MECH_LAND, 1.4F, 1.1F);
        // Кат-сцена доиграла — пилот погибает (время начала не сбрасываем: тело лежит в последнем кадре).
        if (a >= DEATH && player.isAlive()) player.kill(player.level());
    }
}
