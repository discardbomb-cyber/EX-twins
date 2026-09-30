package dev.hurtify.relicsaddon.domain.hive;

import net.minecraft.world.phys.Vec3;

/**
 * The shapes a swarm builds, as offsets from their centre for member {@code m} of {@code count}.
 * Everything here is pure geometry shared by the server (where blows land) and the renderer (where
 * drones are drawn), animated only by {@code time}.
 */
public final class HiveShapes {
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    // --- droplet mode --------------------------------------------------------------------------

    /**
     * RF: a tesseract. Sixteen drones sit on the corners of a four-dimensional cube and the rest line
     * its thirty-two edges; turning it through the fourth dimension gives the familiar animation of
     * the inner cube swelling out through the outer one.
     */
    public static Vec3 tesseract(int m, int count, double time, double size) {
        double[] p = tesseractPoint(m, count);
        return project(p, time, size);
    }

    /** One of the sixteen corners, projected; for drawing the edges between drones. */
    public static Vec3 tesseractCorner(int corner, double time, double size) {
        return project(corner(corner), time, size);
    }

    /** The 32 edges as pairs of corner indices (corners differing in exactly one coordinate). */
    public static final int[][] TESSERACT_EDGES = edges();

    private static double[] tesseractPoint(int m, int count) {
        if (m < 16) return corner(m);
        int extra = m - 16, layers = Math.max(1, (count - 16 + 31) / 32);
        int[] edge = TESSERACT_EDGES[extra % 32];
        double along = (extra / 32 + 1) / (double) (layers + 1);
        double[] a = corner(edge[0]), b = corner(edge[1]);
        return new double[]{a[0] + (b[0] - a[0]) * along, a[1] + (b[1] - a[1]) * along, a[2] + (b[2] - a[2]) * along, a[3] + (b[3] - a[3]) * along};
    }

    private static double[] corner(int index) {
        return new double[]{(index & 1) == 0 ? -1 : 1, (index & 2) == 0 ? -1 : 1, (index & 4) == 0 ? -1 : 1, (index & 8) == 0 ? -1 : 1};
    }

    private static int[][] edges() {
        int[][] result = new int[32][];
        int count = 0;
        for (int a = 0; a < 16; a++) for (int bit = 0; bit < 4; bit++) {
            int b = a ^ (1 << bit);
            if (a < b) result[count++] = new int[]{a, b};
        }
        return result;
    }

    /** Rotates through the XW and YZ planes, then projects from four dimensions with perspective. */
    private static Vec3 project(double[] p, double time, double size) {
        double a = time * .045, b = time * .021;
        double x = p[0] * Math.cos(a) - p[3] * Math.sin(a), w = p[0] * Math.sin(a) + p[3] * Math.cos(a);
        double y = p[1] * Math.cos(b) - p[2] * Math.sin(b), z = p[1] * Math.sin(b) + p[2] * Math.cos(b);
        double perspective = 2.6 / (3.6 - w);
        return new Vec3(x * perspective * size, y * perspective * size, z * perspective * size);
    }

    /** Mana: a droplet, round at the back and drawn to a point along {@code forward}. */
    public static Vec3 droplet(int m, int count, double time, Vec3 forward, double size) {
        double y = 1 - 2 * (m + .5) / count;
        double around = m * GOLDEN_ANGLE + time * .03;
        double ring = Math.sqrt(Math.max(0, 1 - y * y));
        // Front half narrows into a tip twice as long as the round back.
        double length = y > 0 ? y * 2.1 : y;
        double width = y > 0 ? ring * Math.pow(1 - y, .7) : ring;
        double wobble = 1 + .05 * Math.sin(time * .2 + m * .7);
        Vec3[] axes = axes(forward);
        return axes[0].scale(length * size).add(axes[1].scale(Math.cos(around) * width * size * wobble))
                .add(axes[2].scale(Math.sin(around) * width * size * wobble));
    }

    /** Twins: hexagons side by side across the direction of flight, each turning on its own. */
    public static Vec3 hexagons(int m, int count, double time, Vec3 forward, double size) {
        int rings = hexagonCount(count);
        int ring = m % rings, slot = m / rings, population = (count - 1 - ring) / rings + 1;
        Vec3 centre = hexagonCentre(ring, rings, time, forward, size);
        double spin = time * (ring % 2 == 0 ? .05 : -.05);
        double along = population <= 1 ? 0 : slot / (double) population * 6;
        Vec3 edge = hexagonPoint(along, spin, forward, size * .45);
        return centre.add(edge);
    }

    public static int hexagonCount(int count) {
        return Math.clamp(count / 10, 3, 6);
    }

    /** Centre of hexagon {@code ring}, relative to the group's centre. */
    public static Vec3 hexagonCentre(int ring, int rings, double time, Vec3 forward, double size) {
        Vec3[] axes = axes(forward);
        double angle = ring * Math.PI * 2 / rings + time * .012;
        return axes[1].scale(Math.cos(angle) * size * .9).add(axes[2].scale(Math.sin(angle) * size * .9));
    }

    /** A point {@code along} (0..6, one unit per side) the outline of a hexagon across {@code forward}. */
    public static Vec3 hexagonPoint(double along, double spin, Vec3 forward, double radius) {
        Vec3[] axes = axes(forward);
        int side = (int) Math.floor(along) % 6;
        double t = along - Math.floor(along);
        double a0 = spin + side * Math.PI / 3, a1 = spin + (side + 1) * Math.PI / 3;
        double c = Math.cos(a0) * (1 - t) + Math.cos(a1) * t, s = Math.sin(a0) * (1 - t) + Math.sin(a1) * t;
        return axes[1].scale(c * radius).add(axes[2].scale(s * radius));
    }

    // --- barrage mode --------------------------------------------------------------------------

    /**
     * Member {@code m} of a dense barrage clump of {@code count} drones around its charge, within
     * {@code radius}: RF a thick shell swirling faster inside than out, Mana a breathing shell, Twins a
     * thick octagonal disc across the line to the target ({@code facing}). The shells leave the middle
     * to the glowing charge.
     */
    public static Vec3 clump(HiveType type, int m, int count, double time, Vec3 facing, double radius) {
        count = Math.max(1, count);
        double fill = (m + .5) / count;
        return switch (type) {
            case RF, MANA -> {
                // Golden-ratio directions with depth growing by volume: an even, dense shell.
                double y = 1 - 2 * ((m * .6180339887 + .25) % 1);
                double ring = Math.sqrt(Math.max(0, 1 - y * y)), around = m * GOLDEN_ANGLE;
                double depth = radius * (.55 + .45 * Math.cbrt(fill));
                Vec3 direction = new Vec3(Math.cos(around) * ring, y, Math.sin(around) * ring);
                if (type == HiveType.RF) {
                    yield rotate(direction, RF_SWIRL, time * (.07 - .03 * fill)).scale(depth);
                }
                double breath = 1 + .07 * Math.sin(time * .12 + m * .4);
                yield rotate(direction, new Vec3(0, 1, 0), -time * .035).scale(depth * breath);
            }
            case TWINS -> {
                Vec3[] axes = axes(facing);
                double spin = time * .02, angle = m * GOLDEN_ANGLE + spin;
                // Spread evenly over the disc, then pushed out to the octagon's edge along each spoke.
                double relative = ((angle - spin) % (Math.PI / 4) + Math.PI / 4) % (Math.PI / 4) - Math.PI / 8;
                double r = radius * 1.2 * Math.sqrt(fill) * Math.cos(Math.PI / 8) / Math.cos(relative);
                double thickness = (((m * .7548776662) % 1) - .5) * radius * .7;
                yield axes[1].scale(Math.cos(angle) * r).add(axes[2].scale(Math.sin(angle) * r)).add(axes[0].scale(thickness));
            }
        };
    }

    private static final Vec3 RF_SWIRL = new Vec3(.3, 1, .2).normalize();

    /** {@code v} turned by {@code angle} about the unit {@code axis}. */
    public static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos)));
    }

    // --- containment mode ----------------------------------------------------------------------

    /** RF: a torus of hexagon rows around the target, turning and twisting slowly. */
    public static Vec3 torus(int s, int count, double time, double major, double minor) {
        int rows = 6, perRow = Math.max(1, (count + rows - 1) / rows);
        int row = s % rows, column = s / rows;
        double u = Math.PI * 2 * (column + (row % 2) * .5) / perRow + time * .015;
        double v = Math.PI * 2 * row / rows + time * .03;
        double ring = major + minor * Math.cos(v);
        return new Vec3(Math.cos(u) * ring, minor * Math.sin(v), Math.sin(u) * ring);
    }

    /**
     * Mana: a ward of three upright rhombi turned 120 degrees apart and two level circles above and
     * below; drones stream along its lines.
     */
    public static Vec3 ward(int s, int count, double time, double scale) {
        double rhombus = 4 * Math.hypot(1.1, 1.55), circle = Math.PI * 2 * 1.3;
        double total = 3 * rhombus + 2 * circle;
        double at = ((s + .5) / count * total + time * .02) % total;
        double turn = time * .008;
        for (int index = 0; index < 3; index++) {
            if (at < rhombus) return rhombusPoint(at / rhombus, turn + index * Math.PI * 2 / 3).scale(scale);
            at -= rhombus;
        }
        boolean top = at < circle;
        double angle = (top ? at : at - circle) / circle * Math.PI * 2 - turn;
        return new Vec3(Math.cos(angle) * 1.3, top ? 1.0 : -1.0, Math.sin(angle) * 1.3).scale(scale);
    }

    /** A point {@code t} (0..1) around an upright rhombus turned by {@code angle} about the vertical. */
    public static Vec3 rhombusPoint(double t, double angle) {
        double[][] corners = {{0, 1.55}, {1.1, 0}, {0, -1.55}, {-1.1, 0}};
        double along = (t % 1) * 4;
        int side = (int) Math.floor(along);
        double f = along - side;
        double[] a = corners[side], b = corners[(side + 1) % 4];
        double h = a[0] + (b[0] - a[0]) * f, y = a[1] + (b[1] - a[1]) * f;
        return new Vec3(Math.cos(angle) * h, y, Math.sin(angle) * h);
    }

    /** Twins: four hexagon-shelled spheres around the target, now and then spinning up like rifts opening. */
    public static Vec3 riftSpheres(int s, int count, double time, double distance) {
        int spheres = 4, sphere = s % spheres, j = s / spheres, population = (count - 1 - sphere) / spheres + 1;
        Vec3 centre = riftCentre(sphere, time, distance);
        double spin = riftSpin(sphere, time);
        double y = 1 - 2 * (j + .5) / Math.max(1, population);
        double ring = Math.sqrt(Math.max(0, 1 - y * y)), around = j * GOLDEN_ANGLE + spin;
        return centre.add(Math.cos(around) * ring * .5, y * .5, Math.sin(around) * ring * .5);
    }

    public static Vec3 riftCentre(int sphere, double time, double distance) {
        double[][] tetra = {{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}};
        double[] d = tetra[sphere % 4];
        double turn = time * .01, length = Math.sqrt(3);
        double x = d[0] / length, y = d[1] / length * .6, z = d[2] / length;
        return new Vec3((x * Math.cos(turn) - z * Math.sin(turn)) * distance, y * distance, (x * Math.sin(turn) + z * Math.cos(turn)) * distance);
    }

    /** Accumulated spin of a rift sphere: its speed swells from a drift to a whirl and back (the integral of .05 + .04 sin). */
    public static double riftSpin(int sphere, double time) {
        double rate = .007;
        return time * .05 - .04 / rate * Math.cos(time * rate + sphere * 1.7);
    }

    /** Forward, side and up unit axes around a direction (forward first). */
    public static Vec3[] axes(Vec3 forward) {
        Vec3 f = forward.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : forward.normalize();
        Vec3 side = f.cross(Math.abs(f.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        return new Vec3[]{f, side, side.cross(f)};
    }

    private HiveShapes() {
    }
}
