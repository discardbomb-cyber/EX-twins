package dev.hurtify.relicsaddon.shield;

import java.util.ArrayList;
import java.util.List;

/** Pure server/client-shared cell accounting. Neither upgrade can create HP. */
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

    private ShieldCellDefense() { }
}
