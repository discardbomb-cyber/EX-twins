package dev.hurtify.relicsaddon.shipshield;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The exact shape of a ship shield: every point no further than the offset from any block of the
 * structure. The shell is the boundary of that set, so "inside the shield" is a distance test
 * against the blocks themselves, with no mesh in the way, and a shot's way in is found by walking
 * its path until it is inside. Positions are in the structure's own coordinates (a ship's plot).
 * Air sealed inside the structure further than the offset from any block (a cabin, a hold) is
 * under the shield too: the shell is one outer surface. Pure: no level, no side; shared by the
 * server (hits) and the client (drawing).
 */
public final class ShellField {
    /** The nearest point of the structure to a point, and how far it is. */
    public record Nearest(double distance, double x, double y, double z) {
    }

    /** How finely a shot's path is walked, in blocks, before the crossing is bisected. */
    private static final double WALK = .5;

    private final LongSet blocks;
    private final double offset;
    private final int window;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    /** Voxels within the block box that lie beyond the offset yet are sealed in by the structure; made on first use. */
    private volatile java.util.BitSet enclosed;

    public ShellField(LongSet blocks, double offset) {
        this.blocks = new LongOpenHashSet(blocks);
        this.offset = offset;
        this.window = (int) Math.ceil(offset) + 1;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long packed : blocks) {
            int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        }
        if (blocks.isEmpty()) x0 = y0 = z0 = x1 = y1 = z1 = 0;
        minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
    }

    public double offset() { return offset; }
    public int size() { return blocks.size(); }
    public boolean isEmpty() { return blocks.isEmpty(); }
    public LongSet blocks() { return blocks; }
    public boolean hasBlock(int x, int y, int z) { return blocks.contains(BlockPos.asLong(x, y, z)); }

    /** The blocks' own box (whole blocks). */
    public AABB blockBounds() { return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1); }

    /** The box the shell fits in. */
    public AABB bounds() { return blockBounds().inflate(offset); }

    /** The structure's nearest point to {@code (x, y, z)}; the distance is {@code offset + 2} or more when nothing is near. */
    public Nearest nearest(double x, double y, double z) {
        double far = offset + 2;
        if (blocks.isEmpty() || x < minX - far || y < minY - far || z < minZ - far || x > maxX + 1 + far || y > maxY + 1 + far || z > maxZ + 1 + far) {
            return new Nearest(far, x, y, z);
        }
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
        double best = far, bx = x, by = y, bz = z;
        for (int shell = 0; shell <= window; shell++) {
            // Every block in shell k lies at least k - 1 away: once the best is nearer, no farther shell can beat it.
            if (best <= shell - 1) break;
            for (int dx = -shell; dx <= shell; dx++) for (int dy = -shell; dy <= shell; dy++) for (int dz = -shell; dz <= shell; dz++) {
                if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != shell) continue;
                int qx = cx + dx, qy = cy + dy, qz = cz + dz;
                if (!blocks.contains(BlockPos.asLong(qx, qy, qz))) continue;
                double px = Math.clamp(x, qx, qx + 1), py = Math.clamp(y, qy, qy + 1), pz = Math.clamp(z, qz, qz + 1);
                double distance = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
                if (distance < best) {
                    best = distance;
                    bx = px; by = py; bz = pz;
                }
            }
        }
        return new Nearest(best, bx, by, bz);
    }

    public double distance(double x, double y, double z) { return nearest(x, y, z).distance(); }
    public double distance(Vec3 point) { return distance(point.x, point.y, point.z); }

    /** Whether a point is under the shield: no further than the offset from the structure, or sealed inside it. */
    public boolean inside(double x, double y, double z) { return distance(x, y, z) <= offset || enclosed(x, y, z); }

    /** Works out the sealed cavities now (call off-thread; otherwise the first {@link #inside} does it). */
    public void warm() { enclosedVoxels(); }

    private boolean enclosed(double x, double y, double z) {
        int vx = (int) Math.floor(x) - minX, vy = (int) Math.floor(y) - minY, vz = (int) Math.floor(z) - minZ;
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        if (vx < 0 || vy < 0 || vz < 0 || vx >= sx || vy >= sy || vz >= sz) return false;
        return enclosedVoxels().get((vx * sy + vy) * sz + vz);
    }

    /**
     * Voxels of the block box beyond the offset that the outside cannot reach: flooded from a
     * margin round the box through voxels whose middle is beyond the offset; what the flood misses
     * is sealed in.
     */
    private java.util.BitSet enclosedVoxels() {
        java.util.BitSet known = enclosed;
        if (known != null) return known;
        synchronized (this) {
            if (enclosed != null) return enclosed;
            int margin = window + 1;
            int ox = minX - margin, oy = minY - margin, oz = minZ - margin;
            int sx = maxX - minX + 1 + 2 * margin, sy = maxY - minY + 1 + 2 * margin, sz = maxZ - minZ + 1 + 2 * margin;
            java.util.BitSet open = new java.util.BitSet(sx * sy * sz), reached = new java.util.BitSet(sx * sy * sz);
            for (int i = 0; i < sx; i++) for (int j = 0; j < sy; j++) for (int k = 0; k < sz; k++) {
                if (distance(ox + i + .5, oy + j + .5, oz + k + .5) > offset) open.set((i * sy + j) * sz + k);
            }
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            reached.set(0);
            queue.add(0);
            int[] strides = {sy * sz, sz, 1}, sizes = {sx, sy, sz};
            while (!queue.isEmpty()) {
                int at = queue.poll();
                int[] here = {at / (sy * sz), at / sz % sy, at % sz};
                for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
                    int coordinate = here[axis] + sign;
                    if (coordinate < 0 || coordinate >= sizes[axis]) continue;
                    int next = at + sign * strides[axis];
                    if (reached.get(next) || !open.get(next)) continue;
                    reached.set(next);
                    queue.add(next);
                }
            }
            int bx = maxX - minX + 1, by = maxY - minY + 1, bz = maxZ - minZ + 1;
            java.util.BitSet sealed = new java.util.BitSet(bx * by * bz);
            for (int i = 0; i < bx; i++) for (int j = 0; j < by; j++) for (int k = 0; k < bz; k++) {
                int index = ((i + margin) * sy + j + margin) * sz + k + margin;
                if (open.get(index) && !reached.get(index)) sealed.set((i * by + j) * bz + k);
            }
            enclosed = sealed;
            return sealed;
        }
    }
    public boolean inside(Vec3 point) { return inside(point.x, point.y, point.z); }

    /** The way out of the shield at a point: away from the nearest block (straight up when the point is inside a block). */
    public Vec3 outward(Vec3 point) {
        Nearest near = nearest(point.x, point.y, point.z);
        Vec3 out = new Vec3(point.x - near.x(), point.y - near.y(), point.z - near.z());
        return out.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : out.normalize();
    }

    /**
     * Where the way from {@code a} to {@code b} first comes under the shield, as a fraction of the way
     * (0..1), or -1 when it never does or when it starts inside (the ship's own shots fly out).
     */
    public double entry(Vec3 a, Vec3 b) {
        if (blocks.isEmpty() || inside(a)) return -1;
        AABB box = bounds();
        if (!box.contains(a) && !box.contains(b) && box.clip(a, b).isEmpty()) return -1;
        double length = a.distanceTo(b);
        int steps = Math.max(1, (int) Math.ceil(length / WALK));
        double before = 0;
        for (int step = 1; step <= steps; step++) {
            double t = step / (double) steps;
            if (inside(a.lerp(b, t))) {
                double low = before, high = t;
                for (int round = 0; round < 8; round++) {
                    double mid = (low + high) * .5;
                    if (inside(a.lerp(b, mid))) high = mid; else low = mid;
                }
                return high;
            }
            before = t;
        }
        return -1;
    }

    /** A stable fingerprint of the blocks, for caches. */
    public long fingerprint() {
        long hash = 1469598103934665603L ^ Double.doubleToLongBits(offset);
        long sum = 0, xor = 0;
        for (long packed : blocks) {
            long mixed = packed * 0x9E3779B97F4A7C15L;
            mixed ^= mixed >>> 29;
            sum += mixed;
            xor ^= mixed;
        }
        return hash ^ sum ^ (xor << 1) ^ (long) blocks.size() << 48;
    }
}
