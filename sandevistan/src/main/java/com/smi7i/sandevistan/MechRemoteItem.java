package com.smi7i.sandevistan;

import com.smi7i.sandevistan.mech.MechManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Пульт костюма: ПКМ вызывает костюм-мех туда, куда смотрит игрок (как раньше клавиша B). Многоразовый.
 * Сандевистан в слоте не нужен; перезарядка вызова — как у костюма (30 секунд).
 */
public class MechRemoteItem extends Item {
    public MechRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            return MechManager.summon(sp) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }
}
