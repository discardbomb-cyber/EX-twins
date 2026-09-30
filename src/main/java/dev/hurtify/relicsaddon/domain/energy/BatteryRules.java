package dev.hurtify.relicsaddon.domain.energy;

import dev.hurtify.relicsaddon.domain.device.RelicRole;

/**
 * Built-in batteries. RF devices carry an RF battery, Mana devices a mana battery and Twins both; a
 * device only operates while one of its switched-on batteries holds charge. Twins split every cost
 * between their batteries and fall back to whichever still has charge.
 */
public final class BatteryRules {
    /** A drain's outcome: whether it was paid, and the batteries afterwards (emptied when it was not). */
    public record Drain(boolean paid, DeviceEnergy after) { }

    public static boolean hasRf(RelicRole role) {
        return role == RelicRole.RF_SHIELD || role == RelicRole.RF_HIVE || role == RelicRole.TWINS_SHIELD || role == RelicRole.TWINS_HIVE;
    }

    public static boolean hasMana(RelicRole role) {
        return role == RelicRole.MANA_SHIELD || role == RelicRole.MANA_HIVE || role == RelicRole.TWINS_SHIELD || role == RelicRole.TWINS_HIVE;
    }

    /** Battery size in points; it grows with the device level (25 000 up to 100 000). */
    public static int capacity(int level) {
        return 25_000 + 7_500 * level;
    }

    public static int feCapacity(int level) {
        return capacity(level) * EnergyCosts.FE_PER_POINT;
    }

    /** A freshly made device ships with full batteries. */
    public static DeviceEnergy full(RelicRole role, int level) {
        return new DeviceEnergy(hasRf(role) ? feCapacity(level) : 0, hasMana(role) ? capacity(level) : 0, true, true, DeviceEnergy.ManaSource.AUTO);
    }

    /** Points the RF battery can pay: none when it is missing or switched off. */
    public static int usableRf(RelicRole role, DeviceEnergy energy) {
        return hasRf(role) && energy.rfOn() ? energy.rf() / EnergyCosts.FE_PER_POINT : 0;
    }

    public static int usableMana(RelicRole role, DeviceEnergy energy) {
        return hasMana(role) && energy.manaOn() ? energy.mana() : 0;
    }

    /**
     * How a cost of {@code points} is shared between usable RF and mana charge (both in points);
     * requires {@code rf + mana >= points}. Twins split evenly and a short battery hands the rest
     * to the other one.
     */
    public static int[] split(int rf, int mana, int points) {
        if (rf > 0 && mana > 0) {
            int fromMana = Math.min(mana, points - Math.min(rf, (points + 1) / 2));
            return new int[]{points - fromMana, fromMana};
        }
        return rf > 0 ? new int[]{points, 0} : new int[]{0, points};
    }

    /**
     * Spends {@code points}. When the usable batteries cannot cover it, every usable battery is emptied
     * and the drain is not paid; the device then stops until it is recharged.
     */
    public static Drain drain(RelicRole role, DeviceEnergy energy, int points) {
        int rf = usableRf(role, energy), mana = usableMana(role, energy);
        if (rf + mana < points) {
            return new Drain(false, energy.withRf(energy.rf() - rf * EnergyCosts.FE_PER_POINT).withMana(energy.mana() - mana));
        }
        int[] share = split(rf, mana, points);
        return new Drain(true, energy.withRf(energy.rf() - share[0] * EnergyCosts.FE_PER_POINT).withMana(energy.mana() - share[1]));
    }

    /** FE the RF battery takes of {@code amount} in one tick; none without an RF battery. */
    public static int acceptFe(RelicRole role, DeviceEnergy energy, int level, int amount) {
        if (!hasRf(role) || amount <= 0) return 0;
        return Math.min(Math.min(amount, EnergyCosts.FE_TRANSFER_PER_TICK), Math.max(0, feCapacity(level) - energy.rf()));
    }

    private BatteryRules() { }
}
