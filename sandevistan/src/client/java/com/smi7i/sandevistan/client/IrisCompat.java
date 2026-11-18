package com.smi7i.sandevistan.client;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Проверка: включён ли шейдерпак Iris. Через рефлексию, чтобы мод работал и без Iris.
 * Когда шейдерпак включён, мир рисует он сам, и наш отдельный проход (фильтр под игроком) может сломать картинку,
 * поэтому тогда используем простой фильтр поверх всего экрана.
 */
public final class IrisCompat {
    private static boolean checked;
    private static Object api;
    private static Method isShaderPackInUse;

    private IrisCompat() {}

    public static boolean shadersActive() {
        if (!checked) {
            checked = true;
            if (FabricLoader.getInstance().isModLoaded("iris")) {
                try {
                    Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = apiClass.getMethod("getInstance").invoke(null);
                    isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    api = null;
                }
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) isShaderPackInUse.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
