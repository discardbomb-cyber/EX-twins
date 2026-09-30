package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.math.Vec3d;

/** How the shell holds a hostile mob out: the geometry of the shove back to the surface. */
public final class BarrierPush {
    /** Charge spent each tick a mob is held out. */
    public static final int PUSH_COST = 1;
    /** Outward drift given to a held mob that is not already flying away. */
    public static final double DRIFT = .35;

    /** How far from the field centre a mob of {@code width} touches the shell. */
    public static double reach(double radius, double width) {
        return radius + width * .5;
    }

    /**
     * The unit direction from the field centre out to the mob; the owner's horizontal look when the mob
     * sits on the centre, and +x when that is no direction either.
     */
    public static Vec3d outward(Vec3d offset, double distance, Vec3d look) {
        Vec3d out = distance < 1e-3 ? look.multiply(1, 0, 1) : offset.scale(1 / distance);
        if (out.lengthSqr() < 1e-6) out = new Vec3d(1, 0, 0);
        return out.normalize();
    }

    /** The ground-plane part of {@code out}, as a unit vector; +x when it is straight up or down. */
    public static Vec3d horizontal(Vec3d out) {
        Vec3d horizontal = new Vec3d(out.x, 0, out.z);
        if (horizontal.lengthSqr() < 1e-6) horizontal = new Vec3d(1, 0, 0);
        return horizontal.normalize();
    }

    /** How far one tick moves a mob that is {@code depth} inside the shell. */
    public static double step(double depth) {
        return Math.min(depth, 1.5);
    }

    /** Whether the mob is not already moving outward at the drift speed. */
    public static boolean needsDrift(Vec3d motion, Vec3d horizontal) {
        return motion.x * horizontal.x + motion.z * horizontal.z < DRIFT;
    }

    /** Outward drift that keeps any upward motion, and at least a small lift. */
    public static Vec3d drift(Vec3d horizontal, Vec3d motion) {
        return horizontal.scale(DRIFT).add(0, Math.max(motion.y, .05), 0);
    }

    private BarrierPush() { }
}
