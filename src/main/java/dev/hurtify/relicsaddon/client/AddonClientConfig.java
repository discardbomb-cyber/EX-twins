package dev.hurtify.relicsaddon.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player visual preferences; never read on a dedicated server. */
public final class AddonClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SHIELD_REFRACTION = BUILDER
            .comment("Screen-space refraction along the hit wave of Mana and Twins shields.",
                    "Disabled automatically while an Iris/Oculus shader pack is active.")
            .define("shield.refraction", true);
    public static final ModConfigSpec.DoubleValue SHIELD_RIPPLE_STRENGTH = BUILDER
            .comment("Scale of the geometric hit wave on Mana and Twins shields. 0 disables it.")
            .defineInRange("shield.rippleStrength", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue SHIELD_REFRACTION_STRENGTH = BUILDER
            .comment("How strongly the refraction band bends the image behind the wave.")
            .defineInRange("shield.refractionStrength", 1.0, 0.0, 3.0);

    public static final ModConfigSpec.DoubleValue SHIELD_IDLE_OPACITY = BUILDER
            .comment("How visible a shield is when nothing is attacking (0 hides it until a hit, 1 is as bright as in combat).")
            .defineInRange("shield.idleOpacity", 0.0, 0.0, 1.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    static double idleOpacity() {
        return SPEC.isLoaded() ? SHIELD_IDLE_OPACITY.get() : 0;
    }

    static double rippleStrength() {
        return SPEC.isLoaded() ? SHIELD_RIPPLE_STRENGTH.get() : 1.0;
    }

    static boolean refraction() {
        return !SPEC.isLoaded() || SHIELD_REFRACTION.get();
    }

    static double refractionStrength() {
        return SPEC.isLoaded() ? SHIELD_REFRACTION_STRENGTH.get() : 1.0;
    }

    private AddonClientConfig() {
    }
}
