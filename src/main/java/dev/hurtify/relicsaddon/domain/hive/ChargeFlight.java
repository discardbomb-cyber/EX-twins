package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.math.Trig;
import dev.hurtify.relicsaddon.domain.math.Vec3d;

/** A barrage charge (ball lightning) flying from its clump to the target. */
public final class ChargeFlight {
    /** Blocks a charge flies per tick. */
    public static final double BALL_SPEED = .9;
    /** Ticks past its expected arrival before a charge that met nothing bursts where it is. */
    public static final int EXPIRY_GRACE = 40;

    public static Vec3d velocity(Vec3d start, Vec3d end) {
        return end.subtract(start).normalize().scale(BALL_SPEED);
    }

    /** Ticks a charge needs from {@code start} to {@code end}, at least one. */
    public static int flightTicks(Vec3d start, Vec3d end) {
        return Math.max(1, Trig.ceil(start.distanceTo(end) / BALL_SPEED));
    }

    public static long expiresAt(long now, Vec3d start, Vec3d end) {
        return now + flightTicks(start, end) + EXPIRY_GRACE;
    }

    /** The estimated impact time of a charge fired at {@code firedAt}; the landing replaces it. */
    public static long arrival(long firedAt, Vec3d start, Vec3d end) {
        return firedAt + flightTicks(start, end);
    }

    /** Charges home in on the target so a fleeing mob cannot sidestep them; one already at the target keeps its course. */
    public static Vec3d homing(Vec3d targetCentre, Vec3d position, Vec3d velocity) {
        Vec3d aim = targetCentre.subtract(position);
        return aim.lengthSqr() > 1e-6 ? aim.normalize().scale(BALL_SPEED) : velocity;
    }

    private ChargeFlight() { }
}
