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

    // Иммуносупрессор: снимает одну ячейку киберпсихоза. Получить: творческий режим или /give @s sandevistan:immunosuppressant.
    public static final ResourceKey<Item> IMMUNOSUPPRESSANT_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("immunosuppressant"));

    public static final Item IMMUNOSUPPRESSANT = Registry.register(
            BuiltInRegistries.ITEM,
            IMMUNOSUPPRESSANT_KEY,
            new ImmunosuppressantItem(new Item.Properties().setId(IMMUNOSUPPRESSANT_KEY).stacksTo(16))
    );

    // Имплант руки: ставится в один из двух слотов рук рядом со слотом Сандевистана. Крафта пока нет.
    public static final ResourceKey<Item> HAND_IMPLANT_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("hand_implant"));

    public static final Item HAND_IMPLANT = Registry.register(
            BuiltInRegistries.ITEM,
            HAND_IMPLANT_KEY,
            new Item(new Item.Properties().setId(HAND_IMPLANT_KEY).stacksTo(1))
    );

    // Нейро интерфейс: имплант головы. Даёт клавишу H со списком целей рядом (заготовка под будущие нейро импланты).
    public static final ResourceKey<Item> NEURO_INTERFACE_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("neuro_interface"));

    public static final Item NEURO_INTERFACE = Registry.register(
            BuiltInRegistries.ITEM,
            NEURO_INTERFACE_KEY,
            new Item(new Item.Properties().setId(NEURO_INTERFACE_KEY).stacksTo(1))
    );

    // Вызывается из Sandevistan.onInitialize(), чтобы класс загрузился и предмет зарегистрировался.
    public static void init() {
        // Имплант во вкладке «Бой» творческого режима.
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT)
                .register(output -> {
                    output.accept(new ItemStack(SANDEVISTAN), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(IMMUNOSUPPRESSANT), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(HAND_IMPLANT), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(NEURO_INTERFACE), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                });
    }
}
