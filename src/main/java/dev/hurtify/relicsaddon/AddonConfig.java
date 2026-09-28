package dev.hurtify.relicsaddon;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned limits; per-item preferences cannot exceed these values. */
public final class AddonConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue SHIELD_MAX_RADIUS;
    public static final ModConfigSpec.BooleanValue HIVE_INTERCEPTION;
    public static final ModConfigSpec.DoubleValue HIVE_TARGET_RANGE;
    public static final ModConfigSpec.DoubleValue HIVE_PURSUIT_RANGE;
    static {
        var builder = new ModConfigSpec.Builder();
        SHIELD_MAX_RADIUS = builder.defineInRange("shield.maxRadius", 12.0, 2.0, 24.0);
        HIVE_INTERCEPTION = builder.define("hive.interceptProjectiles", true);
        HIVE_TARGET_RANGE = builder.defineInRange("hive.targetRange", 16.0, 4.0, 32.0);
        HIVE_PURSUIT_RANGE = builder.defineInRange("hive.pursuitRange", 24.0, 4.0, 48.0);
        SPEC = builder.build();
    }
    private AddonConfig() { }
}
