package dev.hurtify.relicsaddon.drone;

/** Fibonacci shell avoids clustering at 50 drones; shared by server and renderer. */
public final class HiveOrbit {
    public record Point(double x, double y, double z) { }
    public static Point at(int index, int count, int type, double time) {
        count = Math.clamp(count, 1, HiveType.MAX_DRONES);
        index = Math.clamp(index, 0, count - 1);
        if (!Double.isFinite(time)) time = 0;
        double y = 1 - 2 * (index + .5) / count;
        double angle = index * 2.399963229728653 + time * .014 + type * 2.094395102;
        double radius = Math.sqrt(Math.max(0, 1 - y * y));
        return new Point(Math.cos(angle) * radius, y * .30, Math.sin(angle) * radius);
    }
    private HiveOrbit() { }
}
