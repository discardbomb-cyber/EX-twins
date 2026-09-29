package dev.hurtify.relicsaddon.relic;

/** Compact ids and requirements used by the standalone device menu. */
public enum DeviceUpgrade {
    DISTRIBUTION(0, ShieldUpgrades.DISTRIBUTION, 2),
    GATHER(1, ShieldUpgrades.GATHER, 2),
    RESTORATION(2, ShieldUpgrades.RESTORATION, 3),
    STABILIZATION(3, ShieldUpgrades.STABILIZATION, 4),
    COMBAT(4, HiveUpgrades.COMBAT, 2),
    SUPPORT(5, HiveUpgrades.SUPPORT, 3),
    RECOVERY(6, HiveUpgrades.RECOVERY, 4);

    private final int bit;
    private final String id;
    private final int requiredLevel;
    DeviceUpgrade(int bit, String id, int requiredLevel) { this.bit = bit; this.id = id; this.requiredLevel = requiredLevel; }
    public int bit() { return bit; }
    public String id() { return id; }
    public int requiredLevel() { return requiredLevel; }
    public static DeviceUpgrade byId(String id) {
        for (DeviceUpgrade value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("Unknown device upgrade: " + id);
    }
}
