package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.MantisBlades;
import com.smi7i.sandevistan.ModItems;
import com.smi7i.sandevistan.client.MantisBladeLayer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Кладём в состояние отрисовки модели достанных клинков (для MantisBladeLayer). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererBladeMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void sandevistan$extractBlades(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        boolean out = entity instanceof Player player && MantisBlades.isExtended(player)
                && !com.smi7i.sandevistan.ModAttachments.hasMech(entity);
        boolean left = out && MantisBlades.hasLeft((Player) entity);
        boolean right = out && MantisBlades.hasRight((Player) entity);
        apply(state, MantisBladeLayer.BLADE_LEFT, left ? new ItemStack(ModItems.BLADE_LEFT) : null,
                ItemDisplayContext.THIRD_PERSON_LEFT_HAND, entity);
        apply(state, MantisBladeLayer.BLADE_RIGHT, right ? new ItemStack(ModItems.BLADE_RIGHT) : null,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, entity);
    }

    private static void apply(AvatarRenderState state, RenderStateDataKey<ItemStackRenderState> key, ItemStack stack,
                              ItemDisplayContext context, Avatar entity) {
        ItemStackRenderState item = state.getData(key);
        if (stack == null) {
            if (item != null) item.clear();
            return;
        }
        if (item == null) {
            item = new ItemStackRenderState();
            state.setData(key, item);
        }
        Minecraft.getInstance().getItemModelResolver().updateForLiving(item, stack, context, entity);
    }
}
