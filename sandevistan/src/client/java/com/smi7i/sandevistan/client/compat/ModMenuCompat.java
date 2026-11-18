package com.smi7i.sandevistan.client.compat;

import com.smi7i.sandevistan.client.SandevistanConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Кнопка «Настройки» у мода в ModMenu (если ModMenu установлен; без него — своя клавиша). */
public class ModMenuCompat implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SandevistanConfigScreen::new;
    }
}
