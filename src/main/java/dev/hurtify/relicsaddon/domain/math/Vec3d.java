package dev.hurtify.relicsaddon.domain.math;

/**
 * An immutable vector with exactly the arithmetic of Minecraft's {@code Vec3}: the same operations in
 * the same order, so domain geometry gives the game's results bit for bit. MathKernelCheck compares
 * every operation with {@code Vec3}.
 */
public final class Vec3d {
    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);
    public final double x;
    public final double y;
    public final double z;

    public Vec3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The look direction for a pitch and a yaw in degrees, computed in float through the sine table like {@code Vec3}. */
    public static Vec3d directionFromRotation(float xRot, float yRot) {
        float cosYaw = Trig.cos(-yRot * (float) (Math.PI / 180.0) - (float) Math.PI);
        float sinYaw = Trig.sin(-yRot * (float) (Math.PI / 180.0) - (float) Math.PI);
        float negCosPitch = -Trig.cos(-xRot * (float) (Math.PI / 180.0));
        float sinPitch = Trig.sin(-xRot * (float) (Math.PI / 180.0));
        return new Vec3d((double) (sinYaw * negCosPitch), (double) sinPitch, (double) (cosYaw * negCosPitch));
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public Vec3d add(Vec3d other) {
        return add(other.x, other.y, other.z);
    }

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d subtract(Vec3d other) {
        return subtract(other.x, other.y, other.z);
    }

    public Vec3d subtract(double dx, double dy, double dz) {
        return add(-dx, -dy, -dz);
    }

    public Vec3d multiply(double fx, double fy, double fz) {
        return new Vec3d(x * fx, y * fy, z * fz);
    }

    public Vec3d scale(double factor) {
        return multiply(factor, factor, factor);
    }

    public Vec3d reverse() {
        return scale(-1.0);
    }

    /** {@link #ZERO} itself below a length of 1.0E-4. */
    public Vec3d normalize() {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-4 ? ZERO : new Vec3d(x / length, y / length, z / length);
    }

    public double dot(Vec3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3d cross(Vec3d other) {
        return new Vec3d(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double horizontalDistance() {
        return Math.sqrt(x * x + z * z);
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public double distanceTo(Vec3d other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceToSqr(Vec3d other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceToSqr(double px, double py, double pz) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public Vec3d lerp(Vec3d to, double delta) {
        return new Vec3d(Trig.lerp(delta, x, to.x), Trig.lerp(delta, y, to.y), Trig.lerp(delta, z, to.z));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Vec3d vector)) return false;
        if (Double.compare(vector.x, x) != 0) return false;
        return Double.compare(vector.y, y) != 0 ? false : Double.compare(vector.z, z) == 0;
    }

    @Override
    public int hashCode() {
        long bits = Double.doubleToLongBits(x);
        int hash = (int) (bits ^ bits >>> 32);
        bits = Double.doubleToLongBits(y);
        hash = 31 * hash + (int) (bits ^ bits >>> 32);
        bits = Double.doubleToLongBits(z);
        return 31 * hash + (int) (bits ^ bits >>> 32);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
