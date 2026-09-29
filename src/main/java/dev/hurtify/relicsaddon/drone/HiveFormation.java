package dev.hurtify.relicsaddon.drone;

import net.minecraft.world.phys.Vec3;

/**
 * Shared, deterministic hive positions.  The server may use these same points as
 * projectile origins; the small motion is analytic rather than a client-only random walk.
 */
public final class HiveFormation {
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    public static Vec3 idle(Vec3 owner, float yaw, int index, int count, HiveType type, double time) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        // The resting cluster grows with the swarm so hundreds of drones do not collapse into one point.
        return belt(owner, yaw, type).add(fibonacciSphere(index, count, .045 * Math.max(1, Math.sqrt(count / 120.0)), .6));
    }

    public static Vec3 belt(Vec3 owner, float yaw, HiveType type) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        return owner.add(right.scale((type.ordinal() - 1) * .16)).add(forward.scale(.22)).add(0, .86, 0);
    }

    public static Vec3 healing(Vec3 owner, float yaw, int index, int count, HiveType type, double time, double progress) {
        Vec3 rest = idle(owner, yaw, index, count, type, time);
        Vec3 deployed = owner.add(0, 1.12, 0).add(fibonacciSphere(index, safeCount(count), .92, .60))
                .add(beeJitter(index, type, safeTime(time), .035));
        double p = Math.clamp(progress, 0, 1);
        return rest.lerp(deployed, p * p * (3 - 2 * p));
    }

    public static Vec3 combat(Vec3 target, double targetWidth, double targetHeight,
            int index, int count, HiveType type, double time) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        time = safeTime(time);
        targetWidth = saneSize(targetWidth, .6);
        targetHeight = saneSize(targetHeight, 1.8);
        double radius = Math.max(Math.max(1.35, targetWidth * .65 + 1.25), Math.sqrt(count) * .175);
        Vec3 center = target.add(0, targetHeight * .55, 0);
        Vec3 offset = switch (type) {
            case RF -> fibonacciSphere(index, count, radius, 1.0);
            case MANA -> manaRings(index, count, radius, time);
            case TWINS -> twinsNetwork(index, count, radius);
        };
        return center.add(offset).add(beeJitter(index, type, time, .055));
    }

    public static Vec3 position(Vec3 owner, float yaw, Vec3 target, double targetWidth, double targetHeight,
            int index, int count, HiveType type, double time, double progress) {
        Vec3 rest = idle(owner, yaw, index, count, type, time);
        if (target == null) return rest;
        double p = Math.clamp(progress, 0, 1);
        double smooth = p * p * (3 - 2 * p);
        Vec3 direct = rest.lerp(combat(target, targetWidth, targetHeight, index, count, type, time), smooth);
        double weight = travelWeight(p);
        if (weight <= 0) return direct;
        double polar = Math.acos(1 - 2 * (index + .5) / safeCount(count));
        Vec3 drop = travelPoint(owner, yaw, target, targetHeight, p, polar, index * GOLDEN_ANGLE, time)
                .add(beeJitter(index, type, time, .035));
        return direct.lerp(drop, weight);
    }

    public static double travelWeight(double progress) {
        double edge = Math.min(Math.clamp(progress / .22, 0, 1), Math.clamp((1 - progress) / .22, 0, 1));
        return edge * edge * (3 - 2 * edge);
    }

    /** Analytic drop shared with the faint traveling wave bands and the server's formation pose. */
    public static Vec3 travelPoint(Vec3 owner, float yaw, Vec3 target, double targetHeight, double progress,
            double polar, double azimuth, double time) {
        Vec3 start = owner.add(Vec3.directionFromRotation(0, yaw).scale(.22)).add(0, .86, 0);
        Vec3 end = target.add(0, saneSize(targetHeight, 1.8) * .55, 0);
        Vec3 forward = end.subtract(start).normalize();
        if (forward.lengthSqr() < .01) forward = new Vec3(0, 0, 1);
        Vec3 right = forward.cross(Math.abs(forward.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(forward).normalize();
        double p = Math.clamp(progress, 0, 1), smooth = p * p * (3 - 2 * p);
        Vec3 center = start.lerp(end, smooth);
        double along = Math.cos(polar);
        double pulse = 1 + .045 * Math.sin(time * .22) + .018 * Math.sin(polar * 4 - time * .31);
        double around = Math.sin(polar) * (.68 + .32 * along) * pulse;
        return center.add(forward.scale((along * 1.22 - .22) * pulse))
                .add(right.scale(Math.cos(azimuth) * around)).add(up.scale(Math.sin(azimuth) * around));
    }

    private static Vec3 fibonacciSphere(int index, int count, double radius, double vertical) {
        double y = 1.0 - 2.0 * (index + .5) / count;
        double around = index * GOLDEN_ANGLE;
        double horizontal = Math.sqrt(Math.max(0, 1 - y * y));
        return new Vec3(Math.cos(around) * horizontal * radius, y * radius * vertical, Math.sin(around) * horizontal * radius);
    }

    private static Vec3 manaRings(int index, int count, double radius, double time) {
        int rings = Math.max(2, (int) Math.ceil(Math.sqrt(count / 3.0)));
        int ring = index % rings;
        int slot = index / rings;
        int ringPopulation = (count - 1 - ring) / rings + 1;
        double angle = slot * Math.PI * 2 / ringPopulation + ring * .78 + time * (.035 + ring * .006);
        // Three differently inclined rings provide the rotating-magic look without all drones sharing a plane.
        Vec3 point = new Vec3(Math.cos(angle), Math.sin(angle), 0);
        double tilt = ring * Math.PI / rings + .35;
        double x = point.x * Math.cos(tilt) - point.z * Math.sin(tilt);
        double z = point.x * Math.sin(tilt) + point.z * Math.cos(tilt);
        return new Vec3(x * radius, point.y * radius, z * radius);
    }

    private static Vec3 twinsNetwork(int index, int count, double radius) {
        // Fibonacci sites make a stable polygonal cage; a small alternating radial step prevents a flat silhouette.
        Vec3 point = fibonacciSphere(index, count, radius, 1.0);
        return point.scale(1.0 + ((index * 37) % 5 - 2) * .018);
    }

    private static Vec3 beeJitter(int index, HiveType type, double time, double amount) {
        double phase = index * 1.731 + type.ordinal() * 2.41;
        return new Vec3(Math.sin(time * .19 + phase) * amount,
                Math.cos(time * .23 + phase * 1.7) * amount * .7,
                Math.sin(time * .17 + phase * .61) * amount);
    }

    private static int safeCount(int count) { return Math.clamp(count, 1, HiveType.MAX_DRONES); }
    private static double safeTime(double time) { return Double.isFinite(time) ? time : 0; }
    private static double saneSize(double value, double fallback) { return Double.isFinite(value) ? Math.max(.1, value) : fallback; }
    private HiveFormation() { }
}
