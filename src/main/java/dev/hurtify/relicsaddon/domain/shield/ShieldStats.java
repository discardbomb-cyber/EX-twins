package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.device.DeviceStat;
import dev.hurtify.relicsaddon.domain.device.DeviceStats;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import java.util.Optional;

/** A shield's numbers by level: buffer, integrity, radius and the strike it deals. */
public final class ShieldStats {
    /** Shared buffer capacity: 504 at level 0 up to 5 000 at level 10. */
    public static int capacity(int level) {
        return (int) Math.round(DeviceStats.stat(Optional.empty(), level, DeviceStat.BUFFER_CAPACITY, 504, 504, 5500));
    }

    /** The buffer plus every cell at full health. */
    public static int totalCapacity(int level) {
        return capacity(level) + ShieldTopology.CELL_COUNT * ShieldStackState.MAX_PANEL_INTEGRITY;
    }

    /** The largest radius the level allows: 2 blocks plus one per level. */
    public static double radiusLimit(int level) {
        return DeviceStats.stat(Optional.empty(), level, DeviceStat.RADIUS, 2, 2, 24);
    }

    /** The largest radius the wearer may choose; the server's limit is read by the caller. */
    public static double maxRadius(double configLimit, int level) {
        return Math.min(configLimit, radiusLimit(level));
    }

    /** The field's radius: the chosen one, within the maximum. */
    public static double radius(double maxRadius, ShieldSettings settings) {
        return Math.min(maxRadius, settings.radius());
    }

    /**
     * Damage the shell deals to a hostile mob it throws back: RF 3 to 7, Mana 2.5 to 6, Twins 4 to 9
     * from level 0 to 10, times the server multiplier. Nothing for a device that is not a shield.
     */
    public static float strikeDamage(Optional<RelicRole> role, int level, double multiplier) {
        if (role.isEmpty() || !role.get().isShield()) return 0;
        double base = switch (role.get()) {
            case MANA_SHIELD -> 2.5 + .35 * level;
            case TWINS_SHIELD -> 4 + .5 * level;
            default -> 3 + .4 * level;
        };
        return (float) (base * multiplier);
    }

    /** Knockback strength of a strike, in vanilla units (a plain melee hit is 0.4), times the server multiplier. */
    public static double strikeKnockback(RelicRole role, double multiplier) {
        double base = switch (role) {
            case MANA_SHIELD -> .8;
            case TWINS_SHIELD -> 1.2;
            default -> 1.0;
        };
        return base * multiplier;
    }

    private ShieldStats() { }
}
