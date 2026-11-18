package com.smi7i.sandevistan.client.compat;

import com.smi7i.sandevistan.Sandevistan;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Совместимость с [EMF] Entity Model Features (и ресурс-паками анимаций вроде Fresh Animations).
 * Подключается, только если EMF установлен: классы EMF трогает лишь EmfCompatImpl, который грузится после проверки.
 */
public final class EmfCompat {
    private EmfCompat() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("entity_model_features")) return;
        try {
            EmfCompatImpl.register();
        } catch (Throwable t) {
            Sandevistan.LOGGER.warn("Sandevistan: не удалось подключить совместимость с EMF", t);
        }
    }
}
