package com.smi7i.sandevistan.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к направлению движения (поле объявлено в ClientInput, поэтому @Accessor, а не @Shadow). */
@Mixin(ClientInput.class)
public interface ClientInputAccessor {
    @Accessor("moveVector")
    void sandevistan$setMoveVector(Vec2 moveVector);
}
