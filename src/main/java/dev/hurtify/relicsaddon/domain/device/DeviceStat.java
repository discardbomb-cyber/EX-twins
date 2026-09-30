package dev.hurtify.relicsaddon.domain.device;

import java.util.Optional;

/** The level-dependent device stats, by the ids the console and the saved callers use. */
public enum DeviceStat {
    BUFFER_CAPACITY("buffer_capacity"),
    RADIUS("radius"),
    DRONE_COUNT("drone_count"),
    DRONE_HEALTH("drone_health"),
    ATTACK_DAMAGE("attack_damage"),
    ATTACK_INTERVAL_MAX("attack_interval_max"),
    COOLDOWN("cooldown");

    private final String id;

    DeviceStat(String id) { this.id = id; }

    public String id() { return id; }

    /** The stat with this id, if any. A null id throws, as a switch over the id always did. */
    public static Optional<DeviceStat> byId(String id) {
        for (DeviceStat stat : values()) if (id.equals(stat.id)) return Optional.of(stat);
        return Optional.empty();
    }
}
