package dev.hurtify.relicsaddon.domain.hive;

/**
 * What the hive upgrades do at each rank; ranks above 3 count as 3. A hive that cannot operate counts
 * as rank 0; the caller decides that. Each product keeps its association: {@code r * (cap / 3D)} is not
 * {@code r * cap / 3D} in the last bit.
 */
public final class HiveUpgradeEffects {
    /** Multiplier of each drone's share of a blow (combat protocol). */
    public static double damage(HiveType type, int rank) {
        double perRank = switch (type) { case RF -> .10; case MANA -> .08; case TWINS -> .12; };
        return 1 + Math.min(3, rank) * perRank;
    }

    /** Multiplier of what the healers mend (support protocol). */
    public static double healing(HiveType type, int rank) {
        double cap = switch (type) { case RF -> .50; case MANA -> 1D; case TWINS -> .75; };
        return 1 + Math.min(3, rank) * (cap / 3D);
    }

    /** Multiplier of the time a hit drone stays away (recovery protocol). */
    public static double rebuild(HiveType type, int rank) {
        double cap = switch (type) { case RF -> .30; case MANA -> .20; case TWINS -> .35; };
        return 1 - Math.min(3, rank) * (cap / 3D);
    }

    private HiveUpgradeEffects() { }
}
