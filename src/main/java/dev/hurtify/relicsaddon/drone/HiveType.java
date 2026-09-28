package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.relic.RelicRole;

/** One active hive per type; additional copies never multiply the swarm cap. */
public enum HiveType {
    RF(RelicRole.RF_HIVE, RelicRole.RF_DRONE, 12, 12, 40, 80, 40, 2, 3),
    MANA(RelicRole.MANA_HIVE, RelicRole.MANA_DRONE, 12, 8, 30, 50, 20, 2, 3),
    TWINS(RelicRole.TWINS_HIVE, RelicRole.TWINS_DRONE, 12, 18, 60, 120, 60, 3, 4);

    public static final int MAX_DRONES = 250;
    public final RelicRole role, drone;
    public final int initialCount, initialHealth, maxHealth, initialCooldown, minCooldown;
    public final int initialAttackDamage, maxAttackDamage;

    HiveType(RelicRole role, RelicRole drone, int count, int health, int maxHealth, int cooldown, int minCooldown,
             int initialAttackDamage, int maxAttackDamage) {
        this.role = role;
        this.drone = drone;
        this.initialCount = count;
        this.initialHealth = health;
        this.maxHealth = maxHealth;
        this.initialCooldown = cooldown;
        this.minCooldown = minCooldown;
        this.initialAttackDamage = initialAttackDamage;
        this.maxAttackDamage = maxAttackDamage;
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
