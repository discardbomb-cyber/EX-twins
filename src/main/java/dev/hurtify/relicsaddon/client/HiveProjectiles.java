package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.domain.hive.HiveShapes;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The barrage shots of RF and Twins, a quarter of a block across, as they charge in their clumps and fly:
 * <ul>
 *   <li>RF, a ring of lightning (after the reference's ball): a bright blue rim round a dark middle, with
 *   branching bolts tearing round it, longer than the ring itself. It grows from a spark to a ring to a ring
 *   in its lightning, and trails small bolts in flight.</li>
 *   <li>Twins, a glass icosahedron: dark violet depth behind bright violet edges and the edges of an inner
 *   reflection, turning slowly as it charges and quickly in flight.</li>
 * </ul>
 * Mana's charge stays the glowing ball it was. Shots in flight are noted while the drones are drawn and drawn
 * with the constructs, where their glass has its batch ({@link #flush}).
 */
final class HiveProjectiles {
    /** A shot's size: a quarter of a block across. */
    static final double RADIUS = .125;
    private record Flying(HiveType type, Vec3 at, Vec3 heading, double time, long seed) { }
    private static final List<Flying> FLYING = new ArrayList<>();

    /** Notes a shot in flight, drawn at {@link #flush}. */
    static void fly(HiveType type, Vec3 at, Vec3 heading, double time, long seed) {
        if (FLYING.size() < 128) FLYING.add(new Flying(type, at, heading, time, seed));
    }

    static void flush(Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m) {
        for (Flying shot : FLYING) {
            Vec3 at = shot.at.subtract(camera);
            if (shot.type == HiveType.RF) {
                ring(glow, m, at, shot.heading, 1, shot.time, shot.seed);
                // A tail of small bolts.
                Vec3 back = shot.heading.lengthSqr() < 1e-8 ? new Vec3(0, -1, 0) : shot.heading.normalize().scale(-1);
                long flicker = (long) Math.floor(shot.time);
                for (int bolt = 0; bolt < 3; bolt++) {
                    Vec3 end = at.add(back.scale(.5 + .5 * bolt)).add(jitter(shot.seed * 7 + flicker, bolt, .25));
                    GlowBrush.lightning(glow, m, at, end, shot.seed * 13 + flicker * 3 + bolt, 4, .25, .008, 0xBFF6FF, 150 - 35 * bolt);
                }
            } else {
                icosahedron(glow, fill, m, at, RADIUS, shot.time * .35, 1);
                GlowBrush.dot(glow, m, at, RADIUS * 3, HiveModeVisual.color(HiveType.TWINS), 60);
            }
        }
        FLYING.clear();
    }

    /**
     * The RF ring at {@code at}, facing along {@code facing}, {@code charge} of the way grown (0 to 1): a spark,
     * then a ring, then a ring with bolts tearing round it up to a block and a half out.
     */
    static void ring(VertexConsumer glow, Matrix4f m, Vec3 at, Vec3 facing, double charge, double time, long seed) {
        Vec3[] axes = SwarmMath.axes(facing.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : facing);
        int blue = 0x38E8FF, hot = 0xBFF6FF;
        if (charge < .3) {
            GlowBrush.dot(glow, m, at, .04 + .2 * charge, hot, 120 + 400 * charge);
            return;
        }
        double grown = Math.min(1, (charge - .3) / .4), radius = RADIUS * grown;
        // A soft halo, so a ring a quarter of a block across still reads from across a fight.
        GlowBrush.dot(glow, m, at, radius * 3.2, blue, 70 + 60 * grown);
        GlowBrush.circle(glow, m, at, axes[1], axes[2], radius, 28, .018, hot, 240);
        GlowBrush.circle(glow, m, at, axes[1], axes[2], radius * 1.08, 28, .05, blue, 120);
        // Its bolts only near by, and fewer but at high detail.
        if (charge < .7 || at.lengthSqr() > 32 * 32) return;
        boolean high = HiveJuice.detail() == HiveJuice.Detail.HIGH;
        double reach = .6 + .9 * (charge - .7) / .3;
        long flicker = (long) Math.floor(time / 1.5);
        for (int bolt = 0; bolt < (high ? 5 : 3); bolt++) {
            double angle = hash(seed + flicker, bolt) * Math.PI * 2;
            Vec3 out = axes[1].scale(Math.cos(angle)).add(axes[2].scale(Math.sin(angle)));
            Vec3 start = at.add(out.scale(radius)), end = start.add(out.scale(reach * (.5 + .5 * hash(seed + flicker, bolt + 9))))
                    .add(axes[0].scale((hash(seed + flicker, bolt + 17) - .5) * .6));
            GlowBrush.lightning(glow, m, start, end, seed * 31 + flicker * 7 + bolt, 5, .3, .007, hot, 190);
            if (!high) continue;
            // A branch off each bolt.
            Vec3 fork = start.lerp(end, .5);
            GlowBrush.lightning(glow, m, fork, fork.add(jitter(seed + flicker, bolt, reach * .45)), seed * 37 + flicker + bolt, 3, .35, .005, blue, 150);
        }
    }

    /** The unit corners of an icosahedron and its thirty edges. */
    private static final Vec3[] ICO;
    private static final int[][] ICO_EDGES, ICO_FACES;

    static {
        double t = (1 + Math.sqrt(5)) / 2;
        Vec3[] corners = {new Vec3(-1, t, 0), new Vec3(1, t, 0), new Vec3(-1, -t, 0), new Vec3(1, -t, 0), new Vec3(0, -1, t), new Vec3(0, 1, t),
                new Vec3(0, -1, -t), new Vec3(0, 1, -t), new Vec3(t, 0, -1), new Vec3(t, 0, 1), new Vec3(-t, 0, -1), new Vec3(-t, 0, 1)};
        for (int index = 0; index < corners.length; index++) corners[index] = corners[index].normalize();
        ICO = corners;
        ICO_FACES = new int[][]{{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        List<int[]> edges = new ArrayList<>();
        for (int a = 0; a < 12; a++) for (int b = a + 1; b < 12; b++) if (corners[a].distanceTo(corners[b]) < 1.1) edges.add(new int[]{a, b});
        ICO_EDGES = edges.toArray(int[][]::new);
    }

    private static final Vec3 Y_AXIS = new Vec3(0, 1, 0), X_AXIS = new Vec3(1, 0, 0);

    /** A corner of an icosahedron of {@code radius} turned by {@code spin}; for tests and shards. */
    static Vec3 icosahedronCorner(int corner, double radius, double spin) {
        Vec3 p = SwarmMath.rotate(ICO[Math.floorMod(corner, 12)], Y_AXIS, spin);
        return SwarmMath.rotate(p, X_AXIS, spin * .6).scale(radius);
    }

    /** All twelve corners of that icosahedron, into {@code into}. */
    static Vec3[] icosahedronCorners(double radius, double spin, Vec3[] into) {
        for (int corner = 0; corner < 12; corner++) into[corner] = icosahedronCorner(corner, radius, spin);
        return into;
    }

    static int[][] icosahedronFaces() { return ICO_FACES; }

    /** The corners of the icosahedron being drawn and of its inner reflection, worked out once per figure. */
    private static final Vec3[] OUTER = new Vec3[12], INNER = new Vec3[12];

    /**
     * The Twins glass icosahedron: dark violet faces, bright edges, and the edges of an inner reflection turned
     * against it; {@code alpha} fades the whole.
     */
    static void icosahedron(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 at, double radius, double spin, double alpha) {
        int violet = 0xB151FF, bright = 0xE7C6FF;
        Vec3[] outer = icosahedronCorners(radius, spin, OUTER), inner = icosahedronCorners(radius * .55, -spin * 1.3, INNER);
        for (int[] face : ICO_FACES) {
            Vec3 oa = outer[face[0]], ob = outer[face[1]], oc = outer[face[2]];
            double ax = at.x + oa.x, ay = at.y + oa.y, az = at.z + oa.z;
            double bx = at.x + ob.x, by = at.y + ob.y, bz = at.z + ob.z;
            double cx = at.x + oc.x, cy = at.y + oc.y, cz = at.z + oc.z;
            double px = bx - ax, py = by - ay, pz = bz - az, qx = cx - ax, qy = cy - ay, qz = cz - az;
            double nx = py * qz - pz * qy, ny = pz * qx - px * qz, nz = px * qy - py * qx;
            double edgeOn = nx * nx + ny * ny + nz * nz < 1e-14 ? 0 : 1 - Math.abs(GlowBrush.facing(nx, ny, nz, ax, ay, az));
            int tint = GlowBrush.mix(0x12031F, violet, .25 + .6 * edgeOn);
            double body = (120 + 90 * edgeOn) * alpha;
            GlowBrush.quad(fill, m, ax, ay, az, bx, by, bz, cx, cy, cz, cx, cy, cz, tint, tint, tint, tint, body, body, body, body);
        }
        for (int[] edge : ICO_EDGES) {
            Vec3 a = outer[edge[0]], b = outer[edge[1]];
            GlowBrush.line(glow, m, at.x + a.x, at.y + a.y, at.z + a.z, at.x + b.x, at.y + b.y, at.z + b.z, .008, bright, 230 * alpha);
            a = inner[edge[0]]; b = inner[edge[1]];
            GlowBrush.line(glow, m, at.x + a.x, at.y + a.y, at.z + a.z, at.x + b.x, at.y + b.y, at.z + b.z, .005, violet, 150 * alpha);
        }
    }

    private static Vec3 jitter(long seed, int salt, double size) {
        return new Vec3(hash(seed, salt) - .5, hash(seed, salt + 3) - .5, hash(seed, salt + 5) - .5).scale(size * 2);
    }

    private static double hash(long seed, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private HiveProjectiles() { }
}
