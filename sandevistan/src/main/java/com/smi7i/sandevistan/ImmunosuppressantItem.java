package com.smi7i.sandevistan;

import java.util.function.BooleanSupplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;

/**
 * Иммуносупрессор: зажми ПКМ — идёт укол (1.5 с, со звуком), в конце снимается одна ячейка киберпсихоза
 * и тратится один предмет. Работает в любой момент, кроме самого приступа киберпсихоза. Ожидание
 * «3 минуты после перегрева» игнорирует.
 */
public class ImmunosuppressantItem extends Item {
    /** Длительность укола: 30 тиков = 1.5 с (звук mech_inject длится 1.45 с). */
    public static final int USE_TICKS = 30;

    /**
     * Проверка на клиенте (чтобы анимация укола не начиналась зря). Клиентский код ставит сюда
     * свою проверку в SandevistanClient; на сервере всё равно проверяется заново.
     */
    public static BooleanSupplier clientCanUse = () -> true;

    /** Урон от укола: 2 сердца = 4 здоровья. Броню и Сандевистан-снижение урона игнорирует. */
    public static final float INJECTION_DAMAGE = 4.0F;

    private static boolean injecting;

    /** Идёт ли сейчас укол (миксин урона тогда не делит урон на 2.5 и не применяет множитель костюма). */
    public static boolean isInjecting() {
        return injecting;
    }

    public ImmunosuppressantItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            if (!clientCanUse.getAsBoolean()) return InteractionResult.FAIL;
        } else if (player instanceof ServerPlayer sp) {
            // Нет ячеек или идёт приступ — укол не начинаем (текст над хотбаром не показываем).
            if (SandevistanManager.isInPsychosis(sp) || SandevistanManager.getStreak(sp) <= 0) {
                return InteractionResult.FAIL;
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.MECH_INJECT, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return USE_TICKS;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BLOCK; // рука с предметом перед собой; можно заменить на SPEAR / BOW
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer sp && SandevistanManager.removeOneCell(sp)) {
            stack.consume(1, sp); // в творческом режиме предмет не тратится
            // Урон magic() проходит мимо брони; может убить, если здоровья мало.
            injecting = true;
            try {
                sp.hurtServer(sp.level(), sp.damageSources().magic(), INJECTION_DAMAGE);
            } finally {
                injecting = false;
            }
        }
        return stack;
    }
}
