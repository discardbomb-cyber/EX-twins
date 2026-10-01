package dev.hurtify.relicsaddon.shipshield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The integrity of a ship shield: one patch per emitter seat on every layer, outermost layer
 * last. A blow lands on the outermost patch over the seat; what the patch cannot hold spreads to
 * the neighbouring patches of the same layer, ring by ring, and what the rings cannot hold goes
 * on to the same seat's patch one layer in. A blow that no layer can hold overloads the shield:
 * every patch drops to nothing and the rest of the blow passes. Pure.
 */
public final class ShieldLayers {
    /** One patch's loss in a blow: which layer and seat, and how much. */
    public record Drain(int layer, int seat, int amount) {
    }

    /** What became of a blow. */
    public record Strike(int absorbed, int passed, boolean overloaded, int layersStripped, List<Drain> drains) {
        public boolean held() { return passed == 0; }
    }

    private final int layers, seats, max;
    private final int[][] integrity;

    public ShieldLayers(int layers, int seats, int max) {
        this.layers = Math.max(1, layers);
        this.seats = Math.max(0, seats);
        this.max = Math.max(1, max);
        integrity = new int[this.layers][this.seats];
        fill();
    }

    public int layers() { return layers; }
    public int seats() { return seats; }
    public int max() { return max; }
    public int integrity(int layer, int seat) { return seat < 0 || seat >= seats ? 0 : integrity[layer][seat]; }
    public int[][] all() { return integrity; }

    /** Everything a shield can hold, and what it holds now. */
    public int capacity() { return layers * seats * max; }
    public int total() {
        int sum = 0;
        for (int[] layer : integrity) for (int patch : layer) sum += patch;
        return sum;
    }

    public void fill() {
        for (int[] layer : integrity) Arrays.fill(layer, max);
    }

    public void drainAll() {
        for (int[] layer : integrity) Arrays.fill(layer, 0);
    }

    public void set(int layer, int seat, int value) {
        integrity[layer][seat] = Math.clamp(value, 0, max);
    }

    /**
     * A blow of {@code cost} on {@code seat}'s outermost patch. {@code neighbours} lists the seats
     * whose patches touch each seat's; {@code rings} is how many rings of them a layer may spread
     * the blow to before it goes inward.
     */
    public Strike strike(int seat, int cost, int[][] neighbours, int rings) {
        List<Drain> drains = new ArrayList<>();
        int remaining = Math.max(0, cost);
        int absorbed = 0, stripped = 0;
        if (seats == 0 || seat < 0 || seat >= seats) return new Strike(0, remaining, false, 0, drains);
        for (int layer = layers - 1; layer >= 0 && remaining > 0; layer--) {
            int taken = take(layer, seat, remaining, drains);
            remaining -= taken;
            absorbed += taken;
            if (remaining == 0) break;
            // Rings of neighbours round the seat, each ring sharing what is left evenly, as far as it goes.
            boolean[] visited = new boolean[seats];
            visited[seat] = true;
            List<Integer> ring = List.of(seat);
            for (int depth = 0; depth < rings && remaining > 0; depth++) {
                List<Integer> next = new ArrayList<>();
                for (int at : ring) for (int other : neighbours[at]) {
                    if (other < 0 || other >= seats || visited[other]) continue;
                    visited[other] = true;
                    next.add(other);
                }
                if (next.isEmpty()) break;
                remaining -= share(layer, next, remaining, drains);
                absorbed = cost - remaining;
                ring = next;
            }
            if (remaining > 0) stripped++;
        }
        boolean overloaded = remaining > 0;
        if (overloaded) {
            for (int layer = 0; layer < layers; layer++) for (int patch = 0; patch < seats; patch++) {
                if (integrity[layer][patch] > 0) {
                    drains.add(new Drain(layer, patch, integrity[layer][patch]));
                    integrity[layer][patch] = 0;
                }
            }
        }
        return new Strike(absorbed, remaining, overloaded, stripped, drains);
    }

    private int take(int layer, int seat, int amount, List<Drain> drains) {
        int taken = Math.min(integrity[layer][seat], amount);
        if (taken > 0) {
            integrity[layer][seat] -= taken;
            drains.add(new Drain(layer, seat, taken));
        }
        return taken;
    }

    /** The patches of {@code ring} share {@code amount} evenly, round and round until it is paid or they are empty. */
    private int share(int layer, List<Integer> ring, int amount, List<Drain> drains) {
        int[] paid = new int[ring.size()];
        int left = amount;
        boolean any = true;
        while (left > 0 && any) {
            any = false;
            int each = Math.max(1, left / ring.size());
            for (int index = 0; index < ring.size() && left > 0; index++) {
                int seat = ring.get(index);
                int taken = Math.min(integrity[layer][seat], Math.min(each, left));
                if (taken <= 0) continue;
                integrity[layer][seat] -= taken;
                paid[index] += taken;
                left -= taken;
                any = true;
            }
        }
        for (int index = 0; index < ring.size(); index++) if (paid[index] > 0) drains.add(new Drain(layer, ring.get(index), paid[index]));
        return amount - left;
    }

    /** One point back on every damaged patch, innermost layers first; how many points it gave back. */
    public int repair(int points) {
        int given = 0;
        for (int layer = 0; layer < layers && given < points; layer++) {
            for (int seat = 0; seat < seats && given < points; seat++) {
                if (integrity[layer][seat] < max) {
                    integrity[layer][seat]++;
                    given++;
                }
            }
        }
        return given;
    }

    /** Patches in breadth-first order from a seat, for callers that want the same rings a blow uses. */
    public static List<Integer> ring(int seat, int[][] neighbours, int depth) {
        List<Integer> order = new ArrayList<>();
        boolean[] seen = new boolean[neighbours.length];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{seat, 0});
        seen[seat] = true;
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            if (at[1] == depth) order.add(at[0]);
            if (at[1] >= depth) continue;
            for (int other : neighbours[at[0]]) if (!seen[other]) {
                seen[other] = true;
                queue.add(new int[]{other, at[1] + 1});
            }
        }
        return order;
    }
}
