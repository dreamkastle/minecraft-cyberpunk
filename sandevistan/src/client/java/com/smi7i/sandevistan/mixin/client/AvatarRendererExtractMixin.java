package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.ImplantSlot;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.client.ImplantBackLayer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Когда игра готовит игрока к отрисовке, кладём в состояние модель надетого импланта (для ImplantBackLayer). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererExtractMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void sandevistan$extractImplant(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        boolean mech = ModAttachments.hasMech(entity);
        state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.IN_MECH, mech);
        state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.STAGE, false);
        state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.STAGE_SIT, false);
        // Кат-сцена посадки: время с ПКМ (тики), -1 — нет. По нему рисуется стук рукой по броне.
        long boardStart = entity.getAttachedOrElse(ModAttachments.MECH_BOARD_START, 0L);
        state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.BOARD_TIME,
                boardStart > 0 ? entity.level().getGameTime() - boardStart + partialTicks : -1.0F);
        if (mech) {
            // Корпус костюма разворачивается по движению, голова пилота — за взглядом; время шага — в родном темпе анимации.
            com.smi7i.sandevistan.client.mech.MechMotion.Gait gait =
                    com.smi7i.sandevistan.client.mech.MechMotion.apply(entity, state, partialTicks);
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.WALK_TIME, gait.walkTime());
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.WALK_WEIGHT, gait.weight());
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.STRAFE_TIME, gait.strafeTime());
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.STRAFE_WEIGHT, gait.strafeWeight());
            // Атака костюма: время с начала (секунды), -1 — не атакует. Видят все игроки.
            // С Сандевистаном атака идёт в 2 раза быстрее (attackAge уже умножен на скорость).
            float attackAge = entity instanceof net.minecraft.world.entity.player.Player pl
                    ? com.smi7i.sandevistan.mech.MechManager.attackAge(pl, partialTicks) : -1.0F;
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.ATTACK_TIME, attackAge >= 0 ? attackAge / 20.0F : -1.0F);
            // «Последний бой»: со стороны — трясущаяся голова в кат-сцене; гибель — анимация death.
            if (entity instanceof net.minecraft.world.entity.player.Player sp) {
                float sa = com.smi7i.sandevistan.client.mech.MechShowdownClient.age(sp, partialTicks);
                // Со стороны (не свой игрок — свою тряску даёт камера).
                state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.SHOWDOWN_GLITCH,
                        sa >= 0 && sa < com.smi7i.sandevistan.mech.MechShowdown.CUTSCENE && sp != net.minecraft.client.Minecraft.getInstance().player);
                state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.GLIDE,
                        com.smi7i.sandevistan.client.mech.MechShowdownClient.running(sp));
                float dt = com.smi7i.sandevistan.client.mech.MechShowdownClient.deathTime(sp, partialTicks);
                state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.DEATH_TIME, dt);
                // Погиб в «Последнем бою»: костюм уже лежит лицом в землю — обычное «заваливание набок» при смерти не нужно.
                if (dt >= 0) state.deathTime = 0.0F;
            }
            // «Вихрь»: секунды с начала (-1 — нет).
            long skillStart = entity.getAttachedOrElse(ModAttachments.MECH_SKILL_START, 0L);
            float skillT = skillStart > 0 ? (entity.level().getGameTime() - skillStart + partialTicks) / 20.0F : -1.0F;
            // «Метеор» — та же анимация skill: прыжок = её взлёт (0..1.1 с), появление → удар (2.5..3.6 с) → подъём (до 5.0 с).
            if (entity instanceof net.minecraft.world.entity.player.Player mp && com.smi7i.sandevistan.mech.MechMeteor.isActive(mp)) {
                long ms = com.smi7i.sandevistan.mech.MechMeteor.startTime(mp), md = com.smi7i.sandevistan.mech.MechMeteor.dropTime(mp);
                long gt = entity.level().getGameTime();
                if (md > 0) skillT = 2.5F + (gt - md + partialTicks) / 20.0F;
                else skillT = Math.min(1.1F, (gt - ms + partialTicks) / com.smi7i.sandevistan.mech.MechMeteor.LAUNCH * 1.1F);
            }
            // «Сингулярность» — та же анимация skill: взлёт (0..2.2 с), парение (покачивание 1.8..2.6 с), спуск после взрыва (3.0..5.0 с).
            if (entity instanceof net.minecraft.world.entity.player.Player sp2 && com.smi7i.sandevistan.mech.MechSingularity.isActive(sp2)) {
                long ss = com.smi7i.sandevistan.mech.MechSingularity.startTime(sp2), sf = com.smi7i.sandevistan.mech.MechSingularity.fireTime(sp2);
                float gt = entity.level().getGameTime() + partialTicks;
                float age = gt - ss;
                float down = sf > 0 ? gt - sf - (com.smi7i.sandevistan.mech.MechSingularity.NOVA + 8) : -1;
                if (down >= 0) skillT = Math.min(5.0F, 3.0F + down / 20.0F);
                else if (age < 44) skillT = age / 20.0F;
                else skillT = 2.2F + 0.4F * (float) Math.sin((age - 44) * 0.05);
            }
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.SKILL_TIME, skillT);
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.BANK, com.smi7i.sandevistan.client.mech.MechMotion.bank(entity, partialTicks));
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.LOST,
                    entity.getAttachedOrElse(com.smi7i.sandevistan.ModAttachments.MECH_LOST, 0));
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.OWNER_ID, entity.getId());
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.HP,
                    entity instanceof net.minecraft.world.entity.LivingEntity le ? le.getHealth() / Math.max(1, le.getMaxHealth()) : 1.0F);
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.PUSH_TIME,
                    entity instanceof net.minecraft.world.entity.player.Player pp ? com.smi7i.sandevistan.client.mech.MechPushClient.age(pp, partialTicks) / 20.0F : -1.0F);
            // Посадка в костюм: секунды с ПКМ (-1 — уже сидит).
            long equipStart = entity.getAttachedOrElse(ModAttachments.MECH_EQUIP_START, 0L);
            float equip = equipStart > 0 ? (entity.level().getGameTime() - equipStart + partialTicks) / 20.0F : -1.0F;
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.EQUIP_TIME,
                    equip < com.smi7i.sandevistan.client.mech.MechPoses.EQUIP_SECONDS ? equip : -1.0F);
            state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.FIRST_PERSON, false);
        }
        state.setData(com.smi7i.sandevistan.client.mech.MechWornLayer.LIQUID,
                entity.getAttachedOrElse(ModAttachments.MECH_DOSES, 0) / (float) com.smi7i.sandevistan.mech.MechManager.DOSES);
        ItemStack implant = entity.getAttached(ModAttachments.IMPLANT);
        if (implant == null || !ImplantSlot.isImplant(implant)) {
            clear(state, ImplantBackLayer.IMPLANT_ITEM);
            clear(state, ImplantBackLayer.IMPLANT_GLOW);
            return;
        }
        resolve(state, ImplantBackLayer.IMPLANT_ITEM, implant, entity);
        // Та же модель, но с текстурой-маской свечения (models/item/sandevistan_glow.json).
        ItemStack glow = implant.copyWithCount(1);
        glow.set(DataComponents.ITEM_MODEL, GLOW_MODEL);
        resolve(state, ImplantBackLayer.IMPLANT_GLOW, glow, entity);
    }

    private static final Identifier GLOW_MODEL = Identifier.fromNamespaceAndPath("sandevistan", "sandevistan_glow");

    private static void resolve(AvatarRenderState state, RenderStateDataKey<ItemStackRenderState> key, ItemStack stack, Avatar entity) {
        ItemStackRenderState item = state.getData(key);
        if (item == null) {
            item = new ItemStackRenderState();
            state.setData(key, item);
        }
        Minecraft.getInstance().getItemModelResolver().updateForLiving(item, stack, ItemDisplayContext.NONE, entity);
    }

    private static void clear(AvatarRenderState state, RenderStateDataKey<ItemStackRenderState> key) {
        ItemStackRenderState item = state.getData(key);
        if (item != null) item.clear();
    }
}
