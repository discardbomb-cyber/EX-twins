package dev.hurtify.relicsaddon.domain.hive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.hurtify.relicsaddon.domain.math.Vec3d;

/**
 * The containment constructs, as offsets from the middle of the creature they hold for place {@code s} of
 * {@code count}. Drones stand on the constructs' corners; the lines between them are drawn by the renderer
 * from {@link #edges}. All pure geometry, shared by the server and the client, animated only by the time
 * since the construct began to close ({@code age}, in ticks) and the world time.
 * <ul>
 *   <li>RF, a Faraday cage: a geodesic sphere. Its first twelve drones make an icosahedron; more drones make
 *   the finer geodesic spheres (42, 92, 162 corners), and any left over stand on the middles of their edges.
 *   It closes from the ground up, its panels folding in like petals.</li>
 *   <li>Mana, a lotus: tiers of six glass petals rising from a common base and closing into a bud over the
 *   creature, each petal a base shared by all, two side corners and a tip.</li>
 *   <li>Twins, a rift: hexagonal shards of broken space circling the creature, each on its own axis.</li>
 * </ul>
 */
public final class HiveConstructs {
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    /** Ticks a construct takes to close round its creature once its drones arrive. */
    public static final double CLOSING = 24;

    // --- RF: the Faraday cage --------------------------------------------------------------------------

    /** Corners of the coarsest cage, an icosahedron: the fewest drones that make one. */
    public static final int CAGE_CORNERS = 12;
    /** The cage's radius per block of the room it leaves round its creature: it sits just outside it. */
    public static final double CAGE_SCALE = 1.08;

    /** A geodesic sphere of frequency {@code f}: its unit corners, its edges as pairs of corners and its triangles as threes. */
    public record Geodesic(int frequency, List<Vec3d> corners, List<int[]> edges, List<int[]> faces) { }

    private static final Map<Integer, Geodesic> GEODESICS = new HashMap<>();

    /** Corners of a geodesic sphere of frequency {@code f}. */
    public static int geodesicCorners(int f) {
        return 10 * f * f + 2;
    }

    /** The geodesic sphere of frequency {@code f} (1 is the icosahedron), its first corners the icosahedron's. */
    public static synchronized Geodesic geodesic(int f) {
        return GEODESICS.computeIfAbsent(Math.clamp(f, 1, 12), HiveConstructs::buildGeodesic);
    }

    private static Geodesic buildGeodesic(int f) {
        double t = (1 + Math.sqrt(5)) / 2;
        Vec3d[] ico = {new Vec3d(-1, t, 0), new Vec3d(1, t, 0), new Vec3d(-1, -t, 0), new Vec3d(1, -t, 0), new Vec3d(0, -1, t), new Vec3d(0, 1, t),
                new Vec3d(0, -1, -t), new Vec3d(0, 1, -t), new Vec3d(t, 0, -1), new Vec3d(t, 0, 1), new Vec3d(-t, 0, -1), new Vec3d(-t, 0, 1)};
        int[][] faces = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        List<Vec3d> corners = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (Vec3d corner : ico) add(corners, index, corner.normalize());
        List<int[]> edges = new ArrayList<>(), triangles = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] face : faces) {
            Vec3d a = ico[face[0]], b = ico[face[1]], c = ico[face[2]];
            int[][] grid = new int[f + 1][];
            for (int i = 0; i <= f; i++) {
                grid[i] = new int[f + 1 - i];
                for (int j = 0; j <= f - i; j++) {
                    Vec3d p = a.add(b.subtract(a).scale(i / (double) f)).add(c.subtract(a).scale(j / (double) f)).normalize();
                    grid[i][j] = add(corners, index, p);
                }
            }
            for (int i = 0; i <= f; i++) for (int j = 0; j <= f - i; j++) {
                if (i + 1 <= f - j) {
                    edge(edges, seen, grid[i][j], grid[i + 1][j]);
                    edge(edges, seen, grid[i][j], grid[i][j + 1]);
                    edge(edges, seen, grid[i + 1][j], grid[i][j + 1]);
                    triangles.add(new int[]{grid[i][j], grid[i + 1][j], grid[i][j + 1]});
                }
                if (i + j <= f - 2) triangles.add(new int[]{grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]});
            }
        }
        return new Geodesic(f, List.copyOf(corners), List.copyOf(edges), List.copyOf(triangles));
    }

    private static int add(List<Vec3d> corners, Map<String, Integer> index, Vec3d p) {
        String key = Math.round(p.x * 1e6) + "," + Math.round(p.y * 1e6) + "," + Math.round(p.z * 1e6);
        return index.computeIfAbsent(key, ignored -> {
            corners.add(p);
            return corners.size() - 1;
        });
    }

    private static void edge(List<int[]> edges, java.util.Set<Long> seen, int a, int b) {
        long key = Math.min(a, b) * 100_000L + Math.max(a, b);
        if (seen.add(key)) edges.add(new int[]{Math.min(a, b), Math.max(a, b)});
    }

    /** The finest geodesic sphere {@code count} drones can make whole (the icosahedron if fewer than twelve). */
    public static int cageFrequency(int count) {
        int f = 1;
        while (geodesicCorners(f + 1) <= count && f < 12) f++;
        return f;
    }

    /**
     * Unit direction of cage place {@code s} of {@code count}, before the cage turns: a corner of the finest
     * whole geodesic sphere, or, past its corners, the middle of one of its edges, spread evenly round it.
     */
    public static Vec3d cageDirection(int s, int count) {
        Geodesic sphere = geodesic(cageFrequency(count));
        int corners = sphere.corners().size();
        if (s < corners) return sphere.corners().get(Math.max(0, s));
        int extra = s - corners, extras = Math.max(1, count - corners), edges = sphere.edges().size();
        int[] edge = sphere.edges().get((int) ((long) extra * edges / extras) % edges);
        return sphere.corners().get(edge[0]).add(sphere.corners().get(edge[1])).normalize();
    }

    /** Which cage place joins which: the geodesic edges, a mid-edge place joined to both ends of its edge. */
    public static List<int[]> cageEdges(int count) {
        Geodesic sphere = geodesic(cageFrequency(count));
        int corners = sphere.corners().size();
        if (count < corners) {
            List<int[]> some = new ArrayList<>();
            for (int[] edge : sphere.edges()) if (edge[0] < count && edge[1] < count) some.add(edge);
            return some;
        }
        List<int[]> edges = new ArrayList<>(sphere.edges());
        int extras = count - corners;
        for (int extra = 0; extra < extras; extra++) {
            int[] edge = sphere.edges().get((int) ((long) extra * sphere.edges().size() / extras) % sphere.edges().size());
            edges.add(new int[]{edge[0], corners + extra});
            edges.add(new int[]{edge[1], corners + extra});
        }
        return edges;
    }

    /** The cage's panels, as threes of places: the triangles of the finest whole geodesic sphere whose corners are all flown. */
    public static List<int[]> cageFaces(int count) {
        Geodesic sphere = geodesic(cageFrequency(count));
        if (count >= sphere.corners().size()) return sphere.faces();
        List<int[]> some = new ArrayList<>();
        for (int[] face : sphere.faces()) if (face[0] < count && face[1] < count && face[2] < count) some.add(face);
        return some;
    }

    /** The cage's turn: slowly about the vertical, its axis nodding a little. */
    private static Vec3d cageTurn(Vec3d v, double time) {
        Vec3d turned = HiveShapes.rotate(v, new Vec3d(0, 1, 0), time * .006);
        return HiveShapes.rotate(turned, new Vec3d(1, 0, 0), .18 * Math.sin(time * .004));
    }

    /**
     * Cage place {@code s} of {@code count} round a room of {@code room} blocks radius, {@code age} ticks after
     * the cage began to close. Each drone rises from a ring on the ground up its meridian to its corner, the
     * lowest first, so the cage folds shut from the ground up.
     */
    public static Vec3d cage(int s, int count, double age, double time, double room) {
        Vec3d corner = cageDirection(s, count);
        double radius = room * CAGE_SCALE;
        double height = Math.asin(Math.clamp(corner.y, -1, 1)), around = Math.atan2(corner.z, corner.x);
        double start = (corner.y + 1) / 2 * CLOSING * .55, closed = smooth((age - start) / (CLOSING * .45));
        // Each starts from its own height on the folded-down panels, so no two share a place on the way up.
        double low = -Math.PI / 2 * .98 + .45 * (corner.y + 1) / 2;
        double elevation = low + (height - low) * closed;
        double out = radius * (1 + (.35 + .1 * (corner.y + 1)) * (1 - closed));
        Vec3d at = new Vec3d(Math.cos(around) * Math.cos(elevation), Math.sin(elevation), Math.sin(around) * Math.cos(elevation)).scale(out);
        return cageTurn(at, time);
    }

    /** How far below the middle of its creature the cage reaches: its radius and the drones on it. */
    public static double cageReach(double room) {
        return room * CAGE_SCALE + .2;
    }

    // --- Mana: the lotus -------------------------------------------------------------------------------

    /** Petals in a tier, corners of a petal of its own (the base is shared), and the tiers there can be. */
    public static final int PETALS = 6, PETAL_CORNERS = 3, TIERS = 3;
    /** The corners of one tier and its shared base: the fewest drones that make a lotus. */
    public static final int LOTUS_CORNERS = 1 + PETALS * PETAL_CORNERS;
    /** The lotus's size per block of the room it leaves round its creature. */
    public static final double LOTUS_SCALE = 1.12;
    /** Each tier's side corners (radius, height) and tip when closed and when open, in lotus sizes. */
    private static final double[][] TIER = {
            {.92, -.12, .22, .98, 1.05, .45},
            {1.06, -.38, .55, .72, 1.35, .10},
            {1.18, -.64, .86, .30, 1.55, -.30}};
    /** Half the angle a petal spans, and how far each tier is turned from the first. */
    private static final double PETAL_HALF = Math.toRadians(27), TIER_TURN = Math.toRadians(30);

    /** Tiers {@code count} drones can make whole (one at least). */
    public static int lotusTiers(int count) {
        return Math.clamp((count - 1) / (PETALS * PETAL_CORNERS), 1, TIERS);
    }

    /**
     * Lotus place {@code s} of {@code count}, as a point in lotus sizes before it turns: the shared base, then
     * each petal's left side, right side and tip, tier by tier; past whole tiers, the middles of the petals.
     * {@code open} is how far the petals lie open (1 flat out, 0 closed into a bud).
     */
    public static Vec3d lotusPoint(int s, int count, double open) {
        if (s == 0) return new Vec3d(0, -1, 0);
        int tiers = lotusTiers(count), whole = 1 + tiers * PETALS * PETAL_CORNERS;
        if (s >= whole) {
            int extra = s - whole, petals = tiers * PETALS, petal = extra % petals, layer = extra / petals + 1;
            int tier = petal / PETALS, p = petal % PETALS;
            Vec3d base = new Vec3d(0, -1, 0), left = petalCorner(tier, p, 0, open), right = petalCorner(tier, p, 1, open), tip = petalCorner(tier, p, 2, open);
            double along = layer / (double) (layer + 1);
            Vec3d middle = left.add(right).scale(.5);
            return base.lerp(middle, .5).lerp(tip, along * .8);
        }
        int index = s - 1, tier = index / (PETALS * PETAL_CORNERS), p = index / PETAL_CORNERS % PETALS, corner = index % PETAL_CORNERS;
        return petalCorner(tier, p, corner, open);
    }

    /** Corner {@code corner} (0 left side, 1 right side, 2 tip) of petal {@code p} of tier {@code tier}. */
    public static Vec3d petalCorner(int tier, int p, int corner, double open) {
        double[] shape = TIER[Math.clamp(tier, 0, TIERS - 1)];
        double angle = p * Math.PI * 2 / PETALS + tier * TIER_TURN;
        if (corner == 2) {
            double radius = shape[2] + (shape[4] - shape[2]) * open, height = shape[3] + (shape[5] - shape[3]) * open;
            return new Vec3d(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
        }
        double side = angle + (corner == 0 ? -PETAL_HALF : PETAL_HALF), height = shape[1] - .15 * open;
        return new Vec3d(Math.cos(side) * shape[0], height, Math.sin(side) * shape[0]);
    }

    /** Which lotus places join which: each petal's outline from the base up round its tip, and the petals' middles to their tips. */
    public static List<int[]> lotusEdges(int count) {
        List<int[]> edges = new ArrayList<>();
        int tiers = lotusTiers(count);
        for (int tier = 0; tier < tiers; tier++) for (int p = 0; p < PETALS; p++) {
            int first = 1 + (tier * PETALS + p) * PETAL_CORNERS;
            if (first + 2 >= count) continue;
            edges.add(new int[]{0, first});
            edges.add(new int[]{0, first + 1});
            edges.add(new int[]{first, first + 2});
            edges.add(new int[]{first + 1, first + 2});
        }
        int whole = 1 + tiers * PETALS * PETAL_CORNERS;
        for (int s = whole; s < count; s++) {
            int petal = (s - whole) % (tiers * PETALS);
            edges.add(new int[]{s, 1 + petal * PETAL_CORNERS + 2});
        }
        return edges;
    }

    /**
     * Lotus place {@code s} of {@code count} round a room of {@code room} blocks radius, {@code age} ticks after
     * the lotus began to close: the petals rise from lying open on the ground and close into a bud over the
     * creature, the inner tier first; the bud turns slowly.
     */
    public static Vec3d lotus(int s, int count, double age, double time, double room) {
        int tier = s == 0 ? 0 : Math.min(TIERS - 1, (s - 1) / (PETALS * PETAL_CORNERS));
        double closed = smooth((age - tier * CLOSING * .2) / (CLOSING * .6));
        double breathe = .04 * Math.sin(time * .05);
        Vec3d at = lotusPoint(s, count, Math.clamp(1 - closed + breathe, 0, 1)).scale(room * LOTUS_SCALE);
        return HiveShapes.rotate(at, new Vec3d(0, 1, 0), time * .004);
    }

    /** How far below the middle of its creature the lotus reaches: its shared base. */
    public static double lotusReach(double room) {
        return room * LOTUS_SCALE + .15;
    }

    // --- Twins: the rift -------------------------------------------------------------------------------

    /** Shards a rift has at least and at most, and the corners of each. */
    public static final int MIN_SHARDS = 4, MAX_SHARDS = 12, SHARD_CORNERS = 6;
    /** The corners of the fewest shards that make a rift. */
    public static final int RIFT_CORNERS = MIN_SHARDS * SHARD_CORNERS;
    /** How far the shards circle from the creature's middle, and their size, per block of the room round it. */
    public static final double RIFT_DISTANCE = 1.35, SHARD_SIZE = .62;

    /** Shards {@code count} drones make. */
    public static int shards(int count) {
        return Math.clamp(count / SHARD_CORNERS, MIN_SHARDS, MAX_SHARDS);
    }

    /** The shard a rift place belongs to, and its place in it (corners 0 to 5, then an inner hexagon). */
    public static int shardOf(int s, int count) {
        return s % shards(count);
    }

    /** Where shard {@code shard} of {@code shards} sits (a unit direction from the creature) and which way it faces. */
    public static Vec3d shardDirection(int shard, int shards, double time) {
        double y = 1 - 2 * (shard + .5) / shards, ring = Math.sqrt(Math.max(0, 1 - y * y)), around = shard * GOLDEN_ANGLE;
        Vec3d home = new Vec3d(Math.cos(around) * ring, y * .8, Math.sin(around) * ring).normalize();
        Vec3d axis = new Vec3d(hash(shard, 1) - .5, 1, hash(shard, 2) - .5).normalize();
        // Each shard circles on its own axis; now and then it surges round faster.
        double surge = Math.pow(Math.max(0, Math.sin(time * .013 + shard * 1.7)), 8);
        double angle = time * (.012 + .01 * hash(shard, 3)) * (hash(shard, 4) < .5 ? -1 : 1) + surge * 2.2 * Math.sin(shard + 1.0);
        return HiveShapes.rotate(home, axis, angle);
    }

    /** Corner {@code corner} (0 to 5) of a shard facing {@code direction}, {@code radius} across, turned by {@code spin}. */
    public static Vec3d shardCorner(Vec3d direction, int corner, double spin, double radius) {
        Vec3d[] axes = HiveShapes.axes(direction);
        double angle = spin + corner * Math.PI / 3;
        return axes[1].scale(Math.cos(angle) * radius).add(axes[2].scale(Math.sin(angle) * radius));
    }

    /**
     * Rift place {@code s} of {@code count} round a room of {@code room} blocks radius, {@code age} ticks after
     * the rift began to open: the shards crack out from the creature to their orbits. Past six corners a
     * shard's drones make a smaller hexagon inside it.
     */
    public static Vec3d rift(int s, int count, double age, double time, double room) {
        int shards = shards(count), shard = s % shards, member = s / shards;
        double opened = smooth(age / CLOSING);
        Vec3d direction = shardDirection(shard, shards, time);
        double distance = room * RIFT_DISTANCE * (.35 + .65 * opened), size = room * SHARD_SIZE * (.4 + .6 * opened);
        double spin = time * (shard % 2 == 0 ? .02 : -.02) + shard;
        Vec3d centre = direction.scale(distance);
        if (member < SHARD_CORNERS) return centre.add(shardCorner(direction, member, spin, size));
        int population = (count - 1 - shard) / shards + 1, inner = Math.max(1, population - SHARD_CORNERS);
        double along = (member - SHARD_CORNERS) / (double) inner * SHARD_CORNERS + .5;
        return centre.add(HiveShapes.hexagonPoint(along, -spin, direction, size * .55));
    }

    /** How far from the middle of its creature the rift's shards reach. */
    public static double riftReach(double room) {
        return room * (RIFT_DISTANCE + SHARD_SIZE) + .2;
    }

    // --- shared ----------------------------------------------------------------------------------------

    private static double smooth(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double hash(int index, int salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private HiveConstructs() { }
}
