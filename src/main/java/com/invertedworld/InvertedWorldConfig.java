package com.invertedworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class InvertedWorldConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("invertedworld.json");

    public enum InversionFormula {
        RANK_SWAP("Перестановка шансов (Точная)", "Меняет местами точные веса редких и частых предметов (1-й с последним, 2-й с предпоследним). Сумма весов не меняется."),
        RECIPROCAL("Обратная пропорция (1/P)", "Математическая гиперболическая инверсия: новый вес = (Max * Min / СтарыйВес)^Сила."),
        LINEAR_MIRROR("Зеркальная (Max + Min - W)", "Линейное отражение весов относительно середины пула.");

        private final String title;
        private final String description;

        InversionFormula(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public InversionFormula next() {
            InversionFormula[] vals = values();
            return vals[(this.ordinal() + 1) % vals.length];
        }
    }

    private static InvertedWorldConfig INSTANCE = new InvertedWorldConfig();

    // Главные переключатели и математические параметры
    public boolean modEnabled = true;
    public InversionFormula formula = InversionFormula.RANK_SWAP;
    public float inversionStrength = 1.0f; // 1.0 = 100% (диапазон 0.25 .. 2.50)

    // Модули инверсии
    public boolean invertLootTables = true;
    public boolean reduceEmptySlotWeight = true;
    public boolean enrichVillageChests = true;
    public boolean invertBiomeSpawns = true;
    public boolean invertMobGroupSizes = true;
    public boolean invertEnchantments = true;
    public boolean invertMobVariants = true;
    public boolean invertVillagerTrades = true;

    public static InvertedWorldConfig get() {
        return INSTANCE;
    }

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                    InvertedWorldConfig loaded = GSON.fromJson(reader, InvertedWorldConfig.class);
                    if (loaded != null) {
                        if (loaded.formula == null) {
                            loaded.formula = InversionFormula.RANK_SWAP;
                        }
                        INSTANCE = loaded;
                    }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            LOGGER.error("[InvertedWorld] Failed to load config, using defaults", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (Exception e) {
            LOGGER.error("[InvertedWorld] Failed to save config", e);
        }
    }

    public void resetDefaults() {
        this.modEnabled = true;
        this.formula = InversionFormula.RANK_SWAP;
        this.inversionStrength = 1.0f;
        this.invertLootTables = true;
        this.reduceEmptySlotWeight = true;
        this.enrichVillageChests = true;
        this.invertBiomeSpawns = true;
        this.invertMobGroupSizes = true;
        this.invertEnchantments = true;
        this.invertMobVariants = true;
        this.invertVillagerTrades = true;
        save();
    }
}
