package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.relic.RelicRole;

/**
 * Hive families. A player runs one hive at a time; its swarm grows to {@link #MAX_DRONES} drones, of
 * which at most {@link #MAX_DEPLOYED} fly at once while the rest wait in the hive as replacements.
 */
public enum HiveType {
    RF(RelicRole.RF_HIVE, RelicRole.RF_DRONE, Counts.INITIAL, 80, 40, 2, 3),
    MANA(RelicRole.MANA_HIVE, RelicRole.MANA_DRONE, Counts.INITIAL, 50, 20, 2, 3),
    TWINS(RelicRole.TWINS_HIVE, RelicRole.TWINS_DRONE, Counts.INITIAL, 120, 60, 3, 4);

    public static final int MAX_DRONES = 2000;
    /** Drones in a level 0 hive. */
    public static final int INITIAL_DRONES = Counts.INITIAL;
    public static final int MAX_DEPLOYED = 250;
    /** Every drone can take three hits' worth of damage; any damage sends it home for repair. */
    public static final int DRONE_HP = 3;
    public final RelicRole role, drone;
    /** Starting swarm size, and ticks to rebuild a destroyed drone at level 0 and level 10. */
    public final int initialCount, initialCooldown, minCooldown;
    /** Damage one drone adds to its group's blow, at level 0 and level 10. */
    public final int initialAttackDamage, maxAttackDamage;

    HiveType(RelicRole role, RelicRole drone, int count, int cooldown, int minCooldown, int initialAttackDamage, int maxAttackDamage) {
        this.role = role;
        this.drone = drone;
        this.initialCount = count;
        this.initialCooldown = cooldown;
        this.minCooldown = minCooldown;
        this.initialAttackDamage = initialAttackDamage;
        this.maxAttackDamage = maxAttackDamage;
    }

    /** Holds the starting count for the enum constants, which are initialised before the enum's own fields. */
    private static final class Counts {
        static final int INITIAL = 100;
    }

    public static HiveType of(RelicRole role) {
        return switch (role) {
            case RF_HIVE -> RF;
            case MANA_HIVE -> MANA;
            case TWINS_HIVE -> TWINS;
            default -> throw new IllegalArgumentException("Not a hive: " + role);
        };
    }
}
