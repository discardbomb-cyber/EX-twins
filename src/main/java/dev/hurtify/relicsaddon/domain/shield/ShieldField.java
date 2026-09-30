package dev.hurtify.relicsaddon.domain.shield;

import net.minecraft.world.phys.Vec3;

/** Shared world-space boundary for server interception and client anticipation. */
public final class ShieldField {
    public static final double RADIUS = 2.0D;
    public static final double CENTER_Y = .92D;
    public static final double PREVIEW_TICKS = 4.0D;

    public record Crossing(double time, Vec3 normal) {
    }

    public static Crossing incoming(Vec3 relativeStart, Vec3 velocity, double maxTicks) {
        return incoming(relativeStart, velocity, maxTicks, RADIUS);
    }

    public static Crossing incoming(Vec3 relativeStart, Vec3 velocity, double maxTicks, double radius) {
        double a = velocity.lengthSqr();
        double b = relativeStart.dot(velocity);
        double c = relativeStart.lengthSqr() - radius * radius;
        if (!Double.isFinite(radius) || radius <= 0) return null;
        if (!Double.isFinite(a + b + c + maxTicks) || a < 1e-10D || b >= 0 || c < -1e-6D || maxTicks < 0) return null;
        double discriminant = b * b - a * c;
        if (discriminant <= 1e-10D) return null;
        double time = (-b - Math.sqrt(discriminant)) / a;
        if (time < -1e-6D || time > maxTicks) return null;
        time = Math.max(0, time);
        return new Crossing(time, relativeStart.add(velocity.scale(time)).normalize());
    }

    public static Crossing intercept(Vec3 relativeStart, Vec3 velocity, double maxTicks, double radius) {
        if (!Double.isFinite(relativeStart.lengthSqr() + velocity.lengthSqr() + radius + maxTicks)
                || radius <= 0 || maxTicks < 0 || velocity.lengthSqr() < 1e-10) return null;
        if (relativeStart.lengthSqr() < radius * radius - 1e-6) {
            Vec3 normal = relativeStart.lengthSqr() > 1e-8 ? relativeStart.normalize() : velocity.normalize().scale(-1);
            return new Crossing(0, normal);
        }
        return incoming(relativeStart, velocity, maxTicks, radius);
    }

    public static double focus(double directionDot, double width) {
        double distance = Math.acos(Math.clamp(directionDot, -1, 1));
        double value = Math.clamp(1 - distance / width, 0, 1);
        return value * value * (3 - 2 * value);
    }

    public static double fade(double age, double duration) {
        return age < 0 || age >= duration ? 0 : 1 - age / duration;
    }

    private ShieldField() {
    }
}
