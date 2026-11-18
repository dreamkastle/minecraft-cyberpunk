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
                    .syncWith(ItemStack.OPTIONAL_STREAM_CODEC, AttachmentSyncPredicate.targetOnly())
    );

    public static void init() {
    }
}
