package com.smi7i.sandevistan.mixin.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к слоям модели предмета — чтобы нарисовать грани маски свечения своим способом. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] sandevistan$layers();

    @Accessor("activeLayerCount")
    int sandevistan$activeLayerCount();
}
