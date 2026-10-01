package dev.hurtify.relicsaddon.shipshield;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;

/**
 * The shell shared out between the emitter drones: every cell of the mesh belongs to the nearest
 * drone that is at its seat, so a drone away in its dock leaves its cells to its neighbours. Two
 * seats are neighbours when their patches share an edge. Pure; worked out again whenever a drone
 * leaves or comes back.
 */
public final class ShellPatches {
    private final Vec3[] seats;
    private final boolean[] held;
    private final int[] owner;
    private final int[][] neighbours;
    private final int[] cells;

    private ShellPatches(Vec3[] seats, boolean[] held, int[] owner, int[][] neighbours, int[] cells) {
        this.seats = seats;
        this.held = held;
        this.owner = owner;
        this.neighbours = neighbours;
        this.cells = cells;
    }

    public static ShellPatches of(ShellMesh mesh, Vec3[] seats, boolean[] held) {
        int count = seats.length;
        int[] owner = new int[mesh.quadCount()];
        int[] cells = new int[count];
        boolean any = false;
        for (boolean holding : held) any |= holding;
        for (int quad = 0; quad < owner.length; quad++) {
            owner[quad] = any ? nearest(seats, held, mesh.quadCentre(quad)) : -1;
            if (owner[quad] >= 0) cells[owner[quad]]++;
        }
        // Seats whose patches touch: an edge of the mesh with a different owner on each side.
        IntOpenHashSet[] touching = new IntOpenHashSet[count];
        for (int seat = 0; seat < count; seat++) touching[seat] = new IntOpenHashSet();
        var edgeOwner = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        edgeOwner.defaultReturnValue(-2);
        int[] quads = mesh.quads();
        for (int quad = 0; quad < owner.length; quad++) {
            for (int corner = 0; corner < 4; corner++) {
                int a = quads[quad * 4 + corner], b = quads[quad * 4 + (corner + 1) % 4];
                long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                int other = edgeOwner.put(key, owner[quad]);
                if (other == -2 || other == owner[quad] || other < 0 || owner[quad] < 0) continue;
                touching[other].add(owner[quad]);
                touching[owner[quad]].add(other);
            }
        }
        int[][] neighbours = new int[count][];
        for (int seat = 0; seat < count; seat++) {
            neighbours[seat] = touching[seat].toIntArray();
            Arrays.sort(neighbours[seat]);
        }
        return new ShellPatches(seats.clone(), held.clone(), owner, neighbours, cells);
    }

    private static int nearest(Vec3[] seats, boolean[] held, Vec3 point) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int seat = 0; seat < seats.length; seat++) {
            if (!held[seat]) continue;
            double distance = seats[seat].distanceToSqr(point);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = seat;
            }
        }
        return best;
    }

    public int seats() { return seats.length; }
    public boolean held(int seat) { return held[seat]; }
    /** The seat whose patch a point of the shell belongs to, or -1 when no drone holds any. */
    public int seatAt(Vec3 point) { return nearest(seats, held, point); }
    public int owner(int quad) { return owner[quad]; }
    public int[] owners() { return owner; }
    public int[][] neighbours() { return neighbours; }
    public int[] neighbours(int seat) { return neighbours[seat]; }
    /** Cells of the shell each seat holds; a seat away in its dock holds none. */
    public int cells(int seat) { return cells[seat]; }
    public Vec3 seat(int seat) { return seats[seat]; }
}
