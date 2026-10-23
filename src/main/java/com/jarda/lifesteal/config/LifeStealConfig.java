package com.jarda.lifesteal.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LifeStealConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("lifesteal-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Výchozí hodnoty
    public int startingHearts = 10;
    public int minHearts = 1;
    public int maxHearts = 20;
    public int heartsGainedPerKill = 1;
    public int heartsLostPerDeath = 1;
    public int killAttributionWindowSeconds = 10;
    public boolean debugLogging = false;

    private transient Path configPath;

    private LifeStealConfig() {}

    public static LifeStealConfig load(MinecraftServer server) {
        LifeStealConfig config = new LifeStealConfig();
        // V 26.x používáme server.getServerDirectory() pro adresář serveru
        try {
            config.configPath = server.getServerDirectory().resolve("config").resolve("lifesteal.json");
        } catch (Exception e) {
            LOGGER.warn("Nepodařilo se získat cestu pro konfiguraci: {}", e.getMessage());
            config.configPath = Path.of("config", "lifesteal.json");
        }

        try {
            if (Files.exists(config.configPath)) {
                String json = Files.readString(config.configPath);
                LifeStealConfig loaded = GSON.fromJson(json, LifeStealConfig.class);
                if (loaded != null) {
                    loaded.configPath = config.configPath;
                    loaded.validateAndFix();
                    return loaded;
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Nepodařilo se načíst konfiguraci, používám výchozí hodnoty: {}", e.getMessage());
        }

        // Vytvořit výchozí konfiguraci
        config.save();
        return config;
    }

    private void validateAndFix() {
        boolean hasErrors = false;

        if (minHearts < 1) {
            LOGGER.warn("Neplatná hodnota min_hearts: {} (musí být >= 1), používám výchozí: {}", minHearts, 1);
            minHearts = 1;
            hasErrors = true;
        }

        if (maxHearts < minHearts) {
            LOGGER.warn("Neplatná hodnota max_hearts: {} (musí být >= min_hearts: {}), používám výchozí: {}", maxHearts, minHearts, 20);
            maxHearts = 20;
            hasErrors = true;
        }

        if (startingHearts < minHearts || startingHearts > maxHearts) {
            LOGGER.warn("Neplatná hodnota starting_hearts: {} (musí být mezi {} a {}), používám výchozí: {}", startingHearts, minHearts, maxHearts, 10);
            startingHearts = Math.max(minHearts, Math.min(maxHearts, 10));
            hasErrors = true;
        }

        if (heartsGainedPerKill < 0) {
            LOGGER.warn("Neplatná hodnota hearts_gained_per_kill: {} (musí být >= 0), používám výchozí: {}", heartsGainedPerKill, 1);
            heartsGainedPerKill = 1;
            hasErrors = true;
        }

        if (heartsLostPerDeath < 0) {
            LOGGER.warn("Neplatná hodnota hearts_lost_per_death: {} (musí být >= 0), používám výchozí: {}", heartsLostPerDeath, 1);
            heartsLostPerDeath = 1;
            hasErrors = true;
        }

        if (killAttributionWindowSeconds < 0) {
            LOGGER.warn("Neplatná hodnota kill_attribution_window_seconds: {} (musí být >= 0), používám výchozí: {}", killAttributionWindowSeconds, 10);
            killAttributionWindowSeconds = 10;
            hasErrors = true;
        }

        // boolean pole nemá validaci

        if (hasErrors) {
            save();
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            LOGGER.error("Nepodařilo se uložit konfiguraci: {}", e.getMessage());
        }
    }

    // Gettery pro přístup k hodnotám
    public int getStartingHearts() { return startingHearts; }
    public int getMinHearts() { return minHearts; }
    public int getMaxHearts() { return maxHearts; }
    public int getHeartsGainedPerKill() { return heartsGainedPerKill; }
    public int getHeartsLostPerDeath() { return heartsLostPerDeath; }
    public int getKillAttributionWindowSeconds() { return killAttributionWindowSeconds; }
    public boolean isDebugLogging() { return debugLogging; }
}