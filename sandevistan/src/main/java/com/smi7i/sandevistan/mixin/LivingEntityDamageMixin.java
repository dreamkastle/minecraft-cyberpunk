package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.SandevistanManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Пока работает Сандевистан (и во время приступа), любой урон по игроку — падение, огонь, мобы, взрывы —
 * в DAMAGE_DIVISOR (2.5) раза меньше. Урон, пробивающий любую защиту (пустота, /kill), не трогаем.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float sandevistan$reduceDamage(float amount, ServerLevel level, DamageSource source, float originalAmount) {
        // Кат-сцена «Последнего боя»: пилот неуязвим (кроме пустоты и /kill).
        if ((Object) this instanceof ServerPlayer cut && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && (com.smi7i.sandevistan.mech.MechShowdown.inCutscene(cut) || com.smi7i.sandevistan.mech.MechMeteor.isHidden(cut))) {
            return 0.0F;
        }
        if ((Object) this instanceof ServerPlayer dying && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && com.smi7i.sandevistan.mech.MechDeath.isDying(dying)) {
            return 0.0F; // идёт кат-сцена гибели в костюме
        }
        float result = amount;
        if ((Object) this instanceof ServerPlayer player
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && SandevistanManager.isDamageReduced(player)) {
            result /= SandevistanManager.DAMAGE_DIVISOR;
        }
        // Костюм — тяжёлая броня: урон ≈ в 4.5 раза меньше (кроме иссушения — так умирает пилот без жизнеобеспечения).
        if ((Object) this instanceof ServerPlayer pilot && com.smi7i.sandevistan.ModAttachments.hasMech(pilot)
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && !source.is(net.minecraft.world.damagesource.DamageTypes.WITHER)) {
            result *= com.smi7i.sandevistan.mech.MechManager.MECH_DAMAGE_TAKEN;
        }
        return result;
    }
}
