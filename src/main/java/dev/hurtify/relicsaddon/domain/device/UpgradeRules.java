package dev.hurtify.relicsaddon.domain.device;

/** Buying upgrade ranks with device points, and how the console shows an upgrade's button. */
public final class UpgradeRules {
    /** An upgrade button's state, in the order the console checks it. */
    public enum Button { MAXED, NEEDS_LEVEL, NO_POINTS, BUY }

    /**
     * Whether {@code role} may buy the next rank of {@code upgrade}: the upgrade must suit the device's
     * kind, a Twins shield has no gather, and it takes the required level, a point and a free rank.
     * Stabilization is not held to Twins shields here.
     */
    public static boolean canPurchase(RelicRole role, DeviceUpgrade upgrade, DeviceProgression progression) {
        if (role.isShield() != (upgrade.bit() <= DeviceUpgrade.STABILIZATION.bit())) return false;
        if (upgrade == DeviceUpgrade.GATHER && role == RelicRole.TWINS_SHIELD) return false;
        int rank = progression.rank(upgrade.id());
        if (progression.level() < upgrade.requiredLevel() || progression.points() < 1 || rank >= 3) return false;
        return true;
    }

    /** The progression after a purchase: one more rank for one point. */
    public static DeviceProgression afterPurchase(DeviceProgression progression, DeviceUpgrade upgrade) {
        return progression.withRank(upgrade.id(), progression.rank(upgrade.id()) + 1).withPoints(progression.points() - 1);
    }

    /** Whether the console offers the next rank: a point to spend, the level reached and a rank left. */
    public static boolean canBuy(DeviceUpgrade upgrade, DeviceProgression progression) {
        return progression.points() > 0 && progression.level() >= upgrade.requiredLevel() && progression.rank(upgrade.id()) < 3;
    }

    public static Button button(DeviceUpgrade upgrade, DeviceProgression progression) {
        if (progression.rank(upgrade.id()) >= 3) return Button.MAXED;
        if (progression.level() < upgrade.requiredLevel()) return Button.NEEDS_LEVEL;
        if (progression.points() <= 0) return Button.NO_POINTS;
        return Button.BUY;
    }

    private UpgradeRules() { }
}
