package dev.hurtify.relicsaddon.drone;

import net.minecraft.world.phys.Vec3;

/**
 * The Mana hive's ultimate, Mana Armageddon: the swarm spirals into two flowers of drones over its owner's
 * shoulders, a turquoise one on the left and a gold one on the right, facing the point the owner looks at.
 * Seals and rings of runes are written round them as the hive's charge pours in. A minute in, each flower
 * looses a stream: the turquoise one dense and scaled, the gold one a dazzling beam. They arc out to either
 * side and meet head-on at the target, where the land is torn up into a vortex. Then the flowers close, a
 * small sun ignites where the streams met inside a sphere of runes standing on a seal on the ground, and the
 * sphere shatters: a thin flash, a ring of stones and burning runes along the ground, a dome of light sweeping
 * out over the land, and a column of light that grows for as long as the blast is heard before it dissolves
 * into a white sky with a pale crescent moon. Timings are ticks from the start (ages) or from the burst
 * ({@code sinceImpact}); everything here is shared by the server and the client, so both see the same.
 */
public final class ManaArmageddon {
    /** How far the blast reaches, and how far off its owner can aim the shot. */
    public static final double RADIUS = 256, REACH = 256;
    /**
     * Ticks from the start: the swarm has spiralled into the flowers; the streams leave a minute in, meet at the
     * target and tear the land up into a vortex; the flowers close and the sun ignites in its sphere of runes;
     * the sphere shatters.
     */
    public static final int ASSEMBLED = 60, FIRE = 1200, ARRIVE = FIRE + 24, IGNITE = ARRIVE + 100, IMPACT = IGNITE + 100;
    /**
     * The blast lasts exactly as long as it is heard: the one number the blast's sound (made to this length by
     * {@code tools/build_combat_sounds.mjs}, which reads it here) and the column that grows with it are made from.
     */
    public static final double BLAST_SECONDS = 40;
    public static final int BLAST = (int) Math.round(BLAST_SECONDS * 20);
    /**
     * After the burst: the thin flash, the ring of stones along the ground, the dome of light setting out, holding
     * and fading, and the column of light, growing from COLUMN until the blast falls silent (BLAST). By WHITE it has
     * dissolved into a white sky; in the silence under it, until QUIET, sparks fall.
     */
    public static final int FLASH = 8, SHOCK = 70, DOME = 10, EXPAND = 130, DOME_HOLD = 230, DOME_GONE = 330, COLUMN = 24, WHITE = BLAST + 30,
            QUIET = WHITE + 70;
    /** The drones start home once the white has settled, and by END they are there. */
    public static final int RECOVER = IMPACT + QUIET, END = RECOVER + 30;
    /** The dome of light's radius as it forms, and how far the ring of stones runs along the ground. */
    public static final double LIGHT_START = 6, SHOCK_RADIUS = 170;
    /** The column of light: a thread as it rises, this wide at the end (no wider than the dome), and how high it goes. */
    public static final double COLUMN_START = .6, COLUMN_RADIUS = 48, COLUMN_TOP = 640;
    /** The land torn up into the vortex: within this many blocks of the target, from TEAR until just before the sun ignites. */
    public static final double CARVE_RADIUS = 90;
    public static final int TEAR = ARRIVE + 10;
    /** The sphere of runes round the sun (a dome on the seal, its lower half in the ground), the sun's height in it, and the seal's radius. */
    public static final double SPHERE = 16, SUN_LIFT = 5.5, SEAL = 64;
    /** How fast the sphere rises, how long its runes take to write in their running wave, and how long the flowers take to close. */
    public static final int SPHERE_RISE = 4, SPHERE_WRITE = 44, COLLAPSE = 18;

    // --- the flowers ------------------------------------------------------------------------------

    /** The flowers' hearts lie this far to either side of the view axis, this far over the eyes and this far ahead of them. */
    public static final double SPREAD = 3.4, LIFT = .9, AHEAD = 1.0;
    /** Each flower's four petals: from this far out of its heart to its tip, and half as wide as this at their widest. */
    public static final double PETAL_BASE = .2, PETAL = 1.65, PETAL_WIDTH = .52;
    public static final int PETALS = 4;
    /** Rings of runes round each flower: the seal's belt behind it, then the gyroscope's three; each carries this many runes. */
    public static final int RINGS = 4, RUNES = 24;
    /** The seal behind each flower (its radius and how far behind), and the gyroscope's rings round it. */
    public static final double SEAL_RADIUS = 1.95, SEAL_BEHIND = .55;
    private static final double[] RING_RADIUS = {1.95, 2.1, 2.34, 2.58};
    /** Each gyroscope ring's tilt from the flower's face, where round the face it leans, and how fast it turns once written. */
    private static final double[] RING_TILT = {0, 1.02, .58, 1.3}, RING_LEAN = {0, .35, 2.2, 4.1}, RING_SPIN = {.008, -.021, .016, -.013};
    /** The central seal between the flowers, over the owner's head: its radius and how far up it hangs. */
    public static final double CENTRAL = 1.3, CENTRAL_UP = 1.95;
    /** The share of the places that ride the streams as their escort; the rest fill the petals. */
    private static final double ESCORT_SHARE = .2;
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    /** How full the charge is {@code age} ticks in: nothing until the flowers have formed, all of it as the streams leave. */
    public static double charge(double age) {
        return Math.clamp((age - ASSEMBLED) / (FIRE - ASSEMBLED), 0, 1);
    }

    /** How much of ring {@code ring}'s runes are written {@code age} ticks in: one ring after another, the last as the charge fills. */
    public static double written(int ring, double age) {
        return Math.clamp(charge(age) * RINGS - ring, 0, 1);
    }

    /** When ring {@code ring} is full, and starts to turn. */
    public static double filledAt(int ring) {
        return ASSEMBLED + (FIRE - ASSEMBLED) * (ring + 1.0) / RINGS;
    }

    /** How far ring {@code ring} has turned {@code age} ticks in: still while it is written, turning its own way once full. */
    public static double ringTurn(int ring, double age) {
        return RING_SPIN[ring] * Math.max(0, age - filledAt(ring));
    }

    public static double ringRadius(int ring) {
        return RING_RADIUS[ring];
    }

    /** Where the flowers hang for an owner whose eyes are at {@code eye}: the point between their hearts, over the eyes and a little ahead. */
    public static Vec3 origin(Vec3 eye, Vec3 target) {
        return eye.add(flat(target.subtract(eye)).scale(AHEAD)).add(0, LIFT, 0);
    }

    /** The flowers' frame: forward (level, towards the target), right and up. */
    public static Vec3[] frame(ArmageddonState state) {
        Vec3 forward = flat(state.target().subtract(state.origin()));
        return new Vec3[]{forward, new Vec3(-forward.z, 0, forward.x), new Vec3(0, 1, 0)};
    }

    private static Vec3 flat(Vec3 aim) {
        Vec3 level = new Vec3(aim.x, 0, aim.z);
        return level.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : level.normalize();
    }

    /** The heart of flower {@code side} (-1 the turquoise one on the left, +1 the gold one on the right). */
    public static Vec3 heart(ArmageddonState state, int side) {
        return state.origin().add(frame(state)[1].scale(side * SPREAD));
    }

    /**
     * Flower {@code side}'s face: right and up across it, and its normal towards the target (each flower faces
     * the target itself, so the two lean in a little towards each other).
     */
    public static Vec3[] face(ArmageddonState state, int side) {
        Vec3 heart = heart(state, side), normal = state.target().subtract(heart);
        normal = normal.lengthSqr() < 1e-6 ? frame(state)[0] : normal.normalize();
        Vec3 right = normal.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? frame(state)[1] : right.normalize();
        return new Vec3[]{right, right.cross(normal).normalize(), normal};
    }

    /** A point of flower {@code side}: {@code right} and {@code up} across its face and {@code out} towards the target, from its heart. */
    public static Vec3 onFace(ArmageddonState state, int side, Vec3[] face, double right, double up, double out) {
        return heart(state, side).add(face[0].scale(right)).add(face[1].scale(up)).add(face[2].scale(out));
    }

    /** The angle across the face that petal {@code petal}'s axis points along: the four make an X. */
    public static double petalAngle(int petal) {
        return Math.PI / 4 + petal * Math.PI / 2;
    }

    /** How wide a petal is (half its width) {@code along} blocks out of the heart: rounded at both ends, fuller towards the tip. */
    public static double petalHalfWidth(double along) {
        double middle = (PETAL_BASE + PETAL) / 2, half = (PETAL - PETAL_BASE) / 2, x = (along - middle) / half;
        if (Math.abs(x) >= 1) return 0;
        return PETAL_WIDTH * Math.sqrt(1 - x * x) * (.8 + .35 * (along - PETAL_BASE) / (PETAL - PETAL_BASE));
    }

    /** How many of {@code slots} places ride the streams. */
    public static int escorts(int slots) {
        return slots < 12 ? 0 : (int) Math.round(slots * ESCORT_SHARE);
    }

    /** How many of {@code slots} places fill the petals. */
    public static int flowered(int slots) {
        return Math.max(0, slots - escorts(slots));
    }

    /** Which flower place {@code slot} belongs to: the flowers take the places in turn, the left first. */
    public static int side(int slot) {
        return slot % 2 == 0 ? -1 : 1;
    }

    /**
     * Petal place {@code member} of {@code members} in a petal, across the face from the heart: spread evenly over
     * the petal like the seeds of a sunflower, so the petal fills from its middle out.
     */
    public static double[] petalPlace(int petal, int member, int members) {
        double f = (member + .5) / Math.max(1, members), rho = Math.sqrt(f), phi = member * GOLDEN_ANGLE;
        double middle = (PETAL_BASE + PETAL) / 2, half = (PETAL - PETAL_BASE) / 2;
        // The unit disc laid over the petal: along its axis as the ellipse runs, across it as wide as it is there.
        double along = middle + half * .92 * rho * Math.cos(phi);
        double across = .92 * rho * Math.sin(phi) * PETAL_WIDTH * (.8 + .35 * (along - PETAL_BASE) / (PETAL - PETAL_BASE));
        double angle = petalAngle(petal);
        return new double[]{Math.cos(angle) * along - Math.sin(angle) * across, Math.sin(angle) * along + Math.cos(angle) * across};
    }

    /** How long a petal drone takes to spiral in, and how late the ones furthest from the heart set off. */
    private static final int GATHER = 36, GATHER_STAGGER = ASSEMBLED - GATHER;

    /** Where petal place {@code index} of flower {@code side} (of {@code count} in it) rests across the face, before it drifts. */
    private static double[] flowerPlace(int index, int count) {
        int petal = index % PETALS, member = index / PETALS, members = (count - petal + PETALS - 1) / PETALS;
        return petalPlace(petal, member, Math.max(1, members));
    }

    /**
     * How far flower place {@code slot} of {@code slots} is on its spiral in from where it was, {@code age} ticks in:
     * the places nearest the heart fill first, the petal tips last, all by {@link #ASSEMBLED}.
     */
    public static double gathered(int slot, int slots, double age) {
        int flowered = flowered(slots), side = side(slot), count = side < 0 ? (flowered + 1) / 2 : flowered / 2;
        double[] place = flowerPlace(slot / 2, Math.max(1, count));
        double out = Math.hypot(place[0], place[1]) / PETAL;
        return Math.clamp((age - GATHER_STAGGER * Math.min(1, out)) / GATHER, 0, 1);
    }

    /**
     * On its way in: from {@code from} to {@code to}, winding one and a quarter turns round the flower's axis (the
     * left flower's drones one way, the right's the other) and swinging a little wide as it goes; exactly at
     * {@code from} as it sets off and at {@code to} as it lands, and following {@code to} smoothly as that moves.
     */
    public static Vec3 spiral(ArmageddonState state, int side, Vec3 from, Vec3 to, double progress) {
        double p = Math.clamp(progress, 0, 1), t = p * p * (3 - 2 * p);
        if (p <= 0) return from;
        if (p >= 1) return to;
        Vec3 axis = face(state, side)[2], heart = heart(state, side);
        double sweep = (side < 0 ? 1 : -1) * Math.PI * 2.5;
        // Unwound by the whole sweep at the start, so that winding it back on the way lands exactly where it began.
        Vec3 start = HiveShapes.rotate(from.subtract(heart), axis, -sweep), end = to.subtract(heart);
        Vec3 between = start.lerp(end, t);
        Vec3 across = between.subtract(axis.scale(between.dot(axis)));
        between = between.add(across.scale(.35 * Math.sin(Math.PI * t)));
        return heart.add(HiveShapes.rotate(between, axis, sweep * (1 - t)));
    }

    /** Where place {@code slot} of {@code slots} is at {@code time} (flower places once gathered; see {@link #gathered} for the way in). */
    public static Vec3 station(ArmageddonState state, int slot, int slots, double time) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        double age = state.age(time);
        int flowered = flowered(slots);
        if (slot < flowered) return flowerStation(state, slot, flowered, age);
        return escortStation(state, slot - flowered, slots - flowered, age);
    }

    /**
     * A petal drone: resting in its petal and drifting slowly about its place, until the flowers close at
     * IGNITE and every drone folds into a small ball at its flower's heart, where it waits to go home.
     */
    private static Vec3 flowerStation(ArmageddonState state, int slot, int flowered, double age) {
        int side = side(slot), count = side < 0 ? (flowered + 1) / 2 : flowered / 2, index = slot / 2;
        double[] place = flowerPlace(index, Math.max(1, count));
        Vec3[] face = face(state, side);
        double drift = .06;
        double right = place[0] + drift * Math.sin(age * .021 + hash(slot, 1) * 6.283), up = place[1] + drift * Math.cos(age * .017 + hash(slot, 2) * 6.283);
        double out = .05 * Math.sin(age * .013 + hash(slot, 3) * 6.283) - .12 * Math.hypot(place[0], place[1]) / PETAL;
        Vec3 petal = onFace(state, side, face, right, up, out);
        if (age < IGNITE) return petal;
        // Closing: every petal folds into the heart, into a ball a third of a block across.
        double y = 1 - 2 * (index + .5) / Math.max(1, count), around = index * GOLDEN_ANGLE + age * .04, r = Math.sqrt(Math.max(0, 1 - y * y));
        Vec3 bud = onFace(state, side, face, Math.cos(around) * r * .32, y * .32, Math.sin(around) * r * .32);
        double closed = Math.clamp((age - IGNITE) / COLLAPSE, 0, 1);
        closed = closed * closed * (3 - 2 * closed);
        return petal.lerp(bud, closed);
    }

    /**
     * An escort drone: circling its flower's heart while it charges; then riding its stream out to the target in
     * a helix round the beam, one after another; swirling round the collision; holding the sphere of runes round
     * the sun; and flung away when the sphere shatters.
     */
    private static Vec3 escortStation(ArmageddonState state, int escort, int escorts, double age) {
        int side = side(escort), count = side < 0 ? (escorts + 1) / 2 : escorts / 2, index = escort / 2;
        count = Math.max(1, count);
        Vec3[] face = face(state, side);
        double ring = index * Math.PI * 2 / count + age * .035 * side;
        Vec3 circling = onFace(state, side, face, Math.cos(ring) * .62, Math.sin(ring) * .62, .08);
        if (age < FIRE) return circling;
        // Along the stream behind its head, each drone setting off a little after the one before and flying as the head flew.
        double delay = 3 + 22.0 * index / count, along = streamHead(age - delay);
        Vec3 riding = circling;
        if (along > 0) {
            Vec3[] axes = streamAxes(state, side, along);
            double angle = index * GOLDEN_ANGLE + age * .45 * side, radius = 1.15 + .45 * hash(escort, 4);
            Vec3 helix = stream(state, side, along).add(axes[1].scale(Math.cos(angle) * radius)).add(axes[2].scale(Math.sin(angle) * radius));
            // Leaving the heart's ring smoothly as its turn on the stream comes.
            double leave = Math.clamp((age - FIRE - delay) / 4, 0, 1);
            riding = circling.lerp(helix, leave * leave * (3 - 2 * leave));
        }
        // Where the stream ends: swirling round the collision.
        Vec3 swirl = swirl(state, escort, escorts, age);
        double joined = Math.clamp((age - ARRIVE - delay) / 10, 0, 1);
        Vec3 at = joined <= 0 ? riding : riding.lerp(swirl, joined * joined * (3 - 2 * joined));
        if (age < IGNITE) return at;
        // Holding the sphere: each drone flies to its own place on the dome round the sun and rides it.
        Vec3 shell = shell(state, escort, escorts, age);
        double held = Math.clamp((age - IGNITE) / 12, 0, 1);
        at = at.lerp(shell, held * held * (3 - 2 * held));
        if (age < IMPACT) return at;
        // The sphere shatters: flung out from it, slowing as they go, each its own way.
        Vec3 away = shell.subtract(state.target());
        away = away.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : away.normalize();
        double u = Math.clamp((age - IMPACT) / 40, 0, 1), flown = 70 * (.6 + .8 * hash(escort, 5)) * u * u * (3 - 2 * u);
        return shell(state, escort, escorts, IMPACT).add(away.scale(flown)).add(0, flown * .3, 0);
    }

    /** Escort drone {@code escort}'s place in the swirl round the collision, turning faster the nearer it is. */
    private static Vec3 swirl(ArmageddonState state, int escort, int escorts, double age) {
        double radius = 4 + 9 * hash(escort, 6), height = 1.5 + 13 * hash(escort, 7);
        double angle = escort * GOLDEN_ANGLE + age * (.05 + .5 / (1 + radius));
        return state.target().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    /** Escort drone {@code escort}'s place on the dome of runes round the sun, turning with it. */
    private static Vec3 shell(ArmageddonState state, int escort, int escorts, double age) {
        double y = (escort + .5) / Math.max(1, escorts) * .92, around = escort * GOLDEN_ANGLE + age * .01, r = Math.sqrt(Math.max(0, 1 - y * y));
        double radius = SPHERE * 1.04;
        return state.target().add(Math.cos(around) * r * radius, y * radius, Math.sin(around) * r * radius);
    }

    // --- the streams ------------------------------------------------------------------------------

    /**
     * How far along its arc each stream's head has flown {@code age} ticks in (0 at its flower, 1 at the target),
     * gathering speed as it goes.
     */
    public static double streamHead(double age) {
        double u = Math.clamp((age - FIRE) / (ARRIVE - FIRE), 0, 1);
        return Math.pow(u, 1.25);
    }

    /** How much of each stream's tail has drawn in after the flowers close: 0 while they pour, 1 once the stream is gone. */
    public static double streamTail(double age) {
        double u = Math.clamp((age - IGNITE) / 14, 0, 1);
        return u * u * (3 - 2 * u);
    }

    /**
     * The point {@code u} (0 at the flower's heart, 1 at the target) along flower {@code side}'s stream: an arc that
     * bows out to its own side and comes in to the target from that side, so the two streams meet head-on.
     */
    public static Vec3 stream(ArmageddonState state, int side, double u) {
        Vec3[] c = streamControls(state, side);
        double t = Math.clamp(u, 0, 1), s = 1 - t;
        return c[0].scale(s * s * s).add(c[1].scale(3 * s * s * t)).add(c[2].scale(3 * s * t * t)).add(c[3].scale(t * t * t));
    }

    /** The stream's direction at {@code u}, and two axes across it. */
    public static Vec3[] streamAxes(ArmageddonState state, int side, double u) {
        Vec3[] c = streamControls(state, side);
        double t = Math.clamp(u, 0, 1), s = 1 - t;
        Vec3 tangent = c[1].subtract(c[0]).scale(3 * s * s).add(c[2].subtract(c[1]).scale(6 * s * t)).add(c[3].subtract(c[2]).scale(3 * t * t));
        tangent = tangent.lengthSqr() < 1e-9 ? frame(state)[0] : tangent.normalize();
        Vec3 across = tangent.cross(new Vec3(0, 1, 0));
        across = across.lengthSqr() < 1e-6 ? frame(state)[1] : across.normalize();
        return new Vec3[]{tangent, across, across.cross(tangent).normalize()};
    }

    /** The four control points of flower {@code side}'s arc. */
    private static Vec3[] streamControls(ArmageddonState state, int side) {
        Vec3[] f = frame(state);
        Vec3 from = heart(state, side), to = state.target();
        double reach = to.subtract(from).length(), bow = Math.min(reach * .42, 95) + 1.5;
        Vec3 out = from.add(f[0].scale(reach * .3)).add(f[1].scale(side * bow * .7)).add(0, reach * .07 + 1.5, 0);
        Vec3 in = to.add(f[1].scale(side * bow));
        return new Vec3[]{from, out, in, to};
    }

    // --- the vortex -------------------------------------------------------------------------------

    /** How far out the vortex has torn the land {@code age} ticks in: nothing until TEAR, all of its reach just before the sun ignites. */
    public static double torn(double age) {
        double u = Math.clamp((age - TEAR) / (IGNITE - 4 - TEAR), 0, 1);
        return CARVE_RADIUS * u * u * (3 - 2 * u);
    }

    /** When the vortex tears up the land {@code distance} blocks from the target: the age at which {@link #torn} first reaches it. */
    public static double tornAt(double distance) {
        if (distance <= 0) return TEAR;
        double low = TEAR, high = IGNITE - 4;
        if (torn(high) < distance) return high;
        for (int step = 0; step < 24; step++) {
            double middle = (low + high) / 2;
            if (torn(middle) < distance) low = middle;
            else high = middle;
        }
        return high;
    }

    // --- the blast --------------------------------------------------------------------------------

    /** How far the dome of light has swept {@code sinceImpact} ticks after the burst: out to {@link #RADIUS}, easing as it goes. */
    public static double dome(double sinceImpact) {
        if (sinceImpact < DOME) return 0;
        double t = Math.clamp((sinceImpact - DOME) / EXPAND, 0, 1);
        return LIGHT_START + (RADIUS - LIGHT_START) * (1 - Math.pow(1 - t, 1.5));
    }

    /** When the dome of light first reaches {@code distance} blocks from the burst, in ticks after it. */
    public static double domeReaches(double distance) {
        if (distance <= LIGHT_START) return DOME;
        double f = Math.clamp((distance - LIGHT_START) / (RADIUS - LIGHT_START), 0, 1);
        return DOME + EXPAND * (1 - Math.pow(1 - f, 2 / 3.0));
    }

    /**
     * The column of light's radius {@code sinceImpact} ticks after the burst: a thread as it rises at COLUMN, easing
     * out to {@link #COLUMN_RADIUS} exactly as the blast falls silent at {@link #BLAST}, and no wider after, while it
     * dissolves.
     */
    public static double column(double sinceImpact) {
        if (sinceImpact < COLUMN) return 0;
        double u = Math.clamp((sinceImpact - COLUMN) / (BLAST - COLUMN), 0, 1);
        return COLUMN_START + (COLUMN_RADIUS - COLUMN_START) * (1 - (1 - u) * (1 - u));
    }

    /** How far the ring of stones has run along the ground {@code sinceImpact} ticks after the burst. */
    public static double shock(double sinceImpact) {
        if (sinceImpact < 0) return 0;
        double u = Math.clamp(sinceImpact / SHOCK, 0, 1);
        return SHOCK_RADIUS * (1 - (1 - u) * (1 - u) * (1 - u));
    }

    /**
     * The Mana Armageddon's course: the vortex tears the land up from TEAR and keeps catching up until well after
     * the burst, lifting creatures into it until the sun ignites; every client near is told of the blast as the
     * streams meet, since all that follows is seen from far off; the dome of light strikes as it sweeps out.
     */
    public static final ArmageddonTimeline TIMELINE = new ArmageddonTimeline() {
        @Override public int assembled() { return ASSEMBLED; }
        @Override public int fire() { return FIRE; }
        @Override public int arrive() { return ARRIVE; }
        @Override public int told() { return ARRIVE; }
        @Override public int impact() { return IMPACT; }
        @Override public int recover() { return RECOVER; }
        @Override public int end() { return END; }
        @Override public double carveRadius() { return CARVE_RADIUS; }
        @Override public double carved(double age) { return torn(age); }
        @Override public double carvedAt(double distance) { return tornAt(distance); }
        @Override public int carvedUntil() { return IMPACT + 200; }
        @Override public boolean drags(double age) { return age >= TEAR && age < IGNITE; }
        @Override public double radius() { return RADIUS; }
        @Override public double reach(double sinceImpact) { return dome(sinceImpact); }
        @Override public double reaches(double distance) { return domeReaches(distance); }
        @Override public int swept() { return DOME + EXPAND; }
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

    private ManaArmageddon() {
    }
}
