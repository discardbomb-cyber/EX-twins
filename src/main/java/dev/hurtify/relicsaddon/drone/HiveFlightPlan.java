package dev.hurtify.relicsaddon.drone;

import java.util.List;

/**
 * Which drones may fly and where, worked out the same way on the server and the client from the hive's
 * size and orders alone.
 *
 * <p>The hive's drones are laid out in its item in this order: Droplet's, Barrage's, Containment's, the
 * free ones, and last the healers ({@link HiveSettings}). At most {@link HiveType#MAX_DEPLOYED} fly at once:
 * the healers take their places first, and each mode gets a share of the rest in proportion to its drones,
 * rounded so the shares add up. A mode whose share is smaller than its figure has corners does not fly at
 * all, and its places go to the others ({@link #seats}).
 *
 * <p>Each mode flies as a wing with its own places. Every place has a queue of the mode's drones, its lane:
 * units {@code base + s}, {@code base + s + slots}, and so on (see {@link HiveSlots}).
 */
public record HiveFlightPlan(HiveType type, int units, HiveSettings settings, int healerSlots, List<Wing> wings) {
    /**
     * One mode's part of the swarm: its drones are units {@code base} to {@code base + pool - 1}, {@code slots}
     * of them fly at once in places {@code first} to {@code first + slots - 1} of the whole swarm.
     */
    public record Wing(AttackMode mode, HiveType type, int base, int pool, int slots, int first) {
        public int minimum() { return HiveFigures.minimum(type, mode); }
        public boolean flies() { return slots > 0; }
        /** The mode has drones but no places in the air: the others took them all. */
        public boolean grounded() { return pool > 0 && slots == 0; }
        public int groups() { return HiveSlots.groups(slots, mode, type); }
        public int figures() { return HiveSlots.figures(groups(), mode, type); }
        public boolean owns(int unit) { return unit >= base && unit < base + pool; }

        /** The drone of place {@code slot}'s own lane that flies it, or -1 when every drone of the lane is away (see {@link #occupants}). */
        public int occupant(List<HiveStackState.Unit> units, int slot, long now) {
            int best = -1;
            long bestReady = Long.MAX_VALUE;
            for (int unit = base + slot; unit < base + pool && unit < units.size(); unit += slots) {
                HiveStackState.Unit state = units.get(unit);
                if (state.ready(now) && state.readyAt() < bestReady) {
                    best = unit;
                    bestReady = state.readyAt();
                }
            }
            return best;
        }

        /** When {@code occupant} took its place: the latest hit among the rest of its lane, or -1 if it has flown from the start. */
        public long since(List<HiveStackState.Unit> units, int slot, int occupant, long now) {
            long since = -1;
            for (int unit = base + slot; unit < base + pool && unit < units.size(); unit += slots) {
                if (unit == occupant) continue;
                long hit = units.get(unit).lastHit();
                if (hit >= 0 && hit <= now) since = Math.max(since, hit);
            }
            return since;
        }

        /**
         * The unit flying each place right now, or -1. A place is flown by its own lane's drone that has been
         * ready longest; a place whose whole lane is away takes a spare drone of the wing's (one of another
         * lane that is not flying), the one ready longest first, so the wing runs short only when its whole
         * reserve is spent.
         */
        public int[] occupants(List<HiveStackState.Unit> units, long now) {
            int[] result = new int[slots];
            boolean vacant = false;
            boolean[] flying = new boolean[pool];
            for (int slot = 0; slot < slots; slot++) {
                result[slot] = occupant(units, slot, now);
                if (result[slot] >= 0) flying[result[slot] - base] = true;
                else vacant = true;
            }
            if (!vacant) return result;
            List<Integer> spare = new java.util.ArrayList<>();
            for (int unit = base; unit < base + pool && unit < units.size(); unit++) {
                if (!flying[unit - base] && units.get(unit).ready(now)) spare.add(unit);
            }
            spare.sort(java.util.Comparator.<Integer>comparingLong(unit -> units.get(unit).readyAt()).thenComparingInt(unit -> unit));
            for (int slot = 0, next = 0; slot < slots && next < spare.size(); slot++) if (result[slot] < 0) result[slot] = spare.get(next++);
            return result;
        }

        /** Whether enough of its places are flown to build one figure; fewer, and the wing goes home until repairs fill them. */
        public boolean ready(List<HiveStackState.Unit> units, long now) {
            if (!flies()) return false;
            int occupied = 0;
            for (int unit : occupants(units, now)) if (unit >= 0) occupied++;
            return occupied >= minimum();
        }

        /** Its layout: a wing whose layout changes sets out afresh. */
        public int layout() {
            return flies() ? 31 * (31 * (31 * base + pool) + slots) + first + 1 : 0;
        }
    }

    public static HiveFlightPlan of(HiveType type, int units, HiveSettings settings) {
        units = Math.clamp(units, 0, HiveType.MAX_DRONES);
        HiveSettings resolved = settings.resolve(type, units);
        int healerSlots = Math.min(resolved.healerCount(units), HiveType.MAX_DEPLOYED);
        AttackMode[] modes = AttackMode.values();
        int[] pools = new int[modes.length], minimums = new int[modes.length];
        for (AttackMode mode : modes) {
            pools[mode.ordinal()] = resolved.allocated(mode);
            minimums[mode.ordinal()] = HiveFigures.minimum(type, mode);
        }
        int[] seats = seats(pools, minimums, HiveType.MAX_DEPLOYED - healerSlots);
        Wing[] wings = new Wing[modes.length];
        int base = 0, first = 0;
        for (AttackMode mode : modes) {
            int index = mode.ordinal();
            wings[index] = new Wing(mode, type, base, pools[index], seats[index], first);
            base += pools[index];
            first += seats[index];
        }
        return new HiveFlightPlan(type, units, resolved, healerSlots, List.of(wings));
    }

    /**
     * Places in the air for modes with {@code pools} drones and figures of {@code minimums} corners, when
     * {@code available} places are left after the healers. If every drone fits, each mode flies all of its
     * own. Otherwise the places are shared in proportion to the drones: a mode whose share comes to fewer
     * places than its figure has corners stays home, and the modes left share all the places again, by
     * largest remainder so the shares add up to {@code available}. Their shares only grow, so none of them
     * falls short in turn, and a place more never grounds a mode.
     */
    public static int[] seats(int[] pools, int[] minimums, int available) {
        int count = pools.length;
        available = Math.max(0, available);
        int[] seats = new int[count];
        long total = 0;
        for (int index = 0; index < count; index++) if (pools[index] >= minimums[index] && pools[index] > 0) total += pools[index];
        boolean[] flies = new boolean[count];
        long flying = 0;
        for (int index = 0; index < count; index++) {
            if (pools[index] <= 0 || pools[index] < minimums[index]) continue;
            // Compared exactly, not rounded: rounding never decides who flies.
            flies[index] = total <= available || (long) pools[index] * available >= (long) minimums[index] * total;
            if (flies[index]) flying += pools[index];
        }
        if (flying <= available) {
            for (int index = 0; index < count; index++) if (flies[index]) seats[index] = pools[index];
            return seats;
        }
        long given = 0;
        long[] remainder = new long[count];
        for (int index = 0; index < count; index++) {
            if (!flies[index]) continue;
            long share = (long) pools[index] * available;
            seats[index] = (int) (share / flying);
            remainder[index] = share % flying;
            given += seats[index];
        }
        for (long left = available - given; left > 0; left--) {
            int best = -1;
            for (int index = 0; index < count; index++) if (flies[index] && (best < 0 || remainder[index] > remainder[best])) best = index;
            seats[best]++;
            remainder[best] = -1;
        }
        return seats;
    }

    public Wing wing(AttackMode mode) { return wings.get(mode.ordinal()); }

    /** Every fighter place in the air, all wings together. */
    public int slots() {
        int slots = 0;
        for (Wing wing : wings) slots += wing.slots();
        return slots;
    }

    /** The unit flying each swarm-wide place, all wings together, or -1. */
    public int[] occupants(List<HiveStackState.Unit> units, long now) {
        int[] result = new int[slots()];
        for (Wing wing : wings) System.arraycopy(wing.occupants(units, now), 0, result, wing.first(), wing.slots());
        return result;
    }

    /** Drones that do not heal. */
    public int fighters() { return settings.fighters(units); }

    /** Lanes as {base, pool, slots} for {@link HiveStackState#settle}. */
    public int[][] lanes() {
        int[][] lanes = new int[wings.size()][];
        for (int index = 0; index < lanes.length; index++) {
            Wing wing = wings.get(index);
            lanes[index] = new int[]{wing.base(), wing.pool(), wing.slots()};
        }
        return lanes;
    }
}
