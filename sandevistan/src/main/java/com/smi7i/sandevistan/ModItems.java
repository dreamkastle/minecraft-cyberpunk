package com.smi7i.sandevistan;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ModItems {
    public static final ResourceKey<Item> SANDEVISTAN_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("sandevistan"));

    public static final Item SANDEVISTAN = Registry.register(
            BuiltInRegistries.ITEM,
            SANDEVISTAN_KEY,
            new Item(new Item.Properties().setId(SANDEVISTAN_KEY).stacksTo(1))
    );

    // Вызывается из Sandevistan.onInitialize(), чтобы класс загрузился и предмет зарегистрировался.
    public static void init() {
        // Имплант во вкладке «Бой» творческого режима.
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT)
                .register(output -> output.accept(new ItemStack(SANDEVISTAN), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS));
    }
}
