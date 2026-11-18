package com.smi7i.sandevistan.mixin.client;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Позволяет переставить слот (координаты в игре объявлены final). Нужно для творческого инвентаря. */
@Mixin(Slot.class)
public interface SlotAccessor {
    @Mutable
    @Accessor("x")
    void sandevistan$setX(int x);

    @Mutable
    @Accessor("y")
    void sandevistan$setY(int y);
}
