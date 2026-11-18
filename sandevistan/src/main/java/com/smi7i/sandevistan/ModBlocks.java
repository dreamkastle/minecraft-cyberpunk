package com.smi7i.sandevistan;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * «Осколок неба» — из него сложены куски неба, упавшие после сверхновой «Сингулярности»: сверху ночное небо
 * со звёздами, снизу дневное с облаками, по бокам глитч. По ним можно ходить, их можно ломать (кирка, выпадает блок).
 */
public final class ModBlocks {
    public static final ResourceKey<Block> SKY_SHARD_KEY = ResourceKey.create(Registries.BLOCK, Sandevistan.id("sky_shard"));
    public static final Block SKY_SHARD = Registry.register(BuiltInRegistries.BLOCK, SKY_SHARD_KEY,
            new Block(BlockBehaviour.Properties.of().setId(SKY_SHARD_KEY).mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(1.2F, 3.0F).sound(SoundType.GLASS).lightLevel(state -> 7)));

    /** Тот же осколок, но перевёрнутый: сверху день, снизу ночь (кусок упал небом вверх). Выпадает обычный осколок. */
    public static final ResourceKey<Block> SKY_SHARD_FLIP_KEY = ResourceKey.create(Registries.BLOCK, Sandevistan.id("sky_shard_flip"));
    public static final Block SKY_SHARD_FLIP = Registry.register(BuiltInRegistries.BLOCK, SKY_SHARD_FLIP_KEY,
            new Block(BlockBehaviour.Properties.of().setId(SKY_SHARD_FLIP_KEY).mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(1.2F, 3.0F).sound(SoundType.GLASS).lightLevel(state -> 7)));

    public static final ResourceKey<Item> SKY_SHARD_ITEM_KEY = ResourceKey.create(Registries.ITEM, Sandevistan.id("sky_shard"));
    public static final Item SKY_SHARD_ITEM = Registry.register(BuiltInRegistries.ITEM, SKY_SHARD_ITEM_KEY,
            new BlockItem(SKY_SHARD, new Item.Properties().setId(SKY_SHARD_ITEM_KEY).useBlockDescriptionPrefix()));

    private ModBlocks() {}

    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS)
                .register(output -> output.accept(new ItemStack(SKY_SHARD_ITEM), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS));
    }
}
