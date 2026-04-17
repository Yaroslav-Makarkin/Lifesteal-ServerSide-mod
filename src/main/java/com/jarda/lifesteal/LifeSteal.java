package com.jarda.lifesteal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.properties.Property;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.scoreboard.*;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LifeSteal implements ModInitializer {
    public static final String MOD_ID = "lifesteal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Identifier HEALTH_MODIFIER_ID = Identifier.of(MOD_ID, "lifesteal_health");
    private static final String ELYTRA_BOSS_NAME = "§6§lHusk Strážce";
    private static final String BOSS_STRAY_NAME = "§b§lStray Strážce";
    private static final String BOSS_WITHER_NAME = "§8§lWither Strážce";
    
    // Achievements
    public record Achievement(String id, String name, String description, String tier, String rewardMsg) {}
    private static final Map<String, Achievement> ACHIEVEMENTS = new LinkedHashMap<>();
    
    static {
        ACHIEVEMENTS.put("STEAL_HEART", new Achievement("STEAL_HEART", "První loupež", "Ukrasti své první srdce jinému hráči.", "BRONZE", "§7Bonus: 1x Golden Apple"));
        ACHIEVEMENTS.put("REACH_20_HEARTS", new Achievement("REACH_20_HEARTS", "Polobůh", "Dosáhnout 20 srdcí (40 HP).", "GOLD", "§eBonus: 1x Revival Heart"));
        ACHIEVEMENTS.put("KILL_BOSS_SOLO", new Achievement("KILL_BOSS_SOLO", "Lovec stvůr", "Zabít bosse strážce bez pomoci ostatních.", "SILVER", "§fBonus: 2x Heart"));
        ACHIEVEMENTS.put("SURVIVE_1_HEART", new Achievement("SURVIVE_1_HEART", "Na hraně", "Přežít s pouze 1 srdcem po dobu 10 minut.", "SILVER", "§fBonus: 1x Golden Head"));
        ACHIEVEMENTS.put("COMPLETE_10_BOUNTIES", new Achievement("COMPLETE_10_BOUNTIES", "Nájemný vrah", "Dokončit 10 bounty misí.", "GOLD", "§eBonus: 5x Netherite Ingot"));
    }
    private static final Map<UUID, Integer> BOSS_ATTACK_TIMER = new HashMap<>();

    public static final Map<UUID, SimpleInventory> OPEN_SHOPS = new HashMap<>();
    public static final Map<UUID, String> OPEN_MENUS = new HashMap<>(); // UUID -> menu type
    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<UUID, Long> SURVIVAL_TIMER = new HashMap<>();
    private static final Map<UUID, Integer> IN_AIR_TICKS = new HashMap<>();
    private static final Map<UUID, TeleportTarget> LOGIN_POS = new HashMap<>();
    private static final Map<UUID, TeleportTarget> RETURN_POS = new HashMap<>();
    private static final Map<UUID, Integer> PENDING_SPAWN_TELEPORTS = new HashMap<>();
    private static final int SPAWN_TELEPORT_RETRY_TICKS = 60;

    private static final Set<UUID> LOGGED_IN = new HashSet<>();
    private static final Set<UUID> LAYING_PLAYERS = new HashSet<>();
    private static final Set<UUID> CRAWLING_PLAYERS = new HashSet<>();
    private static final Map<UUID, Long> COMBAT_TAGS = new HashMap<>();
    private static final Map<UUID, UUID> COMBAT_LAST_ATTACKER = new HashMap<>();
    private static final Map<UUID, ServerBossBar> COMBAT_BOSSBARS = new HashMap<>();
    private static final long COMBAT_TAG_DURATION_MS = 120000L;
    private static final Map<UUID, Long> JOIN_TIMES = new HashMap<>();
    // Admin features
    private static final Set<UUID> FROZEN_PLAYERS = new HashSet<>();
    private static final Map<UUID, Long> MUTED_PLAYERS = new HashMap<>(); // UUID -> mute end time (ms)
    private static final Map<String, Long> BANNED_PLAYERS = new HashMap<>(); // UUID string -> ban end time (ms)
    private static final Map<String, String> BAN_REASONS = new HashMap<>(); // UUID string -> reason
    private static final Set<UUID> VANISHED_PLAYERS = new HashSet<>();
    private static final Map<UUID, String> PASSWORDS = new HashMap<>();
    private static final Map<UUID, Integer> PLAYER_VOTES = new HashMap<>();
    private static final Map<Integer, Integer> VOTE_COUNTS = new HashMap<>();
    private static final Map<UUID, UUID> SHARED_FATE_LINKS = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path dataPath;
    private static Path oraclePath;
    
    private static final ThreadLocal<Boolean> IS_DAMAGING_SHARED = ThreadLocal.withInitial(() -> false);

    private static final Map<Integer, String> EVENT_NAMES = new HashMap<>();
    private static final Set<Integer> IMPLEMENTED_EVENTS = Set.of(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
        21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
        51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66,
        67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81
    );
    static {
        // Category: World and Atmosphere
        EVENT_NAMES.put(1, "Low Gravity");
        EVENT_NAMES.put(2, "High Gravity");
        EVENT_NAMES.put(3, "Acid Rain");
        EVENT_NAMES.put(4, "Healing Drizzle");
        EVENT_NAMES.put(5, "Eternal Night");
        EVENT_NAMES.put(6, "Eternal Day");
        EVENT_NAMES.put(7, "Slippery World");
        EVENT_NAMES.put(8, "Thick Fog");
        EVENT_NAMES.put(9, "Magnetic Poles");
        EVENT_NAMES.put(10, "Lava Ocean");
        EVENT_NAMES.put(11, "Rapid Growth");
        EVENT_NAMES.put(12, "Lost Compass");
        // Category: Economy and Trade
        EVENT_NAMES.put(13, "Gold Rush");
        EVENT_NAMES.put(14, "Diamond Inflation");
        EVENT_NAMES.put(15, "Villagers on Strike");
        EVENT_NAMES.put(16, "Happy Trader");
        EVENT_NAMES.put(17, "Emerald Rain");
        EVENT_NAMES.put(18, "Wealth Tax");
        EVENT_NAMES.put(19, "Barter Trade");
        EVENT_NAMES.put(20, "XP Bonus");
        // Category: Combat & Mobs
        EVENT_NAMES.put(21, "Giant Invasion");
        EVENT_NAMES.put(22, "Invisible Creepers");
        EVENT_NAMES.put(23, "Fast Skeletons");
        EVENT_NAMES.put(24, "Friendly Mobs");
        EVENT_NAMES.put(25, "Spider Web");
        EVENT_NAMES.put(26, "Explosive Death");
        EVENT_NAMES.put(27, "Double Boss HP");
        EVENT_NAMES.put(28, "Hordes");
        EVENT_NAMES.put(29, "Air Support");
        EVENT_NAMES.put(30, "Fire Attack");
        // Category: Player Mechanics
        EVENT_NAMES.put(31, "Shared Health");
        EVENT_NAMES.put(32, "Vampire Weekend");
        EVENT_NAMES.put(33, "Fragile Bones");
        EVENT_NAMES.put(34, "Speedrunner");
        EVENT_NAMES.put(35, "Heavy Backpack");
        EVENT_NAMES.put(36, "Random Teleportation");
        EVENT_NAMES.put(37, "Position Swap");
        EVENT_NAMES.put(38, "No Name");
        EVENT_NAMES.put(39, "Pacifism");
        EVENT_NAMES.put(40, "Berserker");
        // Category: Mining & Crafting
        EVENT_NAMES.put(41, "Vein Miner");
        EVENT_NAMES.put(42, "Lucky Ore");
        EVENT_NAMES.put(43, "Indestructible Tools");
        EVENT_NAMES.put(44, "Expensive Crafting");
        EVENT_NAMES.put(45, "Random Drop");
        EVENT_NAMES.put(46, "Strong Furnace");
        EVENT_NAMES.put(47, "Lumberjack");
        EVENT_NAMES.put(48, "Obsidian softness");
        EVENT_NAMES.put(49, "Lava fishing");
        EVENT_NAMES.put(50, "No Craft");
        // Category: Special Challenges/Bounties
        EVENT_NAMES.put(51, "King of the Hill");
        EVENT_NAMES.put(52, "Last Survivor");
        EVENT_NAMES.put(53, "Skull Hunter");
        EVENT_NAMES.put(54, "Collection");
        EVENT_NAMES.put(55, "Silent Mail");
        // Category: Fun and Chaos
        EVENT_NAMES.put(56, "Chicken Rain");
        EVENT_NAMES.put(57, "Bouncy Blocks");
        EVENT_NAMES.put(58, "Mini-Players");
        EVENT_NAMES.put(59, "Giants");
        EVENT_NAMES.put(60, "Pig Transport");
        EVENT_NAMES.put(61, "Reverse Mode");
        EVENT_NAMES.put(62, "Omnipresent Sound");
        EVENT_NAMES.put(63, "Colorful Weekend");
        EVENT_NAMES.put(64, "Anti-Gravity Arrows");
        EVENT_NAMES.put(65, "TNT Fishing");
        // Category: Hardcore elements
        EVENT_NAMES.put(66, "One Life Weekend");
        EVENT_NAMES.put(67, "Limited Inventory");
        EVENT_NAMES.put(68, "No Regeneration");
        EVENT_NAMES.put(69, "Toxic Water");
        EVENT_NAMES.put(70, "Armor Weight");
        // Category: Bonus (mixed)
        EVENT_NAMES.put(71, "Double Health");
        EVENT_NAMES.put(72, "Glass World");
        EVENT_NAMES.put(73, "Fireproof");
        EVENT_NAMES.put(74, "Infinite Fireworks");
        EVENT_NAMES.put(75, "Shield Master");
        EVENT_NAMES.put(76, "Loot Boxes");
        EVENT_NAMES.put(77, "No Fall Damage");
        EVENT_NAMES.put(78, "Zombie Apocalypse");
        EVENT_NAMES.put(79, "Mob Disguise");
        EVENT_NAMES.put(80, "The Floor is Lava");
        EVENT_NAMES.put(81, "Anti Snowball System");
    }

    public static class OracleState {
        public int currentActiveEffect = 0;
        public boolean isEventActive = false;
        public long effectEndTime = 0;
        public List<Integer> recentEvents = new ArrayList<>();
        public List<Integer> currentOptions = new ArrayList<>();
        public boolean votingActive = false;
        public int lastWeekVoted = -1;
        public long lastVoteBroadcast = 0;
        public long votingEndTime = 0;
        public Map<String, Integer> playerVotes = new HashMap<>();
        public Map<Integer, Integer> voteCounts = new HashMap<>();
        public UUID targetPlayerUuid = null;
        public UUID assassinUuid = null;
        public UUID assassinTargetUuid = null;
        public UUID lastBountyTarget = null;
        public UUID lastAssassinTarget = null;
        public Set<String> triggeredShips = new HashSet<>();
        public Set<String> unlockedFrames = new HashSet<>();
        public Map<String, Integer> pendingHealthChanges = new HashMap<>();
        public Map<String, SkinData> savedSkins = new HashMap<>();
        
        // Achievement tracking
        public Map<String, Set<String>> playerAchievements = new HashMap<>();
        public Map<String, Integer> totalHeartsStolen = new HashMap<>();
        public Map<String, Integer> totalBountiesCompleted = new HashMap<>();
        public Map<String, Integer> bossKills = new HashMap<>();
        public Set<String> receivedVoteTicketUuids = new HashSet<>();
        
        // Spawn coordinates
        public double spawnX = 0;
        public double spawnY = 100;
        public double spawnZ = 0;
        public String spawnDimension = "minecraft:overworld";
        public float spawnYaw = 0;
        public float spawnPitch = 0;
        public boolean spawnSet = false;
        public int spawnProtectionRadius = 16;
        public long lastAntiSnowballRun = 0;
        
        // Ban/mute persistence
        public Map<String, Long> bannedPlayers = new HashMap<>();
        public Map<String, String> banReasons = new HashMap<>();
        public Map<String, Long> mutedPlayers = new HashMap<>();
        public Map<String, CannonAccessData> cannonAccess = new HashMap<>();
    }

    public static class SkinData {
        public String value;
        public String signature;
    }

    public static class CannonAccessData {
        public String previousGameMode = GameMode.SURVIVAL.name();
        public boolean wasOperator = false;
    }

    private static OracleState oracleState = new OracleState();

    @Override
    public void onInitialize() {
        LOGGER.info("LifeSteal SMP mod initializing...");
        loadData();
        syncVoteRuntimeFromState();

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            OPEN_SHOPS.clear();
            OPEN_MENUS.clear();
            SHOP_COOLDOWNS.clear();
            COMBAT_TAGS.clear();
            COMBAT_LAST_ATTACKER.clear();
            clearAllCombatBossBars();
            SHARED_FATE_LINKS.clear();
            BOSS_ATTACK_TIMER.clear();
            SURVIVAL_TIMER.clear();
            LOGIN_POS.clear();
            RETURN_POS.clear();
            PENDING_SPAWN_TELEPORTS.clear();
            LAST_POS.clear();
            IN_AIR_TICKS.clear();
            JOIN_TIMES.clear();
            LAYING_PLAYERS.clear();
            CRAWLING_PLAYERS.clear();
            FROZEN_PLAYERS.clear();
            VANISHED_PLAYERS.clear();
            LOGGED_IN.clear();
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity player && !LOGGED_IN.contains(player.getUuid())) {
                return false;
            }
            if (source.getAttacker() instanceof ServerPlayerEntity attacker && !LOGGED_IN.contains(attacker.getUuid())) {
                return false;
            }
            // Combat Tag
            if (entity instanceof ServerPlayerEntity victim && source.getAttacker() instanceof ServerPlayerEntity killer) {
                long now = System.currentTimeMillis();
                applyCombatTag(victim, now);
                applyCombatTag(killer, now);
                COMBAT_LAST_ATTACKER.put(victim.getUuid(), killer.getUuid());
            }
            
            // Oracle Logic: Vampire Weekend (32)
            if (oracleState.currentActiveEffect == 32 && source.getAttacker() instanceof ServerPlayerEntity attacker) {
                attacker.heal(Math.min(4.0f, amount * 0.20f)); // Heals up to 2 hearts per hit
            }

            // Oracle Logic: Shared Fate/Hunger (31)
            if (oracleState.currentActiveEffect == 31 && entity instanceof ServerPlayerEntity victim) {
                if (IS_DAMAGING_SHARED.get()) return true;
                UUID linkedId = SHARED_FATE_LINKS.get(victim.getUuid());
                if (linkedId != null) {
                    World world = entity.getEntityWorld();
                    if (world instanceof ServerWorld sw) {
                        ServerPlayerEntity linkedPlayer = sw.getServer().getPlayerManager().getPlayer(linkedId);
                        if (linkedPlayer != null && linkedPlayer != victim) {
                            IS_DAMAGING_SHARED.set(true);
                            linkedPlayer.damage(sw, source, amount);
                            IS_DAMAGING_SHARED.set(false);
                        }
                    }
                }
            }
            
            // Oracle Logic: Pacifism (39)
            if (oracleState.currentActiveEffect == 39 && entity instanceof ServerPlayerEntity && source.getAttacker() instanceof ServerPlayerEntity) {
                return false;
            }

            // Oracle Logic: Friendly Mobs (24)
            if (oracleState.currentActiveEffect == 24 && entity instanceof ServerPlayerEntity && source.getAttacker() instanceof HostileEntity) {
                return false;
            }

            return true;
        });

        // Use item: blokace pro nepřihlášené + spotřeba srdce
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (player instanceof ServerPlayerEntity sp && !LOGGED_IN.contains(sp.getUuid())) {
                sp.sendMessage(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"), true);
                return ActionResult.FAIL;
            }
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(Items.NETHER_STAR)) {
                NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
                if (nbtComponent != null && nbtComponent.copyNbt().contains("lifesteal:heart")) {
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        updateMaxHealth(serverPlayer, 2.0);
                        stack.decrement(1);
                        updatePlayerStats(serverPlayer);
                        return ActionResult.SUCCESS;
                    }
                }
            }
            if (stack.isOf(Items.PLAYER_HEAD)) {
                NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
                if (nbtComponent != null && nbtComponent.copyNbt().contains("lifesteal:golden_head")) {
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        serverPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 1)); // 10s II
                        serverPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 2400, 1)); // 2m II
                        serverPlayer.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 400, 1)); // 20s II
                        
                        world.playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, world.random.nextFloat() * 0.1f + 0.9f);
                        
                        stack.decrement(1);
                        return ActionResult.SUCCESS;
                    }
                }
            }

            // Lost Compass (12): compasses/maps are disrupted
            if (oracleState.currentActiveEffect == 12 && (stack.isOf(Items.COMPASS) || stack.isOf(Items.RECOVERY_COMPASS) || stack.isOf(Items.FILLED_MAP) || stack.isOf(Items.MAP))) {
                if (player instanceof ServerPlayerEntity sp) {
                    sp.sendMessage(Text.literal("§cKompas i mapy jsou během eventu Ztracený kompas nestabilní!"), true);
                }
                return ActionResult.FAIL;
            }

            // Lava fishing (49): right-click fishing rod while touching lava can grant loot
            if (oracleState.currentActiveEffect == 49 && stack.isOf(Items.FISHING_ROD) && (player.isInLava() || player.getEntityWorld().getBlockState(player.getBlockPos().down()).isOf(Blocks.LAVA))) {
                if (world.random.nextFloat() < 0.12f) {
                    Item[] lavaLoot = {Items.BLAZE_ROD, Items.MAGMA_CREAM, Items.GOLD_INGOT, Items.QUARTZ, Items.OBSIDIAN};
                    ItemStack reward = new ItemStack(lavaLoot[world.random.nextInt(lavaLoot.length)], world.random.nextInt(2) + 1);
                    if (!player.getInventory().insertStack(reward.copy())) player.dropItem(reward, false);
                    world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 0.8f, 0.8f);
                }
            }
            // New items logic
            NbtComponent customNbt = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (customNbt != null) {
                NbtCompound nbt = customNbt.copyNbt();
                if (stack.isOf(Items.POTION)) {
                    if (nbt.contains("lifesteal:adrenaline")) {
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600, 2));
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 1));
                        stack.decrement(1);
                        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 1.0f, 1.0f);
                        return ActionResult.SUCCESS;
                    }
                    if (nbt.contains("lifesteal:ironskin")) {
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 1200, 1));
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 1200, 4));
                        stack.decrement(1);
                        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 1.0f, 1.0f);
                        return ActionResult.SUCCESS;
                    }
                }
                if (stack.isOf(Items.ENDER_PEARL) && nbt.contains("lifesteal:magnetic_pearl")) {
                    world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(15), e -> true).forEach(item -> {
                        item.setPosition(player.getX(), player.getY(), player.getZ());
                    });
                    world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                    stack.decrement(1);
                    return ActionResult.SUCCESS;
                }
                if (stack.isOf(Items.GOAT_HORN) && nbt.contains("lifesteal:echoing_horn")) {
                    world.getPlayers().stream().filter(p -> p != player && p.squaredDistanceTo(player) < 100*100).forEach(p -> {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0));
                    });
                    world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EVENT_RAID_HORN, SoundCategory.PLAYERS, 1.0f, 1.0f);
                    stack.decrement(1);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // Blokace interakcí pro nepřihlášené + Ochrana spawnu
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp) {
                if (!LOGGED_IN.contains(sp.getUuid())) {
                    sp.sendMessage(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"), true);
                    return ActionResult.FAIL;
                }
                BlockState state = world.getBlockState(hitResult.getBlockPos());
                // Povolit interakci s tlačítky, pákami a nášlapnými deskami na spawnu
                boolean isInteractive = state.isIn(BlockTags.BUTTONS) ||
                                        state.isOf(Blocks.LEVER) ||
                                        state.isIn(BlockTags.PRESSURE_PLATES);
                
                if (isInSpawnProtection(sp, hitResult.getBlockPos()) && !isInteractive) {
                    sp.sendMessage(Text.literal("§cOkolí spawnu je chráněno!"), true);
                    return ActionResult.FAIL;
                }

                // No Craft (50)
                if (oracleState.currentActiveEffect == 50 && state.isOf(Blocks.CRAFTING_TABLE)) {
                    sp.sendMessage(Text.literal("§cBěhem eventu No Craft je crafting table zablokovaný."), true);
                    return ActionResult.FAIL;
                }

                // Expensive Crafting (44)
                if (oracleState.currentActiveEffect == 44 && state.isOf(Blocks.CRAFTING_TABLE)) {
                    if (!hasItems(sp, Items.IRON_NUGGET, 1)) {
                        sp.sendMessage(Text.literal("§cBěhem eventu Expensive Crafting potřebuješ 1x Iron Nugget na otevření craftingu."), true);
                        return ActionResult.FAIL;
                    }
                    removeItems(sp, Items.IRON_NUGGET, 1);
                    sp.sendMessage(Text.literal("§eExpensive Crafting: zaplaceno 1x Iron Nugget."), true);
                }

                if (!sp.isCreative() && isInsideEndShip(world, hitResult.getBlockPos())) {
                    sp.sendMessage(Text.literal("§cTato loď je chráněna strážcem!"), true);
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
        
        // Ochrana spawnu proti ničení a Oracle eventy pro těžbu
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayerEntity sp) {
                if (!LOGGED_IN.contains(sp.getUuid())) {
                    return ActionResult.FAIL;
                }
                if (isInSpawnProtection(sp, pos)) {
                    sp.sendMessage(Text.literal("§cOkolí spawnu je chráněno!"), true);
                    return ActionResult.FAIL;
                }
                if (!sp.isCreative() && isInsideEndShip(world, pos)) {
                    sp.sendMessage(Text.literal("§cTato loď je chráněna strážcem!"), true);
                    return ActionResult.FAIL;
                }
                
                // Obsidian softness (48)
                if (oracleState.currentActiveEffect == 48 && world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                    world.breakBlock(pos, true, player);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity)) return;
            
            // Lucky Ore (42)
            if (oracleState.currentActiveEffect == 42 && state.isOf(Blocks.COAL_ORE) && world.random.nextFloat() < 0.01) {
                ItemStack diamond = new ItemStack(Items.DIAMOND);
                ItemEntity entity = new ItemEntity(world, pos.getX(), pos.getY(), pos.getZ(), diamond);
                world.spawnEntity(entity);
            }
            
            // Lumberjack (47)
            if (oracleState.currentActiveEffect == 47 && state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
                breakTree(world, pos, new HashSet<>());
            }
            
            // Vein Miner (41)
            if (oracleState.currentActiveEffect == 41 && state.isIn(net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BLOCK, Identifier.of("c", "ores")))) {
                breakVein(world, pos, state.getBlock(), new HashSet<>(), 0);
            }
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && !LOGGED_IN.contains(sp.getUuid())) {
                sp.sendMessage(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"), true);
                return ActionResult.FAIL;
            }

            // Villagers on Strike (15)
            if (!world.isClient() && oracleState.currentActiveEffect == 15 && entity instanceof net.minecraft.entity.passive.VillagerEntity) {
                if (player instanceof ServerPlayerEntity sp) {
                    sp.sendMessage(Text.literal("§cVesničané během eventu stávkují a neobchodují."), true);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Příkazy
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("achievements")
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    
                    Set<String> unlocked = oracleState.playerAchievements.getOrDefault(p.getUuid().toString(), new HashSet<>());
                    
                    p.sendMessage(Text.literal("§6§l--- Tvé Achievementy ---"), false);
                    for (Achievement a : ACHIEVEMENTS.values()) {
                        String prefix = unlocked.contains(a.id) ? "§a[✔] " : "§c[✘] ";
                        String color = a.tier.equals("GOLD") ? "§e" : a.tier.equals("SILVER") ? "§f" : "§7";
                        p.sendMessage(Text.literal(prefix + color + a.name + " §8- §7" + a.description), false);
                    }
                    p.sendMessage(Text.literal("§6§l-----------------------"), false);
                    return 1;
                }));

            dispatcher.register(CommandManager.literal("register")
                .then(CommandManager.argument("password", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) return 0;
                        UUID id = p.getUuid();
                        if (PASSWORDS.containsKey(id)) {
                            ctx.getSource().sendError(Text.literal("§cUž jsi registrován. Použij /login <heslo>."));
                            return 0;
                        }
                        String pass = StringArgumentType.getString(ctx, "password");
                        String hash = hashPassword(id, pass);
                        PASSWORDS.put(id, hash);
                        LOGGED_IN.add(id);
                        p.removeStatusEffect(StatusEffects.SLOWNESS);
                        p.removeStatusEffect(StatusEffects.DARKNESS);
                        p.removeStatusEffect(StatusEffects.BLINDNESS);
                        saveData();
                        ctx.getSource().sendFeedback(() -> Text.literal("§aRegistrace úspěšná, jsi přihlášen."), false);
                        ctx.getSource().sendFeedback(() -> Text.literal("§eTip: Pro návrat na původní místo zmáčkni tlačítko na spawnu."), false);
                        return 1;
                    })
                )
            );
            dispatcher.register(CommandManager.literal("login")
                .then(CommandManager.argument("password", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) return 0;
                        UUID id = p.getUuid();
                        if (LOGGED_IN.contains(id)) {
                            ctx.getSource().sendFeedback(() -> Text.literal("§aUž jsi přihlášen."), false);
                            return 1;
                        }
                        if (!PASSWORDS.containsKey(id)) {
                            ctx.getSource().sendError(Text.literal("§eNejsi registrován. Použij /register <heslo>."));
                            return 0;
                        }
                        String pass = StringArgumentType.getString(ctx, "password");
                        String expect = PASSWORDS.get(id);
                        if (expect.equals(hashPassword(id, pass))) {
                            LOGGED_IN.add(id);
                            p.removeStatusEffect(StatusEffects.SLOWNESS);
                            p.removeStatusEffect(StatusEffects.DARKNESS);
                            p.removeStatusEffect(StatusEffects.BLINDNESS);
                            if (oracleState.spawnSet) {
                                teleportToConfiguredSpawn(p);
                                updateLoginFreezeAnchorToCurrentPosition(p);
                            }
                            ctx.getSource().sendFeedback(() -> Text.literal("§aPřihlášení úspěšné!"), false);
                            ctx.getSource().sendFeedback(() -> Text.literal("§eTip: Pro návrat na původní místo zmáčkni tlačítko na spawnu."), false);
                            return 1;
                        } else {
                            ctx.getSource().sendError(Text.literal("§cŠpatné heslo."));
                            return 0;
                        }
                    })
                )
            );

            // withdraw
            dispatcher.register(CommandManager.literal("withdraw").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                    if (instance != null && instance.getValue() >= 4.0) {
                        updateMaxHealth(player, -2.0);

                        ItemStack heart = new ItemStack(Items.NETHER_STAR);
                        NbtCompound nbt = new NbtCompound();
                        nbt.putBoolean("lifesteal:heart", true);
                        heart.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                        heart.set(DataComponentTypes.ITEM_NAME, Text.literal("Heart"));

                        if (!player.getInventory().insertStack(heart)) {
                            player.dropItem(heart, false);
                        }

                        context.getSource().sendFeedback(() -> Text.literal("Withdrew 1 heart!"), false);
                        return 1;
                    } else {
                        context.getSource().sendError(Text.literal("You don't have enough health to withdraw a heart!"));
                    }
                }
                return 0;
            }));

            // shop
            dispatcher.register(CommandManager.literal("shop").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    openShop(player);
                    return 1;
                }
                return 0;
            }));

            // menu
            dispatcher.register(CommandManager.literal("menu").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    openMainMenu(player);
                    return 1;
                }
                return 0;
            }));

            // spawn
            dispatcher.register(CommandManager.literal("spawn").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    long now = System.currentTimeMillis();
                    Long combatUntil = COMBAT_TAGS.get(player.getUuid());
                    if (combatUntil != null && combatUntil > now) {
                        context.getSource().sendError(Text.literal("§cNemůžeš se teleportovat na spawn, když jsi v boji!"));
                        return 0;
                    } else if (combatUntil != null) {
                        COMBAT_TAGS.remove(player.getUuid());
                        COMBAT_LAST_ATTACKER.remove(player.getUuid());
                        removeCombatBossBar(player.getUuid());
                    }
                    if (teleportToConfiguredSpawn(player)) {
                        context.getSource().sendFeedback(() -> Text.literal("§aByl jsi teleportován na spawn."), false);
                        return 1;
                    } else {
                        context.getSource().sendError(Text.literal("§cSpawn nebyl administrátorem nastaven."));
                        return 0;
                    }
                }
                return 0;
            }));

            // sit
            dispatcher.register(CommandManager.literal("sit").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    if (!player.isOnGround()) {
                        context.getSource().sendError(Text.literal("§cMusíš stát na zemi, aby sis mohl sednout!"));
                        return 0;
                    }
                    if (player.hasVehicle()) {
                        return 0;
                    }

                    ServerWorld world = (ServerWorld) player.getCommandSource().getWorld();
                    ArmorStandEntity chair = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
                    chair.refreshPositionAndAngles(player.getX(), player.getY() - 1.6, player.getZ(), 0, 0);
                    chair.setInvisible(true);
                    chair.setNoGravity(true);
                    chair.setInvulnerable(true);
                    chair.setCustomName(Text.literal("ls_chair"));
                    world.spawnEntity(chair);
                    player.startRiding(chair);
                    return 1;
                }
                return 0;
            }));

            // lay
            dispatcher.register(CommandManager.literal("lay").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    if (player.isGliding()) {
                        context.getSource().sendError(Text.literal("§cBěhem letu na elytře nelze použít /lay."));
                        return 0;
                    }
                    if (player.hasVehicle()) {
                        player.stopRiding();
                    }
                    CRAWLING_PLAYERS.remove(player.getUuid());
                    if (LAYING_PLAYERS.contains(player.getUuid())) {
                        LAYING_PLAYERS.remove(player.getUuid());
                        player.setPose(EntityPose.STANDING);
                        player.clearSleepingPosition();
                        context.getSource().sendFeedback(() -> Text.literal("§eJiž neležíš."), false);
                    } else {
                        LAYING_PLAYERS.add(player.getUuid());
                        player.setPose(EntityPose.SLEEPING);
                        player.setSleepingPosition(player.getBlockPos());
                        context.getSource().sendFeedback(() -> Text.literal("§aNyní ležíš. Pohybem nebo Shiftem se zvedneš."), false);
                    }
                    return 1;
                }
                return 0;
            }));

            // crawl
            dispatcher.register(CommandManager.literal("crawl").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    if (!LOGGED_IN.contains(player.getUuid())) {
                        context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                        return 0;
                    }
                    if (player.isGliding()) {
                        context.getSource().sendError(Text.literal("§cBěhem letu na elytře nelze použít /crawl."));
                        return 0;
                    }
                    if (player.hasVehicle()) {
                        player.stopRiding();
                    }
                    if (LAYING_PLAYERS.remove(player.getUuid())) {
                        player.clearSleepingPosition();
                    }
                    if (CRAWLING_PLAYERS.contains(player.getUuid())) {
                        CRAWLING_PLAYERS.remove(player.getUuid());
                        player.setPose(EntityPose.STANDING);
                        context.getSource().sendFeedback(() -> Text.literal("§eJiž se neplazíš."), false);
                    } else {
                        CRAWLING_PLAYERS.add(player.getUuid());
                        player.setPose(EntityPose.SWIMMING);
                        context.getSource().sendFeedback(() -> Text.literal("§aNyní se plazíš. Můžeš se pohybovat. Shiftem se zvedneš."), false);
                    }
                    return 1;
                }
                return 0;
            }));

            // vote internal command
            dispatcher.register(CommandManager.literal("lsvote")
                .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) return 0;
                        int id = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id");
                        return submitVote(p, id);
                    })
                )
            );

            // /event - info o aktuálním eventu pro všechny hráče
            dispatcher.register(CommandManager.literal("event").executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null && !LOGGED_IN.contains(player.getUuid())) {
                    context.getSource().sendError(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"));
                    return 0;
                }

                context.getSource().sendFeedback(() -> Text.literal("§6§l══════ The Oracle ══════"), false);

                if (oracleState.votingActive) {
                    context.getSource().sendFeedback(() -> Text.literal("§e⏳ Právě probíhá hlasování!"), false);
                    long voteRemaining = oracleState.votingEndTime - System.currentTimeMillis();
                    String voteTimeStr = voteRemaining > 0 ? formatDuration(voteRemaining) : "brzy skončí";
                    context.getSource().sendFeedback(() -> Text.literal("§7Zbývá: §f" + voteTimeStr), false);
                    context.getSource().sendFeedback(() -> Text.literal("§7Použij §f/menu §7a otevři Oracle Hlasování."), false);
                    if (!oracleState.currentOptions.isEmpty()) {
                        context.getSource().sendFeedback(() -> Text.literal("§e--- Možnosti ---"), false);
                        for (int i = 0; i < oracleState.currentOptions.size(); i++) {
                            int eventId = oracleState.currentOptions.get(i);
                            String name = EVENT_NAMES.getOrDefault(eventId, "Neznámý");
                            int votes = VOTE_COUNTS.getOrDefault(i + 1, 0);
                            final int optNum = i + 1;
                            context.getSource().sendFeedback(() -> Text.literal("§a" + optNum + ". §f" + name + " §7(" + votes + " hlasů)"), false);
                        }
                    }
                } else if (oracleState.isEventActive && oracleState.currentActiveEffect > 0) {
                    int eid = oracleState.currentActiveEffect;
                    String ename = EVENT_NAMES.getOrDefault(eid, "Neznámý");
                    long remaining = oracleState.effectEndTime - System.currentTimeMillis();
                    String timeStr = remaining > 0 ? formatDuration(remaining) : "brzy skončí";

                    context.getSource().sendFeedback(() -> Text.literal("§a✦ Aktivní event: §f§l" + ename), false);
                    context.getSource().sendFeedback(() -> Text.literal("§7ID: §f#" + eid), false);
                    context.getSource().sendFeedback(() -> Text.literal("§7Zbývá: §f" + timeStr), false);

                    String desc = getEventDescription(eid);
                    context.getSource().sendFeedback(() -> Text.literal("§7Popis: §f" + desc), false);

                    // Special info for bounty/assassin
                    if (eid == 53 && oracleState.targetPlayerUuid != null) {
                        MinecraftServer srv = context.getSource().getServer();
                        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(oracleState.targetPlayerUuid);
                        String targetName = target != null ? target.getName().getString() : "offline hráč";
                        context.getSource().sendFeedback(() -> Text.literal("§c⚔ Cíl (Skull Hunter): §f" + targetName + " §7— pokud přežije, ostatní ztratí 1 srdce!"), false);
                    }
                    if (eid == 55 && player != null && player.getUuid().equals(oracleState.assassinUuid) && oracleState.assassinTargetUuid != null) {
                        MinecraftServer srv = context.getSource().getServer();
                        ServerPlayerEntity target = srv.getPlayerManager().getPlayer(oracleState.assassinTargetUuid);
                        String targetName = target != null ? target.getName().getString() : "offline hráč";
                        context.getSource().sendFeedback(() -> Text.literal("§4§l⚠ Jsi atentátník! §cTvůj cíl: §f" + targetName), false);
                    }
                } else {
                    context.getSource().sendFeedback(() -> Text.literal("§7Žádný event momentálně neprobíhá."), false);
                    context.getSource().sendFeedback(() -> Text.literal("§7Hlasování probíhá každou sobotu 15:00–15:30."), false);
                }

                context.getSource().sendFeedback(() -> Text.literal("§6§l════════════════════"), false);
                return 1;
            }));

            // admin lifesteal (nahrazeno lsadmin)
            dispatcher.register(CommandManager.literal("lsadmin")
                .requires(LifeSteal::isAdminSource)
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal("§6§lLifeSteal Admin Commands:"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e--- Hearts ---"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin sethearts <player> <amount>"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin addhearts <player> <amount>"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin gethearts <player>"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e--- Player ---"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin kick|ban|unban|mute|unmute|freeze|unfreeze|vanish|resetlogin|resetprogress"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin cannon allow|revoke <player>"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e--- Oracle ---"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin event start|stop|list|status|vote"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e--- Server ---"), false);
                    context.getSource().sendFeedback(() -> Text.literal("§e/lsadmin setspawn|tpto|tphere|broadcast|reload"), false);
                    return 1;
                })
                .then(CommandManager.literal("sethearts")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(2.0))
                            .executes(context -> {
                                ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                                double amount = DoubleArgumentType.getDouble(context, "amount");

                                EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                                if (instance != null) {
                                    instance.removeModifier(HEALTH_MODIFIER_ID);
                                    instance.addPersistentModifier(new EntityAttributeModifier(
                                            HEALTH_MODIFIER_ID,
                                            amount - instance.getBaseValue(),
                                            EntityAttributeModifier.Operation.ADD_VALUE
                                    ));
                                    if (player.getHealth() > (float) amount) player.setHealth((float) amount);
                                    updatePlayerStats(player);
                                    context.getSource().sendFeedback(() -> Text.literal("§aZdraví hráče " + player.getName().getString() + " nastaveno na " + (amount/2) + " srdíček."), true);
                                    return 1;
                                }
                                return 0;
                            })
                        )
                    )
                )
                .then(CommandManager.literal("addhearts")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg())
                            .executes(context -> {
                                ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                                double amount = DoubleArgumentType.getDouble(context, "amount");
                                updateMaxHealth(player, amount * 2.0);
                                updatePlayerStats(player);
                                context.getSource().sendFeedback(() -> Text.literal("§aHráči " + player.getName().getString() + " bylo přidáno/odebráno " + amount + " srdíček."), true);
                                return 1;
                            })
                        )
                    )
                )
                .then(CommandManager.literal("event")
                    .then(CommandManager.literal("start")
                        .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 81))
                            .executes(context -> {
                                int id = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id");
                                if (!IMPLEMENTED_EVENTS.contains(id)) {
                                    context.getSource().sendError(Text.literal("§cTento event ještě není plně implementovaný a nelze ho spustit."));
                                    return 0;
                                }
                                oracleState.currentActiveEffect = id;
                                oracleState.isEventActive = true;
                                LocalDateTime startNow = LocalDateTime.now();
                                LocalDateTime end = startNow.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX);
                                oracleState.effectEndTime = end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

                                if (id == 53 || id == 55) {
                                    setupBountyOrAssassin(context.getSource().getServer(), id);
                                } else if (id == 81) {
                                    antiSnowball(context.getSource().getServer());
                                } else if (id == 31) {
                                    setupSharedFate(context.getSource().getServer());
                                }

                                String eventName = EVENT_NAMES.getOrDefault(id, "Neznámý");
                                context.getSource().getServer().getPlayerManager().broadcast(Text.literal("§6§lOracle Admin: §eAktivován event č. " + id + " - " + eventName), false);
                                
                                // Title pro admin start
                                for (ServerPlayerEntity p : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("§6§lThe Oracle")));
                                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal("§eAdmin aktivoval: §f" + eventName)));
                                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(10, 70, 20));
                                }

                                saveData();
                                return 1;
                            })
                        )
                    )
                    .then(CommandManager.literal("stop")
                        .executes(context -> {
                            int endingEffect = oracleState.currentActiveEffect;
                            MinecraftServer server = context.getSource().getServer();

                            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                                p.removeStatusEffect(StatusEffects.SLOW_FALLING);
                                p.removeStatusEffect(StatusEffects.JUMP_BOOST);
                                p.removeStatusEffect(StatusEffects.SLOWNESS);
                                p.removeStatusEffect(StatusEffects.BLINDNESS);
                                p.removeStatusEffect(StatusEffects.SPEED);
                                p.removeStatusEffect(StatusEffects.INVISIBILITY);
                                p.removeStatusEffect(StatusEffects.STRENGTH);
                                p.removeStatusEffect(StatusEffects.POISON);
                                p.removeStatusEffect(StatusEffects.REGENERATION);
                                p.removeStatusEffect(StatusEffects.RESISTANCE);
                                p.removeStatusEffect(StatusEffects.FIRE_RESISTANCE);
                                p.removeStatusEffect(StatusEffects.GLOWING);
                                p.removeStatusEffect(StatusEffects.DARKNESS);
                                p.removeStatusEffect(StatusEffects.HASTE);
                                p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
                                p.removeStatusEffect(StatusEffects.HUNGER);
                                p.removeStatusEffect(StatusEffects.LUCK);
                                p.removeStatusEffect(StatusEffects.NAUSEA);
                                p.removeStatusEffect(StatusEffects.NIGHT_VISION);
                                p.removeStatusEffect(StatusEffects.WITHER);
                                p.removeStatusEffect(StatusEffects.WEAKNESS);
                                p.removeStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE);

                                if (endingEffect == 71) {
                                    EntityAttributeInstance hpAttr = p.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                                    if (hpAttr != null && hpAttr.getBaseValue() != 20.0) {
                                        hpAttr.setBaseValue(20.0);
                                    }
                                }
                                if (endingEffect == 58 || endingEffect == 59) {
                                    EntityAttributeInstance scaleAttr = p.getAttributeInstance(EntityAttributes.SCALE);
                                    if (scaleAttr != null && scaleAttr.getBaseValue() != 1.0) {
                                        scaleAttr.setBaseValue(1.0);
                                    }
                                }
                            }

                            oracleState.currentActiveEffect = 0;
                            oracleState.isEventActive = false;
                            oracleState.effectEndTime = 0;
                            oracleState.targetPlayerUuid = null;
                            oracleState.assassinUuid = null;
                            oracleState.assassinTargetUuid = null;
                            oracleState.lastAntiSnowballRun = 0;
                            SHARED_FATE_LINKS.clear();
                            server.getPlayerManager().broadcast(Text.literal("§6§lOracle Admin: §cEvent byl zastaven."), false);
                            saveData();
                            return 1;
                        })
                    )
                    .then(CommandManager.literal("vote")
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            if (oracleState.votingActive) {
                                context.getSource().sendError(Text.literal("§cHlasování již probíhá."));
                                return 0;
                            }
                            startVoting(context.getSource().getServer(), -1);
                            return 1;
                        })
                    )
                    .then(CommandManager.literal("list")
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            context.getSource().sendFeedback(() -> Text.literal("§6§l=== Oracle Events (1-81) ==="), false);
                            for (int i = 1; i <= 81; i++) {
                                final int eventId = i;
                                String name = EVENT_NAMES.getOrDefault(eventId, "Neznámý");
                                context.getSource().sendFeedback(() -> Text.literal("§e" + eventId + ". §f" + name), false);
                            }
                            return 1;
                        })
                    )
                    .then(CommandManager.literal("status")
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            if (!oracleState.isEventActive) {
                                context.getSource().sendFeedback(() -> Text.literal("§7Žádný event není aktivní."), false);
                            } else {
                                int eid = oracleState.currentActiveEffect;
                                String ename = EVENT_NAMES.getOrDefault(eid, "Neznámý");
                                long remaining = oracleState.effectEndTime - System.currentTimeMillis();
                                String timeStr = remaining > 0 ? formatDuration(remaining) : "vypršelo";
                                context.getSource().sendFeedback(() -> Text.literal("§aAktivní event: §f#" + eid + " " + ename + " §7(zbývá: " + timeStr + ")"), false);
                            }
                            if (oracleState.votingActive) {
                                context.getSource().sendFeedback(() -> Text.literal("§eHlasování probíhá."), false);
                            }
                            return 1;
                        })
                    )
                )
                .then(CommandManager.literal("setspawn")
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player != null) {
                            oracleState.spawnX = player.getX();
                            oracleState.spawnY = player.getY();
                            oracleState.spawnZ = player.getZ();
                            oracleState.spawnDimension = player.getEntityWorld().getRegistryKey().getValue().toString();
                            oracleState.spawnYaw = player.getYaw();
                            oracleState.spawnPitch = player.getPitch();
                            oracleState.spawnSet = true;
                            saveData();
                            context.getSource().sendFeedback(() -> Text.literal("§aSpawn byl nastaven na tvou aktuální pozici."), true);
                            return 1;
                        }
                        return 0;
                    })
                )
                .then(CommandManager.literal("cannon")
                    .then(CommandManager.literal("allow")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(context -> {
                                ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                                if (grantCannonAccess(context.getSource().getServer(), player)) {
                                    context.getSource().sendFeedback(() -> Text.literal("§aHráč " + player.getName().getString() + " dostal creative a oprávnění na stavbu kanónu."), false);
                                    return 1;
                                }
                                context.getSource().sendError(Text.literal("§cNepodařilo se schválení aktivovat."));
                                return 0;
                            })
                        )
                    )
                    .then(CommandManager.literal("revoke")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(context -> {
                                ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                                if (revokeCannonAccess(context.getSource().getServer(), player)) {
                                    context.getSource().sendFeedback(() -> Text.literal("§eHráči " + player.getName().getString() + " byla vrácena původní práva."), false);
                                    return 1;
                                }
                                context.getSource().sendError(Text.literal("§cHráč nemá aktivní schválení kanónu."));
                                return 0;
                            })
                        )
                    )
                )
                .then(CommandManager.literal("setspawnradius")
                    .then(CommandManager.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .executes(context -> {
                            int radius = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius");
                            oracleState.spawnProtectionRadius = radius;
                            saveData();
                            context.getSource().sendFeedback(() -> Text.literal("§aRadius ochrany spawnu byl nastaven na " + radius + " bloků."), true);
                            return 1;
                        })
                    )
                )
                .then(CommandManager.literal("return")
                    .executes(context -> {
                        ServerPlayerEntity closest = null;
                        double minDist = Double.MAX_VALUE;
                        Vec3d sourcePos = context.getSource().getPosition();
                        
                        for (ServerPlayerEntity p : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                            if (RETURN_POS.containsKey(p.getUuid())) {
                                double dist = p.squaredDistanceTo(sourcePos);
                                if (dist < minDist) {
                                    minDist = dist;
                                    closest = p;
                                }
                            }
                        }
                        
                        if (closest != null) {
                            final ServerPlayerEntity target = closest;
                            TeleportTarget targetData = RETURN_POS.get(target.getUuid());
                            target.teleportTo(targetData);
                            context.getSource().sendFeedback(() -> Text.literal("§aNejbližší hráč " + target.getName().getString() + " byl portnut zpět."), true);
                            RETURN_POS.remove(target.getUuid());
                            return 1;
                        } else {
                            context.getSource().sendError(Text.literal("§cV dosahu není žádný hráč s uloženou pozicí pro návrat."));
                            return 0;
                        }
                    })
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                            if (player != null) {
                                TeleportTarget targetData = RETURN_POS.get(player.getUuid());
                                if (targetData != null) {
                                    player.teleportTo(targetData);
                                    context.getSource().sendFeedback(() -> Text.literal("§aHráč " + player.getName().getString() + " byl portnut zpět."), true);
                                    RETURN_POS.remove(player.getUuid());
                                    return 1;
                                } else {
                                    context.getSource().sendError(Text.literal("§cHráč nemá uloženou pozici pro návrat."));
                                    return 0;
                                }
                            }
                            return 0;
                        })
                    )
                )
                .then(CommandManager.literal("resetlogin")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                            PASSWORDS.remove(player.getUuid());
                            LOGGED_IN.remove(player.getUuid());
                            saveData();
                            context.getSource().sendFeedback(() -> Text.literal("§aResetována registrace pro " + player.getName().getString()), true);
                            player.networkHandler.disconnect(Text.literal("§eTvoje registrace byla smazána administrátorem."));
                            return 1;
                        })
                    )
                )
                .then(CommandManager.literal("removeachievement")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("achievement", StringArgumentType.string())
                            .suggests((context, builder) -> CommandSource.suggestMatching(ACHIEVEMENTS.keySet(), builder))
                            .executes(context -> {
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                String achievementId = StringArgumentType.getString(context, "achievement");
                                
                                Set<String> playerAch = oracleState.playerAchievements.get(target.getUuid().toString());
                                if (playerAch != null && playerAch.remove(achievementId)) {
                                    context.getSource().sendFeedback(() -> Text.literal("§aAchievement " + achievementId + " byl odebrán hráči " + target.getName().getString() + "."), true);
                                    saveData();
                                } else {
                                    context.getSource().sendError(Text.literal("§cHráč " + target.getName().getString() + " nemá tento achievement nebo ID neexistuje."));
                                }
                                return 1;
                            })
                        )
                    )
                )
                .then(CommandManager.literal("reload")
                    .executes(context -> {
                        if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                        loadData();
                        context.getSource().sendFeedback(() -> Text.literal("§aData byla znovu načtena ze souboru."), true);
                        return 1;
                    })
                )
                // === NEW: gethearts ===
                .then(CommandManager.literal("gethearts")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            int hearts = (int) (target.getMaxHealth() / 2);
                            context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " má " + hearts + " srdíček (" + target.getMaxHealth() + " HP)."), false);
                            return 1;
                        })
                    )
                )
                // === NEW: kick ===
                .then(CommandManager.literal("kick")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            target.networkHandler.disconnect(Text.literal("§cByl jsi vyhozen ze serveru."));
                            context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " byl vyhozen."), true);
                            return 1;
                        })
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                            .executes(context -> {
                                if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                String reason = StringArgumentType.getString(context, "reason");
                                target.networkHandler.disconnect(Text.literal("§cVyhozen: " + reason));
                                context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " byl vyhozen. Důvod: " + reason), true);
                                return 1;
                            })
                        )
                    )
                )
                // === NEW: ban ===
                .then(CommandManager.literal("ban")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("duration", StringArgumentType.word())
                            .executes(context -> {
                                if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                String durationStr = StringArgumentType.getString(context, "duration");
                                long durationMs = parseDuration(durationStr);
                                if (durationMs <= 0) { context.getSource().sendError(Text.literal("§cNeplatná délka (použij např. 1d, 7d, 1h).")); return 0; }
                                long endTime = System.currentTimeMillis() + durationMs;
                                BANNED_PLAYERS.put(target.getUuid().toString(), endTime);
                                BAN_REASONS.put(target.getUuid().toString(), "Bez důvodu");
                                saveData();
                                target.networkHandler.disconnect(Text.literal("§cByl jsi zabanován na " + durationStr + "."));
                                context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " zabanován na " + durationStr + "."), true);
                                return 1;
                            })
                            .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> {
                                    if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                    String durationStr = StringArgumentType.getString(context, "duration");
                                    String reason = StringArgumentType.getString(context, "reason");
                                    long durationMs = parseDuration(durationStr);
                                    if (durationMs <= 0) { context.getSource().sendError(Text.literal("§cNeplatná délka.")); return 0; }
                                    long endTime = System.currentTimeMillis() + durationMs;
                                    BANNED_PLAYERS.put(target.getUuid().toString(), endTime);
                                    BAN_REASONS.put(target.getUuid().toString(), reason);
                                    saveData();
                                    target.networkHandler.disconnect(Text.literal("§cByl jsi zabanován na " + durationStr + ". Důvod: " + reason));
                                    context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " zabanován na " + durationStr + ". Důvod: " + reason), true);
                                    return 1;
                                })
                            )
                        )
                    )
                )
                // === NEW: unban ===
                .then(CommandManager.literal("unban")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            String name = StringArgumentType.getString(context, "name");
                            // Remove ban by offline UUID fallback
                            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                            boolean removed = BANNED_PLAYERS.remove(offlineUuid.toString()) != null;
                            BAN_REASONS.remove(offlineUuid.toString());

                            // Also check all online player UUIDs by exact name match
                            MinecraftServer srv = context.getSource().getServer();
                            for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                                if (p.getName().getString().equalsIgnoreCase(name)) {
                                    removed |= BANNED_PLAYERS.remove(p.getUuid().toString()) != null;
                                    BAN_REASONS.remove(p.getUuid().toString());
                                }
                            }
                            if (removed) {
                                saveData();
                                context.getSource().sendFeedback(() -> Text.literal("§aHráč " + name + " byl odbanován."), true);
                            } else {
                                context.getSource().sendError(Text.literal("§cHráč " + name + " nebyl nalezen v banlistu."));
                            }
                            return 1;
                        })
                    )
                )
                // === NEW: mute ===
                .then(CommandManager.literal("mute")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("duration", StringArgumentType.word())
                            .executes(context -> {
                                if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                String durationStr = StringArgumentType.getString(context, "duration");
                                long durationMs = parseDuration(durationStr);
                                if (durationMs <= 0) { context.getSource().sendError(Text.literal("§cNeplatná délka.")); return 0; }
                                MUTED_PLAYERS.put(target.getUuid(), System.currentTimeMillis() + durationMs);
                                saveData();
                                target.sendMessage(Text.literal("§cByl jsi umlčen na " + durationStr + "."), false);
                                context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " umlčen na " + durationStr + "."), true);
                                return 1;
                            })
                        )
                    )
                )
                // === NEW: unmute ===
                .then(CommandManager.literal("unmute")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            if (MUTED_PLAYERS.remove(target.getUuid()) == null) {
                                context.getSource().sendError(Text.literal("§cHráč není aktuálně umlčen."));
                                return 0;
                            }
                            saveData();
                            target.sendMessage(Text.literal("§aByl jsi odumlčen."), false);
                            context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " odumlčen."), true);
                            return 1;
                        })
                    )
                )
                // === NEW: freeze ===
                .then(CommandManager.literal("freeze")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            FROZEN_PLAYERS.add(target.getUuid());
                            target.sendMessage(Text.literal("§cByl jsi zamrazen adminem!"), false);
                            context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " zamrazen."), true);
                            return 1;
                        })
                    )
                )
                // === NEW: unfreeze ===
                .then(CommandManager.literal("unfreeze")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            FROZEN_PLAYERS.remove(target.getUuid());
                            target.sendMessage(Text.literal("§aByl jsi odmrazen."), false);
                            context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " odmrazen."), true);
                            return 1;
                        })
                    )
                )
                // === NEW: vanish ===
                .then(CommandManager.literal("vanish")
                    .executes(context -> {
                        if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) { context.getSource().sendError(Text.literal("§cPouze pro hráče.")); return 0; }
                        if (VANISHED_PLAYERS.contains(player.getUuid())) {
                            VANISHED_PLAYERS.remove(player.getUuid());
                            // Make visible again
                            for (ServerPlayerEntity other : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                                if (other != player) {
                                    other.networkHandler.sendPacket(new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.of(player)));
                                    other.networkHandler.sendPacket(new EntitySpawnS2CPacket(player, 0, player.getBlockPos()));
                                }
                            }
                            context.getSource().sendFeedback(() -> Text.literal("§aVanish: §fVYPNUT §7- jsi viditelný."), false);
                        } else {
                            VANISHED_PLAYERS.add(player.getUuid());
                            // Hide from all players
                            for (ServerPlayerEntity other : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                                if (other != player) {
                                    other.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(player.getId()));
                                    other.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
                                }
                            }
                            context.getSource().sendFeedback(() -> Text.literal("§aVanish: §fZAPNUT §7- jsi neviditelný."), false);
                        }
                        return 1;
                    })
                )
                // === NEW: resetprogress ===
                .then(CommandManager.literal("resetprogress")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            // Reset hearts to default
                            EntityAttributeInstance instance = target.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                            if (instance != null) {
                                instance.removeModifier(HEALTH_MODIFIER_ID);
                                target.setHealth(20.0f);
                            }
                            // Reset K/D
                            Scoreboard scoreboard = context.getSource().getServer().getScoreboard();
                            ScoreboardObjective killsObj = getKillsObjective(context.getSource().getServer());
                            ScoreboardObjective deathsObj = getDeathsObjective(context.getSource().getServer());
                            scoreboard.getOrCreateScore(target, killsObj).setScore(0);
                            scoreboard.getOrCreateScore(target, deathsObj).setScore(0);
                            // Reset achievements
                            oracleState.playerAchievements.remove(target.getUuid().toString());
                            updatePlayerStats(target);
                            saveData();
                            context.getSource().sendFeedback(() -> Text.literal("§aVeškerý progress hráče " + target.getName().getString() + " byl resetován."), true);
                            return 1;
                        })
                    )
                )
                // === NEW: tpto ===
                .then(CommandManager.literal("tpto")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity admin = context.getSource().getPlayer();
                            if (admin == null) { context.getSource().sendError(Text.literal("§cPouze pro hráče.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            ServerWorld targetWorld = (ServerWorld) target.getCommandSource().getWorld();
                            admin.teleportTo(new TeleportTarget(targetWorld, new Vec3d(target.getX(), target.getY(), target.getZ()), Vec3d.ZERO, target.getYaw(), target.getPitch(), TeleportTarget.NO_OP));
                            context.getSource().sendFeedback(() -> Text.literal("§aTeleportován k hráči " + target.getName().getString() + "."), false);
                            return 1;
                        })
                    )
                )
                // === NEW: tphere ===
                .then(CommandManager.literal("tphere")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            ServerPlayerEntity admin = context.getSource().getPlayer();
                            if (admin == null) { context.getSource().sendError(Text.literal("§cPouze pro hráče.")); return 0; }
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                            ServerWorld adminWorld = (ServerWorld) admin.getCommandSource().getWorld();
                            target.teleportTo(new TeleportTarget(adminWorld, new Vec3d(admin.getX(), admin.getY(), admin.getZ()), Vec3d.ZERO, admin.getYaw(), admin.getPitch(), TeleportTarget.NO_OP));
                            context.getSource().sendFeedback(() -> Text.literal("§aHráč " + target.getName().getString() + " teleportován k tobě."), true);
                            return 1;
                        })
                    )
                )
                // === NEW: broadcast ===
                .then(CommandManager.literal("broadcast")
                    .then(CommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            if (!isAdmin(context)) { context.getSource().sendError(Text.literal("§cNemáš oprávnění.")); return 0; }
                            String message = StringArgumentType.getString(context, "message");
                            Text broadcastText = Text.literal("§c§l[Server] §r" + message.replace("&", "§"));
                            context.getSource().getServer().getPlayerManager().broadcast(broadcastText, false);
                            // Title too
                            for (ServerPlayerEntity p : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("§c§l[Server]")));
                                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal("§f" + message.replace("&", "§"))));
                                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(10, 60, 20));
                            }
                            return 1;
                        })
                    )
                )
            );

        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && !LOGGED_IN.contains(sp.getUuid())) {
                return ActionResult.FAIL;
            }
            if (!world.isClient() && player instanceof ServerPlayerEntity sp) {
                if (entity instanceof ItemFrameEntity frame && frame.getHeldItemStack().isOf(Items.ELYTRA)
                        && world.getRegistryKey().equals(World.END)
                        && isInsideEndShip(world, frame.getBlockPos())) {
                    sp.sendMessage(Text.literal("§cTato Elytra je střežena! Musíš nejprve porazit jejího strážce."), true);
                    return ActionResult.FAIL;
                }
                if (sp.isCreative() || sp.isSpectator()) return ActionResult.PASS;
                double dx = sp.getX() - entity.getX();
                double dy = sp.getY() - entity.getY();
                double dz = sp.getZ() - entity.getZ();
                double distSq = dx*dx + dy*dy + dz*dz;
                double limit = 5.5; // Mírná rezerva (normálně 4.5-5.0)
                if (distSq > limit * limit) {
                    LOGGER.warn("Reach Check: Hráč " + sp.getName().getString() + " zasáhl entitu z dálky " + Math.sqrt(distSq));
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });

        // Přihlášení/odhlášení události
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity joinedPlayer = handler.player;
            UUID id = joinedPlayer.getUuid();
            
            // Ban check on join
            Long banEnd = BANNED_PLAYERS.get(id.toString());
            if (banEnd != null) {
                if (System.currentTimeMillis() < banEnd) {
                    String reason = BAN_REASONS.getOrDefault(id.toString(), "Bez důvodu");
                    String remaining = formatDuration(banEnd - System.currentTimeMillis());
                    server.execute(() -> {
                        ServerPlayerEntity bp = server.getPlayerManager().getPlayer(id);
                        if (bp != null) bp.networkHandler.disconnect(Text.literal("§cJsi zabanován! Zbývá: " + remaining + "\n§7Důvod: " + reason));
                    });
                    return;
                } else {
                    BANNED_PLAYERS.remove(id.toString());
                    BAN_REASONS.remove(id.toString());
                    saveData();
                }
            }

            updatePlayerStats(joinedPlayer);
            if (oracleState.votingActive) giveVoteTicket(joinedPlayer);
            LAST_POS.put(id, new Vec3d(joinedPlayer.getX(), joinedPlayer.getY(), joinedPlayer.getZ()));
            IN_AIR_TICKS.put(id, 0);
            JOIN_TIMES.put(id, System.currentTimeMillis());
            LOGGED_IN.remove(id); // Vždy vyžadovat přihlášení/registraci po připojení

            // Save original position BEFORE teleporting to spawn so admin can return players if needed.
            TeleportTarget originalPos = new TeleportTarget(
                (ServerWorld) joinedPlayer.getEntityWorld(),
                new Vec3d(joinedPlayer.getX(), joinedPlayer.getY(), joinedPlayer.getZ()),
                joinedPlayer.getVelocity(), joinedPlayer.getYaw(), joinedPlayer.getPitch(),
                TeleportTarget.NO_OP
            );
            RETURN_POS.put(id, originalPos);
            LOGIN_POS.put(id, originalPos);

            // Queue spawn teleport and retry for a short window so login freeze and respawn timing cannot override it.
            if (oracleState.spawnSet) {
                scheduleSpawnTeleport(id);
            }

            // Apply pending oracle penalties
            if (oracleState.pendingHealthChanges.containsKey(id.toString())) {
                server.execute(() -> {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p != null) {
                        updateMaxHealth(p, (double) oracleState.pendingHealthChanges.remove(id.toString()));
                        saveData();
                    }
                });
            }

            if (PASSWORDS.containsKey(id)) {
                joinedPlayer.sendMessage(Text.literal("§ePřihlas se: §f/login <heslo>"), false);
            } else {
                joinedPlayer.sendMessage(Text.literal("§eNastav si heslo: §f/register <heslo>"), false);
            }

            if (oracleState.cannonAccess.containsKey(id.toString())) {
                grantCannonAccess(server, joinedPlayer);
            }
        });

        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            EntityAttributeInstance oldInstance = oldPlayer.getAttributeInstance(EntityAttributes.MAX_HEALTH);
            EntityAttributeInstance newInstance = newPlayer.getAttributeInstance(EntityAttributes.MAX_HEALTH);
            if (oldInstance != null && newInstance != null) {
                EntityAttributeModifier modifier = oldInstance.getModifier(HEALTH_MODIFIER_ID);
                if (modifier != null) {
                    newInstance.removeModifier(HEALTH_MODIFIER_ID);
                    newInstance.addPersistentModifier(new EntityAttributeModifier(
                            HEALTH_MODIFIER_ID,
                            modifier.value(),
                            modifier.operation()
                    ));
                }
            }

            if (!alive && oracleState.spawnSet) {
                scheduleSpawnTeleport(newPlayer.getUuid());
            }

            updatePlayerStats(newPlayer);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUuid();
            if (COMBAT_TAGS.remove(uuid) != null) {
                try {
                    UUID attackerUuid = COMBAT_LAST_ATTACKER.remove(uuid);
                    ServerPlayerEntity attacker = attackerUuid != null ? server.getPlayerManager().getPlayer(attackerUuid) : null;

                    if (attacker != null && !attacker.getUuid().equals(uuid)) {
                        updateMaxHealth(attacker, 2.0);
                        updatePlayerStats(attacker);

                        int stolen = oracleState.totalHeartsStolen.getOrDefault(attacker.getUuid().toString(), 0) + 1;
                        oracleState.totalHeartsStolen.put(attacker.getUuid().toString(), stolen);
                        if (stolen == 1) {
                            grantAchievement(attacker, "STEAL_HEART");
                        }
                        saveData();

                        attacker.sendMessage(Text.literal("§a" + handler.player.getName().getString() + " se odpojil v boji. Získal jsi 1 srdce."), false);
                        server.getPlayerManager().broadcast(Text.literal("§c" + handler.player.getName().getString() + " se odpojil v boji a zemřel. §a" + attacker.getName().getString() + " získal 1 srdce."), false);
                    } else {
                        server.getPlayerManager().broadcast(Text.literal("§c" + handler.player.getName().getString() + " se odpojil v boji a zemřel."), false);
                    }

                    ServerWorld world = (ServerWorld) handler.player.getEntityWorld();
                    handler.player.damage(world, world.getDamageSources().outOfWorld(), Float.MAX_VALUE);
                } catch (Exception e) {
                    LOGGER.error("Chyba při combat-log postihu hráče {}", handler.player.getName().getString(), e);
                }
            }
            COMBAT_LAST_ATTACKER.remove(uuid);
            removeCombatBossBar(uuid);

            UUID linked = SHARED_FATE_LINKS.remove(uuid);
            if (linked != null) {
                SHARED_FATE_LINKS.remove(linked);
            }

            OPEN_SHOPS.remove(uuid);
            OPEN_MENUS.remove(uuid);
            LAST_POS.remove(uuid);
            IN_AIR_TICKS.remove(uuid);
            LOGIN_POS.remove(uuid);
            RETURN_POS.remove(uuid);
            PENDING_SPAWN_TELEPORTS.remove(uuid);
            LOGGED_IN.remove(uuid);
            LAYING_PLAYERS.remove(uuid);
            CRAWLING_PLAYERS.remove(uuid);
            JOIN_TIMES.remove(uuid);
            FROZEN_PLAYERS.remove(uuid);
            VANISHED_PLAYERS.remove(uuid);
            SURVIVAL_TIMER.remove(uuid);

                // Cleanup seat entities when player disconnects while sitting.
                ServerWorld playerWorld = (ServerWorld) handler.player.getEntityWorld();
                if (playerWorld != null) {
                Box chairSearch = new Box(
                    handler.player.getX() - 2.0,
                    handler.player.getY() - 3.0,
                    handler.player.getZ() - 2.0,
                    handler.player.getX() + 2.0,
                    handler.player.getY() + 1.0,
                    handler.player.getZ() + 2.0
                );
                playerWorld.getEntitiesByClass(ArmorStandEntity.class, chairSearch,
                        e -> e.getCustomName() != null && "ls_chair".equals(e.getCustomName().getString()))
                    .forEach(ArmorStandEntity::discard);
                }
        });

        // Mute enforcement: block chat messages from muted players
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (sender instanceof ServerPlayerEntity sp) {
                Long muteEnd = MUTED_PLAYERS.get(sp.getUuid());
                if (muteEnd != null) {
                    if (System.currentTimeMillis() < muteEnd) {
                        sp.sendMessage(Text.literal("§cJsi umlčen! Zbývá: " + formatDuration(muteEnd - System.currentTimeMillis())), false);
                        return false;
                    } else {
                        MUTED_PLAYERS.remove(sp.getUuid());
                        saveData();
                    }
                }
            }
            return true;
        });

        // PVP lifesteal a odemčení Elytry po bosovi
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity victim) {
                COMBAT_TAGS.remove(victim.getUuid());
                COMBAT_LAST_ATTACKER.remove(victim.getUuid());
                removeCombatBossBar(victim.getUuid());
                boolean protectedFromHeartLoss = false;
                // Check Soul Anchor protection
                for (int i = 0; i < victim.getInventory().size(); i++) {
                    ItemStack stack = victim.getInventory().getStack(i);
                    if (stack.isOf(Items.TOTEM_OF_UNDYING)) {
                        NbtComponent nbt = stack.get(DataComponentTypes.CUSTOM_DATA);
                        if (nbt != null && nbt.copyNbt().contains("lifesteal:soul_anchor")) {
                            stack.decrement(1);
                            protectedFromHeartLoss = true;
                            victim.sendMessage(Text.literal("§b§lRevival Heart tě ochránilo před ztrátou srdce!"), false);
                            break;
                        }
                    }
                }

                if (damageSource.getAttacker() instanceof ServerPlayerEntity killer) {
                    // Shared Fate Link Break
                    if (SHARED_FATE_LINKS.containsKey(victim.getUuid())) {
                        UUID partnerUuid = SHARED_FATE_LINKS.remove(victim.getUuid());
                        SHARED_FATE_LINKS.remove(partnerUuid);
                    }

                    // Skull Hunter (53) - Bounty Logic
                    if (oracleState.currentActiveEffect == 53 && victim.getUuid().equals(oracleState.targetPlayerUuid)) {
                        killer.sendMessage(Text.literal("§aZabil jsi cíl odměny! Získáváš 10x Deepslate Diamond Ore."), false);
                        ItemStack reward = new ItemStack(Items.DEEPSLATE_DIAMOND_ORE, 10);
                        if (!killer.getInventory().insertStack(reward)) killer.dropItem(reward, false);
                        oracleState.targetPlayerUuid = null;
                        
                        int bCount = oracleState.totalBountiesCompleted.getOrDefault(killer.getUuid().toString(), 0) + 1;
                        oracleState.totalBountiesCompleted.put(killer.getUuid().toString(), bCount);
                        if (bCount >= 10) grantAchievement(killer, "COMPLETE_10_BOUNTIES");
                        
                        saveData();
                    }
                    
                    double lifestealAmount = 2.0;
                    if (!protectedFromHeartLoss) {
                        updateMaxHealth(killer, lifestealAmount);
                        updateMaxHealth(victim, -lifestealAmount);
                        
                        int stolen = oracleState.totalHeartsStolen.getOrDefault(killer.getUuid().toString(), 0) + 1;
                        oracleState.totalHeartsStolen.put(killer.getUuid().toString(), stolen);
                        if (stolen == 1) grantAchievement(killer, "STEAL_HEART");
                    }
                } else {
                    if (!protectedFromHeartLoss) {
                        updateMaxHealth(victim, -2.0);
                    }
                }
                updatePlayerStats(victim);

                // Drop Head
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                NbtCompound headNbt = new NbtCompound();
                headNbt.putString("SkullOwner", victim.getNameForScoreboard());
                head.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(headNbt));
                victim.dropItem(head, true);
            } else if (entity instanceof net.minecraft.entity.mob.HostileEntity boss && entity.getCustomName() != null && 
                    (entity.getCustomName().getString().equals(ELYTRA_BOSS_NAME) || 
                     entity.getCustomName().getString().equals(BOSS_STRAY_NAME) || 
                     entity.getCustomName().getString().equals(BOSS_WITHER_NAME))) {
                
                BOSS_ATTACK_TIMER.remove(boss.getUuid());
                
                if (damageSource.getAttacker() instanceof ServerPlayerEntity killer) {
                    boolean solo = killer.getEntityWorld().getPlayers().stream()
                        .filter(p -> p != killer && p.squaredDistanceTo(killer) < 20 * 20)
                        .findAny().isEmpty();
                    if (solo) grantAchievement(killer, "KILL_BOSS_SOLO");
                }

                // Fix: Get frame key from entity tag instead of static map
                String frameKey = boss.getCommandTags().stream()
                    .filter(t -> t.startsWith("ls_frame:"))
                    .map(t -> t.substring(9))
                    .findFirst().orElse(null);
                
                if (frameKey != null) {
                    oracleState.unlockedFrames.add(frameKey);
                    saveData();
                    boss.getEntityWorld().getPlayers().stream()
                        .filter(p -> p.squaredDistanceTo(boss.getX(), boss.getY(), boss.getZ()) < 100*100)
                        .forEach(p -> p.sendMessage(Text.literal("§aStrážce padl! Elytra na lodi je nyní volná."), false));
                }
            }

            // Explosive Death (26)
            if (oracleState.currentActiveEffect == 26) {
                ServerWorld world = (ServerWorld) entity.getEntityWorld();
                if (entity instanceof CreeperEntity creeper) {
                    world.createExplosion(creeper, creeper.getX(), creeper.getY(), creeper.getZ(), 6.0f, World.ExplosionSourceType.MOB);
                } else if (!(entity instanceof ServerPlayerEntity)) {
                    world.spawnParticles(ParticleTypes.FIREWORK, entity.getX(), entity.getY(), entity.getZ(), 10, 0.5, 0.5, 0.5, 0.1);
                    world.createExplosion(null, entity.getX(), entity.getY(), entity.getZ(), 1.0f, World.ExplosionSourceType.NONE);
                }
            }
            
            // Gold Rush (13)
            if (oracleState.currentActiveEffect == 13 && !(entity instanceof ServerPlayerEntity)) {
                ItemStack gold = new ItemStack(Items.GOLD_NUGGET, entity.getEntityWorld().random.nextInt(3) + 1);
                ItemEntity itemEntity = new ItemEntity(entity.getEntityWorld(), entity.getX(), entity.getY(), entity.getZ(), gold);
                entity.getEntityWorld().spawnEntity(itemEntity);
            }

            // Random Drop (45)
            if (oracleState.currentActiveEffect == 45 && !(entity instanceof ServerPlayerEntity)) {
                Item[] randomDrops = {Items.BONE, Items.STRING, Items.GUNPOWDER, Items.IRON_INGOT, Items.REDSTONE, Items.ARROW, Items.BREAD};
                ItemStack random = new ItemStack(randomDrops[entity.getEntityWorld().random.nextInt(randomDrops.length)], entity.getEntityWorld().random.nextInt(2) + 1);
                ItemEntity randomEntity = new ItemEntity(entity.getEntityWorld(), entity.getX(), entity.getY(), entity.getZ(), random);
                entity.getEntityWorld().spawnEntity(randomEntity);
            }
        });

        // Zablokování interakce s Item Frame v Endu (Elytra protection)
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && world.getRegistryKey().equals(World.END)) {
                if (entity instanceof ItemFrameEntity frame && frame.getHeldItemStack().isOf(Items.ELYTRA)) {
                    if (isInsideEndShip(world, frame.getBlockPos())) {
                        player.sendMessage(Text.literal("§cTato Elytra je střežena! Musíš nejprve porazit jejího strážce."), true);
                        return ActionResult.FAIL;
                    }
                }
            }
            return ActionResult.PASS;
        });

        // Detekce End Ship a vyvolání bosse Husk 2.0 + login freeze + Voting
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            long gameTime = server.getOverworld().getTime();
            boolean isSecond = gameTime % 20 == 0;

            processPendingSpawnTeleports(server);

            // Handle weekend voting logic
            if (isSecond) {
                handleOracleScheduling(server);
                // Combat Tag cleanup
                long now = System.currentTimeMillis();
                cleanupExpiredCombatTags(now);

                if (cleanupExpiredBans()) {
                    saveData();
                }

                cleanupBossAttackTimers(server);
            }

            // Apply active effects
            if (oracleState.currentActiveEffect > 0) {
                if (oracleState.effectEndTime == 0 || System.currentTimeMillis() <= oracleState.effectEndTime) {
                    applyActiveEffectLogic(server, isSecond);
                }
            }

            // Eternal Night (5) / Eternal Day (6)
            if (oracleState.currentActiveEffect == 5) {
                server.getWorlds().forEach(world -> {
                    if (!world.isNight()) world.setTimeOfDay(14000);
                });
            } else if (oracleState.currentActiveEffect == 6) {
                server.getWorlds().forEach(world -> {
                    if (world.isNight()) world.setTimeOfDay(1000);
                });
            }

            // Rapid Growth (11)
            if (oracleState.currentActiveEffect == 11 && isSecond) {
                server.getWorlds().forEach(world -> {
                    world.getPlayers().forEach(player -> {
                        BlockPos ppos = player.getBlockPos();
                        for (BlockPos bpos : BlockPos.iterate(ppos.add(-5, -5, -5), ppos.add(5, 5, 5))) {
                            net.minecraft.block.BlockState state = world.getBlockState(bpos);
                            if (state.getBlock() instanceof net.minecraft.block.Fertilizable f) {
                                if (f.isFertilizable(world, bpos, state)) {
                                    f.grow(world, world.random, bpos, state);
                                }
                            }
                        }
                    });
                });
            }

            // Emerald Rain (17)
            if (oracleState.currentActiveEffect == 17 && isSecond && server.getOverworld().isRaining()) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                   if (server.getOverworld().isSkyVisible(player.getBlockPos()) && server.getOverworld().random.nextFloat() < 0.05) {
                       ItemEntity emerald = new ItemEntity(server.getOverworld(), player.getX(), player.getY() + 5, player.getZ(), new ItemStack(Items.EMERALD));
                       server.getOverworld().spawnEntity(emerald);
                   }
                });
            }

            // Hordes (28)
            if (oracleState.currentActiveEffect == 28 && isSecond) {
                server.getWorlds().forEach(world -> {
                    world.getPlayers().forEach(player -> {
                        if (world.random.nextFloat() < 0.01) {
                            for (int i = 0; i < 5; i++) {
                                EntityType.ZOMBIE.spawn(world, player.getBlockPos().add(world.random.nextInt(10)-5, 0, world.random.nextInt(10)-5), net.minecraft.entity.SpawnReason.EVENT);
                            }
                        }
                    });
                });
            }

            // Chicken Rain (56)
            if (oracleState.currentActiveEffect == 56 && isSecond) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    ServerWorld sw2 = (ServerWorld) player.getCommandSource().getWorld();
                    if (sw2.random.nextFloat() < 0.03 && sw2.isSkyVisible(player.getBlockPos())) {
                        net.minecraft.entity.passive.ChickenEntity chicken = EntityType.CHICKEN.create(sw2, net.minecraft.entity.SpawnReason.EVENT);
                        if (chicken != null) {
                            chicken.refreshPositionAndAngles(player.getX() + sw2.random.nextInt(10) - 5, player.getY() + 15, player.getZ() + sw2.random.nextInt(10) - 5, 0, 0);
                            sw2.spawnEntity(chicken);
                        }
                    }
                });
            }

            // Giant Invasion (21)
            if (oracleState.currentActiveEffect == 21 && isSecond) {
                server.getWorlds().forEach(world -> {
                    world.getPlayers().forEach(player -> {
                        if (world.random.nextFloat() < 0.008f) {
                            for (int i = 0; i < 2; i++) {
                                var zombie = EntityType.ZOMBIE.create(world, net.minecraft.entity.SpawnReason.EVENT);
                                if (zombie != null) {
                                    zombie.refreshPositionAndAngles(player.getX() + world.random.nextInt(20) - 10, player.getY(), player.getZ() + world.random.nextInt(20) - 10, 0.0f, 0.0f);
                                    EntityAttributeInstance scale = zombie.getAttributeInstance(EntityAttributes.SCALE);
                                    if (scale != null) scale.setBaseValue(1.5);
                                    zombie.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 30, 0, false, false));
                                    world.spawnEntity(zombie);
                                }
                            }
                        }
                    });
                });
            }

            // Zombie Apocalypse (78) - zombie spawns at night
            if (oracleState.currentActiveEffect == 78 && isSecond) {
                server.getWorlds().forEach(world -> {
                    if (world.isNight()) {
                        world.getPlayers().forEach(player -> {
                            if (world.random.nextFloat() < 0.02) {
                                for (int i = 0; i < 3; i++) {
                                    EntityType.ZOMBIE.spawn(world, player.getBlockPos().add(world.random.nextInt(16) - 8, 0, world.random.nextInt(16) - 8), net.minecraft.entity.SpawnReason.EVENT);
                                }
                            }
                        });
                    }
                });
            }

            // King of the Hill (51)
            if (oracleState.currentActiveEffect == 51 && isSecond && oracleState.spawnSet) {
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    if (!player.getEntityWorld().getRegistryKey().getValue().toString().equals(oracleState.spawnDimension)) return;
                    double dx = player.getX() - oracleState.spawnX;
                    double dz = player.getZ() - oracleState.spawnZ;
                    if ((dx * dx + dz * dz) <= 10 * 10) {
                        player.addExperience(3);
                        player.sendMessage(Text.literal("§eKing of the Hill: +3 XP za držení kopce."), true);
                    }
                });
            }

            long tickNow = System.currentTimeMillis();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                ServerWorld sw = (ServerWorld) p.getCommandSource().getWorld();
                updateCombatBossBar(p, tickNow);

                if (oracleState.cannonAccess.containsKey(p.getUuid().toString())) {
                    enforceCannonInventory(p);
                }

                // Achievement: Survive with 1 Heart
                double maxHp = p.getMaxHealth();
                if (maxHp <= 2.0 && p.getHealth() > 0) {
                    long startTime = SURVIVAL_TIMER.computeIfAbsent(p.getUuid(), id -> System.currentTimeMillis());
                    if (System.currentTimeMillis() - startTime > 600000) { // 10 minutes
                        grantAchievement(p, "SURVIVE_1_HEART");
                    }
                } else {
                    SURVIVAL_TIMER.remove(p.getUuid());
                }

                // Admin freeze: zamrazení hráče adminem
                if (FROZEN_PLAYERS.contains(p.getUuid())) {
                    p.setVelocity(0, 0, 0);
                    Vec3d frozenPos = LAST_POS.getOrDefault(p.getUuid(), new Vec3d(p.getX(), p.getY(), p.getZ()));
                    if (p.squaredDistanceTo(frozenPos) > 0.1) {
                        p.requestTeleport(frozenPos.x, frozenPos.y, frozenPos.z);
                    }
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 255, false, false));
                    if (isSecond) {
                        p.sendMessage(Text.literal("§c§lJsi zamrazen adminem!"), true);
                    }
                }

                // Login freeze: nepřihlášený hráč se nemůže hýbat/užívat věci
                if (!LOGGED_IN.contains(p.getUuid())) {
                    long joinTime = JOIN_TIMES.getOrDefault(p.getUuid(), System.currentTimeMillis());
                    if (System.currentTimeMillis() - joinTime > 60000) {
                        p.networkHandler.disconnect(Text.literal("§cČas na přihlášení vypršel."));
                        continue;
                    }
                    TeleportTarget targetData = LOGIN_POS.computeIfAbsent(p.getUuid(), id -> new TeleportTarget((ServerWorld) p.getEntityWorld(), new Vec3d(p.getX(), p.getY(), p.getZ()), p.getVelocity(), p.getYaw(), p.getPitch(), TeleportTarget.NO_OP));
                    Vec3d targetPos = targetData.position();
                    if (targetPos == null) {
                        targetPos = new Vec3d(p.getX(), p.getY(), p.getZ());
                        LOGIN_POS.put(p.getUuid(), new TeleportTarget((ServerWorld) p.getEntityWorld(), targetPos, p.getVelocity(), p.getYaw(), p.getPitch(), TeleportTarget.NO_OP));
                    }
                    p.setVelocity(0, 0, 0);
                    if (p.squaredDistanceTo(targetPos) > 0.1 || p.getVelocity().lengthSquared() > 0.01) {
                        p.requestTeleport(targetPos.x, targetPos.y, targetPos.z);
                    }
                    
                    // Efekty pro nepřihlášené
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 255, false, false));
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 40, 0, false, false));
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false)); // Jistota tmy
                    
                    if (isSecond) {
                        // Zpráva do Action Baru (nad hotbar) aby nespamovala chat
                        p.sendMessage(Text.literal("§6§lLifeSteal SMP §8| §ePoužij §f/login <heslo> §enebo §f/register"), true);
                    }
                    continue;
                }
                
                // Cleanup sedátek a Anticheat
                if (isSecond) {
                    // Smazat prázdná sedátka v okolí hráče
                    net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(p.getX()-10, p.getY()-10, p.getZ()-10, p.getX()+10, p.getY()+10, p.getZ()+10);
                    sw.getEntitiesByClass(ArmorStandEntity.class, searchBox, e -> e.getCustomName() != null && "ls_chair".equals(e.getCustomName().getString()))
                        .forEach(chair -> {
                            if (!chair.hasPassengers()) chair.discard();
                        });
                        
                    // Cleanup temp cage block displays
                    sw.getEntitiesByClass(DisplayEntity.BlockDisplayEntity.class, searchBox.expand(10), e -> e.getCommandTags().contains("ls_temp_cage"))
                        .forEach(e -> {
                            if (e.age > 60) e.discard();
                        });
                }

                // Pose enforcement (každý tick)
                if (p.isGliding()) {
                    if (LAYING_PLAYERS.remove(p.getUuid())) {
                        p.clearSleepingPosition();
                    }
                    CRAWLING_PLAYERS.remove(p.getUuid());
                }
                if (LAYING_PLAYERS.contains(p.getUuid())) {
                    p.setPose(EntityPose.SLEEPING);
                    p.setSleepingPosition(p.getBlockPos());
                } else if (CRAWLING_PLAYERS.contains(p.getUuid())) {
                    p.setPose(EntityPose.SWIMMING);
                }

                // Fly / Speed Anticheat + Pose cancellation
                if (!p.isCreative() && !p.isSpectator() && !p.hasVehicle()) {
                    Vec3d lastPos = LAST_POS.get(p.getUuid());
                    if (lastPos != null) {
                        double dx = p.getX() - lastPos.x;
                        double dy = p.getY() - lastPos.y;
                        double dz = p.getZ() - lastPos.z;
                        double dist2D = Math.sqrt(dx*dx + dz*dz);

                        // Pose cancellation on move/shift
                        if (p.isSneaking() || dist2D > 0.01 || (Math.abs(dy) > 0.05 && !p.hasVehicle())) { 
                            if (LAYING_PLAYERS.contains(p.getUuid()) && (dist2D > 0.1 || Math.abs(dy) > 0.15 || p.isSneaking())) {
                                LAYING_PLAYERS.remove(p.getUuid());
                                p.setPose(EntityPose.STANDING);
                                p.clearSleepingPosition();
                            }
                            if (CRAWLING_PLAYERS.contains(p.getUuid()) && p.isSneaking()) {
                                CRAWLING_PLAYERS.remove(p.getUuid());
                                p.setPose(EntityPose.STANDING);
                            }
                        }

                        // Fly Check (každý tick, ale logika počítá tick po ticku)
                        if (dy >= -0.08 && !p.isOnGround() && sw.isAir(p.getBlockPos().down()) && (!p.isSwimming() || CRAWLING_PLAYERS.contains(p.getUuid())) && !p.hasStatusEffect(StatusEffects.SLOW_FALLING) && !p.hasStatusEffect(StatusEffects.LEVITATION)) {
                            int ticks = IN_AIR_TICKS.getOrDefault(p.getUuid(), 0) + 1;
                            IN_AIR_TICKS.put(p.getUuid(), ticks);
                            if (ticks > 120) { // 6 sekund ve vzduchu
                                LOGGER.warn("Fly Check: Hráč " + p.getName().getString() + " možná létá!");
                                IN_AIR_TICKS.put(p.getUuid(), 0);
                            }
                        } else {
                            IN_AIR_TICKS.put(p.getUuid(), 0);
                        }
                    }
                    LAST_POS.put(p.getUuid(), new Vec3d(p.getX(), p.getY(), p.getZ()));
                }

                if (!isSecond) continue;
                if (!sw.getRegistryKey().equals(World.END)) continue;
                
                // Zbytek logiky Endu (jednou za sekundu)
                net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(p.getX()-40, p.getY()-40, p.getZ()-40, p.getX()+40, p.getY()+40, p.getZ()+40);
                java.util.List<ItemFrameEntity> frames = sw.getEntitiesByClass(ItemFrameEntity.class, box, f -> f.getHeldItemStack().isOf(Items.ELYTRA));
                if (!frames.isEmpty()) {
                    ItemFrameEntity frame = frames.get(0);
                    String key = sw.getRegistryKey().getValue().toString()+"|"+frame.getBlockPos().toShortString();
                    if (!oracleState.triggeredShips.contains(key)) {
                        oracleState.triggeredShips.add(key);
                        saveData();
                        // Bod 9: Spawn pod lodí
                        BlockPos spawnPos = frame.getBlockPos().down(20);
                        triggerHuskBoss(sw, spawnPos, p, key);
                    }
                }

                // Přitažení hráče k bossovi pokud je dále než 20 bloků
                net.minecraft.util.math.Box bossSearch = new net.minecraft.util.math.Box(p.getX()-64, p.getY()-64, p.getZ()-64, p.getX()+64, p.getY()+64, p.getZ()+64);
                java.util.List<HostileEntity> bosses = sw.getEntitiesByClass(HostileEntity.class, bossSearch,
                        b -> b.isAlive() && b.getCustomName() != null && (ELYTRA_BOSS_NAME.equals(b.getCustomName().getString()) || BOSS_STRAY_NAME.equals(b.getCustomName().getString()) || BOSS_WITHER_NAME.equals(b.getCustomName().getString())));
                for (HostileEntity boss : bosses) {
                    // Správa útoků bosse
                    int timer = BOSS_ATTACK_TIMER.getOrDefault(boss.getUuid(), 0) + 20;
                    BOSS_ATTACK_TIMER.put(boss.getUuid(), timer);
                    if (timer >= 100) { // Každých 5 sekund útok
                        BOSS_ATTACK_TIMER.put(boss.getUuid(), 0);
                        performBossAttack(sw, boss, p);
                    }

                    double dx = boss.getX() - p.getX();
                    double dy = boss.getY() - p.getY();
                    double dz = boss.getZ() - p.getZ();
                    double distSq = dx*dx + dy*dy + dz*dz;
                    if (distSq > 20*20 && distSq < 80*80) {
                        double dist = Math.sqrt(distSq);
                        double pull = 0.15; // síla přitažení
                        double vx = (dx / dist) * pull;
                        double vz = (dz / dist) * pull;
                        double vy = Math.max(-0.05, Math.min(0.08, (dy / dist) * pull));
                        net.minecraft.util.math.Vec3d newVel = p.getVelocity().add(vx, vy, vz);
                        p.setVelocity(newVel);
                    }
                }
            }
        });
    }

    public static void openShop(ServerPlayerEntity player) {
        SimpleInventory inventory = new SimpleInventory(27);
        
        ItemStack kitItem = new ItemStack(Items.CHEST);
        kitItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§6Kit"));
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7Obsahuje užitečné věci:"));
        lore.add(Text.literal("§8- Dia vybavení (Sharp 1, Prot 3)"));
        lore.add(Text.literal("§8- Ender perly, Totem, Jídlo"));
        lore.add(Text.literal("§8- Potiony, Cobweb, Buckety"));
        lore.add(Text.literal("§eCena: 16x Deepslate Diamond Ore"));
        kitItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
        
        inventory.setStack(10, kitItem);

        ItemStack dirtItem = new ItemStack(Items.DIRT, 64);
        dirtItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§6Dirt (64x)"));
        List<Text> dirtLore = new ArrayList<>();
        dirtLore.add(Text.literal("§eCena: 1x Deepslate Diamond Ore"));
        dirtItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(dirtLore));
        inventory.setStack(12, dirtItem);

        ItemStack cobbleItem = new ItemStack(Items.COBBLESTONE, 64);
        cobbleItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§6Cobblestone (64x)"));
        List<Text> cobbleLore = new ArrayList<>();
        cobbleLore.add(Text.literal("§eCena: 1x Deepslate Diamond Ore"));
        cobbleItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(cobbleLore));
        inventory.setStack(14, cobbleItem);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) -> {
            return net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, inventory);
        }, Text.literal("Obchod")));

        // MUST be after openHandledScreen — otherwise onClosed of the previous screen removes it
        OPEN_SHOPS.put(player.getUuid(), inventory);
    }

    // ===== MENU SYSTEM =====
    public static void openMainMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(27);

        // Slot 11 - My Stats
        ItemStack statsItem = new ItemStack(Items.BOOK);
        statsItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§b§lMoje Statistiky"));
        List<Text> statsLore = new ArrayList<>();
        statsLore.add(Text.literal("§7Klikni pro zobrazení tvých statistik."));
        statsItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(statsLore));
        inv.setStack(11, statsItem);

        // Slot 13 - Crafts
        ItemStack craftsItem = new ItemStack(Items.CRAFTING_TABLE);
        craftsItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§e§lCrafty"));
        List<Text> craftsLore = new ArrayList<>();
        craftsLore.add(Text.literal("§7Klikni pro zobrazení speciálních craftů."));
        craftsItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(craftsLore));
        inv.setStack(13, craftsItem);

        // Slot 15 - Achievements
        ItemStack achItem = new ItemStack(Items.GOLDEN_APPLE);
        achItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lÚspěchy"));
        List<Text> achLore = new ArrayList<>();
        achLore.add(Text.literal("§7Klikni pro zobrazení tvých úspěchů."));
        achItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(achLore));
        inv.setStack(15, achItem);

        // Slot 17 - Voting
        ItemStack voteItem = new ItemStack(Items.WRITABLE_BOOK);
        voteItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§d§lHlasování Oracle"));
        List<Text> voteLore = new ArrayList<>();
        voteLore.add(Text.literal("§7Klikni pro zobrazení hlasování."));
        voteItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(voteLore));
        inv.setStack(17, voteItem);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) ->
            net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, inv),
            Text.literal("§8Menu")));
        OPEN_MENUS.put(player.getUuid(), "main");
    }

    public static void openStatsMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(27);
        MinecraftServer server = player.getCommandSource().getServer();
        Scoreboard scoreboard = server.getScoreboard();

        int hearts = (int) (player.getMaxHealth() / 2);
        ScoreboardObjective killsObj = getKillsObjective(server);
        ScoreboardObjective deathsObj = getDeathsObjective(server);
        int kills = scoreboard.getOrCreateScore(player, killsObj).getScore();
        int deaths = scoreboard.getOrCreateScore(player, deathsObj).getScore();
        double kd = deaths == 0 ? kills : (double) kills / (double) deaths;
        String kdStr = String.format(Locale.US, "%.2f", kd);

        // Slot 10 - Hearts
        ItemStack heartItem = new ItemStack(Items.RED_DYE, Math.max(1, hearts));
        heartItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§c❤ Srdíčka: " + hearts));
        List<Text> heartLore = new ArrayList<>();
        heartLore.add(Text.literal("§7Tvé aktuální maximální zdraví."));
        heartLore.add(Text.literal("§7Max povoleno: §f25 srdíček"));
        heartItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(heartLore));
        inv.setStack(10, heartItem);

        // Slot 12 - K/D
        ItemStack kdItem = new ItemStack(Items.DIAMOND_SWORD);
        kdItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§e⚔ K/D Ratio: " + kdStr));
        List<Text> kdLore = new ArrayList<>();
        kdLore.add(Text.literal("§7Zabití: §f" + kills));
        kdLore.add(Text.literal("§7Úmrtí: §f" + deaths));
        kdItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(kdLore));
        inv.setStack(12, kdItem);

        // Slot 14 - Achievements progress
        Set<String> playerAch = oracleState.playerAchievements.getOrDefault(player.getUuid().toString(), new HashSet<>());
        ItemStack achItem = new ItemStack(Items.EMERALD, Math.max(1, playerAch.size()));
        achItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§a✦ Úspěchy: " + playerAch.size() + "/" + ACHIEVEMENTS.size()));
        inv.setStack(14, achItem);

        // Slot 16 - Bounties
        int bounties = oracleState.totalBountiesCompleted.getOrDefault(player.getUuid().toString(), 0);
        ItemStack bountyItem = new ItemStack(Items.SKELETON_SKULL, Math.max(1, bounties));
        bountyItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§d☠ Bounty dokončeno: " + bounties));
        inv.setStack(16, bountyItem);

        // Slot 22 - Back
        ItemStack backItem = new ItemStack(Items.ARROW);
        backItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§7← Zpět do Menu"));
        inv.setStack(22, backItem);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) ->
            net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, inv),
            Text.literal("§8Moje Statistiky")));
        OPEN_MENUS.put(player.getUuid(), "stats");
    }

    public static void openCraftsMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54); // 6 rows for all crafts

        // Heart
        ItemStack heartItem = new ItemStack(Items.NETHER_STAR);
        heartItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§c§lHeart"));
        heartItem.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> heartLore = new ArrayList<>();
        heartLore.add(Text.literal("§7Přidá +1 srdce k maximálnímu zdraví."));
        heartLore.add(Text.literal(""));
        heartLore.add(Text.literal("§eRecept (Crafting Table):"));
        heartLore.add(Text.literal("§f N T N"));
        heartLore.add(Text.literal("§f T B T"));
        heartLore.add(Text.literal("§f N T N"));
        heartLore.add(Text.literal(""));
        heartLore.add(Text.literal("§7N = §fNetherite Block"));
        heartLore.add(Text.literal("§7T = §fTotem of Undying"));
        heartLore.add(Text.literal("§7B = §fBeacon"));
        heartItem.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(heartLore));
        inv.setStack(10, heartItem);

        // Golden Head
        ItemStack goldenHead = new ItemStack(Items.PLAYER_HEAD);
        goldenHead.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lGolden Head"));
        goldenHead.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> ghLore = new ArrayList<>();
        ghLore.add(Text.literal("§7Regen II, Absorption II, Speed II."));
        ghLore.add(Text.literal(""));
        ghLore.add(Text.literal("§eRecept (Crafting Table):"));
        ghLore.add(Text.literal("§f G G G"));
        ghLore.add(Text.literal("§f G P G"));
        ghLore.add(Text.literal("§f G G G"));
        ghLore.add(Text.literal(""));
        ghLore.add(Text.literal("§7G = §fGold Block"));
        ghLore.add(Text.literal("§7P = §fPlayer Head"));
        goldenHead.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(ghLore));
        inv.setStack(12, goldenHead);

        // Revival Heart
        ItemStack revival = new ItemStack(Items.TOTEM_OF_UNDYING);
        revival.set(DataComponentTypes.ITEM_NAME, Text.literal("§b§lRevival Heart"));
        revival.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> revLore = new ArrayList<>();
        revLore.add(Text.literal("§7Speciální totem."));
        revLore.add(Text.literal(""));
        revLore.add(Text.literal("§eRecept (Crafting Table):"));
        revLore.add(Text.literal("§f N I N"));
        revLore.add(Text.literal("§f I T I"));
        revLore.add(Text.literal("§f N I N"));
        revLore.add(Text.literal(""));
        revLore.add(Text.literal("§7N = §fNetherite Ingot"));
        revLore.add(Text.literal("§7I = §fIron Block"));
        revLore.add(Text.literal("§7T = §fTotem of Undying"));
        revival.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(revLore));
        inv.setStack(14, revival);

        // Adrenaline Shot
        ItemStack adrenaline = new ItemStack(Items.POTION);
        adrenaline.set(DataComponentTypes.ITEM_NAME, Text.literal("§c§lAdrenaline Shot"));
        adrenaline.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> adrLore = new ArrayList<>();
        adrLore.add(Text.literal("§7Silný bojový lektvar."));
        adrLore.add(Text.literal(""));
        adrLore.add(Text.literal("§eRecept (Crafting Table):"));
        adrLore.add(Text.literal("§f   S  "));
        adrLore.add(Text.literal("§f S N S"));
        adrLore.add(Text.literal("§f   R  "));
        adrLore.add(Text.literal(""));
        adrLore.add(Text.literal("§7S = §fSugar"));
        adrLore.add(Text.literal("§7N = §fNether Star"));
        adrLore.add(Text.literal("§7R = §fRedstone Block"));
        adrenaline.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(adrLore));
        inv.setStack(16, adrenaline);

        // Iron Skin Potion
        ItemStack ironskin = new ItemStack(Items.POTION);
        ironskin.set(DataComponentTypes.ITEM_NAME, Text.literal("§7§lIron Skin Potion"));
        ironskin.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> isLore = new ArrayList<>();
        isLore.add(Text.literal("§7Lektvar odolnosti."));
        isLore.add(Text.literal(""));
        isLore.add(Text.literal("§eRecept (Crafting Table):"));
        isLore.add(Text.literal("§f I I I"));
        isLore.add(Text.literal("§f I N I"));
        isLore.add(Text.literal("§f S S S"));
        isLore.add(Text.literal(""));
        isLore.add(Text.literal("§7I = §fIron Block"));
        isLore.add(Text.literal("§7N = §fNether Star"));
        isLore.add(Text.literal("§7S = §fTurtle Scute"));
        ironskin.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(isLore));
        inv.setStack(28, ironskin);

        // Magnetic Pearl
        ItemStack magPearl = new ItemStack(Items.ENDER_PEARL);
        magPearl.set(DataComponentTypes.ITEM_NAME, Text.literal("§5§lMagnetic Pearl"));
        magPearl.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> mpLore = new ArrayList<>();
        mpLore.add(Text.literal("§7Magnetická ender perla."));
        mpLore.add(Text.literal(""));
        mpLore.add(Text.literal("§eRecept (Crafting Table):"));
        mpLore.add(Text.literal("§f   N  "));
        mpLore.add(Text.literal("§f N E N"));
        mpLore.add(Text.literal("§f   N  "));
        mpLore.add(Text.literal(""));
        mpLore.add(Text.literal("§7N = §fNetherite Ingot"));
        mpLore.add(Text.literal("§7E = §fEnder Pearl"));
        magPearl.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(mpLore));
        inv.setStack(30, magPearl);

        // Echoing Horn
        ItemStack echoHorn = new ItemStack(Items.GOAT_HORN);
        echoHorn.set(DataComponentTypes.ITEM_NAME, Text.literal("§9§lEchoing Horn"));
        echoHorn.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        List<Text> ehLore = new ArrayList<>();
        ehLore.add(Text.literal("§7Zvukový roh."));
        ehLore.add(Text.literal(""));
        ehLore.add(Text.literal("§eRecept (Crafting Table):"));
        ehLore.add(Text.literal("§f   E  "));
        ehLore.add(Text.literal("§f E G E"));
        ehLore.add(Text.literal("§f   E  "));
        ehLore.add(Text.literal(""));
        ehLore.add(Text.literal("§7E = §fEcho Shard"));
        ehLore.add(Text.literal("§7G = §fGoat Horn"));
        echoHorn.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(ehLore));
        inv.setStack(32, echoHorn);

        // Slot 49 - Back
        ItemStack backItem = new ItemStack(Items.ARROW);
        backItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§7← Zpět do Menu"));
        inv.setStack(49, backItem);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) ->
            net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(syncId, playerInventory, inv),
            Text.literal("§8Crafty")));
        OPEN_MENUS.put(player.getUuid(), "crafts");
    }

    public static void openAchievementsMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        Set<String> playerAch = oracleState.playerAchievements.getOrDefault(player.getUuid().toString(), new HashSet<>());

        int slot = 10;
        for (Map.Entry<String, Achievement> entry : ACHIEVEMENTS.entrySet()) {
            Achievement a = entry.getValue();
            boolean unlocked = playerAch.contains(a.id);

            ItemStack item;
            if (unlocked) {
                item = new ItemStack(Items.LIME_DYE);
                item.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            } else {
                item = new ItemStack(Items.GRAY_DYE);
            }
            item.set(DataComponentTypes.ITEM_NAME, Text.literal((unlocked ? "§a✔ " : "§c✘ ") + "§l" + a.name));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("§7" + a.description));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§7Tier: §f" + a.tier));
            lore.add(Text.literal(a.rewardMsg));
            lore.add(Text.literal(""));
            lore.add(Text.literal(unlocked ? "§a§lODEMČENO" : "§c§lZAMČENO"));
            item.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(slot, item);
            slot += 2;
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 49) break;
        }

        // Slot 49 - Back
        ItemStack backItem = new ItemStack(Items.ARROW);
        backItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§7← Zpět do Menu"));
        inv.setStack(49, backItem);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) ->
            net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(syncId, playerInventory, inv),
            Text.literal("§8Úspěchy")));
        OPEN_MENUS.put(player.getUuid(), "achievements");
    }

    public static void handleMenuClick(ServerPlayerEntity player, int slotIndex) {
        String menuType = OPEN_MENUS.get(player.getUuid());
        if (menuType == null) return;

        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) return;

        switch (menuType) {
            case "main" -> {
                if (slotIndex == 11) {
                    server.execute(() -> openStatsMenu(player));
                } else if (slotIndex == 13) {
                    server.execute(() -> openCraftsMenu(player));
                } else if (slotIndex == 15) {
                    server.execute(() -> openAchievementsMenu(player));
                } else if (slotIndex == 17) {
                    server.execute(() -> openVotingMenu(player));
                }
            }
            case "stats" -> {
                if (slotIndex == 22) {
                    server.execute(() -> openMainMenu(player));
                }
            }
            case "crafts", "achievements" -> {
                if (slotIndex == 49) {
                    server.execute(() -> openMainMenu(player));
                }
            }
            case "voting" -> {
                if (slotIndex == 22) {
                    server.execute(() -> openMainMenu(player));
                } else if (oracleState.votingActive && slotIndex >= 10 && slotIndex <= 12) {
                    int voteId = (slotIndex - 10) + 1;
                    submitVote(player, voteId);
                    server.execute(() -> openVotingMenu(player));
                }
            }
        }
    }

    public static void openVotingMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(27);

        if (oracleState.votingActive && !oracleState.currentOptions.isEmpty()) {
            for (int i = 0; i < Math.min(3, oracleState.currentOptions.size()); i++) {
                int eventId = oracleState.currentOptions.get(i);
                String eventName = EVENT_NAMES.getOrDefault(eventId, "Neznámý");
                int votes = VOTE_COUNTS.getOrDefault(i + 1, 0);

                ItemStack option = new ItemStack(Items.PAPER);
                option.set(DataComponentTypes.ITEM_NAME, Text.literal("§a" + (i + 1) + ". §f" + eventName));
                List<Text> lore = new ArrayList<>();
                lore.add(Text.literal("§7Hlasů: §f" + votes));
                lore.add(Text.literal("§7Klikni pro hlasování."));
                option.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
                inv.setStack(10 + i, option);
            }
        } else {
            ItemStack info = new ItemStack(Items.BARRIER);
            info.set(DataComponentTypes.ITEM_NAME, Text.literal("§cHlasování není aktivní"));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("§7Hlasování běží v sobotu 15:00–15:30."));
            info.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(11, info);
        }

        ItemStack backItem = new ItemStack(Items.ARROW);
        backItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§7← Zpět do Menu"));
        inv.setStack(22, backItem);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) ->
            net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, inv),
            Text.literal("§8Oracle Hlasování")));
        OPEN_MENUS.put(player.getUuid(), "voting");
    }

    private static final Map<UUID, Long> SHOP_COOLDOWNS = new HashMap<>();

    public static void handleShopClick(ServerPlayerEntity player, int slotIndex) {
        if (slotIndex != 10 && slotIndex != 12 && slotIndex != 14) {
            player.sendMessage(Text.literal("§cKlikni na položku v obchodu."), true);
            return;
        }

        // Cooldown check (500ms)
        long now = System.currentTimeMillis();
        Long last = SHOP_COOLDOWNS.get(player.getUuid());
        if (last != null && now - last < 500) return;
        SHOP_COOLDOWNS.put(player.getUuid(), now);

        int cost;
        List<ItemStack> rewards;
        String successMessage;

        if (slotIndex == 10) {
            cost = 16;
            rewards = getKitItems(player);
            successMessage = "§aZakoupil jsi Kit!";
        } else if (slotIndex == 12) {
            cost = 1;
            rewards = List.of(new ItemStack(Items.DIRT, 64));
            successMessage = "§aZakoupil jsi Dirt!";
        } else {
            cost = 1;
            rewards = List.of(new ItemStack(Items.COBBLESTONE, 64));
            successMessage = "§aZakoupil jsi Cobblestone!";
        }

        if (rewards.isEmpty()) {
            player.sendMessage(Text.literal("§cChyba při vytváření nákupu."), false);
            return;
        }

        if (!hasItems(player, Items.DEEPSLATE_DIAMOND_ORE, cost)) {
            player.sendMessage(Text.literal("§cNemáš dostatek Deepslate Diamond Ore!" + (cost > 1 ? " (Potřebuješ " + cost + "x)" : "")), false);
            return;
        }

        removeItems(player, Items.DEEPSLATE_DIAMOND_ORE, cost);

        int dropped = giveOrDropStacks(player, rewards);

        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
        player.closeHandledScreen();
        if (dropped > 0) {
            player.sendMessage(Text.literal(successMessage + " §e(" + dropped + " itemů bylo vyhozeno k tobě, protože nebylo místo)"), false);
        } else {
            player.sendMessage(Text.literal(successMessage), false);
        }
    }

    private static boolean hasItems(ServerPlayerEntity player, Item item, int count) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
                if (total >= count) return true;
            }
        }
        return false;
    }

    private static void removeItems(ServerPlayerEntity player, Item item, int count) {
        int toRemove = count;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) {
                int amount = Math.min(stack.getCount(), toRemove);
                stack.decrement(amount);
                toRemove -= amount;
                if (toRemove <= 0) break;
            }
        }
    }

    private static boolean removeRandomInventoryItem(ServerPlayerEntity player) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        int slot = candidates.get(player.getEntityWorld().random.nextInt(candidates.size()));
        player.getInventory().setStack(slot, ItemStack.EMPTY);
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
        return true;
    }

    private static void addItems(ServerPlayerEntity player, Item item, int count) {
        ItemStack refund = new ItemStack(item, count);
        if (!player.getInventory().insertStack(refund)) {
            player.dropItem(refund, false);
        }
    }

    private static int giveOrDropStacks(ServerPlayerEntity player, List<ItemStack> stacks) {
        int droppedStacks = 0;
        for (ItemStack stack : stacks) {
            ItemStack copy = stack.copy();
            if (!player.getInventory().insertStack(copy)) {
                player.dropItem(copy, false);
                droppedStacks++;
            }
        }
        return droppedStacks;
    }

    private static void performBossAttack(ServerWorld world, net.minecraft.entity.mob.HostileEntity boss, ServerPlayerEntity target) {
        float hpPercent = boss.getHealth() / boss.getMaxHealth();
        int phase = (hpPercent < 0.2f) ? 3 : (hpPercent < 0.5f) ? 2 : 1;
        
        // Dynamic attacks based on boss type and phase
        int attackType = world.random.nextInt(6);
        boolean isEnraged = phase >= 2;
        boolean isDesperate = phase == 3;
        
        // Common Phase 2/3 enhancements
        if (isEnraged && world.random.nextFloat() < 0.2f) {
            world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, boss.getX(), boss.getY() + 2, boss.getZ(), 5, 0.5, 0.5, 0.5, 0.1);
            boss.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 40, 1, false, false));
        }

        if (boss instanceof net.minecraft.entity.mob.HuskEntity) {
            handleHuskAttacks(world, (net.minecraft.entity.mob.HuskEntity) boss, target, attackType, phase);
        } else if (boss instanceof net.minecraft.entity.mob.StrayEntity) {
            handleStrayAttacks(world, (net.minecraft.entity.mob.StrayEntity) boss, target, attackType, phase);
        } else if (boss instanceof net.minecraft.entity.mob.WitherSkeletonEntity) {
            handleWitherAttacks(world, (net.minecraft.entity.mob.WitherSkeletonEntity) boss, target, attackType, phase);
        }
        
        // Desperate Mode: Area Blast
        if (isDesperate && world.random.nextFloat() < 0.1f) {
            world.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.0f, 0.5f);
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY(), boss.getZ(), 1, 0, 0, 0, 0);
            world.getPlayers().stream().filter(p -> p.squaredDistanceTo(boss.getX(), boss.getY(), boss.getZ()) < 100).forEach(p -> {
                p.damage(world, world.getDamageSources().explosion(boss, boss), 10.0f);
            });
        }
    }

    private static void handleHuskAttacks(ServerWorld world, net.minecraft.entity.mob.HuskEntity boss, ServerPlayerEntity target, int type, int phase) {
        switch (type) {
            case 0 -> { // Fire Spikes (Enhanced Evoker Fangs)
                for (int i = 1; i <= (phase == 3 ? 10 : 5); i++) {
                    double x = boss.getX() + (target.getX() - boss.getX()) * (i / (phase == 3 ? 10.0 : 5.0));
                    double z = boss.getZ() + (target.getZ() - boss.getZ()) * (i / (phase == 3 ? 10.0 : 5.0));
                    net.minecraft.entity.mob.EvokerFangsEntity fangs = new net.minecraft.entity.mob.EvokerFangsEntity(world, x, boss.getY(), z, 0, 10, boss);
                    world.spawnEntity(fangs);
                    // Phase 2+ visual: spawn fire particles instead of permanent blocks
                    if (phase >= 2) world.spawnParticles(ParticleTypes.FLAME, x, boss.getY() + 0.5, z, 5, 0.2, 0.2, 0.2, 0.02);
                }
            }
            case 1 -> { // Shockwave
                world.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.0F, 0.5F);
                world.spawnParticles(ParticleTypes.EXPLOSION, boss.getX(), boss.getY(), boss.getZ(), 20, 3.0, 0.5, 3.0, 0.1);
                world.getPlayers().stream().filter(p -> p.squaredDistanceTo(boss.getX(), boss.getY(), boss.getZ()) < (phase == 3 ? 144 : 64)).forEach(p -> {
                    double dx = p.getX() - boss.getX();
                    double dz = p.getZ() - boss.getZ();
                    double dist = Math.sqrt(dx*dx + dz*dz);
                    p.addVelocity(dx/dist * (phase == 3 ? 2.5 : 1.5), 0.5, dz/dist * (phase == 3 ? 2.5 : 1.5));
                    p.damage(world, world.getDamageSources().mobAttack(boss), 6.0F + (phase * 2.0F));
                });
            }
            case 2 -> { // Fireballs
                int count = phase;
                for (int i = 0; i < count; i++) {
                    Vec3d velocity = new Vec3d((target.getX() - boss.getX()) + (world.random.nextGaussian() * 0.2), (target.getY() + 1 - (boss.getY() + 1.5)), (target.getZ() - boss.getZ()) + (world.random.nextGaussian() * 0.2));
                    SmallFireballEntity fireball = new SmallFireballEntity(world, boss, velocity);
                    fireball.setPosition(boss.getX(), boss.getY() + 2, boss.getZ());
                    world.spawnEntity(fireball);
                }
            }
            default -> { // Basic Melee + Fire
                target.setOnFireFor(5 * phase);
                target.damage(world, world.getDamageSources().mobAttack(boss), 4.0f * phase);
            }
        }
    }

    private static void handleStrayAttacks(ServerWorld world, net.minecraft.entity.mob.StrayEntity boss, ServerPlayerEntity target, int type, int phase) {
        switch (type) {
            case 0 -> { // Ice Prison
                BlockPos pPos = target.getBlockPos();
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos bPos = pPos.add(x, 0, z);
                        if (world.isAir(bPos)) {
                            DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
                            display.setBlockState(Blocks.ICE.getDefaultState());
                            display.setPosition(bPos.getX(), bPos.getY(), bPos.getZ());
                            display.addCommandTag("ls_temp_cage");
                            world.spawnEntity(display);
                        }
                    }
                }
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 4));
            }
            case 1 -> { // Snowball Barrage
                for (int i = 0; i < 5 + phase * 2; i++) {
                    SnowballEntity snowball = new SnowballEntity(EntityType.SNOWBALL, world);
                    snowball.setOwner(boss);
                    snowball.setPosition(boss.getX(), boss.getY() + 1.5, boss.getZ());
                    snowball.setVelocity(target.getX() - boss.getX(), target.getY() + 1 - boss.getY(), target.getZ() - boss.getZ(), 1.5f, 10.0f);
                    world.spawnEntity(snowball);
                }
            }
            default -> { // Freeze
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1));
                target.damage(world, world.getDamageSources().mobAttack(boss), 5.0f * phase);
            }
        }
    }

    private static void handleWitherAttacks(ServerWorld world, net.minecraft.entity.mob.WitherSkeletonEntity boss, ServerPlayerEntity target, int type, int phase) {
        switch (type) {
            case 0 -> { // Wither Skulls
                Vec3d velocity = new Vec3d(target.getX() - boss.getX(), target.getY() + 1 - boss.getY(), target.getZ() - boss.getZ());
                WitherSkullEntity skull = new WitherSkullEntity(world, boss, velocity);
                skull.setCharged(phase >= 2);
                skull.setPosition(boss.getX(), boss.getY() + 2, boss.getZ());
                world.spawnEntity(skull);
            }
            case 1 -> { // Darkness Pulse
                world.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENTITY_WITHER_SKELETON_HURT, SoundCategory.HOSTILE, 1.0f, 0.5f);
                world.getPlayers().stream().filter(p -> p.squaredDistanceTo(boss.getX(), boss.getY(), boss.getZ()) < 100).forEach(p -> {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 100, phase - 1));
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
                });
            }
            default -> { // Life Drain
                target.damage(world, world.getDamageSources().mobAttack(boss), 6.0f * phase);
                boss.heal(2.0f * phase);
            }
        }
    }

    private static void triggerHuskBoss(ServerWorld world, net.minecraft.util.math.BlockPos nearPos, ServerPlayerEntity target, String key) {
        target.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
        
        net.minecraft.util.math.BlockPos pos = findGround(world, nearPos);
        HostileEntity boss;
        String name;
        
        // Random boss type or based on context
        int type = world.random.nextInt(3);
        if (type == 1) {
            boss = new net.minecraft.entity.mob.StrayEntity(EntityType.STRAY, world);
            name = BOSS_STRAY_NAME;
        } else if (type == 2) {
            boss = new net.minecraft.entity.mob.WitherSkeletonEntity(EntityType.WITHER_SKELETON, world);
            name = BOSS_WITHER_NAME;
        } else {
            boss = new net.minecraft.entity.mob.HuskEntity(EntityType.HUSK, world);
            name = ELYTRA_BOSS_NAME;
        }

        target.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§c" + name + " povstává!")));
        target.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§cBraň svou Elytru!")));

        boss.setPersistent();
        boss.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 0.0F, 0.0F);
        
        // Bod 9: Scale 1.5
        EntityAttributeInstance scaleAttr = boss.getAttributeInstance(EntityAttributes.SCALE);
        if (scaleAttr != null) scaleAttr.setBaseValue(1.5);
        
        boss.setCustomName(Text.literal(name));
        boss.setCustomNameVisible(true);
        boss.addCommandTag("ls_frame:" + key);
        boss.setTarget(target);

        EntityAttributeInstance healthAttr = boss.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (healthAttr != null) {
            double bossHp = oracleState.currentActiveEffect == 27 ? 1000.0 : 500.0;
            healthAttr.setBaseValue(bossHp);
            boss.setHealth((float) bossHp);
        }
        
        boss.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20*60*60, 1));
        boss.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20*60*60, 0));
        boss.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20*60*60, 0));

        world.spawnEntity(boss);
        world.playSound(null, pos.getX(), pos.getY(), pos.getZ(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 1.0F, 1.0F);
    }

    private static net.minecraft.util.math.BlockPos findGround(ServerWorld world, net.minecraft.util.math.BlockPos start) {
        net.minecraft.util.math.BlockPos.Mutable m = start.mutableCopy();
        for (int i = 0; i < 40; i++) {
            if (!world.isAir(m) && world.getBlockState(m).isSolidBlock(world, m)) {
                return m.up();
            }
            m.move(0, -1, 0);
        }
        return start;
    }

    /**
     * Finds a safe teleport position: solid ground below, 2 air blocks for the player to stand in.
     * Scans downward from world height at the given X/Z. Returns null if no safe spot found.
     * Avoids lava, water, fire, and the void.
     */
    private static BlockPos findSafeTeleportPos(ServerWorld world, BlockPos hint) {
        int x = hint.getX();
        int z = hint.getZ();
        // Start from the top of the world and scan down
        int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
        if (topY <= world.getBottomY()) return null; // void column
        
        BlockPos.Mutable mutable = new BlockPos.Mutable(x, topY, z);
        for (int y = topY; y > world.getBottomY() + 1; y--) {
            mutable.setY(y);
            BlockPos below = mutable.down();
            BlockPos above = mutable.up();
            
            net.minecraft.block.BlockState groundState = world.getBlockState(below);
            net.minecraft.block.BlockState feetState = world.getBlockState(mutable);
            net.minecraft.block.BlockState headState = world.getBlockState(above);
            
            // Ground must be solid, not lava/fire
            if (!groundState.isSolidBlock(world, below)) continue;
            if (groundState.isOf(Blocks.LAVA) || groundState.isOf(Blocks.FIRE) || groundState.isOf(Blocks.MAGMA_BLOCK)) continue;
            
            // Feet and head must be passable (air or non-solid, non-liquid)
            boolean feetOk = feetState.isAir() || (!feetState.isSolidBlock(world, mutable) && !feetState.getFluidState().isStill());
            boolean headOk = headState.isAir() || (!headState.isSolidBlock(world, above) && !headState.getFluidState().isStill());
            
            if (feetOk && headOk) {
                return mutable.toImmutable();
            }
        }
        return null; // No safe position found
    }

    public static List<ItemStack> getKitItems(ServerPlayerEntity player) {
        List<ItemStack> items = new ArrayList<>();
        var server = player.getCommandSource().getServer();
        if (server == null) return items;
        Registry<Enchantment> enchs = server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        // 1. DIA AXE (Sharpness 1, Unbreaking 2)
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        addEnchantment(axe, enchs, Enchantments.SHARPNESS, 1);
        addEnchantment(axe, enchs, Enchantments.UNBREAKING, 2);
        items.add(axe);

        // 2. DIA SWORD (Sharpness 1, Fire Aspect 1)
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        addEnchantment(sword, enchs, Enchantments.SHARPNESS, 1);
        addEnchantment(sword, enchs, Enchantments.FIRE_ASPECT, 1);
        items.add(sword);

        // 4. Full DIA Armor (Protection 3)
        Item[] armorItems = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
        for (Item armorItem : armorItems) {
            ItemStack armor = new ItemStack(armorItem);
            addEnchantment(armor, enchs, Enchantments.PROTECTION, 3);
            items.add(armor);
        }
        
        // 3. 8x Ender Pearl
        ItemStack pearls = new ItemStack(Items.ENDER_PEARL, 8);
        items.add(pearls);

        // 5. 1x Totem of Undying
        ItemStack totem = new ItemStack(Items.TOTEM_OF_UNDYING);
        items.add(totem);

        // 6. 32x Golden Carrot
        ItemStack carrots = new ItemStack(Items.GOLDEN_CARROT, 32);
        items.add(carrots);

        // 7. 5x Strength 1
        for (int i = 0; i < 5; i++) {
            ItemStack potion = new ItemStack(Items.POTION);
            potion.set(DataComponentTypes.POTION_CONTENTS, new net.minecraft.component.type.PotionContentsComponent(net.minecraft.potion.Potions.STRENGTH));
            items.add(potion);
        }

        // 8. 3x Invis potion 3 min.
        for (int i = 0; i < 3; i++) {
            ItemStack potion = new ItemStack(Items.POTION);
            potion.set(DataComponentTypes.POTION_CONTENTS, new net.minecraft.component.type.PotionContentsComponent(net.minecraft.potion.Potions.INVISIBILITY));
            items.add(potion);
        }

        // 9. 8x Golden Apple
        ItemStack gApples = new ItemStack(Items.GOLDEN_APPLE, 8);
        items.add(gApples);

        // 10. 16x Cobweb
        ItemStack cobwebs = new ItemStack(Items.COBWEB, 16);
        items.add(cobwebs);

        // 11. Water Bucket
        ItemStack water = new ItemStack(Items.WATER_BUCKET);
        items.add(water);

        // 12. Lava bucket
        ItemStack lava = new ItemStack(Items.LAVA_BUCKET);
        items.add(lava);

        // 2 staky Cobblestone
        ItemStack cobble1 = new ItemStack(Items.COBBLESTONE, 64);
        items.add(cobble1);
        ItemStack cobble2 = new ItemStack(Items.COBBLESTONE, 64);
        items.add(cobble2);

        return items;
    }

    private static void addEnchantment(ItemStack stack, Registry<Enchantment> registry, RegistryKey<Enchantment> key, int level) {
        Enchantment ench = registry.get(key);
        if (ench != null) {
            stack.addEnchantment(registry.getEntry(ench), level);
        }
    }

    public static boolean isUnmodifiable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
        return nbtComponent != null && nbtComponent.copyNbt().contains("lifesteal:unmodifiable");
    }

    private static void applyKitIndex(ItemStack stack) {
        stack.set(DataComponentTypes.REPAIR_COST, 1000);
        stack.set(DataComponentTypes.ENCHANTABLE, new net.minecraft.component.type.EnchantableComponent(0));
        
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§c§oNeupravitelný předmět"));
        stack.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
        
        NbtCompound nbt = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        nbt.putBoolean("lifesteal:unmodifiable", true);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    public static void updateMaxHealth(ServerPlayerEntity player, double amount) {
        EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (instance != null) {
            double currentModifierValue = 0;
            EntityAttributeModifier modifier = instance.getModifier(HEALTH_MODIFIER_ID);
            
            if (modifier != null) {
                currentModifierValue = modifier.value();
                instance.removeModifier(HEALTH_MODIFIER_ID);
            }

            double newValue = currentModifierValue + amount;
            
            // Limit health to 20 hearts (40.0 HP)
            double maxModifier = 40.0 - instance.getBaseValue();
            if (newValue > maxModifier) {
                newValue = maxModifier;
            }
            
            // Minimum health: 1 heart (2.0 HP)
            double minModifier = 2.0 - instance.getBaseValue();
            if (newValue < minModifier) {
                newValue = minModifier;
            }
            
            instance.addPersistentModifier(new EntityAttributeModifier(
                    HEALTH_MODIFIER_ID,
                    newValue,
                    EntityAttributeModifier.Operation.ADD_VALUE
            ));

            if (player.getHealth() > instance.getValue()) {
                player.setHealth((float) instance.getValue());
            }

            if (instance.getValue() >= 40.0) {
                grantAchievement(player, "REACH_20_HEARTS");
            }
            
            updatePlayerStats(player);
        }
    }

    private static void grantAchievement(ServerPlayerEntity player, String id) {
        if (id == null || !ACHIEVEMENTS.containsKey(id)) return;
        UUID uuid = player.getUuid();
        Set<String> playerAch = oracleState.playerAchievements.computeIfAbsent(uuid.toString(), k -> new HashSet<>());
        if (playerAch.contains(id)) return;

        playerAch.add(id);
        Achievement a = ACHIEVEMENTS.get(id);
        if (a == null) return;
        
        // Broadcast and reward
        player.getCommandSource().getServer().getPlayerManager().broadcast(Text.literal("§6§lAchievement Odemčen! §f" + player.getName().getString() + " získal §e" + a.name), false);
        player.sendMessage(Text.literal("§7" + a.description), false);
        player.sendMessage(Text.literal(a.rewardMsg), false);

        // Give rewards
        switch (id) {
            case "STEAL_HEART" -> giveRewardItem(player, new ItemStack(Items.GOLDEN_APPLE));
            case "REACH_20_HEARTS" -> {
                ItemStack soulAnchor = new ItemStack(Items.TOTEM_OF_UNDYING);
                NbtCompound nbt = new NbtCompound();
                nbt.putBoolean("lifesteal:soul_anchor", true);
                soulAnchor.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                soulAnchor.set(DataComponentTypes.ITEM_NAME, Text.literal("§b§lRevival Heart").formatted(Formatting.AQUA));
                giveRewardItem(player, soulAnchor);
            }
            case "KILL_BOSS_SOLO" -> {
                ItemStack heart = new ItemStack(Items.NETHER_STAR, 2);
                NbtCompound nbt = new NbtCompound();
                nbt.putBoolean("lifesteal:heart", true);
                heart.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                heart.set(DataComponentTypes.ITEM_NAME, Text.literal("§cHeart").formatted(Formatting.RED));
                giveRewardItem(player, heart);
            }
            case "SURVIVE_1_HEART" -> {
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                NbtCompound nbt = new NbtCompound();
                nbt.putBoolean("lifesteal:golden_head", true);
                head.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                head.set(DataComponentTypes.ITEM_NAME, Text.literal("§6Golden Head").formatted(Formatting.GOLD));
                giveRewardItem(player, head);
            }
            case "COMPLETE_10_BOUNTIES" -> giveRewardItem(player, new ItemStack(Items.NETHERITE_INGOT, 5));
        }
        
        if (player.getEntityWorld() instanceof ServerWorld sw) {
            sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
        saveData();
    }

    private static void giveRewardItem(ServerPlayerEntity player, ItemStack reward) {
        if (!player.getInventory().insertStack(reward.copy())) {
            player.dropItem(reward, false);
            player.sendMessage(Text.literal("§eInventář je plný, odměna byla položena na zem."), true);
        }
    }

    private static ServerWorld resolveConfiguredSpawnWorld(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(oracleState.spawnDimension)) {
                return world;
            }
        }
        return server.getOverworld();
    }

    private static boolean teleportToConfiguredSpawn(ServerPlayerEntity player) {
        if (!oracleState.spawnSet) return false;
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) return false;

        ServerWorld targetWorld = resolveConfiguredSpawnWorld(server);
        player.teleportTo(new TeleportTarget(
            targetWorld,
            new Vec3d(oracleState.spawnX, oracleState.spawnY, oracleState.spawnZ),
            Vec3d.ZERO,
            oracleState.spawnYaw,
            oracleState.spawnPitch,
            TeleportTarget.NO_OP
        ));
        return true;
    }

    private static void scheduleSpawnTeleport(UUID playerId) {
        if (!oracleState.spawnSet) return;
        PENDING_SPAWN_TELEPORTS.merge(playerId, SPAWN_TELEPORT_RETRY_TICKS, Math::max);
    }

    private static boolean isCloseToConfiguredSpawn(ServerPlayerEntity player) {
        if (!oracleState.spawnSet) return false;
        if (!player.getEntityWorld().getRegistryKey().getValue().toString().equals(oracleState.spawnDimension)) return false;

        double dx = player.getX() - oracleState.spawnX;
        double dy = player.getY() - oracleState.spawnY;
        double dz = player.getZ() - oracleState.spawnZ;
        return (dx * dx) + (dy * dy) + (dz * dz) <= 4.0;
    }

    private static void updateLoginFreezeAnchorToCurrentPosition(ServerPlayerEntity player) {
        LOGIN_POS.put(player.getUuid(), new TeleportTarget(
                (ServerWorld) player.getEntityWorld(),
                new Vec3d(player.getX(), player.getY(), player.getZ()),
                player.getVelocity(),
                player.getYaw(),
                player.getPitch(),
                TeleportTarget.NO_OP
        ));
    }

    private static void processPendingSpawnTeleports(MinecraftServer server) {
        if (PENDING_SPAWN_TELEPORTS.isEmpty()) return;

        Iterator<Map.Entry<UUID, Integer>> iterator = PENDING_SPAWN_TELEPORTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int remainingTicks = entry.getValue();
            if (remainingTicks <= 0 || !oracleState.spawnSet) {
                iterator.remove();
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                entry.setValue(remainingTicks - 1);
                continue;
            }

            if (isCloseToConfiguredSpawn(player)) {
                if (!LOGGED_IN.contains(player.getUuid())) {
                    updateLoginFreezeAnchorToCurrentPosition(player);
                }
                iterator.remove();
                continue;
            }

            teleportToConfiguredSpawn(player);

            if (!LOGGED_IN.contains(player.getUuid()) && isCloseToConfiguredSpawn(player)) {
                updateLoginFreezeAnchorToCurrentPosition(player);
            }

            if (remainingTicks <= 1) {
                iterator.remove();
            } else {
                entry.setValue(remainingTicks - 1);
            }
        }
    }

    private static boolean isInSpawnProtection(ServerPlayerEntity player, BlockPos pos) {
        if (player.isCreative()) return false;
        if (!oracleState.spawnSet) return false;
        if (!player.getEntityWorld().getRegistryKey().getValue().toString().equals(oracleState.spawnDimension)) return false;
        
        double dx = pos.getX() - oracleState.spawnX;
        double dz = pos.getZ() - oracleState.spawnZ;
        double distanceSq = dx * dx + dz * dz;
        return distanceSq <= (oracleState.spawnProtectionRadius * oracleState.spawnProtectionRadius);
    }

    public static void updatePlayerStats(ServerPlayerEntity player) {
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) return;
        
        Scoreboard scoreboard = server.getScoreboard();
        
        ScoreboardObjective killsObj = getKillsObjective(server);
        ScoreboardObjective deathsObj = getDeathsObjective(server);
        
        int kills = scoreboard.getOrCreateScore(player, killsObj).getScore();
        int deaths = scoreboard.getOrCreateScore(player, deathsObj).getScore();
        
        double kd = deaths == 0 ? kills : (double) kills / (double) deaths;
        String kdStr = String.format(Locale.US, "%.2f", kd);

        // Tab List & Name Tag: using Teams
        String teamName = "ls_" + player.getName().getString();
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.addTeam(teamName);
            scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), team);
        }
        
        team.setPrefix(Text.literal("§7[" + kdStr + " KD] "));
    }

    private static ScoreboardObjective getKillsObjective(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective obj = scoreboard.getNullableObjective("ls_kills");
        if (obj == null) {
            obj = scoreboard.addObjective("ls_kills", ScoreboardCriterion.PLAYER_KILL_COUNT, Text.literal("Kills"), ScoreboardCriterion.RenderType.INTEGER, true, null);
        }
        return obj;
    }

    private static ScoreboardObjective getDeathsObjective(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective obj = scoreboard.getNullableObjective("ls_deaths");
        if (obj == null) {
            obj = scoreboard.addObjective("ls_deaths", ScoreboardCriterion.DEATH_COUNT, Text.literal("Deaths"), ScoreboardCriterion.RenderType.INTEGER, true, null);
        }
        return obj;
    }

    private static void normalizeOracleState() {
        if (oracleState.recentEvents == null) oracleState.recentEvents = new ArrayList<>();
        if (oracleState.currentOptions == null) oracleState.currentOptions = new ArrayList<>();
        if (oracleState.playerVotes == null) oracleState.playerVotes = new HashMap<>();
        if (oracleState.voteCounts == null) oracleState.voteCounts = new HashMap<>();
        if (oracleState.triggeredShips == null) oracleState.triggeredShips = new HashSet<>();
        if (oracleState.unlockedFrames == null) oracleState.unlockedFrames = new HashSet<>();
        if (oracleState.pendingHealthChanges == null) oracleState.pendingHealthChanges = new HashMap<>();
        if (oracleState.savedSkins == null) oracleState.savedSkins = new HashMap<>();
        if (oracleState.playerAchievements == null) oracleState.playerAchievements = new HashMap<>();
        if (oracleState.totalHeartsStolen == null) oracleState.totalHeartsStolen = new HashMap<>();
        if (oracleState.totalBountiesCompleted == null) oracleState.totalBountiesCompleted = new HashMap<>();
        if (oracleState.bossKills == null) oracleState.bossKills = new HashMap<>();
        if (oracleState.receivedVoteTicketUuids == null) oracleState.receivedVoteTicketUuids = new HashSet<>();
        if (oracleState.bannedPlayers == null) oracleState.bannedPlayers = new HashMap<>();
        if (oracleState.banReasons == null) oracleState.banReasons = new HashMap<>();
        if (oracleState.mutedPlayers == null) oracleState.mutedPlayers = new HashMap<>();
    }

    private static void applyCombatTag(ServerPlayerEntity player, long now) {
        COMBAT_TAGS.put(player.getUuid(), now + COMBAT_TAG_DURATION_MS);
        updateCombatBossBar(player, now);
    }

    private static void updateCombatBossBar(ServerPlayerEntity player, long now) {
        UUID uuid = player.getUuid();
        Long combatUntil = COMBAT_TAGS.get(uuid);
        if (combatUntil == null || combatUntil <= now) {
            COMBAT_TAGS.remove(uuid);
            removeCombatBossBar(uuid);
            return;
        }

        long remainingMs = combatUntil - now;
        long remainingSeconds = (long) Math.ceil(remainingMs / 1000.0);
        float progress = Math.max(0.0f, Math.min(1.0f, (float) remainingMs / (float) COMBAT_TAG_DURATION_MS));

        ServerBossBar bossBar = COMBAT_BOSSBARS.computeIfAbsent(uuid, id -> new ServerBossBar(
            Text.literal("§c⚔ V boji"),
            BossBar.Color.RED,
            BossBar.Style.NOTCHED_20
        ));

        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }

        bossBar.setName(Text.literal("§c⚔ V boji: §f" + remainingSeconds + "s"));
        bossBar.setPercent(progress);
        bossBar.setVisible(true);
    }

    private static void cleanupExpiredCombatTags(long now) {
        Iterator<Map.Entry<UUID, Long>> iterator = COMBAT_TAGS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                COMBAT_LAST_ATTACKER.remove(entry.getKey());
                removeCombatBossBar(entry.getKey());
                iterator.remove();
            }
        }
    }

    private static void removeCombatBossBar(UUID uuid) {
        ServerBossBar bossBar = COMBAT_BOSSBARS.remove(uuid);
        if (bossBar != null) {
            bossBar.clearPlayers();
        }
    }

    private static void clearAllCombatBossBars() {
        for (ServerBossBar bossBar : COMBAT_BOSSBARS.values()) {
            bossBar.clearPlayers();
        }
        COMBAT_BOSSBARS.clear();
    }

    private static void syncVoteRuntimeFromState() {
        PLAYER_VOTES.clear();
        VOTE_COUNTS.clear();

        if (!oracleState.votingActive) {
            oracleState.playerVotes.clear();
            oracleState.voteCounts.clear();
            return;
        }

        for (Map.Entry<String, Integer> entry : oracleState.playerVotes.entrySet()) {
            try {
                PLAYER_VOTES.put(UUID.fromString(entry.getKey()), entry.getValue());
            } catch (Exception ignored) {
            }
        }
        VOTE_COUNTS.putAll(oracleState.voteCounts);
    }

    private static void syncVoteStateFromRuntime() {
        oracleState.playerVotes.clear();
        for (Map.Entry<UUID, Integer> entry : PLAYER_VOTES.entrySet()) {
            oracleState.playerVotes.put(entry.getKey().toString(), entry.getValue());
        }

        oracleState.voteCounts.clear();
        oracleState.voteCounts.putAll(VOTE_COUNTS);
    }

    private static boolean cleanupExpiredBans() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : BANNED_PLAYERS.entrySet()) {
            if (now >= entry.getValue()) {
                expired.add(entry.getKey());
            }
        }

        if (expired.isEmpty()) return false;

        for (String uuid : expired) {
            BANNED_PLAYERS.remove(uuid);
            BAN_REASONS.remove(uuid);
        }
        return true;
    }

    private static void cleanupBossAttackTimers(MinecraftServer server) {
        if (BOSS_ATTACK_TIMER.isEmpty()) return;
        BOSS_ATTACK_TIMER.keySet().removeIf(uuid -> {
            for (ServerWorld world : server.getWorlds()) {
                var entity = world.getEntity(uuid);
                if (entity instanceof HostileEntity hostile && hostile.isAlive() && hostile.getCustomName() != null) {
                    String name = hostile.getCustomName().getString();
                    if (ELYTRA_BOSS_NAME.equals(name) || BOSS_STRAY_NAME.equals(name) || BOSS_WITHER_NAME.equals(name)) {
                        return false;
                    }
                }
            }
            return true;
        });
    }

    private static void giveVoteTicket(ServerPlayerEntity player) {
        if (oracleState.currentOptions.isEmpty()) return;
        if (oracleState.receivedVoteTicketUuids.contains(player.getUuid().toString())) return;
        oracleState.receivedVoteTicketUuids.add(player.getUuid().toString());
        saveData();
        
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<net.minecraft.text.RawFilteredPair<Text>> pages = new ArrayList<>();
        
        Text page1 = Text.literal("§0§lThe Oracle\n\n§rVyber si jednu z možností pro příští týden:\n\n");
        for (int i = 0; i < oracleState.currentOptions.size(); i++) {
             int eventId = oracleState.currentOptions.get(i);
               page1 = ((net.minecraft.text.MutableText)page1).append(createVoteOption(EVENT_NAMES.get(eventId)));
        }
            
        pages.add(net.minecraft.text.RawFilteredPair.of(page1));
        
        book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
            net.minecraft.text.RawFilteredPair.of("Book of Fate"),
            "The Oracle",
            0,
            pages,
            true
        ));
        
        if (!player.getInventory().insertStack(book)) {
            player.dropItem(book, false);
        }
    }

    private static Text createVoteOption(String label) {
        return Text.literal("§1[✔] " + label + "\n")
            .append(Text.literal("    §7(Hlasuj v /menu)").styled(s -> s.withColor(Formatting.GRAY)))
            .append(Text.literal("\n\n"));
    }

    private static boolean isVotingWindowNow() {
        LocalDateTime now = LocalDateTime.now();
        LocalTime time = now.toLocalTime();
        return now.getDayOfWeek() == DayOfWeek.SATURDAY
            && !time.isBefore(LocalTime.of(15, 0))
            && time.isBefore(LocalTime.of(15, 30));
    }

    private static int submitVote(ServerPlayerEntity player, int id) {
        if (!LOGGED_IN.contains(player.getUuid())) {
            player.sendMessage(Text.literal("§cMusíš se nejprve přihlásit: /login <heslo>"), false);
            return 0;
        }
        if (!oracleState.votingActive) {
            player.sendMessage(Text.literal("§cHlasování momentálně neprobíhá!"), false);
            return 0;
        }
        if (!isVotingWindowNow()) {
            player.sendMessage(Text.literal("§cHlasovat lze pouze v sobotu mezi 15:00 a 15:30."), false);
            return 0;
        }
        if (id < 1 || id > oracleState.currentOptions.size()) {
            player.sendMessage(Text.literal("§cNeplatná volba."), false);
            return 0;
        }

        int eventId = oracleState.currentOptions.get(id - 1);
        String eventName = EVENT_NAMES.getOrDefault(eventId, "Neznámý");

        UUID uuid = player.getUuid();
        Integer oldId = PLAYER_VOTES.get(uuid);
        if (oldId != null) {
            VOTE_COUNTS.put(oldId, Math.max(0, VOTE_COUNTS.getOrDefault(oldId, 0) - 1));
        }

        PLAYER_VOTES.put(uuid, id);
        VOTE_COUNTS.put(id, VOTE_COUNTS.getOrDefault(id, 0) + 1);
        syncVoteStateFromRuntime();
        saveData();

        player.sendMessage(Text.literal("§aHlasoval jsi pro: §f" + eventName), false);
        return 1;
    }

    private static void handleOracleScheduling(MinecraftServer server) {
        LocalDateTime now = LocalDateTime.now();
        int currentWeek = now.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        
        // Voting window: Saturday 15:00 - 15:30
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY && now.getHour() == 15 && now.getMinute() < 30) {
            if (!oracleState.votingActive && oracleState.lastWeekVoted != currentWeek) {
                startVoting(server, currentWeek);
            }
        }
        
        if (oracleState.votingActive) {
            if (oracleState.votingEndTime <= 0) {
                oracleState.votingEndTime = System.currentTimeMillis() + (30 * 60 * 1000);
                saveData();
            }

            // Periodic broadcast every 5 minutes (300 seconds)
            long nowMs = System.currentTimeMillis();
            if (nowMs - oracleState.lastVoteBroadcast > 300000) {
                oracleState.lastVoteBroadcast = nowMs;
                broadcastVotingStatus(server);
            }

            // End voting check
            if (nowMs > oracleState.votingEndTime) {
                applyVoteResults(server);
            }
        }
        
        // Bonus: Clean up Bounty/Assassin if they survive until Sunday 23:59
        if (System.currentTimeMillis() > oracleState.effectEndTime && oracleState.effectEndTime != 0) {
             if (oracleState.currentActiveEffect == 53 && oracleState.targetPlayerUuid != null) {
                 // Bounty survived!
                 UUID targetId = oracleState.targetPlayerUuid;
                 ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetId);
                 if (target != null) {
                     target.sendMessage(Text.literal("§aPřežil jsi odměnu! Získáváš 10x Deepslate Diamond Ore."), false);
                     ItemStack reward = new ItemStack(Items.DEEPSLATE_DIAMOND_ORE, 10);
                     if (!target.getInventory().insertStack(reward)) target.dropItem(reward, false);
                 }
                 for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                     if (!p.getUuid().equals(targetId)) {
                         updateMaxHealth(p, -2.0);
                         p.sendMessage(Text.literal("§cCíl odměny přežil! Ztrácíš 1 srdce."), false);
                     }
                 }
                 oracleState.targetPlayerUuid = null;
             }
             if (oracleState.currentActiveEffect == 55 && oracleState.assassinUuid != null) {
                 // Assassin failed! (Fix: Handle offline players)
                 UUID assassinId = oracleState.assassinUuid;
                 ServerPlayerEntity assassin = server.getPlayerManager().getPlayer(assassinId);
                 if (assassin != null) {
                     updateMaxHealth(assassin, -4.0);
                     assassin.sendMessage(Text.literal("§cSelhal jsi v atentátu! Ztrácíš 2 srdce."), false);
                 } else {
                     oracleState.pendingHealthChanges.put(assassinId.toString(), -4);
                 }
                 oracleState.assassinUuid = null;
                 oracleState.assassinTargetUuid = null;
             }
             int endingEffect = oracleState.currentActiveEffect;
             for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                 // Remove Oracle-applied potion effects
                 p.removeStatusEffect(StatusEffects.SLOW_FALLING);
                 p.removeStatusEffect(StatusEffects.JUMP_BOOST);
                 p.removeStatusEffect(StatusEffects.SLOWNESS);
                 p.removeStatusEffect(StatusEffects.BLINDNESS);
                 p.removeStatusEffect(StatusEffects.SPEED);
                 p.removeStatusEffect(StatusEffects.INVISIBILITY);
                 p.removeStatusEffect(StatusEffects.STRENGTH);
                 p.removeStatusEffect(StatusEffects.POISON);
                 p.removeStatusEffect(StatusEffects.REGENERATION);
                 p.removeStatusEffect(StatusEffects.RESISTANCE);
                 p.removeStatusEffect(StatusEffects.FIRE_RESISTANCE);
                 p.removeStatusEffect(StatusEffects.GLOWING);
                 p.removeStatusEffect(StatusEffects.DARKNESS);
                 p.removeStatusEffect(StatusEffects.HASTE);
                 p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
                 p.removeStatusEffect(StatusEffects.HUNGER);
                 p.removeStatusEffect(StatusEffects.LUCK);
                 p.removeStatusEffect(StatusEffects.NAUSEA);
                 p.removeStatusEffect(StatusEffects.NIGHT_VISION);
                 p.removeStatusEffect(StatusEffects.WITHER);
                 p.removeStatusEffect(StatusEffects.WEAKNESS);
                 p.removeStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE);
                 // Reset base HP ONLY if Double Health (71) was active
                 if (endingEffect == 71) {
                     EntityAttributeInstance hpAttr = p.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                     if (hpAttr != null && hpAttr.getBaseValue() != 20.0) {
                         hpAttr.setBaseValue(20.0);
                     }
                 }
                 // Reset scale if Mini-Players (58) or Giants (59) was active
                 if (endingEffect == 58 || endingEffect == 59) {
                     EntityAttributeInstance scaleAttr = p.getAttributeInstance(EntityAttributes.SCALE);
                     if (scaleAttr != null && scaleAttr.getBaseValue() != 1.0) {
                         scaleAttr.setBaseValue(1.0);
                     }
                 }
             }
             // Reset world time if Eternal Day (6) or Eternal Night (5) was active
             if (endingEffect == 5 || endingEffect == 6) {
                 for (ServerWorld sw : server.getWorlds()) {
                     sw.setTimeOfDay(sw.getTimeOfDay() + 1);
                 }
             }
             oracleState.currentActiveEffect = 0;
             oracleState.isEventActive = false;
             oracleState.effectEndTime = 0;
             oracleState.lastAntiSnowballRun = 0;
             SHARED_FATE_LINKS.clear();
             LAYING_PLAYERS.clear();
             CRAWLING_PLAYERS.clear();
             // Reset all players to standing pose
             for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                 if (p.getPose() != EntityPose.STANDING) {
                     p.setPose(EntityPose.STANDING);
                     p.clearSleepingPosition();
                 }
             }
             saveData();
        }
    }

    private static void startVoting(MinecraftServer server, int week) {
        if (week < 0) {
            week = LocalDateTime.now().get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        }

        oracleState.votingActive = true;
        oracleState.lastWeekVoted = week;
        oracleState.votingEndTime = System.currentTimeMillis() + (30 * 60 * 1000); // 30 minutes
        oracleState.lastVoteBroadcast = System.currentTimeMillis();
        PLAYER_VOTES.clear();
        VOTE_COUNTS.clear();
        oracleState.playerVotes.clear();
        oracleState.voteCounts.clear();
        oracleState.receivedVoteTicketUuids.clear();
        
        // Pick 3 random options
        List<Integer> allIds = new ArrayList<>(IMPLEMENTED_EVENTS);
        allIds.removeAll(oracleState.recentEvents);
        if (allIds.size() < 3) oracleState.recentEvents.clear(); // Reset if pool too small
        
        Collections.shuffle(allIds);
        oracleState.currentOptions = allIds.stream().limit(3).collect(Collectors.toList());
        for (int i = 1; i <= oracleState.currentOptions.size(); i++) {
            VOTE_COUNTS.put(i, 0);
        }
        syncVoteStateFromRuntime();
        
        server.getPlayerManager().broadcast(Text.literal("§6§l[The Oracle] §eHlasování o víkendový efekt začalo! Použij knihu v inventáři."), false);
        
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            giveVoteTicket(p);
        }
        saveData();
    }

    private static void broadcastVotingStatus(MinecraftServer server) {
        if (!oracleState.votingActive || oracleState.currentOptions.isEmpty()) return;
        server.getPlayerManager().broadcast(Text.literal("§6§l[The Oracle] §eAktuální stav hlasování:"), false);
        for (int i = 0; i < oracleState.currentOptions.size(); i++) {
            int eventId = oracleState.currentOptions.get(i);
            int optId = i + 1;
            int votes = VOTE_COUNTS.getOrDefault(optId, 0);
            String name = EVENT_NAMES.getOrDefault(eventId, "Neznámý");
            server.getPlayerManager().broadcast(Text.literal("§7" + optId + ". §f" + name + " §8- §e" + votes + " hlasů"), false);
        }
    }

    private static void setupBountyOrAssassin(MinecraftServer server, int type) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return;
        Random rand = new Random();
        
        if (type == 53) { // Skull Hunter (Bounty)
            ServerPlayerEntity target;
            if (players.size() < 1) return;
            target = players.get(rand.nextInt(players.size()));
            
            oracleState.targetPlayerUuid = target.getUuid();
            oracleState.lastBountyTarget = target.getUuid();
            server.getPlayerManager().broadcast(Text.literal("§c§l[The Oracle] §fNa hráče §e" + target.getName().getString() + " §fbyla vypsána odměna! Zabijte ho do neděle."), false);
        } else if (type == 55) { // Silent Mail (Assassin)
            if (players.size() < 2) return;

            ServerPlayerEntity assassin = players.get(rand.nextInt(players.size()));
            List<ServerPlayerEntity> possibleTargets = new ArrayList<>(players);
            possibleTargets.remove(assassin);
            if (possibleTargets.isEmpty()) return;

            ServerPlayerEntity target = possibleTargets.get(rand.nextInt(possibleTargets.size()));
            oracleState.assassinUuid = assassin.getUuid();
            oracleState.assassinTargetUuid = target.getUuid();
            oracleState.lastAssassinTarget = target.getUuid();

            assassin.sendMessage(Text.literal("§4§l[The Oracle] §cJsi atentátník. Tvůj cíl: §f" + target.getName().getString()), false);
            server.getPlayerManager().broadcast(Text.literal("§6§l[The Oracle] §eSilent Mail byl aktivován."), false);
        }
    }

    private static void applyVoteResults(MinecraftServer server) {
        syncVoteRuntimeFromState();

        int winnerOption = 0;
        int maxVotes = -1;
        
        for (int i = 1; i <= 3; i++) {
            int votes = VOTE_COUNTS.getOrDefault(i, 0);
            if (votes > maxVotes) {
                maxVotes = votes;
                winnerOption = i;
            }
        }
        
        if (winnerOption == 0 || oracleState.currentOptions.size() < winnerOption) {
            oracleState.votingActive = false;
            oracleState.currentOptions.clear();
            VOTE_COUNTS.clear();
            PLAYER_VOTES.clear();
            oracleState.voteCounts.clear();
            oracleState.playerVotes.clear();
            oracleState.receivedVoteTicketUuids.clear();
            saveData();
            return;
        }
        
        int winner = oracleState.currentOptions.get(winnerOption - 1);
        String eventName = EVENT_NAMES.getOrDefault(winner, "Neznámý");
        server.getPlayerManager().broadcast(Text.literal("§6§l[The Oracle] §aVítězem se stává: §f" + eventName), false);

        // Zobrazení Title pro všechny hráče
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("§6§lThe Oracle")));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal("§aAktivován: §f" + eventName)));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(10, 70, 20));
        }
        
        oracleState.currentActiveEffect = winner;
        oracleState.isEventActive = true;
        // Lasts until Sunday 23:59:59
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX);
        oracleState.effectEndTime = end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        
        if (winner == 53 || winner == 55) { 
            setupBountyOrAssassin(server, winner);
        } else if (winner == 81) {
            antiSnowball(server);
        } else if (winner == 31) {
            setupSharedFate(server);
        }
        
        oracleState.recentEvents.add(winner);
        if (oracleState.recentEvents.size() > 24) oracleState.recentEvents.remove(0);
        
        saveData();
        
        // Reset votes
        PLAYER_VOTES.clear();
        VOTE_COUNTS.clear();
        oracleState.playerVotes.clear();
        oracleState.voteCounts.clear();
        oracleState.receivedVoteTicketUuids.clear();
        oracleState.votingActive = false;
        oracleState.currentOptions.clear();
        oracleState.votingEndTime = 0;
        saveData();
    }

    private static void setupSharedFate(MinecraftServer server) {
        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (players.size() < 2) return;
        Collections.shuffle(players);
        SHARED_FATE_LINKS.clear();
        for (int i = 0; i < players.size() - 1; i += 2) {
            ServerPlayerEntity p1 = players.get(i);
            ServerPlayerEntity p2 = players.get(i+1);
            SHARED_FATE_LINKS.put(p1.getUuid(), p2.getUuid());
            SHARED_FATE_LINKS.put(p2.getUuid(), p1.getUuid());
            p1.sendMessage(Text.literal("§d[The Oracle] Tvé osudy jsou nyní spojeny s hráče §e" + p2.getName().getString()), false);
            p2.sendMessage(Text.literal("§d[The Oracle] Tvé osudy jsou nyní spojeny s hráče §e" + p1.getName().getString()), false);
        }
    }

    private static void antiSnowball(MinecraftServer server) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return;
        
        ServerPlayerEntity topPlayer = null;
        double maxHP = -1;
        
        for (ServerPlayerEntity p : players) {
            double hp = p.getMaxHealth();
            if (hp > maxHP) {
                maxHP = hp;
                topPlayer = p;
            }
            if (hp <= 10.0) { // 5 hearts or fewer
                updateMaxHealth(p, 4.0);
                p.sendMessage(Text.literal("§aZískal jsi +2 srdíčka díky Anti-snowball efektu!"), false);
            }
        }
        
        if (topPlayer != null) {
            updateMaxHealth(topPlayer, -4.0);
            topPlayer.sendMessage(Text.literal("§cZtratil jsi 2 srdíčka (Anti-snowball)!"), false);
        }
    }

    private static long parseDuration(String input) {
        try {
            if (input.endsWith("d")) return Long.parseLong(input.replace("d", "")) * 86400000L;
            if (input.endsWith("h")) return Long.parseLong(input.replace("h", "")) * 3600000L;
            if (input.endsWith("m")) return Long.parseLong(input.replace("m", "")) * 60000L;
            if (input.endsWith("s")) return Long.parseLong(input.replace("s", "")) * 1000L;
            return Long.parseLong(input) * 1000L; // Default seconds
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    private static String getEventDescription(int id) {
        return switch (id) {
            case 1 -> "Nízká gravitace — Jump Boost II a Slow Falling pro všechny.";
            case 2 -> "Vysoká gravitace — Hráči jsou pomalejší a těžší.";
            case 3 -> "Kyselý déšť — Déšť tě otráví, pokud nemáš helmu.";
            case 4 -> "Léčivý déšť — Déšť léčí všechny hráče pod širým nebem.";
            case 5 -> "Věčná noc — Na serveru je neustále noc.";
            case 6 -> "Věčný den — Na serveru je neustále den.";
            case 7 -> "Kluzký svět — Všichni mají Speed II.";
            case 8 -> "Hustá mlha — Hráči trpí efektem Darkness.";
            case 9 -> "Magnetické póly — Předměty na zemi se přitahují k nejbližšímu hráči.";
            case 10 -> "Lávový oceán — Nebezpečné podmínky v jeskyních.";
            case 11 -> "Rychlý růst — Plodiny rostou rychleji.";
            case 12 -> "Ztracený kompas — Kompasy a mapy nefungují správně.";
            case 13 -> "Zlatá horečka — Moby dropují zlato.";
            case 14 -> "Diamantová inflace — Těžba je pomalejší (Mining Fatigue).";
            case 15 -> "Vesničané stávkují — Nelze obchodovat s vesničany.";
            case 16 -> "Šťastný obchodník — Hero of the Village efekt pro všechny.";
            case 17 -> "Smaragdový déšť — Při dešti padají smaragdy.";
            case 18 -> "Daň z bohatství — Občasná ztráta předmětů.";
            case 19 -> "Barter Trade — Luck efekt, lepší bartering.";
            case 20 -> "XP Bonus — Hráči získávají bonusové XP.";
            case 21 -> "Invaze obrů — Mobové se spawnují větší a silnější.";
            case 22 -> "Neviditelní creepeři — Creepeři jsou neviditelní!";
            case 23 -> "Rychlí skeletoni — Skeletoni mají Speed efekt.";
            case 24 -> "Přátelští mobi — Mobi neútočí první.";
            case 25 -> "Pavučiny — Při zásahu dostaneš Slowness.";
            case 26 -> "Explozivní smrt — Mobi po smrti vybuchnou.";
            case 27 -> "Dvojité HP bossů — Bossové mají 2× více životů.";
            case 28 -> "Hordy — Mobi se spawnují ve větších skupinách.";
            case 29 -> "Letecká podpora — Phantomové pomáhají hráčům.";
            case 30 -> "Ohnivý útok — Zásah tě zapálí.";
            case 31 -> "Sdílené zdraví — Dva náhodní hráči jsou propojeni, zranění jednoho zraní oba.";
            case 32 -> "Vampírský víkend — Útokem léčíš 20% udaného poškození (max 2 srdce na zásah).";
            case 33 -> "Křehké kosti — Pád z výšky způsobuje extra poškození.";
            case 34 -> "Speedrunner — Všichni mají Speed II.";
            case 35 -> "Těžký batoh — Hráči jsou pomalejší (Slowness).";
            case 36 -> "Náhodná teleportace — Malá šance na náhodný teleport.";
            case 37 -> "Výměna pozic — Dva náhodní hráči si občas vymění pozice.";
            case 38 -> "Bez jmen — Jména hráčů jsou skrytá.";
            case 39 -> "Pacifismus — Hráči si nemohou vzájemně ublížit.";
            case 40 -> "Berserkr — Silnější útoky, ale méně obrany.";
            case 41 -> "Vein Miner — Těžba rudy rozbije celou žílu.";
            case 42 -> "Šťastná ruda — Šance na bonus drop z uhlí.";
            case 43 -> "Nezničitelné nástroje — Nástroje se neopotřebovávají.";
            case 44 -> "Drahý crafting — Crafting stojí více surovin.";
            case 45 -> "Náhodný drop — Mobi dropují náhodné předměty.";
            case 46 -> "Silná pec — Pece pracují rychleji.";
            case 47 -> "Dřevorubec — Pokácení jednoho bloku pokácí celý strom.";
            case 48 -> "Měkký obsidián — Obsidián se těží rychleji.";
            case 49 -> "Lávový rybolov — Rybaření v lávě dává speciální loot.";
            case 50 -> "Žádný crafting — Pracovní stůl nefunguje.";
            case 51 -> "Král kopce — Bodový systém za držení pozice.";
            case 52 -> "Poslední přeživší — Speciální výzva pro přežití.";
            case 53 -> "Skull Hunter — Náhodný hráč je označen. Přežije-li, ostatní ztratí srdce.";
            case 54 -> "Sbírka — Sbírej specifické předměty za odměnu.";
            case 55 -> "Silent Mail — Tajný atentátník dostane úkol zabít cíl.";
            case 56 -> "Kuřecí déšť — Z nebe padají kuřata.";
            case 57 -> "Pružné bloky — Svět je pružnější.";
            case 58 -> "Mini hráči — Všichni jsou zmenšeni.";
            case 59 -> "Obři — Všichni jsou zvětšeni.";
            case 60 -> "Prasečí doprava — Cestování na prasatech.";
            case 61 -> "Obrácený mód — Vše je obráceně.";
            case 62 -> "Všudypřítomný zvuk — Náhodné zvuky ze všech stran.";
            case 63 -> "Barevný víkend — Svět je plný barev.";
            case 64 -> "Antigravitační šípy — Šípy ignorují gravitaci.";
            case 65 -> "TNT rybaření — Rybaření s výbušninami.";
            case 66 -> "Víkend jednoho života — Máš jen jeden pokus.";
            case 67 -> "Omezený inventář — Méně slotů v inventáři.";
            case 68 -> "Žádná regenerace — Přirozená regenerace je vypnuta.";
            case 69 -> "Toxická voda — Voda tě otráví.";
            case 70 -> "Váha brnění — Brnění tě zpomaluje.";
            case 71 -> "Dvojité zdraví — Všichni mají 2× více HP.";
            case 72 -> "Skleněný svět — Bloky se rozbíjejí snadněji.";
            case 73 -> "Ohnivzdornost — Hráči jsou imunní vůči ohni.";
            case 74 -> "Nekonečné ohňostroje — Rakety se nespotřebovávají.";
            case 75 -> "Mistr štítu — Štíty jsou silnější.";
            case 76 -> "Loot Boxy — Náhodné odměny za zabití mobů.";
            case 77 -> "Bez poškození z pádu — Žádný fall damage.";
            case 78 -> "Zombie apokalypsa — Zombie se spawnují ve velkém.";
            case 79 -> "Mob převlek — Hráči vypadají jako mobi.";
            case 80 -> "Podlaha je láva — Stání na zemi tě pálí.";
            case 81 -> "Anti Snowball — Nejsilnější hráč přijde o 2 srdce, slabí získají +1.";
            default -> "Neznámý event.";
        };
    }

    private static boolean isAdmin(com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> context) {
        return isAdminSource(context.getSource());
    }

    private static boolean isAdminSource(net.minecraft.server.command.ServerCommandSource source) {
        ServerPlayerEntity p = source.getPlayer();
        if (p == null) return true;
        return p.getPermissions().hasPermission(new net.minecraft.command.permission.Permission.Level(net.minecraft.command.permission.PermissionLevel.GAMEMASTERS));
    }

    private static void addEffect(ServerPlayerEntity p, StatusEffectInstance effect) {
        StatusEffectInstance current = p.getStatusEffect(effect.getEffectType());
        if (current == null || current.getDuration() < 100) {
            p.addStatusEffect(effect);
        }
    }

    private static boolean grantCannonAccess(MinecraftServer server, ServerPlayerEntity player) {
        String uuid = player.getUuid().toString();
        CannonAccessData data = oracleState.cannonAccess.get(uuid);
        if (data == null) {
            data = new CannonAccessData();
            data.previousGameMode = player.getGameMode().name();
            data.wasOperator = player.getPermissions().hasPermission(new net.minecraft.command.permission.Permission.Level(net.minecraft.command.permission.PermissionLevel.GAMEMASTERS));
            oracleState.cannonAccess.put(uuid, data);
        }

        player.changeGameMode(GameMode.CREATIVE);
        enforceCannonInventory(player);
        
        PlayerConfigEntry entry = new PlayerConfigEntry(player.getUuid(), player.getName().getString());
        net.minecraft.server.OperatorEntry operatorEntry = new net.minecraft.server.OperatorEntry(entry, net.minecraft.command.permission.LeveledPermissionPredicate.GAMEMASTERS, false);
        
        server.getPlayerManager().getOpList().remove(entry);
        server.getPlayerManager().getOpList().add(operatorEntry);
        
        server.getPlayerManager().sendCommandTree(player);
        saveData();
        return true;
    }

    private static boolean revokeCannonAccess(MinecraftServer server, ServerPlayerEntity player) {
        String uuid = player.getUuid().toString();
        CannonAccessData data = oracleState.cannonAccess.remove(uuid);
        if (data == null) return false;

        enforceCannonInventory(player);

        try {
            player.changeGameMode(GameMode.valueOf(data.previousGameMode));
        } catch (Exception ignored) {
            player.changeGameMode(GameMode.SURVIVAL);
        }

        PlayerConfigEntry entry = new PlayerConfigEntry(player.getUuid(), player.getName().getString());
        if (!data.wasOperator) {
            server.getPlayerManager().getOpList().remove(entry);
        }
        server.getPlayerManager().sendCommandTree(player);
        saveData();
        return true;
    }

    private static void enforceCannonInventory(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        int stickCount = 0;
        boolean needsCleanup = false;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.isOf(Items.STICK)) {
                needsCleanup = true;
                break;
            }
            stickCount += stack.getCount();
            if (stickCount > 1) {
                needsCleanup = true;
                break;
            }
        }

        if (!needsCleanup && stickCount == 1) return;

        for (int i = 0; i < inventory.size(); i++) {
            inventory.setStack(i, ItemStack.EMPTY);
        }
        inventory.setStack(0, new ItemStack(Items.STICK, 1));
        player.currentScreenHandler.sendContentUpdates();
    }

    private static void applyActiveEffectLogic(MinecraftServer server, boolean isSecond) {
        if (!oracleState.isEventActive) {
            return;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerWorld sw = (ServerWorld) p.getCommandSource().getWorld();
            int effect = oracleState.currentActiveEffect;
            
            // Periodický Anti-Snowball (81) - každých 15 minut
            if (effect == 81 && isSecond) {
                long now = System.currentTimeMillis();
                if (now - oracleState.lastAntiSnowballRun > 900000) { // 15 min
                    oracleState.lastAntiSnowballRun = now;
                    antiSnowball(server);
                }
            }

            // Attribute/HP adjustments for Double Health (71) only
            if (effect == 71) {
                EntityAttributeInstance hpAttr = p.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                if (hpAttr != null && hpAttr.getBaseValue() != 40.0) hpAttr.setBaseValue(40.0);
            }

            if (effect == 1) { // Low Gravity
                addEffect(p, new StatusEffectInstance(StatusEffects.SLOW_FALLING, 40, 0, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.JUMP_BOOST, 40, 1, false, false));
            } else if (effect == 2) { // High Gravity
                addEffect(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 2, false, false));
            } else if (effect == 3) { // Acid Rain
                if (sw.isRaining() && sw.isSkyVisible(p.getBlockPos())) {
                    if (p.getInventory().getStack(39).isEmpty()) {
                        addEffect(p, new StatusEffectInstance(StatusEffects.POISON, 80, 1));
                        if (isSecond) p.damage(sw, sw.getDamageSources().drown(), 0.5f);
                    }
                }
            } else if (effect == 4) { // Healing Drizzle
                if (sw.isRaining() && sw.isSkyVisible(p.getBlockPos())) {
                    addEffect(p, new StatusEffectInstance(StatusEffects.REGENERATION, 40, 0));
                }
            } else if (effect == 5) { // Eternal Night
                sw.setTimeOfDay(13000);
            } else if (effect == 6) { // Eternal Day
                sw.setTimeOfDay(6000);
            } else if (effect == 7) { // Slippery World
                addEffect(p, new StatusEffectInstance(StatusEffects.SPEED, 40, 1, false, false));
            } else if (effect == 8) { // Thick Fog
                addEffect(p, new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false));
            } else if (effect == 9) { // Magnetic Poles
                if (isSecond) {
                    Box box = new Box(p.getX()-10, p.getY()-10, p.getZ()-10, p.getX()+10, p.getY()+10, p.getZ()+10);
                    sw.getEntitiesByClass(ItemEntity.class, box, e -> true).forEach(item -> {
                        Vec3d pPos = new Vec3d(p.getX(), p.getY(), p.getZ());
                        Vec3d iPos = new Vec3d(item.getX(), item.getY(), item.getZ());
                        Vec3d dir = pPos.subtract(iPos).normalize().multiply(0.25);
                        item.setVelocity(item.getVelocity().add(dir));
                    });
                }
            } else if (effect == 10) { // Lava Ocean
                if (isSecond) {
                    for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                        if (pl.getY() < 30) {
                            pl.damage(sw, sw.getDamageSources().drown(), 1.0f);
                            addEffect(pl, new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
                        }
                    }
                }
            } else if (effect == 11) { // Rapid Growth
                addEffect(p, new StatusEffectInstance(StatusEffects.HASTE, 40, 0, false, false));
            } else if (effect == 12) { // Lost Compass
                addEffect(p, new StatusEffectInstance(StatusEffects.NAUSEA, 40, 0, false, false));
            } else if (effect == 14) { // Diamond Inflation - mining speed reduced
                addEffect(p, new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 40, 0, false, false));
            } else if (effect == 15) { // Villagers on Strike - no trading hint
                // Handled via UseEntityCallback check
            } else if (effect == 16) { // Happy Trader
                addEffect(p, new StatusEffectInstance(StatusEffects.HERO_OF_THE_VILLAGE, 40, 1, false, false));
            } else if (effect == 17) { // Emerald Rain
                if (sw.isRaining() && isSecond && sw.random.nextFloat() < 0.05) {
                    for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                        if (pl.getEntityWorld() == sw && !pl.isOnGround()) {
                            ItemStack emerald = new ItemStack(Items.EMERALD, sw.random.nextInt(2) + 1);
                            ItemEntity entity = new ItemEntity(sw, pl.getX(), pl.getY(), pl.getZ(), emerald);
                            sw.spawnEntity(entity);
                        }
                    }
                }
            } else if (effect == 18) { // Wealth Tax
                if (isSecond && sw.random.nextFloat() < 0.01f && removeRandomInventoryItem(p)) {
                    p.sendMessage(Text.literal("§6Daň z bohatství: ztratil jsi 1 náhodný stack."), true);
                }
            } else if (effect == 19) { // Barter Trade - Luck for bartering
                addEffect(p, new StatusEffectInstance(StatusEffects.LUCK, 40, 1, false, false));
            } else if (effect == 20) { // XP Bonus
                if (isSecond && sw.random.nextFloat() < 0.25) p.addExperience(2);
            } else if (effect == 21) { // Giant Invasion
                addEffect(p, new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 0, false, false));
            } else if (effect == 22) { // Invisible Creepers
                Box box = p.getBoundingBox().expand(24);
                sw.getEntitiesByClass(CreeperEntity.class, box, c -> c.isAlive()).forEach(c -> c.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, false, false)));
            } else if (effect == 23) { // Fast Skeletons
                Box box = p.getBoundingBox().expand(24);
                sw.getEntitiesByClass(net.minecraft.entity.mob.SkeletonEntity.class, box, s -> s.isAlive()).forEach(s -> s.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 1, false, false)));
            } else if (effect == 24) { // Friendly Mobs
                addEffect(p, new StatusEffectInstance(StatusEffects.REGENERATION, 40, 0, false, false));
            } else if (effect == 25) { // Spider Web - slowness when hit
                if (p.hurtTime > 0) {
                    addEffect(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
                }
            } else if (effect == 26) { // Explosive Death
                addEffect(p, new StatusEffectInstance(StatusEffects.GLOWING, 40, 0, false, false));
            } else if (effect == 27) { // Double Boss HP
                addEffect(p, new StatusEffectInstance(StatusEffects.STRENGTH, 40, 0, false, false));
            } else if (effect == 28) { // Hordes
                if (isSecond && sw.random.nextFloat() < 0.01) {
                    for (int i = 0; i < sw.random.nextInt(3) + 2; i++) {
                        var zombie = EntityType.ZOMBIE.create(sw, net.minecraft.entity.SpawnReason.EVENT);
                        if (zombie != null) {
                            zombie.refreshPositionAndAngles(p.getX() + sw.random.nextInt(20) - 10, p.getY(), p.getZ() + sw.random.nextInt(20) - 10, 0.0f, 0.0f);
                            sw.spawnEntity(zombie);
                        }
                    }
                }
            } else if (effect == 29) { // Air Support
                addEffect(p, new StatusEffectInstance(StatusEffects.SLOW_FALLING, 40, 0, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.NIGHT_VISION, 240, 0, false, false));
            } else if (effect == 30) { // Fire Attack
                if (p.hurtTime > 0 && !p.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
                    p.setOnFireFor(3);
                }
            } else if (effect == 31) { // Shared Health
                UUID partner = SHARED_FATE_LINKS.get(p.getUuid());
                if (partner != null) {
                    ServerPlayerEntity other = server.getPlayerManager().getPlayer(partner);
                    if (other != null && other.hurtTime > 0) {
                        p.damage(sw, sw.getDamageSources().drown(), 1.0f);
                    }
                }
            } else if (effect == 33) { // Fragile Bones
                if (p.fallDistance > 3.0) {
                    p.damage(sw, sw.getDamageSources().fall(), 10.0f);
                    p.fallDistance = 0;
                }
            } else if (effect == 34) { // Speedrunner
                addEffect(p, new StatusEffectInstance(StatusEffects.SPEED, 40, 1, false, false));
            } else if (effect == 35) { // Heavy Backpack
                addEffect(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 0, false, false));
            } else if (effect == 36) { // Random Teleportation
                if (isSecond && sw.random.nextFloat() < 0.005) {
                    double tx = p.getX() + (sw.random.nextDouble() - 0.5) * 100;
                    double tz = p.getZ() + (sw.random.nextDouble() - 0.5) * 100;
                    BlockPos safePos = findSafeTeleportPos(sw, new BlockPos((int) tx, (int) p.getY(), (int) tz));
                    if (safePos != null) {
                        p.requestTeleport(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
                        p.sendMessage(Text.literal("§dNáhodná teleportace!"), true);
                    }
                }
            } else if (effect == 37) { // Position Swap
                if (isSecond && sw.random.nextFloat() < 0.002) {
                    List<ServerPlayerEntity> others = new ArrayList<>(server.getPlayerManager().getPlayerList());
                    others.remove(p);
                    others.removeIf(other -> other.getEntityWorld() != p.getEntityWorld());
                    if (!others.isEmpty()) {
                        ServerPlayerEntity other = others.get(sw.random.nextInt(others.size()));
                        BlockPos pTarget = findSafeTeleportPos(sw, other.getBlockPos());
                        BlockPos otherTarget = findSafeTeleportPos(sw, p.getBlockPos());
                        if (pTarget != null && otherTarget != null) {
                            p.requestTeleport(pTarget.getX() + 0.5, pTarget.getY(), pTarget.getZ() + 0.5);
                            other.requestTeleport(otherTarget.getX() + 0.5, otherTarget.getY(), otherTarget.getZ() + 0.5);
                            p.sendMessage(Text.literal("§dPozice prohozena s " + other.getName().getString() + "!"), true);
                            other.sendMessage(Text.literal("§dPozice prohozena s " + p.getName().getString() + "!"), true);
                        }
                    }
                }
            } else if (effect == 38) { // No Name
                addEffect(p, new StatusEffectInstance(StatusEffects.INVISIBILITY, 40, 0, false, false));
            } else if (effect == 39) { // Pacifism
                addEffect(p, new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 2, false, false));
            } else if (effect == 40) { // Berserker
                addEffect(p, new StatusEffectInstance(StatusEffects.STRENGTH, 40, 0, false, false));
                if (isSecond) {
                    for (int i = 0; i < 4; i++) {
                        ItemStack armor = p.getInventory().getStack(36 + i);
                        if (!armor.isEmpty()) {
                            p.dropItem(armor.copy(), false);
                            armor.setCount(0);
                        }
                    }
                }
            } else if (effect == 43) { // Indestructible Tools
                addEffect(p, new StatusEffectInstance(StatusEffects.HASTE, 40, 0, false, false));
                ItemStack mainHand = p.getMainHandStack();
                if (mainHand != null && mainHand.isDamageable()) {
                    mainHand.setDamage(0);
                }
                ItemStack offHand = p.getOffHandStack();
                if (offHand != null && offHand.isDamageable()) {
                    offHand.setDamage(0);
                }
            } else if (effect == 44) { // Expensive Crafting
                addEffect(p, new StatusEffectInstance(StatusEffects.HUNGER, 40, 0, false, false));
            } else if (effect == 46) { // Strong Furnace
                addEffect(p, new StatusEffectInstance(StatusEffects.HASTE, 40, 1, false, false));
            } else if (effect == 49) { // Lava fishing
                addEffect(p, new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 40, 0, false, false));
            } else if (effect == 50) { // No Craft
                addEffect(p, new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 40, 0, false, false));
            } else if (effect == 57) { // Bouncy Blocks
                addEffect(p, new StatusEffectInstance(StatusEffects.JUMP_BOOST, 40, 0, false, false));
            } else if (effect == 60) { // Pig Transport
                addEffect(p, new StatusEffectInstance(StatusEffects.SPEED, 40, 0, false, false));
            } else if (effect == 61) { // Reverse Mode
                addEffect(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.NAUSEA, 40, 0, false, false));
            } else if (effect == 62) { // Omnipresent Sound
                if (isSecond && sw.random.nextFloat() < 0.03) {
                    sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.AMBIENT, 0.5f, 0.5f);
                }
            } else if (effect == 63) { // Colorful Weekend
                if (isSecond) {
                    double x = p.getX() + (sw.random.nextDouble() - 0.5) * 2;
                    double y = p.getY() + (sw.random.nextDouble() - 0.5) * 2;
                    double z = p.getZ() + (sw.random.nextDouble() - 0.5) * 2;
                    sw.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0, 0, 0, 0.1);
                }
            } else if (effect == 64) { // Anti-Gravity Arrows
                addEffect(p, new StatusEffectInstance(StatusEffects.SLOW_FALLING, 40, 0, false, false));
            } else if (effect == 65) { // TNT Fishing
                addEffect(p, new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 40, 0, false, false));
            } else if (effect == 66) { // One Life Weekend
                addEffect(p, new StatusEffectInstance(StatusEffects.GLOWING, 40, 0, false, false));
            } else if (effect == 51) { // King of the Hill
                addEffect(p, new StatusEffectInstance(StatusEffects.STRENGTH, 40, 0, false, false));
            } else if (effect == 52) { // Last Survivor
                if (p.getHealth() <= 8.0f) {
                    addEffect(p, new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 1, false, false));
                } else {
                    addEffect(p, new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 0, false, false));
                }
            } else if (effect == 54) { // Collection
                if (isSecond && hasItems(p, Items.ROTTEN_FLESH, 1) && hasItems(p, Items.BONE, 1) && hasItems(p, Items.STRING, 1) && hasItems(p, Items.GUNPOWDER, 1)) {
                    removeItems(p, Items.ROTTEN_FLESH, 1);
                    removeItems(p, Items.BONE, 1);
                    removeItems(p, Items.STRING, 1);
                    removeItems(p, Items.GUNPOWDER, 1);
                    ItemStack reward = new ItemStack(Items.EMERALD, 2);
                    if (!p.getInventory().insertStack(reward.copy())) p.dropItem(reward, false);
                    p.addExperience(5);
                    p.sendMessage(Text.literal("§aCollection splněna: +2 Emeraldy a +5 XP."), true);
                }
            } else if (effect == 58) { // Mini-Players
                EntityAttributeInstance scaleAttr = p.getAttributeInstance(EntityAttributes.SCALE);
                if (scaleAttr != null && scaleAttr.getBaseValue() != 0.5) scaleAttr.setBaseValue(0.5);
            } else if (effect == 59) { // Giants
                EntityAttributeInstance scaleAttr = p.getAttributeInstance(EntityAttributes.SCALE);
                if (scaleAttr != null && scaleAttr.getBaseValue() != 2.0) scaleAttr.setBaseValue(2.0);
            } else if (effect == 67) { // Limited Inventory - slowness when full
                int usedSlots = 0;
                for (int i = 0; i < 36; i++) {
                    if (!p.getInventory().getStack(i).isEmpty()) usedSlots++;
                }
                if (usedSlots > 18) {
                    addEffect(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 40, usedSlots > 27 ? 2 : 1, false, false));
                }
            } else if (effect == 68) { // No Regeneration
                if (p.hasStatusEffect(StatusEffects.REGENERATION)) {
                    p.removeStatusEffect(StatusEffects.REGENERATION);
                }
                if (isSecond && p.getHealth() > p.getMaxHealth()) {
                    p.setHealth(p.getMaxHealth());
                }
            } else if (effect == 69) { // Toxic Water
                if (p.isSubmergedInWater() || p.isTouchingWater()) {
                    addEffect(p, new StatusEffectInstance(StatusEffects.POISON, 80, 1));
                    if (isSecond) p.damage(sw, sw.getDamageSources().drown(), 0.5f);
                }
            } else if (effect == 70) { // Armor Weight
                int armorPieces = 0;
                for (int i = 0; i < 4; i++) {
                    if (!p.getInventory().getStack(36 + i).isEmpty()) armorPieces++;
                }
                if (armorPieces > 0) {
                    addEffect(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 40, armorPieces - 1, false, false));
                }
            } else if (effect == 71) { // Double Health
                // Handled via attribute update above
            } else if (effect == 72) { // Glass World
                // Blocks break easily + extra damage taken
                if (isSecond && sw.random.nextFloat() < 0.2 && p.hurtTime > 0) {
                    p.damage(sw, sw.getDamageSources().generic(), 1.0f);
                }
            } else if (effect == 73) { // Fireproof
                addEffect(p, new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 40, 0, false, false));
            } else if (effect == 75) { // Shield Master
                addEffect(p, new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 1, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.ABSORPTION, 40, 0, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.STRENGTH, 40, 0, false, false));
            } else if (effect == 74) { // Infinite Fireworks
                addEffect(p, new StatusEffectInstance(StatusEffects.LUCK, 40, 0, false, false));
            } else if (effect == 76) { // Loot Boxes
                if (isSecond && sw.random.nextFloat() < 0.005) {
                    Item[] lootItems = {Items.DIAMOND, Items.EMERALD, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.IRON_INGOT, Items.GOLD_INGOT};
                    ItemStack loot = new ItemStack(lootItems[sw.random.nextInt(lootItems.length)], sw.random.nextInt(3) + 1);
                    if (!p.getInventory().insertStack(loot.copy())) p.dropItem(loot, false);
                    p.sendMessage(Text.literal("§6Loot Box! Získal jsi " + loot.getCount() + "x " + loot.getName().getString()), true);
                }
            } else if (effect == 77) { // No Fall Damage
                p.fallDistance = 0;
            } else if (effect == 79) { // Mob Disguise
                addEffect(p, new StatusEffectInstance(StatusEffects.INVISIBILITY, 40, 0, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.GLOWING, 40, 0, false, false));
                addEffect(p, new StatusEffectInstance(StatusEffects.SPEED, 40, 1, false, false));
            } else if (effect == 80) { // The Floor is Lava
                if (isSecond && p.isOnGround() && !p.isSneaking()) {
                    p.setOnFireFor(3);
                }
            }
        }
    }

    private static void loadData() {
        try {
            dataPath = FabricLoader.getInstance().getConfigDir().resolve("lifesteal_passwords.json");
            oraclePath = FabricLoader.getInstance().getConfigDir().resolve("lifesteal_oracle.json");
            PASSWORDS.clear();
            if (Files.exists(dataPath)) {
                String json = new String(Files.readAllBytes(dataPath), StandardCharsets.UTF_8);
                java.lang.reflect.Type type = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> loaded = GSON.fromJson(json, type);
                if (loaded != null) {
                    for (Map.Entry<String, String> e : loaded.entrySet()) {
                        try {
                            UUID id = UUID.fromString(e.getKey());
                            PASSWORDS.put(id, e.getValue());
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (Files.exists(oraclePath)) {
                String json = new String(Files.readAllBytes(oraclePath), StandardCharsets.UTF_8);
                oracleState = GSON.fromJson(json, OracleState.class);
            }

            if (oracleState == null) {
                oracleState = new OracleState();
            }
            normalizeOracleState();

            // Sync persisted bans into runtime maps
            BANNED_PLAYERS.clear();
            BANNED_PLAYERS.putAll(oracleState.bannedPlayers);
            BAN_REASONS.clear();
            BAN_REASONS.putAll(oracleState.banReasons);
            MUTED_PLAYERS.clear();
            for (Map.Entry<String, Long> entry : oracleState.mutedPlayers.entrySet()) {
                try {
                    MUTED_PLAYERS.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (Exception ignored) {
                }
            }
            syncVoteRuntimeFromState();
        } catch (IOException e) {
            LOGGER.error("Chyba při načítání dat: ", e);
        }
    }

    private static void saveData() {
        if (dataPath == null) dataPath = FabricLoader.getInstance().getConfigDir().resolve("lifesteal_passwords.json");
        if (oraclePath == null) oraclePath = FabricLoader.getInstance().getConfigDir().resolve("lifesteal_oracle.json");

        Map<String, String> passwordSnapshot = new HashMap<>();
        synchronized (PASSWORDS) {
            for (Map.Entry<UUID, String> entry : PASSWORDS.entrySet()) {
                passwordSnapshot.put(entry.getKey().toString(), entry.getValue());
            }
        }

        Map<String, Long> bannedSnapshot = new HashMap<>(BANNED_PLAYERS);
        Map<String, String> banReasonSnapshot = new HashMap<>(BAN_REASONS);

        Map<String, Long> mutedSnapshot = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : MUTED_PLAYERS.entrySet()) {
            mutedSnapshot.put(entry.getKey().toString(), entry.getValue());
        }

        Map<String, Integer> playerVotesSnapshot = new HashMap<>();
        synchronized (PLAYER_VOTES) {
            for (Map.Entry<UUID, Integer> entry : PLAYER_VOTES.entrySet()) {
                playerVotesSnapshot.put(entry.getKey().toString(), entry.getValue());
            }
        }

        Map<Integer, Integer> voteCountsSnapshot;
        synchronized (VOTE_COUNTS) {
            voteCountsSnapshot = new HashMap<>(VOTE_COUNTS);
        }

        String oracleJson;
        synchronized (oracleState) {
            normalizeOracleState();
            oracleState.bannedPlayers.clear();
            oracleState.bannedPlayers.putAll(bannedSnapshot);

            oracleState.banReasons.clear();
            oracleState.banReasons.putAll(banReasonSnapshot);

            oracleState.mutedPlayers.clear();
            oracleState.mutedPlayers.putAll(mutedSnapshot);

            oracleState.playerVotes.clear();
            oracleState.playerVotes.putAll(playerVotesSnapshot);

            oracleState.voteCounts.clear();
            oracleState.voteCounts.putAll(voteCountsSnapshot);

            oracleState.playerAchievements.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
            oracleJson = GSON.toJson(oracleState);
        }

        String passwordsJson = GSON.toJson(passwordSnapshot);

        CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(dataPath, passwordsJson, StandardCharsets.UTF_8);
                Files.writeString(oraclePath, oracleJson, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("Chyba při ukládání dat: ", e);
            }
        });
    }

    private static void breakTree(World world, BlockPos pos, Set<BlockPos> visited) {
        if (visited.size() > 256 || visited.contains(pos)) return;
        visited.add(pos);
        if (world.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
            world.breakBlock(pos, true);
            for (BlockPos neighbor : new BlockPos[]{pos.up(), pos.down(), pos.north(), pos.south(), pos.east(), pos.west()}) {
                breakTree(world, neighbor, visited);
            }
        }
    }

    private static void breakVein(World world, BlockPos pos, net.minecraft.block.Block target, Set<BlockPos> visited, int depth) {
        if (depth > 64 || visited.contains(pos)) return;
        visited.add(pos);
        if (world.getBlockState(pos).isOf(target)) {
            world.breakBlock(pos, true);
            for (BlockPos neighbor : new BlockPos[]{pos.up(), pos.down(), pos.north(), pos.south(), pos.east(), pos.west()}) {
                breakVein(world, neighbor, target, visited, depth + 1);
            }
        }
    }

    private static boolean isInsideEndShip(World world, BlockPos pos) {
        if (!world.getRegistryKey().equals(World.END)) return false;
        // Hledání Item Framu s Elytrou v okolí 50 bloků
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(pos).expand(50);
        java.util.List<ItemFrameEntity> frames = world.getEntitiesByClass(ItemFrameEntity.class, box, f -> f.getHeldItemStack().isOf(Items.ELYTRA));
        for (ItemFrameEntity frame : frames) {
            String key = world.getRegistryKey().getValue().toString() + "|" + frame.getBlockPos().toShortString();
            if (!oracleState.unlockedFrames.contains(key)) {
                // Pokud je v lodi (cca 30 bloků od framu)
                if (pos.getSquaredDistance(frame.getBlockPos()) < 30 * 30) return true;
            }
        }
        return false;
    }

    private static String hashPassword(UUID uuid, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salted = uuid.toString() + ":" + password;
            byte[] digest = md.digest(salted.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            LOGGER.error("Password hashing failed.", e);
            return "";
        }
    }
}
