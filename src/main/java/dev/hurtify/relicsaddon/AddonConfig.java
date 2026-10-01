package dev.hurtify.relicsaddon;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned limits; per-item preferences cannot exceed these values. */
public final class AddonConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue SHIELD_MAX_RADIUS;
    public static final ModConfigSpec.DoubleValue SHIELD_STRIKE_DAMAGE;
    public static final ModConfigSpec.DoubleValue SHIELD_STRIKE_KNOCKBACK;
    public static final ModConfigSpec.IntValue SHIELD_STRIKE_COOLDOWN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHIELD_PASSING_DAMAGE_TYPES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHIELD_ABSORBED_DAMAGE_TYPES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHIELD_INTERCEPTED_PROJECTILES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHIELD_IGNORED_PROJECTILES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHIELD_KEPT_EFFECTS;
    public static final ModConfigSpec.DoubleValue HIVE_TARGET_RANGE;
    public static final ModConfigSpec.DoubleValue HIVE_PURSUIT_RANGE;
    public static final ModConfigSpec.DoubleValue HIVE_HEAL_PER_SECOND;
    public static final ModConfigSpec.DoubleValue HIVE_STRIKE_EFFICIENCY;
    public static final ModConfigSpec.BooleanValue POWER_REQUIRED;
    public static final ModConfigSpec.BooleanValue ARMAGEDDON_SAFE;
    public static final ModConfigSpec.IntValue XP_POINT_VALUE;
    public static final ModConfigSpec.IntValue XP_RESERVE_LEVELS;
    public static final ModConfigSpec.DoubleValue PLAYER_MANA_VALUE;
    public static final ModConfigSpec.DoubleValue PLAYER_MANA_RESERVE;
    public static final ModConfigSpec.IntValue BOTANIA_MANA_PER_POINT;
    public static final ModConfigSpec.IntValue XP_PER_MINUTE;
    public static final ModConfigSpec.IntValue SHIP_STATIC_RADIUS;
    public static final ModConfigSpec.IntValue SHIP_MAX_STRUCTURE_BLOCKS;
    public static final ModConfigSpec.IntValue SHIP_DRONES_PER_64_BLOCKS;
    public static final ModConfigSpec.IntValue SHIP_MANA_CELL_POINTS;
    public static final ModConfigSpec.IntValue SHIP_SHELL_OFFSET;
    public static final ModConfigSpec.IntValue SHIP_SHELL_CELLS;
    public static final ModConfigSpec.IntValue SHIP_SHELL_REBUILD_DELAY;
    public static final ModConfigSpec.IntValue SHIP_LEVELS_PER_LAYER;
    public static final ModConfigSpec.IntValue SHIP_PATCH_RF, SHIP_PATCH_MANA, SHIP_PATCH_TWINS;
    public static final ModConfigSpec.IntValue SHIP_SPREAD_RINGS;
    public static final ModConfigSpec.IntValue SHIP_OVERLOAD_RESTART;
    public static final ModConfigSpec.IntValue SHIP_PATCH_REPAIR_INTERVAL;
    public static final ModConfigSpec.IntValue SHIP_DRONE_CHARGE_TICKS;
    public static final ModConfigSpec.IntValue SHIP_DRONE_FLIGHT_COST;
    public static final ModConfigSpec.IntValue SHIP_REPAIR_PAUSE;
    public static final ModConfigSpec.IntValue SHIP_REPAIR_BLOCK_COST;
    public static final ModConfigSpec.DoubleValue SHIP_RANGE;
    public static final ModConfigSpec.BooleanValue SHIP_TARGET_PLAYERS;
    public static final ModConfigSpec.DoubleValue LANCE_DAMAGE;
    public static final ModConfigSpec.IntValue LANCE_ENERGY;
    public static final ModConfigSpec.IntValue AEGIS_UPKEEP;
    public static final ModConfigSpec.IntValue AEGIS_ENERGY_PER_POINT;
    public static final ModConfigSpec.DoubleValue ESCORT_LEASH;
    public static final ModConfigSpec.DoubleValue ESCORT_DAMAGE;
    /** Cached matchers over the shield lists above. */
    public static final RegistryFilter<DamageType> PASSING_DAMAGE;
    public static final RegistryFilter<DamageType> ABSORBED_DAMAGE;
    public static final RegistryFilter<EntityType<?>> INTERCEPTED_PROJECTILES;
    public static final RegistryFilter<EntityType<?>> IGNORED_PROJECTILES;
    public static final RegistryFilter<MobEffect> KEPT_EFFECTS;
    private static final List<RegistryFilter<?>> FILTERS;
    static {
        var builder = new ModConfigSpec.Builder();
        SHIELD_MAX_RADIUS = builder.defineInRange("shield.maxRadius", 12.0, 2.0, 24.0);
        SHIELD_STRIKE_DAMAGE = builder.comment("Multiplier for the damage a shield deals to hostile mobs it throws back. 0 only pushes them.")
                .defineInRange("shield.strikeDamage", 1.0, 0.0, 10.0);
        SHIELD_STRIKE_KNOCKBACK = builder.comment("Multiplier for how far a shield throws hostile mobs back. 0 only holds them at the surface.")
                .defineInRange("shield.strikeKnockback", 1.0, 0.0, 5.0);
        SHIELD_STRIKE_COOLDOWN = builder.comment("Ticks before a shield can strike the same mob again.")
                .defineInRange("shield.strikeCooldownTicks", 20, 5, 200);
        SHIELD_PASSING_DAMAGE_TYPES = builder.comment(
                        "Damage types that always pass the field and reach the wearer, on top of the data tag relics_addon:shield_passes",
                        "(starvation, drowning, suffocation, the void). Damage type ids or #tags, e.g. [\"minecraft:fall\", \"#minecraft:is_fire\"].")
                .defineListAllowEmpty("shield.passingDamageTypes", List::of, () -> "", AddonConfig::isString);
        SHIELD_ABSORBED_DAMAGE_TYPES = builder.comment(
                        "Damage types the field absorbs although relics_addon:shield_passes lets them through, e.g. [\"minecraft:drown\"].",
                        "passingDamageTypes wins over this list, and shield strikes and swarm blows always pass. Damage type ids or #tags.")
                .defineListAllowEmpty("shield.absorbedDamageTypes", List::of, () -> "", AddonConfig::isString);
        SHIELD_INTERCEPTED_PROJECTILES = builder.comment(
                        "Projectiles the field stops in flight besides arrows and the tag relics_addon:shield_interceptable_projectiles,",
                        "e.g. [\"minecraft:snowball\"] or a mod's bullet. Tridents, pearls and potions listed here are stopped too. Entity type ids or #tags.")
                .defineListAllowEmpty("shield.interceptedProjectiles", List::of, () -> "", AddonConfig::isString);
        SHIELD_IGNORED_PROJECTILES = builder.comment(
                        "Projectiles the field never stops in flight, whatever the other rules say. Their hits are still absorbed at the",
                        "wearer unless their damage type passes the field (passingDamageTypes). Entity type ids or #tags.")
                .defineListAllowEmpty("shield.ignoredProjectiles", List::of, () -> "", AddonConfig::isString);
        SHIELD_KEPT_EFFECTS = builder.comment(
                        "Harmful effects the field never cuts off or trims when an attacker applies them, e.g. [\"minecraft:poison\"].",
                        "Mob effect ids or #tags.")
                .defineListAllowEmpty("shield.keptEffects", List::of, () -> "", AddonConfig::isString);
        HIVE_TARGET_RANGE = builder.comment("Blocks from the owner within which a hive picks up a target.")
                .defineInRange("hive.targetRange", 128.0, 4.0, 256.0);
        HIVE_PURSUIT_RANGE = builder.comment("Blocks from the owner a hive keeps chasing its target.")
                .defineInRange("hive.pursuitRange", 128.0, 4.0, 256.0);
        HIVE_STRIKE_EFFICIENCY = builder.comment("Share of each drone's damage that goes into its group's blow or charge.")
                .defineInRange("hive.strikeEfficiency", 0.5, 0.0, 10.0);
        HIVE_HEAL_PER_SECOND = builder.defineInRange("hive.maxHealingPerSecond", 4.0, 0.0, 20.0);
        POWER_REQUIRED = builder.comment("Shields and hives run on their built-in RF and mana batteries. Disable to make them free.")
                .define("power.requireBatteries", true);
        ARMAGEDDON_SAFE = builder.comment("When true, Armageddon breaks no blocks: its black hole and blast still strike creatures but leave the land whole.")
                .define("armageddon.safeMode", false);
        XP_POINT_VALUE = builder.comment("Battery charge gained from one player experience point.")
                .defineInRange("power.experiencePointValue", 10, 1, 1000);
        XP_RESERVE_LEVELS = builder.comment("Experience levels a mana battery never draws below.")
                .defineInRange("power.experienceReserveLevels", 0, 0, 1000);
        PLAYER_MANA_VALUE = builder.comment("Battery charge gained from one point of a player's own mana (Ars Nouveau, Iron's Spells).")
                .defineInRange("power.playerManaValue", 5.0, 0.01, 1000.0);
        PLAYER_MANA_RESERVE = builder.comment("Share of a player's own mana pool that batteries leave untouched for spellcasting.")
                .defineInRange("power.playerManaReserve", 0.25, 0.0, 1.0);
        BOTANIA_MANA_PER_POINT = builder.comment("Botania mana taken from mana tablets and similar items per point of battery charge.")
                .defineInRange("power.botaniaManaPerPoint", 10, 1, 10000);
        XP_PER_MINUTE = builder.comment("Most device experience one shield or hive can earn per minute of combat.")
                .defineInRange("progression.maxExperiencePerMinute", 30, 1, 100000);
        SHIP_STATIC_RADIUS = builder.comment("Without Create Aeronautics a ship device counts the connected solid blocks around it as its structure,",
                        "no further than this many blocks away on any axis.")
                .defineInRange("shipShield.staticStructureRadius", 32, 4, 128);
        SHIP_MAX_STRUCTURE_BLOCKS = builder.comment("Most blocks a structure scan counts; larger builds are truncated and reported as such.")
                .defineInRange("shipShield.maxStructureBlocks", 4096, 64, 65536);
        SHIP_DRONES_PER_64_BLOCKS = builder.comment("Emitter drones a structure needs for every 64 of its blocks (rounded up).")
                .defineInRange("shipShield.dronesPer64Blocks", 8, 1, 64);
        SHIP_MANA_CELL_POINTS = builder.comment("Mana battery points a ship device gains from one Mana Cell taken out of a linked item store.")
                .defineInRange("shipShield.manaCellPoints", 12500, 100, 1000000);
        SHIP_SHELL_OFFSET = builder.comment("Blocks between a ship's blocks and its shield's innermost shell; each further layer stands one block further out.")
                .defineInRange("shipShield.offset", 2, 1, 6);
        SHIP_SHELL_CELLS = builder.comment("Most cells (quads) a shell is traced with; a larger ship gets a coarser shell.")
                .defineInRange("shipShield.maxShellCells", 4096, 64, 16384);
        SHIP_SHELL_REBUILD_DELAY = builder.comment("Ticks a generator waits after its structure changed before it traces the shell again (off the server thread).")
                .defineInRange("shipShield.rebuildDelayTicks", 40, 0, 1200);
        SHIP_LEVELS_PER_LAYER = builder.comment("Device levels per shield layer: a generator has one layer, plus one for every this many levels, three at most.")
                .defineInRange("shipShield.levelsPerLayer", 4, 1, 10);
        SHIP_PATCH_RF = builder.comment("Damage one emitter's patch of an RF ship shield holds at level 0; every level adds a tenth.")
                .defineInRange("shipShield.patchIntegrity.rf", 40, 1, 100000);
        SHIP_PATCH_MANA = builder.comment("The same for a Mana ship shield.")
                .defineInRange("shipShield.patchIntegrity.mana", 60, 1, 100000);
        SHIP_PATCH_TWINS = builder.comment("The same for an Ex-Twins ship shield.")
                .defineInRange("shipShield.patchIntegrity.twins", 50, 1, 100000);
        SHIP_SPREAD_RINGS = builder.comment("Rings of neighbouring patches a blow spreads to on one layer before it goes on to the layer within.")
                .defineInRange("shipShield.spreadRings", 2, 0, 8);
        SHIP_OVERLOAD_RESTART = builder.comment("Ticks an overloaded ship shield stays down before it comes up again.")
                .defineInRange("shipShield.overloadRestartTicks", 600, 20, 72000);
        SHIP_PATCH_REPAIR_INTERVAL = builder.comment("Ticks between a ship shield winning back one point on each damaged patch, once it has been quiet for two seconds.")
                .defineInRange("shipShield.patchRepairIntervalTicks", 20, 1, 1200);
        SHIP_DRONE_CHARGE_TICKS = builder.comment("Ticks a dock takes to charge an emitter drone from empty to full.")
                .defineInRange("shipShield.droneChargeTicks", 200, 20, 72000);
        SHIP_DRONE_FLIGHT_COST = builder.comment("Battery points a dock spends to send a drone out or call it home.")
                .defineInRange("shipShield.droneFlightCost", 20, 0, 100000);
        SHIP_REPAIR_PAUSE = builder.comment("Ticks after the shield was last hit during which the dock repairs no blocks.")
                .defineInRange("shipShield.repairPauseTicks", 200, 0, 72000);
        SHIP_REPAIR_BLOCK_COST = builder.comment("Battery points a dock spends to put one block of the ship back.")
                .defineInRange("shipShield.repairBlockCost", 50, 0, 100000);
        SHIP_RANGE = builder.comment("Blocks round a ship (or round a hive off a ship) within which its hives take on threats.")
                .defineInRange("ship.targetRange", 48.0, 8.0, 128.0);
        SHIP_TARGET_PLAYERS = builder.comment("When true, ship hives also fire on players outside the owner's team who have not attacked the ship.",
                        "Players who hurt the ship's crew are always fought back while PvP is on.")
                .define("ship.targetPlayers", false);
        LANCE_DAMAGE = builder.comment("Damage a lance hive's beam deals every quarter of a second.")
                .defineInRange("ship.lanceDamage", 3.0, 0.0, 100.0);
        LANCE_ENERGY = builder.comment("FE a lance hive draws each tick its beam burns.")
                .defineInRange("ship.lanceEnergyPerTick", 100, 0, 100000);
        AEGIS_UPKEEP = builder.comment("FE an aegis hive draws each tick its shield stands.")
                .defineInRange("ship.aegisUpkeep", 30, 0, 100000);
        AEGIS_ENERGY_PER_POINT = builder.comment("FE an aegis hive spends to win back one point of its shield's charge (a full shield holds 400).")
                .defineInRange("ship.aegisEnergyPerPoint", 150, 0, 100000);
        ESCORT_LEASH = builder.comment("Farthest an escort hive's wings chase a threat from their ship's middle, in blocks.")
                .defineInRange("ship.escortLeash", 40.0, 8.0, 128.0);
        ESCORT_DAMAGE = builder.comment("Damage of an escort wing's arc; its little drones' dives deal a third of it.")
                .defineInRange("ship.escortDamage", 4.0, 0.0, 100.0);
        SPEC = builder.build();
        PASSING_DAMAGE = new RegistryFilter<>(SPEC, Registries.DAMAGE_TYPE, SHIELD_PASSING_DAMAGE_TYPES);
        ABSORBED_DAMAGE = new RegistryFilter<>(SPEC, Registries.DAMAGE_TYPE, SHIELD_ABSORBED_DAMAGE_TYPES);
        INTERCEPTED_PROJECTILES = new RegistryFilter<>(SPEC, Registries.ENTITY_TYPE, SHIELD_INTERCEPTED_PROJECTILES);
        IGNORED_PROJECTILES = new RegistryFilter<>(SPEC, Registries.ENTITY_TYPE, SHIELD_IGNORED_PROJECTILES);
        KEPT_EFFECTS = new RegistryFilter<>(SPEC, Registries.MOB_EFFECT, SHIELD_KEPT_EFFECTS);
        FILTERS = List.of(PASSING_DAMAGE, ABSORBED_DAMAGE, INTERCEPTED_PROJECTILES, IGNORED_PROJECTILES, KEPT_EFFECTS);
    }

    /** Parses the shield lists as the server config is read, so bad entries show up in the log at startup. */
    public static void onConfigLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) FILTERS.forEach(RegistryFilter::refresh);
    }

    // Any text survives the config's own correction; RegistryFilter reports malformed entries instead of dropping them.
    private static boolean isString(Object entry) {
        return entry instanceof String;
    }

    private AddonConfig() { }
}
