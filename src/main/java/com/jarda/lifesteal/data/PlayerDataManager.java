package com.jarda.lifesteal.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import com.jarda.lifesteal.config.LifeStealConfig;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("lifesteal-playerdata");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, PlayerData>>(){}.getType();

    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private Path dataPath;
    private final LifeStealConfig config;

    public PlayerDataManager(LifeStealConfig config) {
        this.config = config;
    }

public void load(MinecraftServer server) {
        // Data uložíme do složky světa (world save folder)
        try {
            this.dataPath = server.getWorldPath(LevelResource.ROOT).resolve("lifesteal").resolve("lifesteal_playerdata.json");
        } catch (Exception e) {
            LOGGER.warn("Nepodařilo se získat cestu pro data ve světě: {}", e.getMessage());
            this.dataPath = Path.of("lifesteal_playerdata.json");
        }

        try {
            if (Files.exists(dataPath)) {
                String json = Files.readString(dataPath);
                Map<String, PlayerData> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    for (Map.Entry<String, PlayerData> entry : loaded.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            playerData.put(uuid, entry.getValue());
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Neplatné UUID v hráčských datech: {}", entry.getKey());
                        }
                    }
                    LOGGER.info("Načtena data pro {} hráčů", playerData.size());
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Nepodařilo se načíst hráčská data: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            Map<String, PlayerData> toSave = new HashMap<>();
            for (Map.Entry<UUID, PlayerData> entry : playerData.entrySet()) {
                toSave.put(entry.getKey().toString(), entry.getValue());
            }

            String json = GSON.toJson(toSave);
            Path tempPath = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
            Files.writeString(tempPath, json);
            Files.move(tempPath, dataPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Nepodařilo se uložit hráčská data: {}", e.getMessage());
        }
    }

    public PlayerData getOrCreate(UUID uuid, String playerName) {
        return playerData.computeIfAbsent(uuid, u -> {
            PlayerData data = new PlayerData();
            data.hearts = config.getStartingHearts();
            data.kills = 0;
            data.deaths = 0;
            data.lastKnownName = playerName;
            return data;
        });
    }

    public PlayerData get(UUID uuid) {
        return playerData.get(uuid);
    }

    public void updateLastKnownName(UUID uuid, String name) {
        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.lastKnownName = name;
        }
    }

    public void addHearts(UUID uuid, int amount) {
        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.hearts = Math.min(config.getMaxHearts(), data.hearts + amount);
            save();
        }
    }

    public void removeHearts(UUID uuid, int amount) {
        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.hearts = Math.max(config.getMinHearts(), data.hearts - amount);
            save();
        }
    }

    public void setHearts(UUID uuid, int hearts) {
        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.hearts = Math.max(config.getMinHearts(), Math.min(config.getMaxHearts(), hearts));
            save();
        }
    }

    public void recordKill(UUID uuid) {
        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.kills++;
            save();
        }
    }

    public void recordDeath(UUID uuid) {
        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.deaths++;
            save();
        }
    }

    public LifeStealConfig getConfig() {
        return config;
    }

    public void applyMaxHealth(ServerPlayer player) {
        PlayerData data = playerData.get(player.getUUID());
        if (data != null) {
            double maxHealth = data.hearts * 2.0;
            var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (attribute != null) {
                attribute.setBaseValue(maxHealth);
                // Clamp current health to new max
                if (player.getHealth() > maxHealth) {
                    player.setHealth((float) maxHealth);
                }
            }
        }
    }

    public static class PlayerData {
        public int hearts;
        public int kills;
        public int deaths;
        public String lastKnownName;
    }
}