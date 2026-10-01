package dev.hurtify.relicsaddon.domain.device;

import dev.hurtify.relicsaddon.domain.hive.HiveType;
import java.util.Optional;

/**
 * Device stats by level: the shield buffer and radius, and the swarm's size, drone health, damage,
 * strike interval and rebuild cooldown. {@code role} is the device's role, empty for a stack that is
 * not a device; only the damage and the cooldown depend on it, through the hive family.
 */
public final class DeviceStats {
    /** The stat at {@code level}, clamped to {@code [min, max]}; {@code fallback} when it is not finite. */
    public static double stat(Optional<RelicRole> role, int level, DeviceStat stat, double fallback, double min, double max) {
        double value = switch (stat) {
            case BUFFER_CAPACITY -> 504 + (5000 - 504) * level / 10.0;
            case RADIUS -> 2 + level;
            case DRONE_COUNT -> HiveType.INITIAL_DRONES + (HiveType.MAX_DRONES - HiveType.INITIAL_DRONES) * level / 10.0;
            case DRONE_HEALTH -> HiveType.DRONE_HP;
            case ATTACK_DAMAGE -> hiveValue(role, level, 2);
            case ATTACK_INTERVAL_MAX -> 100 - 60 * level / 10.0;
            case COOLDOWN -> hiveValue(role, level, 3);
        };
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    /** A hive family's damage (kind 2) or cooldown (kind 3); 0 without a role, and a role that is no hive throws. */
    private static double hiveValue(Optional<RelicRole> role, int level, int kind) {
        if (role.isEmpty()) return 0;
        var type = HiveType.of(role.get());
        return switch (kind) {
            case 2 -> type.initialAttackDamage + (type.maxAttackDamage - type.initialAttackDamage) * level / 10.0;
            default -> type.initialCooldown + (type.minCooldown - type.initialCooldown) * level / 10.0;
        };
    }

    private DeviceStats() { }
}
