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
