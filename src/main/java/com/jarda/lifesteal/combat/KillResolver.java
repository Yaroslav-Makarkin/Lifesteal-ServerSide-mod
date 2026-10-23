package com.jarda.lifesteal.combat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/**
 * Řeší, kdo je zodpovědný za smrt hráče podle pravidel 1-6.
 * Čistá funkce bez vedlejších účinků.
 */
public class KillResolver {

    /**
     * Určuje zodpovědného hráče za smrt.
     *
     * @param victim      oběť
     * @param source      fatální DamageSource
     * @param currentTick aktuální server tick
     * @param lastAttacker UUID posledního útočníka z DamageTracker (nebo empty)
     * @return Optional s UUID vraha, nebo empty pokud není hráč
     */
    public static Optional<UUID> resolve(ServerPlayer victim, DamageSource source, long currentTick, Optional<UUID> lastAttacker) {
        // Pravidlo 1: Přímá smrt (melee)
        UUID direct = getPlayerUuid(source.getEntity());
        if (direct != null && !direct.equals(victim.getUUID())) {
            return Optional.of(direct);
        }

        // Pravidlo 2: Projektily
        UUID projectile = getProjectileOwner(source);
        if (projectile != null && !projectile.equals(victim.getUUID())) {
            return Optional.of(projectile);
        }

        // Pravidlo 3: Exploze
        UUID explosion = getExplosionOwner(source);
        if (explosion != null && !explosion.equals(victim.getUUID())) {
            return Optional.of(explosion);
        }

        // Pravidlo 4: Nepřímá smrt - poslední útočník v časovém okně
        if (lastAttacker.isPresent() && !lastAttacker.get().equals(victim.getUUID())) {
            return lastAttacker;
        }

        // Pravidlo 5 & 6: Žádný hráč nebo sebevražděná
        return Optional.empty();
    }

    private static UUID getPlayerUuid(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }
        return null;
    }

    private static UUID getProjectileOwner(DamageSource source) {
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();
        if (direct != null) {
            String directType = direct.getType().getDescriptionId();
            // Kontrola šípů
            if (directType.contains("arrow") || directType.contains("spectral_arrow")) {
                net.minecraft.world.entity.Entity owner = getArrowOwner(direct);
                if (owner instanceof net.minecraft.world.entity.player.Player player) {
                    return player.getUUID();
                }
            }
            // Kontrola tridentu
            if (direct.getType().getDescriptionId().contains("trident")) {
                net.minecraft.world.entity.Entity owner = getTridentOwner(direct);
                if (owner instanceof net.minecraft.world.entity.player.Player player) {
                    return player.getUUID();
                }
            }
        }
        return null;
    }

    private static UUID getExplosionOwner(DamageSource source) {
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();

        // TNT - majitel primed TNT
        if (direct instanceof net.minecraft.world.entity.item.PrimedTnt tnt) {
            net.minecraft.world.entity.Entity owner = tnt.getOwner();
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                return player.getUUID();
            }
        }

        // Ostatní exploze - causing entity
        net.minecraft.world.entity.Entity causing = source.getEntity();
        if (causing instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }

        return null;
    }

    // Pomocné metody pro získání ownerů bez instanceof na třídách, které nejsou v mapování
    private static net.minecraft.world.entity.Entity getArrowOwner(net.minecraft.world.entity.Entity entity) {
        try {
            return (net.minecraft.world.entity.Entity) entity.getClass().getMethod("getOwner").invoke(entity);
        } catch (Exception e) {
            return null;
        }
    }

    private static net.minecraft.world.entity.Entity getTridentOwner(net.minecraft.world.entity.Entity entity) {
        try {
            return (net.minecraft.world.entity.Entity) entity.getClass().getMethod("getOwner").invoke(entity);
        } catch (Exception e) {
            return null;
        }
    }
}