package dev.hurtify.relicsaddon.drone;

import net.minecraft.world.phys.Vec3;

/**
 * The RF hive's ultimate, RF Armageddon. The swarm flies in to the axis over its owner's head and builds a hologram
 * of the relay drone, thirteen blocks long with its nose towards the target: a cylindrical body belted with copper,
 * bundles of needle antennas at nose and stern, and four long panels, each a frame of drones with a grid of cells,
 * folded along the body. As the hive's charge pours in, the panels unfold smoothly into a cross (fully open is a
 * full charge), their cells lighting row by row from the body out, and before the nose a ball grows with them: a
 * dark core in a crackling electric rim, ringed by atomic orbits that multiply and quicken as it fills. A minute in,
 * the panels snap shut, the hologram scatters back into the swarm and the ball leaves, slow and heavy, ringed by an
 * escort of drones and striking the ground with bolts as it goes. It stops out from the face it is aimed at (over the
 * ground, under a ceiling, before a wall) and hangs there while the world goes grey, then sinks into it and becomes a
 * dome of ice-blue glass that swells and heats to white, its edge cutting the land. Then the atomic flash: everything
 * white, then black silhouettes on white while a shock front runs out to the edge of the blast; and colour comes back
 * over a round crater (with a raised rim, on the ground). Timings are
 * ticks from the start (ages) or from the moment the ball meets the ground ({@code sinceImpact}); everything here is
 * shared by the server and the client, so both see the same.
 */
public final class RfArmageddon {
    /** How far the blast reaches, and how far off its owner can aim the shot. */
    public static final double RADIUS = 256, REACH = 256;
    /**
     * Ticks from the start: the hologram is built; the panels open with the charge and snap shut as the ball leaves a
     * minute in (SNAP ticks later its drones scatter home); the ball flies to its hover over the target, hangs there,
     * sinks and meets the ground at IMPACT.
     */
    public static final int ASSEMBLED = 80, FIRE = 1200, SNAP = 6, SCATTER = FIRE + SNAP, FLIGHT = 180, ARRIVE = FIRE + FLIGHT, HOVER = 80,
            DESCEND = ARRIVE + HOVER, SINK = 40, IMPACT = DESCEND + SINK;
    /** After the ball meets the ground: the dome swells and heats for DOME ticks, then the atomic flash. */
    public static final int DOME = 60, FLASH = DOME;
    /**
     * The blast, from the flash, lasts exactly as long as it is heard: the one number the blast's sound (made to this
     * length by {@code tools/build_combat_sounds.mjs}, which reads it here) and its fading light are made from.
     */
    public static final double BLAST_SECONDS = 40;
    public static final int BLAST = (int) Math.round(BLAST_SECONDS * 20);
    /**
     * After the ball meets the ground: the white floods everything until FLOODED; black silhouettes on white until
     * SILHOUETTES; colour is back by COLOUR; the shock front runs out for SHOCK ticks from the flash; the debris the
     * flash threw up has fallen by FALLEN.
     */
    public static final int FLOODED = FLASH + 8, SILHOUETTES = FLASH + 70, COLOUR = FLASH + 140, SHOCK = 90, FALLEN = FLASH + 200;
    /** The drones start home once the blast has fallen silent, and by END they are there. */
    public static final int RECOVER = IMPACT + FLASH + BLAST, END = RECOVER + 30;

    // --- the hologram -------------------------------------------------------------------------------

    /** Where the hologram hangs: this far over its owner's eyes and this far ahead of them; its axis leans no more than MAX_PITCH towards the target. */
    public static final double LIFT = 8, AHEAD = 2, MAX_PITCH = Math.toRadians(28);
    /** The body: a cylinder from the stern to the nose, a cap behind and a cone in front. */
    public static final double BODY_RADIUS = .9, STERN = -3.2, NOSE = 3.2, STERN_CAP = -3.8, NOSE_TIP = 4.4, CAP_RADIUS = .6, CONE_RADIUS = .55;
    /** Where the cap and the cone narrow to before their tips. */
    public static final double CAP_RING = -3.65, CONE_RING = 4.0;
    /** The body's rings of drones along the axis, and the three copper belts among them. */
    private static final double[] BODY_RINGS = {-3.2, -2.2, -1.1, 0, 1.1, 2.2, 3.2};
    private static final double[] BELTS = {-2.2, 0, 2.2};
    /** Drones round each ring of the body. */
    public static final int RING_NODES = 8;
    /** The needle antennas: a bundle from the nose tip forwards and one from the stern cap back, splayed a little. */
    public static final double NOSE_NEEDLES = 3, STERN_NEEDLES = 2.2, NOSE_SPLAY = .5, STERN_SPLAY = .6;
    public static final int NOSE_BUNDLE = 5, STERN_BUNDLE = 4;
    /** The four panels: hinged this far along the axis and this far out, this long and this wide, their cells in rows and columns. */
    public static final double HINGE = -2.6, HINGE_RADIUS = 1.3, PANEL_LENGTH = 8.8, PANEL_WIDTH = 1.5;
    public static final int PANELS = 4, ROWS = 14, COLUMNS = 3;
    /** The share of the places that escort the ball; the rest scatter home as it leaves. */
    private static final double ESCORT_SHARE = .16;
    /** How long a drone takes to fly in to the hologram, and how long the escort takes to join the ball's rings. */
    private static final int GATHER = 30, JOIN = 14;

    // --- the ball, the dome and the crater --------------------------------------------------------

    /**
     * The ball: where it forms before the nose, its radius when the panels are fully open and when it hangs over the
     * target (with room for it: in a tight place it grows no bigger than fits, never under SMALLEST of it), how far out
     * from the target's face it hangs (less where there is less room: never nearer than LEAST_HOVER, the smallest ball's
     * radius) and how far it bobs there.
     */
    public static final double BALL_AT = 11, BALL_CHARGED = 3.2, BALL_HOVER = 11, HOVER_HEIGHT = 26, SMALLEST = .3, LEAST_HOVER = BALL_HOVER * SMALLEST, BOB = .7;
    /** The dome the ball becomes swells to this radius, the crater's; the bowl goes this share of it deep. */
    public static final double DOME_RADIUS = 44, BOWL = .45;
    /** The crater's rim: this wide outside the bowl and this high at its crest; it only rises where the ground lies within RIM_REACH of the target's height. */
    public static final double RIM_WIDTH = 10, RIM_HEIGHT = 5, RIM_REACH = 12;
    /** The escort's rings round the ball, as a share of its radius. */
    public static final double ESCORT_RINGS = 1.55;
    /** Bolts from the ball to the ground: about this many ticks apart in flight, and over the target. */
    public static final int BOLT_FLIGHT_GAP = 17, BOLT_HOVER_GAP = 7;

    /** The frame's points, coarse to fine, so a small swarm still picks out its outline; and its segments between them. */
    private static final Node[] NODES;
    private static final int[][] EDGES;
    /** When each bolt strikes (ages): in flight, then more often over the target and as it sinks. */
    private static final double[] BOLTS;

    /**
     * A point of the relay's frame: on the body ({@code panel} -1: {@code along} the axis, {@code angle} round it,
     * {@code radius} out from it) or on panel {@code panel} ({@code length} out of its hinge, {@code width} across it).
     */
    private record Node(int panel, double along, double angle, double radius, double length, double width) {
        static Node body(double along, double angle, double radius) {
            return new Node(-1, along, angle, radius, 0, 0);
        }

        static Node panel(int panel, int row, int column) {
            return new Node(panel, 0, 0, 0, PANEL_LENGTH * row / ROWS, -PANEL_WIDTH / 2 + PANEL_WIDTH * column / COLUMNS);
        }
    }

    static {
        java.util.List<Node> nodes = new java.util.ArrayList<>();
        int[][] ring = new int[BODY_RINGS.length][RING_NODES];
        int[][][] grid = new int[PANELS][ROWS + 1][COLUMNS + 1];
        // The panels' corners: the cross.
        for (int panel = 0; panel < PANELS; panel++) for (int row : new int[]{0, ROWS}) for (int column : new int[]{0, COLUMNS}) {
            grid[panel][row][column] = nodes.size();
            nodes.add(Node.panel(panel, row, column));
        }
        // The needles' tips and the tips of nose and stern.
        for (int needle = 0; needle < NOSE_BUNDLE; needle++) nodes.add(Node.body(needleTipAlong(true, needle), needleAngle(true, needle), needleTipRadius(true, needle)));
        for (int needle = 0; needle < STERN_BUNDLE; needle++) nodes.add(Node.body(needleTipAlong(false, needle), needleAngle(false, needle), needleTipRadius(false, needle)));
        nodes.add(Node.body(NOSE_TIP, 0, 0));
        nodes.add(Node.body(STERN_CAP, 0, 0));
        // Every other drone round the body's rings.
        for (int k = 0; k < BODY_RINGS.length; k++) for (int n = 0; n < RING_NODES; n += 2) {
            ring[k][n] = nodes.size();
            nodes.add(Node.body(BODY_RINGS[k], ringAngle(n), BODY_RADIUS));
        }
        // The panels' edges.
        for (int panel = 0; panel < PANELS; panel++) {
            for (int row = 1; row < ROWS; row++) for (int column : new int[]{0, COLUMNS}) {
                grid[panel][row][column] = nodes.size();
                nodes.add(Node.panel(panel, row, column));
            }
            for (int row : new int[]{0, ROWS}) for (int column = 1; column < COLUMNS; column++) {
                grid[panel][row][column] = nodes.size();
                nodes.add(Node.panel(panel, row, column));
            }
        }
        // The rest of the body's rings, and the cap's and the cone's.
        for (int k = 0; k < BODY_RINGS.length; k++) for (int n = 1; n < RING_NODES; n += 2) {
            ring[k][n] = nodes.size();
            nodes.add(Node.body(BODY_RINGS[k], ringAngle(n), BODY_RADIUS));
        }
        for (int n = 1; n < RING_NODES; n += 2) nodes.add(Node.body(CONE_RING, ringAngle(n), CONE_RADIUS));
        for (int n = 1; n < RING_NODES; n += 2) nodes.add(Node.body(CAP_RING, ringAngle(n), CAP_RADIUS));
        // The panels' grids inside.
        for (int panel = 0; panel < PANELS; panel++) for (int row = 1; row < ROWS; row++) for (int column = 1; column < COLUMNS; column++) {
            grid[panel][row][column] = nodes.size();
            nodes.add(Node.panel(panel, row, column));
        }
        NODES = nodes.toArray(Node[]::new);

        java.util.List<int[]> edges = new java.util.ArrayList<>();
        for (int k = 0; k < BODY_RINGS.length; k++) for (int n = 0; n < RING_NODES; n++) {
            edges.add(new int[]{ring[k][n], ring[k][(n + 1) % RING_NODES]});
            if (k + 1 < BODY_RINGS.length) edges.add(new int[]{ring[k][n], ring[k + 1][n]});
        }
        for (int panel = 0; panel < PANELS; panel++) for (int row = 0; row <= ROWS; row++) for (int column = 0; column <= COLUMNS; column++) {
            if (row < ROWS) edges.add(new int[]{grid[panel][row][column], grid[panel][row + 1][column]});
            if (column < COLUMNS) edges.add(new int[]{grid[panel][row][column], grid[panel][row][column + 1]});
        }
        EDGES = edges.toArray(int[][]::new);

        java.util.List<Double> bolts = new java.util.ArrayList<>();
        // The same schedule is written into the flight's sound (tools/build_combat_sounds.mjs), so every crack is heard as it strikes.
        for (int k = 0; ; k++) {
            double at = FIRE + 14 + BOLT_FLIGHT_GAP * k + 6 * Math.sin(2.3 * k);
            if (at >= ARRIVE) break;
            bolts.add(at);
        }
        for (int k = 0; ; k++) {
            double at = ARRIVE + 4 + BOLT_HOVER_GAP * k + 3 * Math.sin(1.7 * k);
            if (at >= DESCEND + SINK / 2.0) break;
            bolts.add(at);
        }
        BOLTS = bolts.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** The angle round the axis of drone {@code n} of a body ring: the first half-way between two panels' hinges. */
    public static double ringAngle(int n) {
        return n * Math.PI * 2 / RING_NODES;
    }

    /** The angle round the axis at which panel {@code panel} is hinged: the four make an X seen from behind. */
    public static double panelAngle(int panel) {
        return Math.PI / 4 + panel * Math.PI / 2;
    }

    /** The angle round the axis of needle {@code needle} of the nose bundle ({@code nose}) or the stern's. */
    public static double needleAngle(boolean nose, int needle) {
        return nose ? Math.PI / 4 + (needle - 1) * Math.PI / 2 : needle * Math.PI / 2;
    }

    /** How far along the axis a needle's tip reaches: the nose bundle's middle needle furthest. */
    public static double needleTipAlong(boolean nose, int needle) {
        return nose ? NOSE_TIP + NOSE_NEEDLES * (needle == 0 ? 1 : .85) : STERN_CAP - STERN_NEEDLES;
    }

    /** How far out from the axis a needle's tip is splayed: the nose bundle's middle needle not at all. */
    public static double needleTipRadius(boolean nose, int needle) {
        return nose ? needle == 0 ? 0 : NOSE_SPLAY : STERN_SPLAY;
    }

    /** Where a needle leaves the body: along the axis, and how far out from it. */
    public static double needleBaseAlong(boolean nose) {
        return nose ? NOSE_TIP - .15 : STERN_CAP + .1;
    }

    public static double needleBaseRadius(boolean nose, int needle) {
        return nose && needle == 0 ? 0 : .14;
    }

    public static double belt(int belt) {
        return BELTS[belt];
    }

    public static int belts() {
        return BELTS.length;
    }

    public static double bodyRing(int ring) {
        return BODY_RINGS[ring];
    }

    public static int bodyRings() {
        return BODY_RINGS.length;
    }

    /** How many points the frame has, before any drone sits on its segments. */
    public static int nodes() {
        return NODES.length;
    }

    // --- the charge ------------------------------------------------------------------------------

    /** How full the charge is {@code age} ticks in: nothing until the hologram is built, all of it as the ball leaves. */
    public static double charge(double age) {
        return Math.clamp((age - ASSEMBLED) / (FIRE - ASSEMBLED), 0, 1);
    }

    /**
     * How far the panels have opened with the charge (0 folded along the body, 1 a full cross): smoothly, as the charge
     * fills, so the cross is complete exactly as the charge is full. They are the charge bar.
     */
    public static double opened(double age) {
        return charge(age);
    }

    /** How far the panels are open {@code age} ticks in: opening with the charge, snapped shut in SNAP ticks as the ball leaves. */
    public static double unfold(double age) {
        return opened(age) * (1 - smooth((age - FIRE) / SNAP));
    }

    /** How much of panel row {@code row} (0 at the hinge) is lit {@code age} ticks in: row after row from the body out as the charge fills. */
    public static double lit(int row, double age) {
        return Math.clamp(charge(age) * ROWS - row, 0, 1);
    }

    // --- the frame -------------------------------------------------------------------------------

    /** Where the hologram hangs for an owner whose eyes are at {@code eye}: over their head and a little ahead. */
    public static Vec3 origin(Vec3 eye, Vec3 target) {
        Vec3 aim = target.subtract(eye), flat = new Vec3(aim.x, 0, aim.z);
        flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        return eye.add(0, LIFT, 0).add(flat.scale(AHEAD));
    }

    /** The hologram's frame: forward (its nose, towards the target, leaning no more than MAX_PITCH), right and up. */
    public static Vec3[] frame(ArmageddonState state) {
        Vec3 aim = state.target().subtract(state.origin());
        double flat = Math.hypot(aim.x, aim.z);
        Vec3 level = flat < 1e-6 ? new Vec3(0, 0, 1) : new Vec3(aim.x / flat, 0, aim.z / flat);
        double pitch = Math.clamp(Math.atan2(aim.y, Math.max(flat, 1e-6)), -MAX_PITCH, MAX_PITCH);
        Vec3 forward = level.scale(Math.cos(pitch)).add(0, Math.sin(pitch), 0), right = new Vec3(-level.z, 0, level.x);
        return new Vec3[]{forward, right, right.cross(forward).normalize()};
    }

    /** A point of the body: {@code along} the axis, {@code angle} round it (from the right, towards up) and {@code radius} out. */
    public static Vec3 body(ArmageddonState state, Vec3[] f, double along, double angle, double radius) {
        return state.origin().add(f[0].scale(along)).add(f[1].scale(Math.cos(angle) * radius)).add(f[2].scale(Math.sin(angle) * radius));
    }

    /**
     * Panel {@code panel}'s frame as far open as {@code unfold}: its hinge, the way along it (forward along the body when
     * folded, straight out when open), the way across it (along the hinge), and its face's normal.
     */
    public static Vec3[] panel(ArmageddonState state, Vec3[] f, int panel, double unfold) {
        double angle = panelAngle(panel), swing = Math.clamp(unfold, 0, 1) * Math.PI / 2;
        Vec3 out = f[1].scale(Math.cos(angle)).add(f[2].scale(Math.sin(angle))), across = f[2].scale(Math.cos(angle)).subtract(f[1].scale(Math.sin(angle)));
        Vec3 hinge = state.origin().add(f[0].scale(HINGE)).add(out.scale(HINGE_RADIUS));
        Vec3 along = f[0].scale(Math.cos(swing)).add(out.scale(Math.sin(swing)));
        return new Vec3[]{hinge, along, across, along.cross(across).normalize()};
    }

    /** A point of a panel: {@code length} out of its hinge and {@code width} across it. */
    public static Vec3 onPanel(Vec3[] panel, double length, double width) {
        return panel[0].add(panel[1].scale(length)).add(panel[2].scale(width));
    }

    private static Vec3 at(ArmageddonState state, Vec3[] f, Node node, double unfold) {
        if (node.panel < 0) return body(state, f, node.along, node.angle, node.radius);
        return onPanel(panel(state, f, node.panel, unfold), node.length, node.width);
    }

    /** How far along the axis a frame point lies with the panels folded: the order the hologram is built in, stern first. */
    private static double builtAlong(Node node) {
        return node.panel < 0 ? node.along : HINGE + node.length;
    }

    // --- the places ------------------------------------------------------------------------------

    /** How many of {@code slots} places escort the ball. */
    public static int escorts(int slots) {
        return slots < 12 ? 0 : (int) Math.round(slots * ESCORT_SHARE);
    }

    /** How many of {@code slots} places only make the hologram, and scatter home as the ball leaves. */
    public static int hologram(int slots) {
        return Math.max(0, slots - escorts(slots));
    }

    /**
     * A shot's shape at one moment, worked out once for all its places: the hologram's frame, its four panels as far
     * open as they are, and the middle and radius of the escort's rings round the ball, with the time they have turned by.
     */
    public record Pose(Vec3[] frame, Vec3[][] panels, Vec3 rings, double ringRadius, double flying) { }

    /** The shot's pose {@code age} ticks in (the hologram held as it snapped shut once it has scattered). */
    public static Pose pose(ArmageddonState state, double age) {
        Vec3[] f = frame(state);
        double unfold = unfold(Math.min(age, SCATTER));
        Vec3[][] panels = new Vec3[PANELS][];
        for (int panel = 0; panel < PANELS; panel++) panels[panel] = panel(state, f, panel, unfold);
        double flying = Math.min(age, IMPACT + FLASH), radius = ballRadius(state, Math.min(flying, DESCEND)) * ESCORT_RINGS;
        Vec3 centre = ball(state, Math.min(flying, DESCEND));
        // As the ball sinks the rings stay out from the face, round the dome it becomes.
        if (flying > DESCEND) centre = centre.lerp(state.target().add(state.normal().scale(Math.min(radius * 1.05, lift(state.room())))), smooth((flying - DESCEND) / SINK));
        return new Pose(f, panels, centre, radius, flying);
    }

    private static Vec3 at(ArmageddonState state, Pose pose, Node node) {
        if (node.panel < 0) return body(state, pose.frame, node.along, node.angle, node.radius);
        return onPanel(pose.panels[node.panel], node.length, node.width);
    }

    /**
     * Place {@code slot} of {@code slots} in the hologram as it stands in {@code pose}: the frame's points from coarse
     * to fine, then evenly along its segments once every point has a drone.
     */
    private static Vec3 place(ArmageddonState state, int slot, int slots, Pose pose) {
        if (slot < NODES.length) return at(state, pose, NODES[slot]);
        int extra = slot - NODES.length, layers = Math.max(1, (slots - NODES.length + EDGES.length - 1) / EDGES.length);
        int[] edge = EDGES[extra % EDGES.length];
        double along = (extra / EDGES.length + 1.0) / (layers + 1);
        return at(state, pose, NODES[edge[0]]).lerp(at(state, pose, NODES[edge[1]]), along);
    }

    /** How far along the axis place {@code slot} of {@code slots} lies in the folded hologram (for the order it is built in). */
    private static double builtAlong(int slot, int slots) {
        if (slot < NODES.length) return builtAlong(NODES[slot]);
        int extra = slot - NODES.length, layers = Math.max(1, (slots - NODES.length + EDGES.length - 1) / EDGES.length);
        int[] edge = EDGES[extra % EDGES.length];
        double along = (extra / EDGES.length + 1.0) / (layers + 1);
        return builtAlong(NODES[edge[0]]) + (builtAlong(NODES[edge[1]]) - builtAlong(NODES[edge[0]])) * along;
    }

    /**
     * How far place {@code slot} of {@code slots} is on its way in, {@code age} ticks in: the body is built from the stern
     * to the nose, then the panels are laid along it from their hinges out, all by ASSEMBLED.
     */
    public static double gathered(int slot, int slots, double age) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        double along = builtAlong(slot, slots);
        boolean panel = slot < NODES.length ? NODES[slot].panel >= 0 : NODES[EDGES[(slot - NODES.length) % EDGES.length][0]].panel >= 0;
        double landed = panel ? panelBuiltAt(along - HINGE) : builtAt(along);
        return Math.clamp((age - landed + GATHER) / GATHER, 0, 1);
    }

    /** The age by which the body's drones {@code along} the axis have landed: the body is built from the stern to the nose. */
    public static double builtAt(double along) {
        double first = STERN_CAP - STERN_NEEDLES, last = NOSE_TIP + NOSE_NEEDLES;
        return (ASSEMBLED - GATHER) * .5 * Math.clamp((along - first) / (last - first), 0, 1) + GATHER;
    }

    /** The age by which a panel's drones {@code length} out of its hinge have landed: the panels are laid along the body after it, hinge first. */
    public static double panelBuiltAt(double length) {
        return (ASSEMBLED - GATHER) * (.45 + .55 * Math.clamp(length / PANEL_LENGTH, 0, 1)) + GATHER;
    }

    /**
     * On its way in: from {@code from} in to the axis at the point of it that {@code to} lies round, then out to
     * {@code to}; exactly at {@code from} as it sets off and at {@code to} as it lands.
     */
    public static Vec3 assemble(ArmageddonState state, Vec3 from, Vec3 to, double progress) {
        double p = Math.clamp(progress, 0, 1), t = p * p * (3 - 2 * p);
        if (p <= 0) return from;
        if (p >= 1) return to;
        Vec3 axis = frame(state)[0], centre = state.origin();
        Vec3 onAxis = centre.add(axis.scale(to.subtract(centre).dot(axis)));
        double s = 1 - t;
        return from.scale(s * s).add(onAxis.scale(2 * s * t)).add(to.scale(t * t));
    }

    /**
     * Where place {@code slot} of {@code slots} is at {@code time}: in the hologram as far open as the charge, until the
     * panels have snapped shut; after that the hologram's places hold where they were (their drones fly home from there)
     * and the escort's ride the rings round the ball.
     */
    public static Vec3 station(ArmageddonState state, int slot, int slots, double time) {
        return station(state, slot, slots, time, pose(state, state.age(time)));
    }

    /** As {@link #station(ArmageddonState, int, int, double)}, with the shot's pose at {@code time} already worked out. */
    public static Vec3 station(ArmageddonState state, int slot, int slots, double time, Pose pose) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        double age = state.age(time);
        int hologram = hologram(slots);
        Vec3 held = place(state, slot, slots, pose);
        if (slot < hologram || age < FIRE) return held;
        int index = slot - hologram, count = slots - hologram;
        Vec3 ring = ring(state, index, count, age, pose);
        double join = smooth((age - FIRE) / JOIN);
        return held.lerp(ring, join);
    }

    /**
     * Escort place {@code index} of {@code count} on the rings round the ball: riding them with it through its flight
     * and its hover, staying up over the target as it sinks, and flung away by the flash.
     */
    private static Vec3 ring(ArmageddonState state, int index, int count, double age, Pose pose) {
        double flying = pose.flying, radius = pose.ringRadius;
        // Laid out once at their full size and scaled with the ball, so no drone changes its place on them as they grow.
        double full = BALL_HOVER * ESCORT_RINGS;
        Vec3 at = pose.rings.add(HiveShapes.dysonRing(index, count, flying, full, false).scale(radius / full));
        if (age <= IMPACT + FLASH) return at;
        Vec3 out = at.subtract(state.target());
        out = out.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : out.normalize();
        double u = smooth((age - IMPACT - FLASH) / 40), flown = 70 * (.6 + .8 * hash(index, 3)) * u;
        return at.add(out.scale(flown)).add(0, flown * .35, 0);
    }

    // --- the ball --------------------------------------------------------------------------------

    /** Where the ball forms, before the nose. */
    public static Vec3 nose(ArmageddonState state) {
        return state.origin().add(frame(state)[0].scale(BALL_AT));
    }

    /**
     * How big the ball can grow where there is {@code room} out from the face it is aimed at (up to the next thing in the
     * way, found by the server as the shot is asked for): whole with room for it and a little over, smaller in a tight
     * place, never under SMALLEST of its size.
     */
    public static double fit(double room) {
        return Math.clamp(room / (2 * BALL_HOVER + 2), SMALLEST, 1);
    }

    /**
     * How far out from the face it lands on the ball hangs: HOVER_HEIGHT, or less where the room out from the face cannot
     * take the whole ball that far out; never so near that the ball cuts into the face before it comes in.
     */
    public static double lift(double room) {
        double radius = BALL_HOVER * fit(room);
        return Math.clamp(room - radius - 1, radius, HOVER_HEIGHT);
    }

    /** Where the ball hangs: out from the face the shot lands on (over the ground, under a ceiling, before a wall). */
    public static Vec3 hover(ArmageddonState state) {
        return state.target().add(state.normal().scale(lift(state.room())));
    }

    /**
     * The ball's radius {@code age} ticks in: a point as the panels start to open, growing with them to BALL_CHARGED as
     * they open fully; then swelling on its way out to BALL_HOVER.
     */
    public static double ballRadius(double age) {
        if (age < FIRE) return BALL_CHARGED * Math.pow(opened(age), 1.2);
        return BALL_CHARGED + (BALL_HOVER - BALL_CHARGED) * smooth((age - FIRE) / FLIGHT);
    }

    /** The ball's radius {@code age} ticks into {@code state}'s shot: on its way out it swells only as far as fits where it is going. */
    public static double ballRadius(ArmageddonState state, double age) {
        if (age < FIRE) return ballRadius(age);
        double whole = Math.max(BALL_CHARGED, BALL_HOVER * fit(state.room()));
        return BALL_CHARGED + (whole - BALL_CHARGED) * smooth((age - FIRE) / FLIGHT);
    }

    /**
     * Where the ball is {@code age} ticks in: before the nose until it leaves; then flying out, slow and heavy, to hang
     * over the target, bobbing; then sinking, faster and faster, until its middle meets the ground at IMPACT.
     */
    public static Vec3 ball(ArmageddonState state, double age) {
        if (age <= FIRE) return nose(state);
        if (age < ARRIVE) return flight(state, smooth((age - FIRE) / FLIGHT));
        Vec3 hover = hover(state);
        if (age < DESCEND) return hover.add(state.normal().scale(BOB * Math.sin(Math.PI * 4 * (age - ARRIVE) / HOVER)));
        double u = Math.clamp((age - DESCEND) / SINK, 0, 1);
        return hover.lerp(state.target(), u * u);
    }

    /** The ball's way out: from before the nose, rising a little ahead, and coming in onto its hover from further out from the face. */
    private static Vec3 flight(ArmageddonState state, double s) {
        Vec3 from = nose(state), to = hover(state), forward = frame(state)[0];
        double reach = to.distanceTo(from);
        Vec3 out = from.add(forward.scale(Math.min(reach * .3, 40))).add(0, 3 + reach * .05, 0), in = to.add(state.normal().scale(4 + reach * .08));
        double t = Math.clamp(s, 0, 1), r = 1 - t;
        return from.scale(r * r * r).add(out.scale(3 * r * r * t)).add(in.scale(3 * r * t * t)).add(to.scale(t * t * t));
    }

    /** How many bolts the ball has loosed at the ground by {@code age}, and when bolt {@code k} struck. */
    public static int bolts() {
        return BOLTS.length;
    }

    public static double boltAt(int k) {
        return BOLTS[k];
    }

    // --- the dome, the shock and the crater -------------------------------------------------------

    /** The dome's radius {@code sinceImpact} ticks after the ball meets the ground: the ball's own at first, swelling to the crater's by the flash. */
    public static double dome(double sinceImpact) {
        if (sinceImpact < 0) return 0;
        double u = Math.clamp(sinceImpact / DOME, 0, 1);
        return BALL_HOVER + (DOME_RADIUS - BALL_HOVER) * (1 - (1 - u) * (1 - u));
    }

    /** How far the blast has struck {@code sinceImpact} ticks after the ball meets the ground: the dome's edge, then the shock front from the flash. */
    public static double reach(double sinceImpact) {
        if (sinceImpact < 0) return 0;
        if (sinceImpact < FLASH) return dome(sinceImpact);
        double u = Math.clamp((sinceImpact - FLASH) / SHOCK, 0, 1);
        return DOME_RADIUS + (RADIUS - DOME_RADIUS) * (1 - Math.pow(1 - u, 1.6));
    }

    /** When the blast first strikes {@code distance} blocks from where the ball met the ground, in ticks after it. */
    public static double reaches(double distance) {
        if (distance <= BALL_HOVER) return 0;
        if (distance <= DOME_RADIUS) return domeReaches(distance);
        double f = Math.clamp((distance - DOME_RADIUS) / (RADIUS - DOME_RADIUS), 0, 1);
        return FLASH + SHOCK * (1 - Math.pow(1 - f, 1 / 1.6));
    }

    /** When the dome's edge reaches {@code distance} blocks out, in ticks after the ball meets the ground. */
    private static double domeReaches(double distance) {
        double f = Math.clamp((distance - BALL_HOVER) / (DOME_RADIUS - BALL_HOVER), 0, 1);
        return DOME * (1 - Math.sqrt(1 - f));
    }

    /** How far out the dome has cut the land {@code age} ticks in: nothing until the ball meets the ground. */
    public static double carved(double age) {
        return age < IMPACT ? 0 : dome(age - IMPACT);
    }

    /** When the dome cuts the land {@code distance} blocks from the target: the age at which {@link #carved} first reaches it. */
    public static double carvedAt(double distance) {
        return IMPACT + (distance <= BALL_HOVER ? 0 : domeReaches(Math.min(distance, DOME_RADIUS)));
    }

    /**
     * The stretch of a column the dome takes (the column {@code dx}, {@code dz} from where the ball met the face whose
     * way out is {@code normal}): everything inside the dome of {@code radius} out from the face, and a bowl behind it
     * {@code depth} of the radius deep. Heights from the target's; null when it takes nothing of that column. On the
     * ground this is the bowl under the dome; under a ceiling it is turned over, and in a wall turned on its side.
     */
    public static double[] bowl(Vec3 normal, double dx, double dz, double radius, double depth) {
        double flat = dx * dx + dz * dz, most = radius * radius;
        if (flat > most) return null;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        double across = dx * normal.x + dz * normal.z, rising = normal.y;
        // In front of the face (out along its normal): the dome, a sphere.
        double span = Math.sqrt(most - flat);
        double[] front = beyond(-span, span, across, rising, true);
        if (front != null) {
            lo = Math.min(lo, front[0]);
            hi = Math.max(hi, front[1]);
        }
        // Behind it: the bowl, the sphere squashed along the normal to depth of it.
        double k = 1 / (depth * depth) - 1, a = 1 + k * rising * rising, b = 2 * k * across * rising, c = k * across * across + flat - most;
        double disc = b * b - 4 * a * c;
        if (disc >= 0) {
            double root = Math.sqrt(disc);
            double[] back = beyond((-b - root) / (2 * a), (-b + root) / (2 * a), across, rising, false);
            if (back != null) {
                lo = Math.min(lo, back[0]);
                hi = Math.max(hi, back[1]);
            }
        }
        return lo <= hi ? new double[]{lo, hi} : null;
    }

    /** The part of the stretch {@code lo} to {@code hi} of a column that lies in front of the face ({@code front}) or behind it. */
    private static double[] beyond(double lo, double hi, double across, double rising, boolean front) {
        // How far out of the face a height y of the column is: across + y * rising.
        if (Math.abs(rising) < 1e-9) return (across >= 0) == front ? new double[]{lo, hi} : null;
        double plane = -across / rising;
        boolean above = front == rising > 0;
        double from = above ? Math.max(lo, plane) : lo, to = above ? hi : Math.min(hi, plane);
        return from <= to ? new double[]{from, to} : null;
    }

    /** How high the crater's rim stands {@code distance} blocks from its middle: a steep face inside, sloping gently away outside. */
    public static double rimHeight(double distance) {
        double q = (distance - DOME_RADIUS) / RIM_WIDTH;
        if (q < -.15 || q > 1) return 0;
        return RIM_HEIGHT * (q < 0 ? smooth((q + .15) / .15) : 1 - smooth(q));
    }

    /** How far out from the crater's middle the rim reaches. */
    public static double rimReach() {
        return DOME_RADIUS + RIM_WIDTH;
    }

    /**
     * The RF Armageddon's course: the land goes as the ball meets the ground and the dome swells, cut into a bowl with
     * the whole dome above it; every client near is told as the ball leaves, since all that follows is seen from afar;
     * the dome's edge strikes as it swells, and the shock front from the flash out to the edge of the blast.
     */
    public static final ArmageddonTimeline TIMELINE = new ArmageddonTimeline() {
        @Override public int assembled() { return ASSEMBLED; }
        @Override public int fire() { return FIRE; }
        @Override public int arrive() { return IMPACT; }
        @Override public int told() { return FIRE; }
        @Override public int impact() { return IMPACT; }
        @Override public int recover() { return RECOVER; }
        @Override public int end() { return END; }
        @Override public double carveRadius() { return DOME_RADIUS; }
        @Override public double carveDepth() { return BOWL; }
        @Override public double carved(double age) { return RfArmageddon.carved(age); }
        @Override public double carvedAt(double distance) { return RfArmageddon.carvedAt(distance); }
        @Override public int carvedUntil() { return IMPACT + DOME + 200; }
        @Override public boolean drags(double age) { return false; }
        @Override public double radius() { return RADIUS; }
        @Override public double reach(double sinceImpact) { return RfArmageddon.reach(sinceImpact); }
        @Override public double reaches(double distance) { return RfArmageddon.reaches(distance); }
        @Override public int swept() { return FLASH + SHOCK; }
    };

    /** Stable per-place random number in [0, 1). */
    static double hash(int index, int salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double x) {
        double t = Math.clamp(x, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private RfArmageddon() {
    }
}
