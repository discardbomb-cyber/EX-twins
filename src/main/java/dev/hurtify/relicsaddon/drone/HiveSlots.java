package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.domain.hive.HiveType;
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

    public static int group(int slot, int groups) { return slot % groups; }

    public static int member(int slot, int groups) { return slot / groups; }

    public static int groupSize(int group, int slots, int groups) {
        return group >= slots ? 0 : (slots - 1 - group) / groups + 1;
    }

    private HiveSlots() {
    }
}
