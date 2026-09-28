package dev.hurtify.relicsaddon.shield;

import java.util.ArrayList;
import java.util.List;

/** Pure server/client-shared cell accounting. Upgrades change repair timing, never either maximum. */
public final class ShieldCellDefense {
    public static final int GATHER_COOLDOWN = 40;
    public static final int MOVE_TICKS = 10;

    public record Damage(List<Integer> health, int sharedBuffer, int spent) {
        public ShieldStackState apply(ShieldStackState state, int cell, float absorbed, long now) {
            return state.withHit(health, sharedBuffer, cell, absorbed, now);
        }
    }

    public static Damage damage(ShieldStackState state, int cell, int cost, double sharing) {
        return damage(state, cell, cost, sharing, Long.MAX_VALUE);
    }

    public static Damage damage(ShieldStackState state, int cell, int cost, double sharing, long now) {
        var health = new ArrayList<>(state.cells());
        cost = Math.clamp(cost, 0, 10000);
        if (cost == 0) return new Damage(List.copyOf(health), state.sharedBuffer(), 0);
        int buffered = Math.min(state.sharedBuffer(), cost);
        cost -= buffered;
        if (cost == 0) return new Damage(List.copyOf(health), state.sharedBuffer() - buffered, buffered);
        if (state.availableHp(cell, now) == 0) return new Damage(List.copyOf(health), state.sharedBuffer() - buffered, buffered);
        int budget = (int) Math.floor(cost * (Double.isFinite(sharing) ? Math.clamp(sharing, 0, .5) : 0));
        int[] neighbors = java.util.Arrays.stream(ShieldTopology.INSTANCE.neighbors(cell))
                .filter(i -> state.availableHp(i, now) > 0).limit(2).toArray();
        int shared = 0;
        while (shared < budget) {
            boolean paid = false;
            for (int neighbor : neighbors) {
                if (shared < budget && health.get(neighbor) > 0) {
                    health.set(neighbor, health.get(neighbor) - 1);
                    shared++;
                    paid = true;
                }
            }
            if (!paid) break;
        }
        int primary = Math.min(health.get(cell), cost - shared);
        health.set(cell, health.get(cell) - primary);
        return new Damage(List.copyOf(health), state.sharedBuffer() - buffered, buffered + primary + shared);
    }

    public static ShieldStackState gather(ShieldStackState state, int target, int count, long now) {
        if (count <= 0 || state.gathering(now) || (state.gatherTime() >= 0 && now >= state.gatherTime()
                && now - state.gatherTime() < GATHER_COOLDOWN)) return state;
        int[] nearest = ShieldTopology.INSTANCE.nearestTo(target);
        var destinations = new ArrayList<Integer>();
        destinations.add(target);
        for (int cell : nearest) if (ShieldTopology.INSTANCE.adjacent(target, cell)) destinations.add(cell);
        var health = new ArrayList<>(state.cells());
        var moves = new ArrayList<ShieldCellMove>();
        for (int destination : destinations) {
            if (health.get(destination) > 0 || moves.size() >= Math.min(3, count)) continue;
            // Donors are outside the defended patch: moving a cell must leave a real hole.
            for (int donor : ShieldTopology.INSTANCE.nearestTo(destination)) {
                if (!destinations.contains(donor) && health.get(donor) > 0) {
                    health.set(destination, health.get(donor));
                    health.set(donor, 0);
                    moves.add(new ShieldCellMove(donor, destination));
                    break;
                }
            }
        }
        return moves.isEmpty() ? state : state.withCells(health, moves, now);
    }

    /** Passive repair restores existing local or buffer HP; it never changes either maximum. */
    public static ShieldStackState repair(ShieldStackState state, long now, int capacity, int quietTicks, int steps) {
        capacity = Math.clamp(capacity, 0, ShieldStackState.MAX_BUFFER_CAPACITY);
        quietTicks = Math.clamp(quietTicks, 0, ShieldStackState.BUFFER_REPAIR_QUIET_TICKS);
        if (steps <= 0 || now < state.lastActiveGameTime() + quietTicks) return state;
        for (int step = 0; step < Math.min(steps, 3); step++) {
            ShieldStackState next = repairOne(state, capacity);
            if (next == state) break;
            state = next;
        }
        return state;
    }

    private static ShieldStackState repairOne(ShieldStackState state, int capacity) {
        var health = new ArrayList<>(state.cells());
        for (int cell = 0; cell < health.size(); cell++) if (health.get(cell) < ShieldStackState.MAX_PANEL_INTEGRITY) {
            health.set(cell, health.get(cell) + 1);
            return state.withCells(health, state.moves(), state.gatherTime());
        }
        if (state.sharedBuffer() < capacity) {
            return state.withCellsAndBuffer(state.cells(), state.sharedBuffer() + 1, state.moves(), state.gatherTime());
        }
        return state;
    }

    private ShieldCellDefense() { }
}
