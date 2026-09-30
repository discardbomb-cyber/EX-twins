package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.domain.hive.HiveType;
import java.util.ArrayList;
import java.util.List;

/**
 * Every drone of a hive: its health and when it is whole and home again. Up to 750 drones live in
 * one item, so both forms are compact: saves keep two plain arrays, and the network sends one byte
 * for a drone at rest.
 */
public record HiveStackState(boolean enabled, List<Unit> units) {
    public static final HiveStackState DEFAULT = new HiveStackState(true, List.of());

    public HiveStackState {
        units = List.copyOf(units.subList(0, Math.min(HiveType.MAX_DRONES, units.size())));
    }

    /**
     * One drone. {@code readyAt} is when it is whole and home again after a hit, {@code lastHit} when
     * it was last hit (it then flies home), {@code attackReadyAt} paces a healer's next mend.
     */
    public record Unit(int hp, long readyAt, long lastHit, long attackReadyAt) {
        public Unit {
            hp = Math.clamp(hp, 0, HiveType.DRONE_HP);
            readyAt = Math.max(0, readyAt);
            lastHit = Math.max(-1, lastHit);
            attackReadyAt = Math.max(0, attackReadyAt);
        }

        public static Unit fresh() { return new Unit(HiveType.DRONE_HP, 0, -1, 0); }
        /** Whole and home, free to fly. */
        public boolean ready(long now) { return hp >= HiveType.DRONE_HP && now >= readyAt; }
        public boolean attackReady(long now) { return hp > 0 && now >= attackReadyAt; }
        /** Takes {@code damage} at {@code now}; it is repaired (or rebuilt) and back at {@code backAt}. */
        public Unit hit(int damage, long now, long backAt) { return new Unit(hp - Math.max(1, damage), backAt, now, attackReadyAt); }
        public Unit withAttackReadyAt(long value) { return new Unit(hp, readyAt, lastHit, value); }
    }

    public HiveStackState withEnabled(boolean value) { return new HiveStackState(value, units); }

    /** Grows or trims the swarm to {@code capacity}; with {@code repair}, drones whose repair is done come back whole. */
    public HiveStackState prepare(int capacity, long now, boolean repair) {
        capacity = Math.clamp(capacity, 1, HiveType.MAX_DRONES);
        boolean changed = capacity != units.size();
        if (!changed && !(repair && enabled)) return this;
        var next = new ArrayList<Unit>(capacity);
        for (int index = 0; index < capacity; index++) {
            Unit unit = index < units.size() ? units.get(index) : Unit.fresh();
            if (repair && enabled && unit.hp() < HiveType.DRONE_HP && now >= unit.readyAt()) {
                unit = new Unit(HiveType.DRONE_HP, unit.readyAt(), unit.lastHit(), unit.attackReadyAt());
                changed = true;
            }
            next.add(unit);
        }
        return changed ? new HiveStackState(enabled, next) : this;
    }

    /**
     * Drops timings the swarm no longer needs, so a hive that has been in a fight goes back to one
     * byte per drone on the wire. A fighter lane whose drones are all whole, home and untouched for
     * {@code quiet} ticks starts afresh: its drones become identical, and the lane's first drone flies
     * its place (possibly taking over from another drone of the lane, at the same station, so nothing
     * visibly moves). Healers drop spent timers the same way. Timings from another world's clock (later
     * than {@code now}) are pulled back to it.
     */
    public HiveStackState settle(long now, int slots, int fighters, long quiet) {
        List<Unit> work = units;
        // Timings from another world's clock (a hive carried between worlds) are pulled back to now.
        for (int index = 0; index < work.size(); index++) {
            Unit unit = work.get(index);
            if (unit.lastHit() > now || unit.readyAt() > now + STALE_TICKS || unit.attackReadyAt() > now + STALE_TICKS) {
                if (work == units) work = new ArrayList<>(units);
                work.set(index, new Unit(unit.hp(), Math.min(unit.readyAt(), now), unit.lastHit() > now ? -1 : unit.lastHit(),
                        Math.min(unit.attackReadyAt(), now)));
            }
        }
        fighters = Math.min(fighters, work.size());
        for (int lane = 0; lane < Math.min(slots, fighters); lane++) {
            boolean still = true, stamped = false;
            for (int index = lane; index < fighters; index += slots) {
                Unit unit = work.get(index);
                if (!settled(unit, now, quiet)) {
                    still = false;
                    break;
                }
                stamped |= !unit.equals(Unit.fresh());
            }
            if (!still || !stamped) continue;
            if (work == units) work = new ArrayList<>(units);
            for (int index = lane; index < fighters; index += slots) work.set(index, Unit.fresh());
        }
        for (int index = Math.max(0, fighters); index < work.size(); index++) {
            Unit unit = work.get(index);
            if (unit.equals(Unit.fresh()) || !settled(unit, now, quiet)) continue;
            if (work == units) work = new ArrayList<>(units);
            work.set(index, Unit.fresh());
        }
        return work == units ? this : new HiveStackState(enabled, work);
    }

    /** Whole, home, not hit for {@code quiet} ticks and with no mend pending. */
    private static boolean settled(Unit unit, long now, long quiet) {
        return unit.ready(now) && unit.attackReadyAt() <= now && (unit.lastHit() < 0 || now - unit.lastHit() >= quiet);
    }

    /** No repair, rebuild or mend is ever scheduled this far ahead; a later stamp came from another world's clock. */
    private static final long STALE_TICKS = 20 * 60 * 10;

    public int readyCount(long now) {
        if (!enabled) return 0;
        int count = 0;
        for (Unit unit : units) if (unit.ready(now)) count++;
        return count;
    }
}
