package dev.hurtify.relicsaddon.domain.energy;

/** Battery costs in points: one point is {@value #FE_PER_POINT} FE or one unit of stored mana. */
public final class EnergyCosts {
    public static final int FE_PER_POINT = 10;
    public static final int FE_TRANSFER_PER_TICK = 20_000;
    /** Idle upkeep per second while a device is switched on and running. */
    public static final int SHIELD_UPKEEP = 20, HIVE_UPKEEP = 20;
    /** Per-event costs. */
    public static final int ABSORB_PER_HP = 10, REPAIR_PER_HP = 2, SHOT = 3, HEAL_PER_HP = 5, HIVE_REPAIR_PER_HP = 1, STRIKE = 6;
    /** Most mana one refill pulse draws. */
    public static final int MANA_CHARGE_PER_PULSE = 250;

    private EnergyCosts() { }
}
