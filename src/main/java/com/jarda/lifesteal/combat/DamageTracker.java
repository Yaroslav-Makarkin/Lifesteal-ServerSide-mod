package com.jarda.lifesteal.combat;

import com.jarda.lifesteal.config.LifeStealConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.PrimedTnt;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Třída pro ukládání posledního útočníka pro každého hráče.
 * Používá se pro pravidlo 4 (nepřímá smrt).
 */
public class DamageTracker {
    private static final Map<UUID, DamageRecord> records = new ConcurrentHashMap<>();

    private record DamageRecord(UUID attacker, long tick) {}

    /**
     * Aktualizuje záznam o útoku, pokud útočník je hráč a není to oběť sama sebe.
     * Volá se v AFTER_DAMAGE (pouze při přežití).
     */
    public static void recordDamage(LivingEntity victim, DamageSource source, MinecraftServer server) {
        if (!(victim instanceof ServerPlayer victimPlayer)) {
            return;
        }

        UUID victimUuid = victimPlayer.getUUID();
        UUID attackerUuid = resolveAttacker(source);

        if (attackerUuid == null || attackerUuid.equals(victimUuid)) {
            return; // Žádný hráč, nebo sebevražděná škoda
        }

        long currentTick = server.getTickCount();
        records.put(victimUuid, new DamageRecord(attackerUuid, currentTick));
    }

    /**
     * Získá UUID posledního útočníka, pokud je stále v časovém okně.
     * Volá se v AFTER_DEATH.
     */
    public static Optional<UUID> getLastAttacker(LivingEntity victim, long currentTick, LifeStealConfig config) {
        if (!(victim instanceof ServerPlayer)) {
            return Optional.empty();
        }

        UUID victimUuid = victim.getUUID();
        DamageRecord record = records.get(victimUuid);
        if (record == null) {
            return Optional.empty();
        }

        long windowTicks = config.getKillAttributionWindowSeconds() * 20L;
        if (currentTick - record.tick() > windowTicks) {
            records.remove(victimUuid);
            return Optional.empty();
        }

        return Optional.of(record.attacker());
    }

    /**
     * Smaže záznam oběti po smrti.
     */
    public static void clearRecord(LivingEntity victim) {
        if (victim instanceof ServerPlayer) {
            records.remove(victim.getUUID());
        }
    }

    /**
     * Určuje hráče odpovědného za škodu podle pravidel 1-3.
     * Vrací UUID hráče nebo null.
     */
    private static UUID resolveAttacker(DamageSource source) {
        // Pravidlo 1: Přímá smrt (melee) - causing entity
        net.minecraft.world.entity.Entity causing = source.getEntity();
        if (causing instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }

        // Pravidlo 2: Projektil - střelec (owner)
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();
        if (direct != null) {
            String directType = direct.getType().getDescriptionId();
            // Kontrola šípů pomocí entity typu
            if (directType.contains("arrow") || directType.contains("spectral_arrow")) {
                net.minecraft.world.entity.Entity owner = getArrowOwner(direct);
                if (owner instanceof net.minecraft.world.entity.player.Player player) {
                    return player.getUUID();
                }
            }
            // Kontrola tridentu
            if (directType.contains("trident")) {
                net.minecraft.world.entity.Entity owner = getTridentOwner(direct);
                if (owner instanceof net.minecraft.world.entity.player.Player player) {
                    return player.getUUID();
                }
            }
        }

        // Pravidlo 3: Exploze - TNT vlastník
        net.minecraft.world.entity.Entity direct2 = source.getDirectEntity();
        if (direct2 instanceof net.minecraft.world.entity.item.PrimedTnt tnt) {
            net.minecraft.world.entity.Entity owner = getTntOwner(tnt);
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                return player.getUUID();
            }
        }

        // Ostatní exploze: causing entity (pokud je hráč)
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

    private static net.minecraft.world.entity.Entity getTntOwner(net.minecraft.world.entity.item.PrimedTnt tnt) {
        try {
            return (net.minecraft.world.entity.Entity) tnt.getClass().getMethod("getOwner").invoke(tnt);
        } catch (Exception e) {
            return null;
        }
    }
}