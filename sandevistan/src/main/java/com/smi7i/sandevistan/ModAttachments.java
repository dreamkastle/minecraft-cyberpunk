package com.smi7i.sandevistan;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public class ModAttachments {
    public static final AttachmentType<ItemStack> IMPLANT = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "implant"),
            builder -> builder
                    .persistent(ItemStack.OPTIONAL_CODEC)
                    .initializer(() -> ItemStack.EMPTY)
                    .copyOnDeath()
                    // Видно всем игрокам рядом: имплант рисуется у владельца на спине.
                    .syncWith(ItemStack.OPTIONAL_STREAM_CODEC, AttachmentSyncPredicate.all())
    );

    /** Игрок «стал» костюмом-мехом (сел в него по ПКМ). Видно всем — по этому флагу рисуется костюм и прячутся руки/ноги. */
    public static final AttachmentType<Boolean> MECH = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech"),
            builder -> builder
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .initializer(() -> false)
                    // Без copyOnDeath: смерть снимает костюм.
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.BOOL, AttachmentSyncPredicate.all())
    );

    /** Сколько тиков ещё может прожить игрок в костюме (20 = 1 секунда); 0 — медленно умирает. Не синхронизируется — владельцу шлём MechStatePayload. */
    public static final AttachmentType<Integer> MECH_TICKS = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_ticks"),
            builder -> builder.persistent(com.mojang.serialization.Codec.INT).initializer(() -> 0)
    );

    /** Сколько уколов жидкости осталось в шприцах костюма (из 5). Видно всем — по нему рисуется уровень жидкости. */
    public static final AttachmentType<Integer> MECH_DOSES = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_doses"),
            builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT)
                    .initializer(() -> 0)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all())
    );

    /** Сколько «стадий» повреждений костюм уже прошёл (0..6): от него отваливаются куски и конечности. Видно всем. */
    public static final AttachmentType<Integer> MECH_LOST = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_lost"),
            builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT)
                    .initializer(() -> 0)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all())
    );

    /** Игровое время начала атаки костюма (0 — не атакует). Видно всем — по нему все проигрывают анимацию атаки. */
    public static final AttachmentType<Long> MECH_ATTACK_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_attack_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** Скорость текущей атаки костюма: 1 — обычная, 2 — с Сандевистаном (в 2 раза быстрее). Видно всем. */
    public static final AttachmentType<Integer> MECH_ATTACK_SPEED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_attack_speed"),
            builder -> builder
                    .initializer(() -> 1)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all())
    );

    /** Игровое время посадки в костюм (0 — не садится). Видно всем — по нему проигрывается анимация посадки. */
    public static final AttachmentType<Long> MECH_EQUIP_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_equip_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** Игровое время начала кат-сцены посадки (0 — нет). Видно всем — по нему рисуется анимация подхода и стука. */
    public static final AttachmentType<Long> MECH_BOARD_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_board_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** Игровое время начала способности «вихрь» (0 — нет). Видно всем — по нему рисуются анимация и эффекты. */
    public static final AttachmentType<Long> MECH_SKILL_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_skill_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** «Метеор»: время нажатия (0 — нет) и время появления в воздухе (0 — ещё летит невидимым). Видно всем. */
    public static final AttachmentType<Long> MECH_METEOR_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_meteor_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );
    /** Гибель в костюме (не «Последний бой»): время начала кат-сцены гибели (0 — нет). Видно всем. */
    public static final AttachmentType<Long> MECH_DEATH_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_death_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** «Магнит»: время начала (0 — нет). Видно всем. */
    public static final AttachmentType<Long> MECH_PUSH_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_push_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** «Сингулярность»: время начала и время выстрела (0 — ещё наводится). Видно всем. */
    public static final AttachmentType<Long> MECH_SING_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_sing_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );
    public static final AttachmentType<Long> MECH_SING_FIRE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_sing_fire"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );
    public static final AttachmentType<Long> MECH_METEOR_DROP = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_meteor_drop"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** «Последний бой»: игровое время начала (0 — нет). Видно всем — по нему кат-сцена, глитчи со стороны, клоны. */
    public static final AttachmentType<Long> MECH_SHOWDOWN_START = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_showdown_start"),
            builder -> builder
                    .initializer(() -> 0L)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    );

    /** «Последний бой» уже использован в этом костюме (сбрасывается при посадке). */
    public static final AttachmentType<Boolean> MECH_SHOWDOWN_DONE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("sandevistan", "mech_showdown_done"),
            builder -> builder
                    .initializer(() -> false)
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .syncWith(net.minecraft.network.codec.ByteBufCodecs.BOOL, AttachmentSyncPredicate.all())
    );

    public static boolean hasMech(net.minecraft.world.entity.Entity entity) {
        return Boolean.TRUE.equals(entity.getAttached(MECH));
    }

    public static void init() {
    }
}
