package com.jarda.lifesteal;

import com.jarda.lifesteal.combat.DamageTracker;
import com.jarda.lifesteal.combat.KillResolver;
import com.jarda.lifesteal.config.LifeStealConfig;
import com.jarda.lifesteal.command.LifeStealCommands;
import com.jarda.lifesteal.data.PlayerDataManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

public class LifeSteal implements ModInitializer {
    public static final String MOD_ID = "lifesteal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LifeStealConfig config;
    private static PlayerDataManager playerDataManager;
    private static MinecraftServer serverInstance;

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

        // Sledování poškození pro nepřímé smrt (pravidlo 4)
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, amount, damage, critical) -> {
            if (entity instanceof ServerPlayer victim && playerDataManager != null) {
                DamageTracker.recordDamage(victim, source, serverInstance);
            }
        });

        // Řešení zabití při smrti hráče
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer victim && playerDataManager != null) {
                long currentTick = serverInstance.getTickCount();
                Optional<UUID> lastAttacker = DamageTracker.getLastAttacker(victim, currentTick, config);
                Optional<UUID> killer = KillResolver.resolve(victim, source, currentTick, lastAttacker);

                if (config.isDebugLogging()) {
                    String reason;
                    if (killer.isPresent()) {
                        UUID k = killer.get();
                        // Určení důvodu podle pravidel
                        if (getPlayerUuid(source.getEntity()) != null && getPlayerUuid(source.getEntity()).equals(k)) {
                            reason = "přímý";
                        } else if (getProjectileOwner(source) != null && getProjectileOwner(source).equals(k)) {
                            reason = "projektil";
                        } else if (getExplosionOwner(source) != null && getExplosionOwner(source).equals(k)) {
                            reason = "TNT";
                        } else {
                            reason = "nepřímý";
                        }
                        LOGGER.info("Hráč {} zemřel, zodpovědný: {} (důvod: {})",
                            victim.getName().getString(), k, reason);
                    } else {
                        LOGGER.info("Hráč {} zemřel, zodpovědný: bez hráče", victim.getName().getString());
                    }
                }

                // Pravidlo 6: vymazat záznam oběti
                DamageTracker.clearRecord(victim);
            }
        });
    }

    // Pomocné metody pro logování důvodu
    private static java.util.UUID getPlayerUuid(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }
        return null;
    }

    private static java.util.UUID getProjectileOwner(net.minecraft.world.damagesource.DamageSource source) {
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();
        if (direct != null) {
            String directType = direct.getType().getDescriptionId();
            if (directType.contains("arrow") || directType.contains("spectral_arrow")) {
                try {
                    net.minecraft.world.entity.Entity owner = (net.minecraft.world.entity.Entity) direct.getClass().getMethod("getOwner").invoke(direct);
                    if (owner instanceof net.minecraft.world.entity.player.Player player) {
                        return player.getUUID();
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            if (direct.getType().getDescriptionId().contains("trident")) {
                try {
                    net.minecraft.world.entity.Entity owner = (net.minecraft.world.entity.Entity) direct.getClass().getMethod("getOwner").invoke(direct);
                    if (owner instanceof net.minecraft.world.entity.player.Player player) {
                        return player.getUUID();
                    }
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static java.util.UUID getExplosionOwner(net.minecraft.world.damagesource.DamageSource source) {
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();
        if (direct instanceof net.minecraft.world.entity.item.PrimedTnt tnt) {
            net.minecraft.world.entity.Entity owner = tnt.getOwner();
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                return player.getUUID();
            }
        }
        net.minecraft.world.entity.Entity causing = source.getEntity();
        if (causing instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }
        return null;
    }

    private void onServerStarted(MinecraftServer server) {
        serverInstance = server;
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