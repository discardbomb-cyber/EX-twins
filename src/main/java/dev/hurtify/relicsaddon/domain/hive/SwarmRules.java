package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.device.DeviceStat;
import dev.hurtify.relicsaddon.domain.device.DeviceStats;
import java.util.Optional;

/** A swarm's numbers by level, and its upkeep rhythm. */
public final class SwarmRules {
    /** Past any flight out (70 ticks) and home (24), with room to spare. */
    public static final long SETTLE_QUIET_TICKS = 120;
    /** Ticks between the swarm's repair and tidying passes. */
    public static final int SETTLE_PERIOD = 10;

    /** Drones in the swarm: the family's starting count at level 0 up to 2 000 at level 10. */
    public static int capacity(HiveType type, int level) {
        return (int) Math.round(DeviceStats.stat(Optional.of(type.role), level, DeviceStat.DRONE_COUNT, type.initialCount,
                HiveType.INITIAL_DRONES, HiveType.MAX_DRONES));
    }

    /** Ticks between one group's blows or charges: 5 seconds at level 0 down to 2 at level 10. */
    public static int strikeInterval(int level) {
        return (int) Math.round(DeviceStats.stat(Optional.empty(), level, DeviceStat.ATTACK_INTERVAL_MAX, 100, 20, 100));
    }

    /** What one drone adds to its group's blow, before upgrades. */
    public static double attackDamageBase(HiveType type, int level) {
        return DeviceStats.stat(Optional.of(type.role), level, DeviceStat.ATTACK_DAMAGE, type.initialAttackDamage, 1, 100);
    }

    /** Ticks to rebuild a destroyed drone, before upgrades. */
    public static double cooldown(HiveType type, int level) {
        return DeviceStats.stat(Optional.of(type.role), level, DeviceStat.COOLDOWN, type.initialCooldown, 10, 400);
    }

    /** HP regained by drones that already existed; drones added by a larger capacity arrive free. */
    public static int restoredHealth(HiveStackState before, HiveStackState after) {
        int sum = 0, shared = Math.min(before.units().size(), after.units().size());
        for (int index = 0; index < shared; index++) sum += Math.max(0, after.units().get(index).hp() - before.units().get(index).hp());
        return sum;
    }

    private SwarmRules() { }
}
