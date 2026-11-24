package com.smi7i.sandevistan.mixin;

import com.smi7i.sandevistan.SandevistanManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Пока работает Сандевистан (и во время приступа), любой урон по игроку — падение, огонь, мобы, взрывы —
 * в DAMAGE_DIVISOR (2.5) раза меньше. Урон, пробивающий любую защиту (пустота, /kill), не трогаем.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {
    /**
     * Пока у игрока включён Сандевистан, враждебные и нейтральные мобы не могут нанести ему урон вообще
     * (ни удар при сближении, ни стрела, ни взрыв крипера): удар отменяется целиком — без урона, отбрасывания и
     * красной вспышки. Остальные источники (падение, огонь, игроки) работают, как раньше. Кончился Сандевистан — всё как обычно.
     */
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void sandevistan$mobsCannotHurt(ServerLevel level, DamageSource source, float amount,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        if (!SandevistanManager.isSandevistanRunning(player)) return;
        net.minecraft.world.entity.Entity attacker = source.getEntity();
        if (attacker instanceof net.minecraft.world.entity.monster.Enemy
                || attacker instanceof net.minecraft.world.entity.NeutralMob) {
            cir.setReturnValue(false);
        }
    }

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
        // Укол иммуносупрессора: ровно 2 сердца, без Сандевистан-снижения и множителя костюма.
        if ((Object) this instanceof ServerPlayer && com.smi7i.sandevistan.ImmunosuppressantItem.isInjecting()) {
            return amount;
        }
        float result = amount;
        // Имплант руки: удар рукой игрока наносит на 2 сердца больше. Бонус добавляем до брони цели — она его уменьшает.
        if (amount > 0.0F && source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                && source.getEntity() instanceof ServerPlayer attacker
                && com.smi7i.sandevistan.HandImplants.hasAny(attacker)) {
            result += com.smi7i.sandevistan.HandImplants.BONUS_DAMAGE;
        }
        // Достанные клинки богомола: удар наносит не меньше 6.5 урона (4 — если надет один клинок) (броня цели его уменьшает) и звенит одним из 3 звуков.
        if (amount > 0.0F && source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                && source.getEntity() instanceof ServerPlayer bladeUser
                && com.smi7i.sandevistan.MantisBlades.isExtended(bladeUser)) {
            result = Math.max(result, com.smi7i.sandevistan.MantisBlades.damageFor(bladeUser));
            net.minecraft.world.entity.LivingEntity victim = (net.minecraft.world.entity.LivingEntity) (Object) this;
            bladeUser.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    com.smi7i.sandevistan.ModSounds.BLADE_HIT, net.minecraft.sounds.SoundSource.PLAYERS,
                    1.0F, 0.92F + victim.getRandom().nextFloat() * 0.16F);
        }
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
