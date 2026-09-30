package dev.hurtify.relicsaddon.drone;

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
     * {@code radius}. Every drone keeps to its own orbit round the charge, tilted its own way and run
     * at its own pace and direction, so the clump swirls on every axis and leaves its middle to the
     * glowing charge: RF quick, Mana slower and breathing, Twins between.
     */
    public static Vec3 clump(HiveType type, int m, int count, double time, Vec3 facing, double radius) {
        count = Math.max(1, count);
        double fill = (m + .5) / count;
        Vec3 axis = new Vec3(hash(m, 1) * 2 - 1, hash(m, 2) * 2 - 1, hash(m, 3) * 2 - 1);
        axis = axis.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : axis.normalize();
        Vec3 start = axis.cross(Math.abs(axis.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        double pace = switch (type) {
            case RF -> .09;
            case MANA -> .05;
            case TWINS -> .07;
        };
        double speed = pace * (.7 + .6 * hash(m, 4)) * (hash(m, 5) < .5 ? -1 : 1);
        double depth = radius * (.55 + .45 * Math.cbrt(fill));
        if (type == HiveType.MANA) depth *= 1 + .07 * Math.sin(time * .12 + m * .4);
        return rotate(start, axis, time * speed + hash(m, 6) * Math.PI * 2).scale(depth);
    }

    /** Stable per-drone random number in [0, 1). */
    private static double hash(int index, int salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** {@code v} turned by {@code angle} about the unit {@code axis}. */
    public static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos)));
    }

    // --- containment mode ----------------------------------------------------------------------

    /**
     * RF: three tori round the target like the rings of a Dyson swarm, each covered in hexagons, of
     * growing radius, each tilted its own way and turning about its own axis; the further out a torus,
     * the faster it turns and the faster its tilt sweeps round. Drones ride just above its hexagons.
     */
    public static final double[] RING_RADII = {.5, .75, 1};
    private static final double[] RING_SPIN = {.012, .022, .036}, RING_PRECESSION = {.004, .007, .011};
    /** Each ring's tilt: an axis in the level plane and an angle from level. */
    private static final double[][] RING_TILT = {{1, 0, 0, .44}, {0, 0, 1, 1.13}, {.7071, 0, .7071, 1.92}};
    /** Rows of hexagons round each torus's tube. */
    public static final int TUBE_ROWS = 6;

    /** Radius of the tube of torus {@code ring} in a construct of {@code radius}. */
    public static double ringTube(int ring, double radius) {
        return radius * RING_RADII[ring] * .13 + .1;
    }

    /** Columns of hexagons along torus {@code ring}, so its hexagons come out about as wide as they are tall. */
    public static int ringColumns(int ring, double radius) {
        double tall = Math.PI * 2 * ringTube(ring, radius) / (1.5 * TUBE_ROWS);
        return Math.max(8, (int) Math.round(Math.PI * 2 * radius * RING_RADII[ring] / (Math.sqrt(3) * tall)));
    }

    /** Place {@code s} of {@code count}: just above one of the hexagons of its torus, spread evenly over them. */
    public static Vec3 dysonRing(int s, int count, double time, double radius) {
        count = Math.max(1, count);
        s = Math.clamp(s, 0, count - 1);
        int ring = 0;
        while (ring < 2 && s >= ringStart(ring + 1, count)) ring++;
        int local = s - ringStart(ring, count), places = Math.max(1, ringStart(ring + 1, count) - ringStart(ring, count));
        int columns = ringColumns(ring, radius), hexagons = columns * TUBE_ROWS;
        int hex = (int) ((long) local * hexagons / places);
        int column = hex / TUBE_ROWS, row = hex % TUBE_ROWS;
        return ringPoint(ring, (column + (row % 2) * .5) / columns * Math.PI * 2, row / (double) TUBE_ROWS * Math.PI * 2, 1.12, time, radius);
    }

    /** The first place of torus {@code ring}: the tori share the places as their circumferences do. */
    public static int ringStart(int ring, int count) {
        double before = 0, total = 0;
        for (int index = 0; index < RING_RADII.length; index++) {
            if (index < ring) before += RING_RADII[index];
            total += RING_RADII[index];
        }
        return (int) Math.round(count * before / total);
    }

    /**
     * Corner {@code corner} (0..5) of the hexagon in {@code column} and {@code row} of torus {@code ring},
     * drawn a little inside its cell so neighbours keep a seam.
     */
    public static Vec3 ringHexCorner(int ring, int column, int row, int corner, double time, double radius) {
        int columns = ringColumns(ring, radius);
        double u = (column + (row % 2) * .5) / columns * Math.PI * 2, v = row / (double) TUBE_ROWS * Math.PI * 2;
        double angle = Math.PI / 6 + corner * Math.PI / 3;
        double du = Math.PI * 2 / columns * Math.cos(angle) / Math.sqrt(3) * .9;
        double dv = Math.PI * 2 / TUBE_ROWS * Math.sin(angle) / 1.5 * .9;
        return ringPoint(ring, u + du, v + dv, 1, time, radius);
    }

    /**
     * A point on torus {@code ring}: {@code u} along the ring, {@code v} round its tube, {@code out} times
     * the tube's radius from its core line. The torus turns about its axis and its tube pattern rolls.
     */
    public static Vec3 ringPoint(int ring, double u, double v, double out, double time, double radius) {
        Vec3[] frame = ringFrame(ring, time);
        double major = radius * RING_RADII[ring], tube = ringTube(ring, radius) * out;
        double along = u + time * RING_SPIN[ring], round = v + time * .015;
        double reach = major + tube * Math.cos(round);
        return frame[0].scale(Math.cos(along) * reach).add(frame[1].scale(Math.sin(along) * reach)).add(frame[2].scale(tube * Math.sin(round)));
    }

    /** A ring's plane: two axes in it and its normal, tilted its own way and sweeping round the vertical. */
    public static Vec3[] ringFrame(int ring, double time) {
        double[] tilt = RING_TILT[ring];
        Vec3 normal = rotate(new Vec3(0, 1, 0), new Vec3(tilt[0], tilt[1], tilt[2]), tilt[3]);
        normal = rotate(normal, new Vec3(0, 1, 0), time * RING_PRECESSION[ring]);
        Vec3 u = normal.cross(Math.abs(normal.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        return new Vec3[]{u, normal.cross(u), normal};
    }

    /**
     * Mana: a ward of three upright rhombi turned 120 degrees apart and two level circles above and
     * below; drones stream along its lines.
     */
    public static Vec3 ward(int s, int count, double time, double scale) {
        double rhombus = 4 * Math.hypot(1.1, 1.55), circle = Math.PI * 2 * 1.3, hexagon = 6 * 1.1;
        double total = 3 * rhombus + 2 * circle + hexagon;
        double at = ((s + .5) / count * total + time * .02) % total;
        double turn = time * .008;
        for (int index = 0; index < 3; index++) {
            if (at < rhombus) return rhombusPoint(at / rhombus, turn + index * Math.PI * 2 / 3).scale(scale);
            at -= rhombus;
        }
        if (at < 2 * circle) {
            boolean top = at < circle;
            double angle = (top ? at : at - circle) / circle * Math.PI * 2 - turn;
            return new Vec3(Math.cos(angle) * 1.3, top ? 1.0 : -1.0, Math.sin(angle) * 1.3).scale(scale);
        }
        return wardHexagonPoint((at - 2 * circle) / hexagon, turn).scale(scale);
    }

    /**
     * A point {@code t} (0..1) round the ward's middle hexagon, which joins the rhombi's six side corners
     * (each rhombus, turned by {@code turn} and then by thirds, has a corner on either side).
     */
    public static Vec3 wardHexagonPoint(double t, double turn) {
        double along = (t - Math.floor(t)) * 6;
        int side = (int) Math.floor(along) % 6;
        double f = along - Math.floor(along);
        double a0 = turn + side * Math.PI / 3, a1 = turn + (side + 1) * Math.PI / 3;
        return new Vec3((Math.cos(a0) * (1 - f) + Math.cos(a1) * f) * 1.1, 0, (Math.sin(a0) * (1 - f) + Math.sin(a1) * f) * 1.1);
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

    /** Radius of one Twins rift sphere at base size. */
    public static final double RIFT_RADIUS = .5;

    /** Twins: four hexagon-shelled spheres of {@code radius} around the target, now and then spinning up like rifts opening. */
    public static Vec3 riftSpheres(int s, int count, double time, double distance, double radius) {
        int spheres = 4, sphere = s % spheres, j = s / spheres, population = (count - 1 - sphere) / spheres + 1;
        Vec3 centre = riftCentre(sphere, time, distance);
        double spin = riftSpin(sphere, time);
        double y = 1 - 2 * (j + .5) / Math.max(1, population);
        double ring = Math.sqrt(Math.max(0, 1 - y * y)), around = j * GOLDEN_ANGLE + spin;
        return centre.add(Math.cos(around) * ring * radius, y * radius, Math.sin(around) * ring * radius);
    }

    public static Vec3 riftCentre(int sphere, double time, double distance) {
        double[][] tetra = {{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}};
        double[] d = tetra[sphere % 4];
        double turn = time * .01, length = Math.sqrt(3);
        // Two spheres ride high and two level with the target, so none sinks below it into the ground.
        double x = d[0] / length, y = d[1] > 0 ? .30 : .02, z = d[2] / length;
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
