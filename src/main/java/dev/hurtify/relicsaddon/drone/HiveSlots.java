package dev.hurtify.relicsaddon.drone;

import java.util.List;

/**
 * Which drones fly right now; shared by the server and the renderer so both agree without extra data.
 *
 * <p>At most {@link HiveType#MAX_DEPLOYED} drones are out at once: healers take their places first
 * and fighters hold the rest. Every fighter place has a queue of drones, its lane: units {@code k},
 * {@code k + slots}, {@code k + 2 slots} and so on. The place is flown by the lane's drone that has
 * been ready longest, so a hit drone turns for home while the next one launches at once, and a
 * repaired drone waits in reserve rather than bumping its replacement.
 *
 * <p>Fighters form two to sixteen strike groups of about sixteen drones; place {@code s} belongs to
 * group {@code s % groups}, as member {@code s / groups}.
 */
public final class HiveSlots {
    public static int healerSlots(int units, HiveSettings settings) {
        return Math.min(settings.healerCount(units), HiveType.MAX_DEPLOYED);
    }

    public static int fighterSlots(int units, HiveSettings settings) {
        return Math.max(0, Math.min(settings.fighters(units), HiveType.MAX_DEPLOYED - healerSlots(units, settings)));
    }

    /** The unit flying fighter place {@code slot}, or -1 when every drone of its lane is away. */
    public static int occupant(List<HiveStackState.Unit> units, int slot, int slots, int fighters, long now) {
        int best = -1;
        long bestReady = Long.MAX_VALUE;
        for (int unit = slot; unit < fighters && unit < units.size(); unit += slots) {
            HiveStackState.Unit state = units.get(unit);
            if (state.ready(now) && state.readyAt() < bestReady) {
                best = unit;
                bestReady = state.readyAt();
            }
        }
        return best;
    }

    /** When {@code occupant} took its place: the latest hit among the rest of its lane, or -1 if it has flown from the start. */
    public static long since(List<HiveStackState.Unit> units, int slot, int slots, int fighters, int occupant, long now) {
        long since = -1;
        for (int unit = slot; unit < fighters && unit < units.size(); unit += slots) {
            if (unit == occupant) continue;
            long hit = units.get(unit).lastHit();
            if (hit >= 0 && hit <= now) since = Math.max(since, hit);
        }
        return since;
    }

    /** Fighter place a unit belongs to (its lane), or -1 for a healer. */
    public static int lane(int unit, int slots, int fighters) {
        return slots <= 0 || unit >= fighters ? -1 : unit % slots;
    }

    public static int groups(int slots) {
        return slots < 2 ? 1 : Math.clamp(Math.round(slots / 16F), 2, 16);
    }

    /**
     * Strike groups in {@code mode}. Barrage clumps draw a pattern around the target, so there are at
     * least three of them, a triangle, as soon as there are three drones to make them.
     */
    public static int groups(int slots, AttackMode mode) {
        int groups = groups(slots);
        return mode == AttackMode.BARRAGE ? Math.min(Math.max(groups, 3), Math.max(1, slots)) : groups;
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
