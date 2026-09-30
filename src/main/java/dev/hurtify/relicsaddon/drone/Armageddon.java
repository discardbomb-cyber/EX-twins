package dev.hurtify.relicsaddon.drone;

import net.minecraft.world.phys.Vec3;

/**
 * The Twins hive's ultimate, Armageddon: the whole deployed swarm builds a cannon over its owner's head
 * and fires one shot at the point the owner looks at, a blast {@link #RADIUS} blocks round. Timings are
 * ticks from the start; everything here is shared by the server and the client, so both build the
 * same cannon.
 *
 * <p>The cannon lies along its axis from the breech, where the core glows, to the muzzle: a barrel of
 * hexagons, four double accelerator rings on it, and round it all a gyroscope of hoops at different
 * angles, turning and sweeping round, the swarm running along them. While it charges the rings light
 * one after another; then the hoops come apart drone by drone and gather again in front of the muzzle
 * as a flared funnel, the hyperbolic focus in which the shot takes shape: a black hole. It flies out to
 * the target ringed by the funnel's drones in three tori, and where it lands they are flung away with
 * their shields as it bursts.
 */
public final class Armageddon {
    /** How far the blast reaches, and how far off its owner can aim the shot. */
    public static final double RADIUS = 256, REACH = 256;
    /**
     * Ticks from the start: the drones have gathered into the cannon, the charge is full (after the best
     * part of a minute) and the hoops start to reform, the black hole is fired a minute in, reaches its target a
     * second later and hangs there for three more, devouring the land round it, before it bursts.
     */
    public static final int ASSEMBLED = 40, CHARGED = 1140, FIRE = 1200, ARRIVE = 1220, IMPACT = 1280;
    /** The black hole's horizon when the ball opens on touching its target (15 blocks across). */
    public static final double HOLE = 7.5;
    /** Round its target the black hole devours everything within this many blocks, from the middle out. */
    public static final double DEVOUR_RADIUS = 90;
    /**
     * At the target: the containment of drones round the black hole breaks at ARRIVE and is gone by BROKEN; a
     * breath of silence; from HUNGER the black hole crushes itself to a point and devours the land until IMPACT.
     */
    public static final int BROKEN = ARRIVE + 18, HUNGER = ARRIVE + 23;
    /**
     * The supernova, in ticks after the black hole bursts (the sounds are timed to these): the white blast
     * until FLASH and the blinded, stunned moments after it until BALL; the ball of light forming at BALL and
     * swelling out to {@link #RADIUS} EXPAND ticks later, holding until HOLD and crushed back in by CRUSHED; the
     * eruption, a beam of light to the zenith, from ERUPT, narrowing to a thread from NARROW, gone at GONE.
     */
    public static final int FLASH = 6, BALL = 50, EXPAND = 130, HOLD = 200, CRUSHED = 260, ERUPT = 255, NARROW = 520, GONE = 570;
    /** The ball of light's radius as it forms. */
    public static final double LIGHT_START = 6;
    /** The eruption's beam at its widest (128 blocks across), and how deep below the burst it bores the land out. */
    public static final double BEAM = 64, BORE_DEPTH = 24;
    /** The cannon comes apart at RECOVER, once the blast has burnt out, and by END its drones are home. */
    public static final int RECOVER = IMPACT + GONE + 20, END = RECOVER + 30;
    public static final int RINGS = 4;
    /** Along the axis from the cannon's middle: the core at the breech, and the muzzle. */
    public static final double CORE = -5.4, MUZZLE = 5.6;
    private static final double[] RING_AT = {-3, -.6, 1.8, 4.2}, RING_RADIUS = {1.7, 2.1, 2.1, 1.8};
    /** The gyroscope round the cannon: six hoops in two layers, each a circle stretched along the axis. */
    public static final int HOOPS = 6;
    private static final double[] HOOP_RADIUS = {3.3, 3.3, 3.3, 4.5, 4.5, 4.5};
    /** How far each hoop's plane leans from square across the axis, where round the axis it leans, and how fast it sweeps round and its drones run along it. */
    private static final double[] HOOP_TILT = {.35, 1.15, 1.9, .8, 1.5, 2.55}, HOOP_TURN = {0, 2.1, 4.2, 1.05, 3.15, 5.25};
    private static final double[] HOOP_SWEEP = {.012, -.017, .022, -.009, .014, -.02}, HOOP_RUN = {.045, -.06, .05, -.035, .04, -.055};
    /** Hoops stretch this much along the axis, so the gyroscope wraps the long cannon. */
    public static final double HOOP_STRETCH = 1.75;
    /** The barrel: its two layers of hexagons, from just ahead of the core to the muzzle. */
    public static final double BARREL_INNER = .8, BARREL_OUTER = 1.3, BARREL_FROM = CORE + .7;
    /** The focus funnel in front of the muzzle: where it starts, its longest, its waist and how fast it flares. */
    public static final double FUNNEL_START = 6.4, FUNNEL_LENGTH = 9, FUNNEL_WAIST = 1.1, FUNNEL_FLARE = 2.6;
    /** Shares of the places: the core's, the rings' (split over the four); the honeycomb takes the rest. */
    private static final double CORE_SHARE = .08, RING_SHARE = .4;
    /** How long each honeycomb drone takes to glide from the shell to the funnel. */
    public static final int REFORM_FLIGHT = 14;
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    public static double ringAt(int ring) {
        return RING_AT[ring];
    }

    public static double ringRadius(int ring) {
        return RING_RADIUS[ring];
    }

    /** When ring {@code ring} lights up: one after another, about every eleven seconds while the cannon charges. */
    public static double ringLit(int ring) {
        return ASSEMBLED + 220 * (ring + 1);
    }

    /** How full the charge is {@code age} ticks in: nothing until the drones have gathered, all of it when the beam fires. */
    public static double charge(double age) {
        return Math.clamp((age - ASSEMBLED) / (FIRE - ASSEMBLED), 0, 1);
    }

    /** Where the cannon hangs for an owner whose eyes are at {@code eye}: over their head and a little behind, well clear of it. */
    public static Vec3 origin(Vec3 eye, Vec3 target) {
        Vec3 aim = target.subtract(eye);
        Vec3 flat = new Vec3(aim.x, 0, aim.z);
        flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        return eye.add(0, 6.5, 0).subtract(flat.scale(1.5));
    }

    /** The cannon's axes: forward (towards the target), side and up. */
    public static Vec3[] frame(ArmageddonState state) {
        return HiveShapes.axes(state.target().subtract(state.origin()));
    }

    /** A point of the cannon: {@code along} its axis from its middle, and {@code side} and {@code up} across it. */
    public static Vec3 point(ArmageddonState state, Vec3[] frame, double along, double side, double up) {
        return state.origin().add(frame[0].scale(along)).add(frame[1].scale(side)).add(frame[2].scale(up));
    }

    public static Vec3 muzzle(ArmageddonState state) {
        return point(state, frame(state), MUZZLE, 0, 0);
    }

    /** The funnel's length for this shot: shorter when the target is close. */
    public static double funnelLength(ArmageddonState state) {
        return Math.clamp(state.target().distanceTo(state.origin()) - FUNNEL_START - 1.5, 2, FUNNEL_LENGTH);
    }

    /** The funnel's radius {@code s} blocks past its start: a waist flaring out like a hyperboloid, with a slow ripple running along it. */
    public static double funnelRadius(double s, double age) {
        return FUNNEL_WAIST * Math.sqrt(1 + (s / FUNNEL_FLARE) * (s / FUNNEL_FLARE)) + .16 * Math.sin(s * 1.9 - age * .25);
    }

    /** The ball of light the cannon fires: its size, and the radius of the tori of drones holding it closed. */
    public static final double SHOT_HORIZON = 3.2, SHOT_RINGS = 9.5;
    /** How long the funnel's drones take to catch up with the black hole as it leaves, and how far they are flung when it bursts. */
    private static final int GATHER = 14;
    private static final double SCATTER = 70;

    /** Where the black hole is {@code age} ticks in: taking shape at the muzzle, then flying out to the target, faster as it goes. */
    public static Vec3 shot(ArmageddonState state, double age) {
        Vec3 muzzle = point(state, frame(state), FUNNEL_START + 1.5, 0, 0);
        double u = Math.clamp((age - FIRE) / (ARRIVE - FIRE), 0, 1);
        return muzzle.lerp(state.target(), Math.pow(u, 1.3));
    }

    /** How far out the black hole has devoured {@code age} ticks in: nothing until it arrives, the whole radius just before it bursts. */
    public static double devoured(double age) {
        double u = Math.clamp((age - HUNGER) / (IMPACT - HUNGER - 4), 0, 1);
        return DEVOUR_RADIUS * u * u * (3 - 2 * u);
    }

    /** When the black hole takes a block {@code distance} blocks from it: the age at which {@link #devoured} first reaches it. */
    public static double devouredAt(double distance) {
        if (distance <= 0) return HUNGER;
        double low = HUNGER, high = IMPACT - 4;
        if (devoured(high) < distance) return high;
        for (int step = 0; step < 24; step++) {
            double middle = (low + high) / 2;
            if (devoured(middle) < distance) low = middle;
            else high = middle;
        }
        return high;
    }

    /** How big the black hole is {@code age} ticks in: nothing until the funnel starts to focus, whole as it leaves. */
    public static double shotSize(double age) {
        double u = Math.clamp((age - CHARGED) / (FIRE - CHARGED), 0, 1);
        return SHOT_HORIZON * u * u * (3 - 2 * u);
    }

    /** How many of {@code slots} places gather round the core. */
    public static int cores(int slots) {
        return Math.min(Math.max(1, slots), Math.max(1, (int) Math.round(slots * CORE_SHARE)));
    }

    /** How many of {@code slots} places ride the rings. */
    public static int ringed(int slots) {
        return Math.max(0, Math.min(slots - cores(slots), (int) Math.round(slots * RING_SHARE)));
    }

    public static double hoopRadius(int hoop) {
        return HOOP_RADIUS[hoop];
    }

    /**
     * Hoop {@code hoop}'s frame at {@code age}: its middle is the cannon's, its first two axes span its
     * plane (the first along the stretch), and it sweeps round the cannon's axis.
     */
    public static Vec3[] hoopFrame(Vec3[] frame, int hoop, double age) {
        double tilt = HOOP_TILT[hoop], turn = HOOP_TURN[hoop] + age * HOOP_SWEEP[hoop];
        Vec3 lean = frame[1].scale(Math.cos(turn)).add(frame[2].scale(Math.sin(turn)));
        // Square across the axis the hoop's normal is the axis; tilting leans it towards {@code lean}.
        Vec3 normal = frame[0].scale(Math.cos(tilt)).add(lean.scale(Math.sin(tilt)));
        Vec3 first = frame[0].scale(-Math.sin(tilt)).add(lean.scale(Math.cos(tilt)));
        return new Vec3[]{first, normal.cross(first).normalize(), normal};
    }

    /** A point {@code angle} round hoop {@code hoop} at {@code age}, stretched along the cannon's axis. */
    public static Vec3 hoopPoint(ArmageddonState state, Vec3[] frame, int hoop, double angle, double age) {
        Vec3[] h = hoopFrame(frame, hoop, age);
        Vec3 local = h[0].scale(Math.cos(angle) * HOOP_RADIUS[hoop]).add(h[1].scale(Math.sin(angle) * HOOP_RADIUS[hoop]));
        double along = local.dot(frame[0]);
        return state.origin().add(local).add(frame[0].scale(along * (HOOP_STRETCH - 1)));
    }

    /** How far drones have run round hoop {@code hoop} by {@code age}. */
    public static double hoopRun(int hoop, double age) {
        return age * HOOP_RUN[hoop];
    }

    /** How many of {@code slots} places make up the gyroscope. */
    public static int shells(int slots) {
        return Math.max(0, slots - cores(slots) - ringed(slots));
    }

    /** How far ring {@code ring} has turned: slowly at first, fast once it is lit, slower again after the shot; neighbours turn opposite ways. */
    public static double ringTurn(int ring, double age) {
        double lit = ringLit(ring), fast = Math.clamp(age - lit, 0, FIRE - lit), after = Math.max(0, age - FIRE);
        return (ring % 2 == 0 ? 1 : -1) * (.015 * age + .09 * fast + .03 * after);
    }

    /**
     * How far gyroscope drone {@code index} of {@code count} is on its way from its hoop to the funnel:
     * one after another they peel off, and the last lands as the beam fires.
     */
    public static double reform(int index, int count, double age) {
        double start = CHARGED + (double) index / Math.max(1, count) * (FIRE - CHARGED - REFORM_FLIGHT);
        return Math.clamp((age - start) / REFORM_FLIGHT, 0, 1);
    }

    /** Where place {@code slot} of {@code slots} sits in the cannon at {@code time}. */
    public static Vec3 station(ArmageddonState state, int slot, int slots, double time) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        double age = state.age(time);
        Vec3[] frame = frame(state);
        int cores = cores(slots), ringed = ringed(slots);
        if (slot < cores) {
            // A ball round the core, drawing in as the charge builds.
            double y = 1 - 2 * (slot + .5) / cores, around = slot * GOLDEN_ANGLE + age * .05;
            double r = Math.sqrt(Math.max(0, 1 - y * y)), size = .95 - .3 * charge(age);
            return point(state, frame, CORE + y * size, Math.cos(around) * r * size, Math.sin(around) * r * size);
        }
        slot -= cores;
        if (slot < ringed) {
            int ring = slot % RINGS, member = slot / RINGS, members = (ringed - ring + RINGS - 1) / RINGS;
            double angle = member * Math.PI * 2 / Math.max(1, members) + ringTurn(ring, age);
            return point(state, frame, RING_AT[ring], Math.cos(angle) * RING_RADIUS[ring], Math.sin(angle) * RING_RADIUS[ring]);
        }
        slot -= ringed;
        int count = slots - cores - ringed;
        if (age >= FIRE) return escort(state, frame, slot, count, age);
        Vec3 shell = shellPoint(state, frame, slot, count, age);
        double reform = reform(slot, count, age);
        if (reform <= 0) return shell;
        Vec3 funnel = funnelPoint(state, frame, slot, count, age);
        if (reform >= 1) return funnel;
        // Each drone peels off its hoop and glides forward on a bow away from the axis, bowing the way its hoop
        // place faces (a steady direction, so it never flips as the drone passes near the axis).
        double t = reform * reform * (3 - 2 * reform);
        Vec3 at = shell.lerp(funnel, t), out = shell.subtract(state.origin());
        Vec3 radial = out.subtract(frame[0].scale(out.dot(frame[0])));
        return radial.lengthSqr() < 1e-6 ? at : at.add(radial.normalize().scale(Math.sin(Math.PI * t) * 1.4));
    }

    /**
     * A funnel drone once the shot has left: catching up with the black hole and riding the tori round it
     * to the target, then flung away from it as it bursts.
     */
    private static Vec3 escort(ArmageddonState state, Vec3[] frame, int index, int count, double age) {
        double flying = Math.min(age, ARRIVE);
        Vec3 ring = shot(state, flying).add(HiveShapes.dysonRing(index, count, flying, SHOT_RINGS, true));
        if (age < FIRE + GATHER) {
            double t = (age - FIRE) / GATHER;
            return funnelPoint(state, frame, index, count, FIRE).lerp(ring, t * t * (3 - 2 * t));
        }
        if (age < ARRIVE) return ring;
        // The containment breaks: flung out from the black hole, slowing as they go, each its own way.
        Vec3 out = ring.subtract(state.target());
        out = out.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : out.normalize();
        double spread = SCATTER * (.6 + .8 * spread(index)), u = Math.clamp((age - ARRIVE) / 36, 0, 1), flown = spread * u * u * (3 - 2 * u);
        return ring.add(out.scale(flown)).add(0, flown * .35, 0);
    }

    private static double spread(int index) {
        long h = index * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Gyroscope drone {@code index} of {@code count}: spread evenly over the six hoops, running round its own. */
    public static Vec3 shellPoint(ArmageddonState state, Vec3[] frame, int index, int count, double age) {
        int hoop = index % HOOPS, member = index / HOOPS, members = Math.max(1, (count - hoop + HOOPS - 1) / HOOPS);
        return hoopPoint(state, frame, hoop, member * Math.PI * 2 / members + hoopRun(hoop, age), age);
    }

    /** Honeycomb drone {@code index} of {@code count} on the funnel: the same spiral laid along the funnel from the muzzle out. */
    public static Vec3 funnelPoint(ArmageddonState state, Vec3[] frame, int index, int count, double age) {
        double s = (index + .5) / Math.max(1, count) * funnelLength(state);
        double around = index * GOLDEN_ANGLE + age * .03, r = funnelRadius(s, age);
        return point(state, frame, FUNNEL_START + s, Math.cos(around) * r, Math.sin(around) * r);
    }

    /** How far the ball of light has swollen {@code sinceImpact} ticks after the burst: out to {@link #RADIUS}, easing as it goes. */
    public static double front(double sinceImpact) {
        if (sinceImpact < BALL) return 0;
        double t = Math.clamp((sinceImpact - BALL) / EXPAND, 0, 1);
        return LIGHT_START + (RADIUS - LIGHT_START) * (1 - Math.pow(1 - t, 1.5));
    }

    /** The ball of light's radius {@code sinceImpact} ticks after the burst: swelling out, holding, then crushed back in to nothing. */
    public static double ball(double sinceImpact) {
        if (sinceImpact < HOLD) return front(sinceImpact);
        double u = Math.clamp((sinceImpact - HOLD) / (CRUSHED - HOLD), 0, 1);
        return RADIUS * (1 - u * u * (3 - 2 * u));
    }

    /** The eruption's beam's radius {@code sinceImpact} ticks after the burst: widening to {@link #BEAM} over three seconds, narrowing to a thread at the end. */
    public static double beam(double sinceImpact) {
        if (sinceImpact < ERUPT) return 0;
        double wide = Math.clamp((sinceImpact - ERUPT) / 60, 0, 1), narrow = Math.clamp((sinceImpact - NARROW) / 40, 0, 1);
        return BEAM * wide * wide * (3 - 2 * wide) * (1 - .97 * narrow * narrow * (3 - 2 * narrow));
    }

    /** How far the blast has struck {@code sinceImpact} ticks after the burst: everything the ball of light has swept over. */
    public static double reach(double sinceImpact) {
        return front(sinceImpact);
    }

    /** When the blast first strikes {@code distance} blocks from where it burst, in ticks after it. */
    public static double reaches(double distance) {
        if (distance <= LIGHT_START) return BALL;
        double f = Math.clamp((distance - LIGHT_START) / (RADIUS - LIGHT_START), 0, 1);
        return BALL + EXPAND * (1 - Math.pow(1 - f, 2 / 3.0));
    }

    /**
     * The Twins Armageddon's course: the black hole devours the land round its target from HUNGER, until
     * the ball of light is crushed back in, and drags creatures in until it bursts; every client is told of
     * the blast as it bursts.
     */
    public static final ArmageddonTimeline TIMELINE = new ArmageddonTimeline() {
        @Override public int assembled() { return ASSEMBLED; }
        @Override public int fire() { return FIRE; }
        @Override public int arrive() { return ARRIVE; }
        @Override public int told() { return IMPACT; }
        @Override public int impact() { return IMPACT; }
        @Override public int recover() { return RECOVER; }
        @Override public int end() { return END; }
        @Override public double carveRadius() { return DEVOUR_RADIUS; }
        @Override public double carved(double age) { return devoured(age); }
        @Override public double carvedAt(double distance) { return devouredAt(distance); }
        @Override public int carvedUntil() { return IMPACT + CRUSHED; }
        @Override public boolean drags(double age) { return age >= HUNGER && age < IMPACT; }
        @Override public double radius() { return RADIUS; }
        @Override public double reach(double sinceImpact) { return Armageddon.reach(sinceImpact); }
        @Override public double reaches(double distance) { return Armageddon.reaches(distance); }
        @Override public int swept() { return BALL + EXPAND; }
    };

    private Armageddon() {
    }
}
