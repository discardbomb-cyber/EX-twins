package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.math.Vec3d;

/**
 * One creature a swarm is engaged with: its entity id, where its feet were when last recorded and its
 * size. The swarm's formation around it is computed from these values alone, so the server and every
 * client place drones the same way even where the creature itself is not loaded.
 */
public record HiveTarget(int id, double x, double y, double z, double width, double height) {
    public HiveTarget {
        x = finite(x);
        y = finite(y);
        z = finite(z);
        width = Double.isFinite(width) ? Math.clamp(width, .1, 16) : .6;
        height = Double.isFinite(height) ? Math.clamp(height, .1, 16) : 1.8;
    }

    public HiveTarget(int id, Vec3d feet, double width, double height) {
        this(id, feet.x, feet.y, feet.z, width, height);
    }

    public Vec3d feet() {
        return new Vec3d(x, y, z);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -30_000_000D, 30_000_000D) : 0;
    }
}
