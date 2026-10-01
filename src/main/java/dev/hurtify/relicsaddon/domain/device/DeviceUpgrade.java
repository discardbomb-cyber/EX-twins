package dev.hurtify.relicsaddon.domain.device;

/** Compact ids and requirements used by the standalone device menu. */
public enum DeviceUpgrade {
    DISTRIBUTION(0, "damage_distribution", 2),
    GATHER(1, "shield_gather", 2),
    RESTORATION(2, "shield_restoration", 3),
    STABILIZATION(3, "shield_stabilization", 4),
    COMBAT(4, "combat_protocol", 2),
    SUPPORT(5, "support_protocol", 3),
    RECOVERY(6, "recovery_protocol", 4);

    private final int bit;
    private final String id;
    private final int requiredLevel;
    DeviceUpgrade(int bit, String id, int requiredLevel) { this.bit = bit; this.id = id; this.requiredLevel = requiredLevel; }
    public int bit() { return bit; }
    public String id() { return id; }
    public int requiredLevel() { return requiredLevel; }
    public boolean shield() { return bit <= STABILIZATION.bit; }
    /** Mirrors the server purchase rules so the menu only lists upgrades a device can take. */
    public boolean availableFor(RelicRole role) {
        if (shield() ? !role.isShield() : !role.isHive()) return false;
        if (this == GATHER) return role != RelicRole.TWINS_SHIELD;
        if (this == STABILIZATION) return role == RelicRole.TWINS_SHIELD;
        return true;
    }
    public static java.util.List<DeviceUpgrade> availableUpgrades(RelicRole role) {
        return java.util.Arrays.stream(values()).filter(upgrade -> upgrade.availableFor(role)).toList();
    }
    public static DeviceUpgrade byId(String id) {
        for (DeviceUpgrade value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("Unknown device upgrade: " + id);
    }
}
