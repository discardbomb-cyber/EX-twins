package dev.hurtify.relicsaddon.drone;

import net.minecraft.world.phys.Vec3;

/**
 * Shared, deterministic hive positions: the server fires shots from these same points and the
 * client draws the drones there, so the motion is analytic rather than a client-only random walk.
 *
 * <p>A swarm is built to look alive at any size. In combat every drone keeps its own orbit around the
 * target (its own radius inside a band that thickens with the swarm, its own plane, speed and phase),
 * so hundreds of drones read as a moving cloud rather than a rigid shell. The families differ in how
 * those orbits are laid out: RF buzzes in a round cloud of randomly tilted orbits, Mana circles in
 * tilted rings, and the Twins turn in two counter-rotating families. Drones leave the hive one after
 * another along their own curved paths, so a deployment is a stream, not a single blob. Healers circle
 * their owner in a ring around the chest instead of crowding the camera.
 */
public final class HiveFormation {
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    /** Share of the deployment over which drone departures are staggered. */
    private static final double STAGGER = .45;
    /** Largest Mana ring; bigger swarms add rings instead of crowding one. */
    private static final int RING_CAPACITY = 24;

    public static Vec3 idle(Vec3 owner, float yaw, int index, int count, HiveType type, double time) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        return belt(owner, yaw, type).add(fibonacciSphere(index, count, .045, .6));
    }

    public static Vec3 belt(Vec3 owner, float yaw, HiveType type) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        return owner.add(right.scale((type.ordinal() - 1) * .16)).add(forward.scale(.22)).add(0, .86, 0);
    }

    /** Healers: a slowly turning ring around the owner's chest, wider and deeper as their number grows. */
    public static Vec3 healing(Vec3 owner, float yaw, int index, int count, HiveType type, double time, double progress) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        time = safeTime(time);
        Vec3 rest = idle(owner, yaw, index, count, type, time);
        double depth = Math.min(1.6, .3 + .07 * Math.sqrt(count));
        double radius = 1.15 + depth * hash(index, type, 11);
        double angle = index * GOLDEN_ANGLE + time * (.028 + .012 * hash(index, type, 12)) * (1.2 - .4 * radius / 2.75);
        double height = 1.0 + (hash(index, type, 13) - .5) * (.35 + depth * .4) + .05 * Math.sin(time * .09 + index);
        Vec3 deployed = owner.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
        return path(rest, deployed, stagger(index, type, progress), index, type);
    }

    /** A drone's own place in the combat swarm around a target standing at {@code target}. */
    public static Vec3 combat(Vec3 target, double targetWidth, double targetHeight,
            int index, int count, HiveType type, double time) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        time = safeTime(time);
        targetWidth = saneSize(targetWidth, .6);
        targetHeight = saneSize(targetHeight, 1.8);
        Vec3 center = target.add(0, targetHeight * .55, 0);
        double inner = Math.max(1.15, targetWidth * .6 + .95);
        double outer = inner + .5 + .25 * Math.cbrt(count);
        return center.add(switch (type) {
            case RF -> cloud(index, inner, outer, time);
            case MANA -> rings(index, count, inner, outer, time);
            case TWINS -> vortices(index, inner, outer, time);
        });
    }

    public static Vec3 position(Vec3 owner, float yaw, Vec3 target, double targetWidth, double targetHeight,
            int index, int count, HiveType type, double time, double progress) {
        Vec3 rest = idle(owner, yaw, index, count, type, time);
        if (target == null) return rest;
        Vec3 station = combat(target, targetWidth, targetHeight, index, count, type, time);
        return path(rest, station, stagger(index, type, progress), index, type);
    }

    /**
     * This drone's own share of a deployment: it sets off after a delay of its own and arrives by the
     * end, so a swarm pours out of the hive; a recall runs the same timeline backwards.
     */
    public static double stagger(int index, HiveType type, double progress) {
        double delay = hash(index, type, 21) * STAGGER;
        return Math.clamp((Math.clamp(progress, 0, 1) - delay) / (1 - STAGGER), 0, 1);
    }

    /** Curved flight from {@code from} to {@code to}: each drone bows out to its own side and rises a little. */
    private static Vec3 path(Vec3 from, Vec3 to, double progress, int index, HiveType type) {
        double p = Math.clamp(progress, 0, 1);
        if (p <= 0) return from;
        if (p >= 1) return to;
        double t = p * p * (3 - 2 * p);
        Vec3 span = to.subtract(from);
        double length = span.length();
        if (length < 1e-6) return to;
        Vec3 forward = span.scale(1 / length);
        Vec3 side = forward.cross(Math.abs(forward.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 lift = side.cross(forward);
        double angle = hash(index, type, 31) * Math.PI * 2;
        double bow = Math.min(2.2, length * (.18 + .22 * hash(index, type, 32)));
        Vec3 control = from.lerp(to, .5).add(side.scale(Math.cos(angle) * bow)).add(lift.scale(Math.abs(Math.sin(angle)) * bow * .8));
        // Quadratic Bezier through the control point.
        double u = 1 - t;
        return from.scale(u * u).add(control.scale(2 * u * t)).add(to.scale(t * t));
    }

    /** RF: a round cloud; every drone circles on its own randomly tilted orbit, inner ones faster. */
    private static Vec3 cloud(int index, double inner, double outer, double time) {
        HiveType type = HiveType.RF;
        double radius = shellRadius(hash(index, type, 1), inner, outer);
        double polar = Math.acos(1 - 2 * hash(index, type, 2)), node = hash(index, type, 3) * Math.PI * 2;
        double speed = orbitSpeed(radius, inner, .055, hash(index, type, 4)) * (hash(index, type, 5) < .5 ? 1 : -1);
        double angle = hash(index, type, 6) * Math.PI * 2 + time * speed;
        double breathe = 1 + .08 * Math.sin(time * .07 + index * 1.7);
        return orbit(radius * breathe, polar, node, angle).add(0, .12 * Math.sin(time * .11 + index), 0);
    }

    /**
     * Mana: tilted rings of evenly spaced drones, each ring at its own radius and speed; bigger swarms
     * add rings rather than crowd one. Drone {@code index} flies in ring {@code index % rings(count)}.
     */
    private static Vec3 rings(int index, int count, double inner, double outer, double time) {
        int rings = rings(count);
        int ring = index % rings, slot = index / rings;
        int population = (count - 1 - ring) / rings + 1;
        double level = rings == 1 ? .5 : ring / (double) (rings - 1);
        double radius = inner + (outer - inner) * level;
        double polar = .35 + (ring * GOLDEN_ANGLE) % 1.2, node = ring * GOLDEN_ANGLE;
        double speed = orbitSpeed(radius, inner, .04, hash(ring, HiveType.MANA, 7)) * (ring % 2 == 0 ? 1 : -1);
        double angle = slot * Math.PI * 2 / population + time * speed;
        double wobble = 1 + .03 * Math.sin(time * .12 + slot * 2.1 + ring);
        return orbit(radius * wobble, polar, node, angle);
    }

    /** How many Mana rings a swarm of {@code count} fighters flies in. */
    public static int rings(int count) {
        return Math.max(1, (int) Math.ceil(safeCount(count) / (double) RING_CAPACITY));
    }

    /** Twins: two families turning against each other in crossed planes, like a pair of gyroscopes. */
    private static Vec3 vortices(int index, double inner, double outer, double time) {
        HiveType type = HiveType.TWINS;
        boolean second = index % 2 == 1;
        double radius = shellRadius(hash(index, type, 1), inner, outer);
        // Planes scatter around each family's axis, so each family is a thick turning disc.
        double polar = (second ? 1.05 : .52) + (hash(index, type, 2) - .5) * .9;
        double node = (second ? Math.PI * .5 : 0) + (hash(index, type, 3) - .5) * 1.0;
        double speed = orbitSpeed(radius, inner, .05, hash(index, type, 4)) * (second ? -1 : 1);
        double angle = hash(index, type, 6) * Math.PI * 2 + time * speed;
        return orbit(radius, polar, node, angle).add(0, .1 * Math.sin(time * .1 + index * .9), 0);
    }

    /** Radius with drones spread evenly through the volume of the band, not piled on its inner edge. */
    private static double shellRadius(double u, double inner, double outer) {
        double a = inner * inner * inner, b = outer * outer * outer;
        return Math.cbrt(a + (b - a) * u);
    }

    /** Radians per tick: inner orbits turn faster, like planets, with a little individual spread. */
    private static double orbitSpeed(double radius, double inner, double base, double spread) {
        return base * Math.pow(inner / radius, 1.5) * (.8 + .4 * spread);
    }

    /** A point on a circle of {@code radius} whose plane is tilted by {@code polar} about X, then turned by {@code node} about Y. */
    private static Vec3 orbit(double radius, double polar, double node, double angle) {
        double x = Math.cos(angle) * radius, flat = Math.sin(angle) * radius;
        double y = flat * Math.sin(polar), z = flat * Math.cos(polar);
        return new Vec3(x * Math.cos(node) - z * Math.sin(node), y, x * Math.sin(node) + z * Math.cos(node));
    }

    private static Vec3 fibonacciSphere(int index, int count, double radius, double vertical) {
        double y = 1.0 - 2.0 * (index + .5) / count;
        double around = index * GOLDEN_ANGLE;
        double horizontal = Math.sqrt(Math.max(0, 1 - y * y));
        return new Vec3(Math.cos(around) * horizontal * radius, y * radius * vertical, Math.sin(around) * horizontal * radius);
    }

    /** Stable per-drone random number in [0, 1). */
    private static double hash(int index, HiveType type, int salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ (type.ordinal() + 1) * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static int safeCount(int count) { return Math.clamp(count, 1, HiveType.MAX_DRONES); }
    private static double safeTime(double time) { return Double.isFinite(time) ? time : 0; }
    private static double saneSize(double value, double fallback) { return Double.isFinite(value) ? Math.max(.1, value) : fallback; }
    private HiveFormation() { }
}
