package com.smi7i.sandevistan.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.smi7i.sandevistan.MantisBlades;
import com.smi7i.sandevistan.ModItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * В костюме-мехе рука «от первого лица» (и предмет в ней) не рисуется — видно руки самого костюма.
 * Отдельно от GameRendererMixin: с шейдерами Iris рисует руку своим путём, но через этот же метод.
 *
 * Достанные клинки богомола от первого лица: на время отрисовки рук подменяем пустую руку клинком, и игра рисует
 * его как обычный предмет в руке (с ударом, настройками display модели). Если в руке есть предмет, клинок рисуется вместо него.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;

    @Unique private ItemStack sandevistan$savedMain;
    @Unique private ItemStack sandevistan$savedOff;
    @Unique private boolean sandevistan$swapped;

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void sandevistan$noHandInMech(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector,
                                         LocalPlayer player, int lightCoords, CallbackInfo ci) {
        if (com.smi7i.sandevistan.ModAttachments.hasMech(player)) {
            ci.cancel();
            return;
        }
        sandevistan$swapped = false;
        if (!MantisBlades.isExtended(player)) return;
        // Левый клинок — в левую руку, правый — в правую; основная рука игрока — главная в отрисовке.
        boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;
        ItemStack rightBlade = MantisBlades.hasRight(player) ? new ItemStack(ModItems.BLADE_RIGHT) : ItemStack.EMPTY;
        ItemStack leftBlade = MantisBlades.hasLeft(player) ? new ItemStack(ModItems.BLADE_LEFT) : ItemStack.EMPTY;
        ItemStack newMain = rightMain ? rightBlade : leftBlade;
        ItemStack newOff = rightMain ? leftBlade : rightBlade;
        sandevistan$savedMain = mainHandItem;
        sandevistan$savedOff = offHandItem;
        // Клинок рисуется вместо предмета в руке (предмет в заблокированном слоте всё равно не используется).
        if (!newMain.isEmpty()) mainHandItem = newMain;
        if (!newOff.isEmpty()) offHandItem = newOff;
        sandevistan$swapped = true;
    }

    @Inject(method = "submitHandsWithItems", at = @At("TAIL"))
    private void sandevistan$restoreHands(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector,
                                         LocalPlayer player, int lightCoords, CallbackInfo ci) {
        if (!sandevistan$swapped) return;
        mainHandItem = sandevistan$savedMain;
        offHandItem = sandevistan$savedOff;
        sandevistan$swapped = false;
    }
}
