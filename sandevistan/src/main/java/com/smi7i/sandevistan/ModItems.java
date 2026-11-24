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

    // Продвинутый нейро интерфейс: имплант головы. Сопротивление киберпсихозу (шкала растёт на 15% медленнее),
    // в чужой зоне Сандевистана движения замедлены слабее, список целей на весь экран в радиусе 100 блоков (клавиша K).
    public static final ResourceKey<Item> ADVANCED_NEURO_INTERFACE_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("advanced_neuro_interface"));

    public static final Item ADVANCED_NEURO_INTERFACE = Registry.register(
            BuiltInRegistries.ITEM,
            ADVANCED_NEURO_INTERFACE_KEY,
            new Item(new Item.Properties().setId(ADVANCED_NEURO_INTERFACE_KEY).stacksTo(1))
    );

    // Пульт костюма: вызов костюма-меха правой кнопкой мыши (раньше клавиша B). Многоразовый.
    public static final ResourceKey<Item> MECH_REMOTE_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("mech_remote"));

    public static final Item MECH_REMOTE = Registry.register(
            BuiltInRegistries.ITEM,
            MECH_REMOTE_KEY,
            new MechRemoteItem(new Item.Properties().setId(MECH_REMOTE_KEY).stacksTo(1))
    );

    // Чип 1 «Овертайм»: вставляется в слот чипа (работает при надетом нейро интерфейсе).
    public static final ResourceKey<Item> CHIP_OVERTIME_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("chip_overtime"));

    public static final Item CHIP_OVERTIME = Registry.register(
            BuiltInRegistries.ITEM,
            CHIP_OVERTIME_KEY,
            new Item(new Item.Properties().setId(CHIP_OVERTIME_KEY).stacksTo(1))
    );

    // Чип 2 «Взлом»: позволяет взломать игрока с нейро интерфейсом (откроется список целей).
    public static final ResourceKey<Item> CHIP_HACK_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("chip_hack"));

    public static final Item CHIP_HACK = Registry.register(
            BuiltInRegistries.ITEM,
            CHIP_HACK_KEY,
            new Item(new Item.Properties().setId(CHIP_HACK_KEY).stacksTo(1))
    );

    // Клинки богомола: левый ставится только в левый слот руки, правый — только в правый. Достаются клавишей M.
    public static final ResourceKey<Item> BLADE_LEFT_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("mantis_blade_left"));
    public static final ResourceKey<Item> BLADE_RIGHT_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("mantis_blade_right"));

    public static final Item BLADE_LEFT = Registry.register(
            BuiltInRegistries.ITEM,
            BLADE_LEFT_KEY,
            new Item(new Item.Properties().setId(BLADE_LEFT_KEY).stacksTo(1))
    );
    public static final Item BLADE_RIGHT = Registry.register(
            BuiltInRegistries.ITEM,
            BLADE_RIGHT_KEY,
            new Item(new Item.Properties().setId(BLADE_RIGHT_KEY).stacksTo(1))
    );

    // Подкожная броня Mk.I / Mk.II / Mk.III: имплант тела, 3 / 4 / 5 единиц брони. Получить: творческий режим или /give.
    public static final ResourceKey<Item> ARMOR_IMPLANT_1_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("armor_implant_1"));
    public static final Item ARMOR_IMPLANT_1 = Registry.register(
            BuiltInRegistries.ITEM,
            ARMOR_IMPLANT_1_KEY,
            new Item(new Item.Properties().setId(ARMOR_IMPLANT_1_KEY).stacksTo(1))
    );
    public static final ResourceKey<Item> ARMOR_IMPLANT_2_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("armor_implant_2"));
    public static final Item ARMOR_IMPLANT_2 = Registry.register(
            BuiltInRegistries.ITEM,
            ARMOR_IMPLANT_2_KEY,
            new Item(new Item.Properties().setId(ARMOR_IMPLANT_2_KEY).stacksTo(1))
    );
    public static final ResourceKey<Item> ARMOR_IMPLANT_3_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("armor_implant_3"));
    public static final Item ARMOR_IMPLANT_3 = Registry.register(
            BuiltInRegistries.ITEM,
            ARMOR_IMPLANT_3_KEY,
            new Item(new Item.Properties().setId(ARMOR_IMPLANT_3_KEY).stacksTo(1))
    );

    // Л.Е.Д Mk.I / Mk.II / Mk.III: программное обеспечение (слот П.О). Защита от взлома: предупреждает владельца сразу,
    // замедляет взлом и усложняет мини-игру (1/2/3 уровень — до 20/40/60 пуль у босса).
    public static final ResourceKey<Item> LED_1_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("led_1"));
    public static final Item LED_1 = Registry.register(
            BuiltInRegistries.ITEM,
            LED_1_KEY,
            new Item(new Item.Properties().setId(LED_1_KEY).stacksTo(1))
    );
    public static final ResourceKey<Item> LED_2_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("led_2"));
    public static final Item LED_2 = Registry.register(
            BuiltInRegistries.ITEM,
            LED_2_KEY,
            new Item(new Item.Properties().setId(LED_2_KEY).stacksTo(1))
    );
    public static final ResourceKey<Item> LED_3_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("led_3"));
    public static final Item LED_3 = Registry.register(
            BuiltInRegistries.ITEM,
            LED_3_KEY,
            new Item(new Item.Properties().setId(LED_3_KEY).stacksTo(1))
    );

    // Чипы «Взлом» Мк 1 и Мк 2 (обычный чип без приписки — базовый).
    // Мк 1: бой с боссом короче на 10 секунд. Мк 2: цель без Л.Е.Д взламывается мгновенно, у цели с Л.Е.Д Mk.I бой короче на 20 секунд.
    public static final ResourceKey<Item> CHIP_HACK_1_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("chip_hack_1"));
    public static final Item CHIP_HACK_1 = Registry.register(
            BuiltInRegistries.ITEM,
            CHIP_HACK_1_KEY,
            new Item(new Item.Properties().setId(CHIP_HACK_1_KEY).stacksTo(1))
    );
    public static final ResourceKey<Item> CHIP_HACK_2_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("chip_hack_2"));
    public static final Item CHIP_HACK_2 = Registry.register(
            BuiltInRegistries.ITEM,
            CHIP_HACK_2_KEY,
            new Item(new Item.Properties().setId(CHIP_HACK_2_KEY).stacksTo(1))
    );

    // Л.Е.Д Mk.IV: «полуадминская» защита от брейнданса — до 120 пуль и толстые лазеры (творческое меню).
    public static final ResourceKey<Item> LED_4_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("led_4"));
    public static final Item LED_4 = Registry.register(
            BuiltInRegistries.ITEM,
            LED_4_KEY,
            new Item(new Item.Properties().setId(LED_4_KEY).stacksTo(1))
    );

    // Админский Сандевистан: бесконечный. В творческом меню его нет — выдаётся только командой
    // (/give @s sandevistan:admin_sandevistan).
    public static final ResourceKey<Item> ADMIN_SANDEVISTAN_KEY =
            ResourceKey.create(Registries.ITEM, Sandevistan.id("admin_sandevistan"));
    public static final Item ADMIN_SANDEVISTAN = Registry.register(
            BuiltInRegistries.ITEM,
            ADMIN_SANDEVISTAN_KEY,
            new Item(new Item.Properties().setId(ADMIN_SANDEVISTAN_KEY).stacksTo(1))
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
                    output.accept(new ItemStack(ADVANCED_NEURO_INTERFACE), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(MECH_REMOTE), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(CHIP_OVERTIME), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(CHIP_HACK), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(CHIP_HACK_1), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(CHIP_HACK_2), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(BLADE_LEFT), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(BLADE_RIGHT), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(ARMOR_IMPLANT_1), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(ARMOR_IMPLANT_2), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(ARMOR_IMPLANT_3), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(LED_1), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(LED_2), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(LED_3), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                    output.accept(new ItemStack(LED_4), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                });
    }
}
