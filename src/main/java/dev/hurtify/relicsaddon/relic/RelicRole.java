package dev.hurtify.relicsaddon.relic;

public enum RelicRole {
    RF_SHIELD("rf_shield", "rf_shield", "charm", Kind.SHIELD, 0x26C6DA),
    MANA_SHIELD("mana_shield", "mana_shield", "charm", Kind.SHIELD, 0x9C6BFF),
    TWINS_SHIELD("twins_shield", "twins_shield", "charm", Kind.SHIELD, 0xBA75EF),
    RF_DRONE("rf_drone", "rf_drone", "charm", Kind.DRONE, 0x26C6DA),
    MANA_DRONE("mana_drone", "mana_drone", "charm", Kind.DRONE, 0x9C6BFF),
    TWINS_DRONE("twins_drone", "twins_drone", "charm", Kind.DRONE, 0xBA75EF),
    RF_HIVE("rf_hive", "rf_hive", "charm", Kind.HIVE, 0x26C6DA),
    MANA_HIVE("mana_hive", "mana_hive", "charm", Kind.HIVE, 0x42DBC3),
    TWINS_HIVE("twins_hive", "twins_hive", "charm", Kind.HIVE, 0xA359E6);

    public static final String EQUIPMENT_SLOT = "charm";

    private static final RelicRole[] SHIELDS = {RF_SHIELD, MANA_SHIELD, TWINS_SHIELD};
    private static final RelicRole[] DRONES = {RF_DRONE, MANA_DRONE, TWINS_DRONE};
    private static final RelicRole[] HIVES = {RF_HIVE, MANA_HIVE, TWINS_HIVE};

    private final String itemId;
    private final String abilityId;
    private final String slot;
    private final Kind kind;
    private final int color;

    RelicRole(String itemId, String abilityId, String slot, Kind kind, int color) {
        this.itemId = itemId;
        this.abilityId = abilityId;
        this.slot = slot;
        this.kind = kind;
        this.color = color;
    }

    public String itemId() {
        return itemId;
    }

    public String abilityId() {
        return abilityId;
    }

    public String slot() {
        return slot;
    }

    public boolean isShield() {
        return kind == Kind.SHIELD;
    }

    public boolean isHive() {
        return kind == Kind.HIVE;
    }

    /** Drone roles identify deployed hive swarm models; only shields and hives are item-backed. */
    public boolean available() {
        return isShield() || isHive();
    }

    public int color() {
        return color;
    }

    public int repairInterval() {
        return switch (this) {
            case MANA_SHIELD -> 10;
            case TWINS_SHIELD -> 30;
            default -> 20;
        };
    }

    public static RelicRole[] shields() {
        return SHIELDS.clone();
    }

    public static RelicRole[] drones() {
        return DRONES.clone();
    }

    public static RelicRole[] hives() {
        return HIVES.clone();
    }

    private enum Kind {
        SHIELD,
        DRONE,
        HIVE
    }
}
