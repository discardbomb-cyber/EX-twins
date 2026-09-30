package dev.hurtify.relicsaddon.domain.math;

/**
 * An immutable axis-aligned box with exactly the arithmetic of Minecraft's {@code AABB}. Containment is
 * half-open: a point on a minimum face is inside, a point on a maximum face is not. MathKernelCheck
 * compares every operation with {@code AABB}.
 */
public final class Box {
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public Box(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public Box(Vec3d first, Vec3d second) {
        this(first.x, first.y, first.z, second.x, second.y, second.z);
    }

    public Box inflate(double amount) {
        return inflate(amount, amount, amount);
    }

    public Box inflate(double dx, double dy, double dz) {
        return new Box(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Grows the box on the side each component points to; a zero or NaN component grows nothing. */
    public Box expandTowards(Vec3d direction) {
        double dx = direction.x, dy = direction.y, dz = direction.z;
        double x1 = minX, y1 = minY, z1 = minZ, x2 = maxX, y2 = maxY, z2 = maxZ;
        if (dx < 0.0) {
            x1 += dx;
        } else if (dx > 0.0) {
            x2 += dx;
        }
        if (dy < 0.0) {
            y1 += dy;
        } else if (dy > 0.0) {
            y2 += dy;
        }
        if (dz < 0.0) {
            z1 += dz;
        } else if (dz > 0.0) {
            z2 += dz;
        }
        return new Box(x1, y1, z1, x2, y2, z2);
    }

    public boolean contains(Vec3d point) {
        return contains(point.x, point.y, point.z);
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    public Vec3d center() {
        return new Vec3d(Trig.lerp(0.5, minX, maxX), Trig.lerp(0.5, minY, maxY), Trig.lerp(0.5, minZ, maxZ));
    }
}
