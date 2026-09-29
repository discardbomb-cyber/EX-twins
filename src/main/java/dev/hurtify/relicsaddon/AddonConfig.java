package dev.hurtify.relicsaddon;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned limits; per-item preferences cannot exceed these values. */
public final class AddonConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue SHIELD_MAX_RADIUS;
    public static final ModConfigSpec.BooleanValue HIVE_INTERCEPTION;
    public static final ModConfigSpec.DoubleValue HIVE_TARGET_RANGE;
    public static final ModConfigSpec.DoubleValue HIVE_PURSUIT_RANGE;
    public static final ModConfigSpec.DoubleValue HIVE_HEAL_PER_SECOND;
    public static final ModConfigSpec.BooleanValue POWER_REQUIRED;
    public static final ModConfigSpec.IntValue XP_POINT_VALUE;
    public static final ModConfigSpec.IntValue XP_RESERVE_LEVELS;
    public static final ModConfigSpec.DoubleValue PLAYER_MANA_VALUE;
    public static final ModConfigSpec.DoubleValue PLAYER_MANA_RESERVE;
    public static final ModConfigSpec.IntValue BOTANIA_MANA_PER_POINT;
    static {
        var builder = new ModConfigSpec.Builder();
        SHIELD_MAX_RADIUS = builder.defineInRange("shield.maxRadius", 12.0, 2.0, 24.0);
        HIVE_INTERCEPTION = builder.define("hive.interceptProjectiles", true);
        HIVE_TARGET_RANGE = builder.defineInRange("hive.targetRange", 16.0, 4.0, 32.0);
        HIVE_PURSUIT_RANGE = builder.defineInRange("hive.pursuitRange", 24.0, 4.0, 48.0);
        HIVE_HEAL_PER_SECOND = builder.defineInRange("hive.maxHealingPerSecond", 4.0, 0.0, 20.0);
        POWER_REQUIRED = builder.comment("Shields and hives run on their built-in RF and mana batteries. Disable to make them free.")
                .define("power.requireBatteries", true);
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
        SPEC = builder.build();
    }
    private AddonConfig() { }
}
