package com.jarda.lifesteal;

import com.jarda.lifesteal.config.LifeStealConfig;
import com.jarda.lifesteal.command.LifeStealCommands;
import com.jarda.lifesteal.data.PlayerDataManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LifeSteal implements ModInitializer {
    public static final String MOD_ID = "lifesteal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LifeStealConfig config;
    private static PlayerDataManager playerDataManager;

    @Override
    public void onInitialize() {
        LOGGER.info("LifeSteal SMP mod načten.");

        // Registrace příkazů
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> LifeStealCommands.register(dispatcher, context, selection));

        // Načtení konfigurace při startu serveru
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);

        // Uložení dat při zastavení serveru
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (playerDataManager != null) {
                playerDataManager.save();
                LOGGER.info("Hráčská data uložena.");
            }
        });

        // Aplikace srdcí při připojení hráče (join)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (playerDataManager != null) {
                playerDataManager.updateLastKnownName(player.getUUID(), player.getName().getString());
                playerDataManager.applyMaxHealth(player);
            }
        });

        // Aplikace srdcí při respawnu/změně dimenze
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (playerDataManager != null) {
                playerDataManager.updateLastKnownName(newPlayer.getUUID(), newPlayer.getName().getString());
                playerDataManager.applyMaxHealth(newPlayer);
            }
        });

        // Aplikace srdcí při respawnu
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (playerDataManager != null) {
                playerDataManager.applyMaxHealth(newPlayer);
            }
        });
    }

    private void onServerStarted(MinecraftServer server) {
        config = LifeStealConfig.load(server);
        playerDataManager = new PlayerDataManager(config);
        playerDataManager.load(server);

        LOGGER.info("Konfigurace načtena: starting_hearts={}, min_hearts={}, max_hearts={}",
            config.getStartingHearts(), config.getMinHearts(), config.getMaxHearts());
    }

    public static LifeStealConfig getConfig() {
        return config;
    }

    public static PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }
}