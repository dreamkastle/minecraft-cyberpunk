package com.smi7i.sandevistan.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.smi7i.sandevistan.Sandevistan;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Настройки мода (только у этого игрока): файл config/sandevistan.json, экран — SandevistanConfigScreen. */
public final class SandevistanConfig {
    /** Сила зелёного/красного фильтра: 0 — без фильтра, 1 — обычно, 1.5 — сильнее. */
    public float filterStrength = 1.0F;
    /** Громкость всех звуков мода (костюм, способности, Сандевистан): 0 — тишина, 1 — обычно, 1.5 — громче. */
    public float soundVolume = 1.0F;
    /** Сколько секунд живёт послеобраз. */
    public float afterimageSeconds = 2.5F;
    /** Максимум послеобразов одновременно (на всех игроков). */
    public int afterimageCount = 60;
    /** Сила свечения импланта при работе: 1 — обычно. */
    public float glowStrength = 1.0F;
    /** Во время киберпсихоза «душа» выходит из тела. */
    public boolean soulMode = true;
    /** Искры и вспышки от ударов в слоумо. */
    public boolean hitEffects = true;
    /** Звуки мира ниже и тягучее в слоумо. */
    public boolean slowWorldSounds = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SandevistanConfig instance;

    public static SandevistanConfig get() {
        if (instance == null) load();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sandevistan.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    instance = GSON.fromJson(r, SandevistanConfig.class);
                }
            }
        } catch (Exception e) {
            Sandevistan.LOGGER.warn("Sandevistan: не удалось прочитать настройки, беру стандартные", e);
        }
        if (instance == null) instance = new SandevistanConfig();
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(get(), w);
        } catch (Exception e) {
            Sandevistan.LOGGER.warn("Sandevistan: не удалось сохранить настройки", e);
        }
    }
}
