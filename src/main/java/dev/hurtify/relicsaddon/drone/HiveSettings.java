package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveType;

/** A hive's standing orders: how many of its last drones heal, and how the fighters attack. Survives damage, repair and reloads. */
public record HiveSettings(int healers, AttackMode mode) {
    public static final HiveSettings DEFAULT = new HiveSettings(0, AttackMode.BARRAGE);

    public HiveSettings {
        healers = Math.clamp(healers, 0, HiveType.MAX_DRONES);
        if (mode == null) mode = AttackMode.BARRAGE;
    }

    public HiveSettings withHealers(int value) { return new HiveSettings(value, mode); }
    public HiveSettings withMode(AttackMode value) { return new HiveSettings(healers, value); }
    public int healerCount(int capacity) { return Math.min(healers, Math.clamp(capacity, 0, HiveType.MAX_DRONES)); }
    public int fighters(int capacity) { return Math.clamp(capacity, 0, HiveType.MAX_DRONES) - healerCount(capacity); }
    public boolean healer(int index, int capacity) { return index >= fighters(capacity) && index < capacity; }
}
