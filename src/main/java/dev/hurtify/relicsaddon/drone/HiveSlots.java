package dev.hurtify.relicsaddon.drone;

/**
 * How a wing's places ({@link HiveFlightPlan.Wing}) make strike groups; shared by the server and the
 * renderer so both agree without extra data.
 *
 * <p>Each place of a wing has a queue of drones, its lane, and is flown by the lane's drone that has been
 * ready longest, so a hit drone turns for home while the next one launches at once, and a repaired drone
 * waits in reserve rather than bumping its replacement.
 *
 * <p>A wing's places form strike groups; place {@code s} belongs to group {@code s % groups}, as member
 * {@code s / groups}. A Droplet group is one figure and a Containment group one construct, so there are as
 * many groups as whole figures fit in the wing (one at least, sixteen at most); the places left over
 * stand on the figures' finer points. Barrage groups are clumps, about sixteen drones each, and each target's
 * clumps make one pattern, so a wing has at least as many clumps as its pattern has corners.
 */
public final class HiveSlots {
    /** Strike groups of a wing of {@code mode} with {@code slots} places in a hive of {@code type}. */
    public static int groups(int slots, AttackMode mode, HiveType type) {
        if (slots <= 0) return 1;
        int minimum = HiveFigures.minimum(type, mode);
        if (mode != AttackMode.BARRAGE) return Math.clamp(slots / minimum, 1, 16);
        if (slots < minimum) return slots;
        // Whole patterns only, of about sixteen drones a clump: each target's clumps make one pattern, and no clump is left over from one.
        int patterns = Math.clamp(Math.round(slots / (16F * minimum)), 1, Math.max(1, Math.min(16 / minimum, slots / minimum)));
        return patterns * minimum;
    }

    /**
     * Figures {@code groups} strike groups of {@code mode} make: as many creatures as the wing can take on.
     * Each Droplet or Containment group is a figure; Barrage clumps make a pattern round each target.
     */
    public static int figures(int groups, AttackMode mode, HiveType type) {
        return mode == AttackMode.BARRAGE ? Math.max(1, groups / HiveFormation.patternCorners(type)) : Math.max(1, groups);
    }

    public static int group(int slot, int groups) { return slot % groups; }

    public static int member(int slot, int groups) { return slot / groups; }

    public static int groupSize(int group, int slots, int groups) {
        return group >= slots ? 0 : (slots - 1 - group) / groups + 1;
    }

    // --- several targets ---------------------------------------------------------------------------
    // Strike group g attacks target g % n. Around each target its groups are renumbered 0, 1, 2... and
    // their places laid out the same way as a whole swarm's: local place member * localGroups + localGroup.

    /** How many strike groups attack target {@code target} of {@code engaged}. */
    public static int localGroups(int target, int engaged, int groups) {
        return target >= groups ? 0 : (groups - 1 - target) / engaged + 1;
    }

    /** How many places belong to target {@code target} of {@code engaged}. */
    public static int localSlots(int target, int engaged, int slots, int groups) {
        int sum = 0;
        for (int group = target; group < groups; group += engaged) sum += groupSize(group, slots, groups);
        return sum;
    }

    /** The swarm-wide place of local place {@code local} around target {@code target}. */
    public static int globalSlot(int target, int engaged, int local, int localGroups, int groups) {
        int member = local / localGroups, localGroup = local % localGroups;
        return member * groups + target + localGroup * engaged;
    }

    private HiveSlots() {
    }
}
