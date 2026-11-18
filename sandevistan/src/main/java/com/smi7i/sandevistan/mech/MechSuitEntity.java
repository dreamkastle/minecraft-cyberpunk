package com.smi7i.sandevistan.mech;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Стоящий (пустой) костюм-мех: падает с неба после вызова, стоит сам. ПКМ — игрок залезает внутрь
 * (костюм-объект исчезает, а игрок сам становится мехом — см. {@link MechManager#equip}).
 */
public class MechSuitEntity extends Entity {
    /** Игровое время, когда костюм вызвали: по нему клиент проигрывает падение (анимация spawn), потом стойку. */
    private static final EntityDataAccessor<Long> DATA_SPAWN_TIME = SynchedEntityData.defineId(MechSuitEntity.class, EntityDataSerializers.LONG);
    /** Игровое время начала кат-сцены посадки в этот костюм (0 — никто не садится): в конце костюм резко бьёт. */
    private static final EntityDataAccessor<Long> DATA_BOARD_TIME = SynchedEntityData.defineId(MechSuitEntity.class, EntityDataSerializers.LONG);

    public long getBoardTime() {
        return this.entityData.get(DATA_BOARD_TIME);
    }

    public void setBoardTime(long time) {
        this.entityData.set(DATA_BOARD_TIME, time);
    }

    public MechSuitEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SPAWN_TIME, 0L);
        builder.define(DATA_BOARD_TIME, 0L);
    }

    /** Кто вызвал костюм (сохраняется — после перезахода старый костюм всё равно найдётся). */
    private java.util.UUID owner;

    public java.util.UUID getOwner() {
        return owner;
    }

    public void setOwner(java.util.UUID owner) {
        this.owner = owner;
    }

    public long getSpawnTime() {
        return this.entityData.get(DATA_SPAWN_TIME);
    }

    public void setSpawnTime(long time) {
        this.entityData.set(DATA_SPAWN_TIME, time);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        setSpawnTime(input.getLongOr("spawn_time", 0L));
        String o = input.getStringOr("owner", "");
        owner = o.isEmpty() ? null : java.util.UUID.fromString(o);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putLong("spawn_time", getSpawnTime());
        if (owner != null) output.putString("owner", owner.toString());
    }

    @Override
    public boolean isPickable() {
        return true; // на него можно навестись
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean skipAttackInteraction(Entity source) {
        return true; // удары по пустому костюму ничего не делают
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (this.level().isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) MechManager.startBoarding(serverPlayer, this);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void tick() {
        // Стоит на месте: ни гравитации, ни толкания. Только приземление после вызова (касание — на 1-й секунде анимации).
        if (this.level() instanceof ServerLevel level && getSpawnTime() > 0) {
            long age = level.getGameTime() - getSpawnTime();
            if (age == LAND_TICK - LAND_SOUND_LEAD) {
                MechManager.sound(level, position(), com.smi7i.sandevistan.ModSounds.MECH_SLAM, 4.0F, 0.85F);
            }
            if (age == LAND_TICK - 5) {
                MechManager.sound(level, position(), com.smi7i.sandevistan.ModSounds.MECH_LAND, 4.0F, 0.9F);
                MechManager.sound(level, position(), com.smi7i.sandevistan.ModSounds.BOOM_PUNCH, 3.5F, 0.7F);
                MechManager.sound(level, position(), com.smi7i.sandevistan.ModSounds.BOOM_LAND, 3.5F, 0.85F);
                MechManager.sound(level, position(), com.smi7i.sandevistan.ModSounds.BOOM_COLLISION, 3.0F, 0.75F);
            }
            if (age == LAND_TICK) MechManager.land(level, position());
        }
    }

    /** Тик касания земли в анимации spawn и насколько раньше начинается звук удара. */
    public static final int LAND_TICK = 50, LAND_SOUND_LEAD = 12; // падение 2.5 с
}
