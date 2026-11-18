package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.SandevistanManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Ускорение и замедление перегрева не должны менять обзор: при расчёте FOV убираем наши модификаторы скорости. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Redirect(method = "getFieldOfViewModifier", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double sandevistan$speedWithoutBoost(AbstractClientPlayer player, Holder<Attribute> attribute) {
        double value = player.getAttributeValue(attribute);
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            value = withoutModifier(value, instance.getModifier(SandevistanManager.SPEED_BOOST_ID));
            value = withoutModifier(value, instance.getModifier(SandevistanManager.OVERHEAT_SLOW_ID));
            value = withoutModifier(value, instance.getModifier(com.smi7i.sandevistan.mech.MechManager.SPEED_ID)); // тяжёлый шаг костюма
        }
        return value;
    }

    /** Модификатор умножает итоговую скорость на (1 + amount), делим обратно. */
    private static double withoutModifier(double value, AttributeModifier modifier) {
        if (modifier == null) return value;
        double factor = 1.0 + modifier.amount();
        return factor > 0.0 ? value / factor : value;
    }
}
