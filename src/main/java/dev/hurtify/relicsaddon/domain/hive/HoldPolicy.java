package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.math.Vec3d;

/**
 * Containment holds: when another swarm joins a hold, when a hold goes stale, where a held creature is
 * pinned and how Mana's ward fills.
 */
public final class HoldPolicy {
    /** Blocks a Twins hold lifts its target at most. */
    public static final int LIFT = 4;
    /** Ticks the lift takes. */
    public static final int LIFT_TICKS = 30;
    /** How far under an airborne target to look for the ground. */
    public static final int GROUND_PROBE = LIFT + 2;

    /** Another swarm holds it and is still at it: join in rather than tear its hold down every tick. */
    public static boolean joinsOther(boolean mine, long seen, long now) {
        return !mine && now - seen <= 1 && now >= seen;
    }

    /** A hold whose hive stopped refreshing it (target lost, hive off, owner gone), or whose target died. */
    public static boolean stale(boolean alive, long seen, long now) {
        return !alive || now - seen > 2 || now < seen;
    }

    /** Where a held creature is kept: its anchor, which a construct raises from {@code startLift} to {@code lift} along a smoothstep. */
    public static Vec3d pinPoint(Vec3d anchor, boolean lifts, double startLift, double lift, long since, long now) {
        if (!lifts) return anchor;
        double t = Math.min(1, (now - since) / (double) LIFT_TICKS);
        return anchor.add(0, startLift + (lift - startLift) * t * t * (3 - 2 * t), 0);
    }

    /** Mana's ward topped up by {@code amount}, up to its capacity. */
    public static double refill(double ward, double wardMax, double amount) {
        return Math.min(wardMax, ward + Math.max(0, amount));
    }

    /** The ward holds one point per flying drone, at least one. */
    public static int wardMax(int drones) {
        return Math.max(1, drones);
    }

    private HoldPolicy() { }
}
