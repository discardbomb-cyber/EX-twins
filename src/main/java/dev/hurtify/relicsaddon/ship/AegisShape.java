package dev.hurtify.relicsaddon.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The aegis shield's shape: an ellipsoid round the ship's blocks, in the ship's own terms (its plot), so it turns and
 * travels with the ship; off a ship, a sphere round the hive. The server tests hits against it and clients draw it
 * from the same numbers. "Unit" terms squash the ellipsoid into a sphere of radius 1: a point is inside the shield
 * when its unit length is below 1.
 *
 * @param centre the middle, in the plot (in the world off a ship)
 * @param radii  its half-widths along the ship's own axes
 */
public record AegisShape(Vec3 centre, Vec3 radii) {
    /** Room kept between the ship's blocks and the shell, as a share of the hull's size and in blocks. */
    private static final double GROW = 1.25, MARGIN = 2.5;
    /** Least and most half-width of the shell, and the radius of a base's dome. */
    public static final double LEAST = 4, MOST = 96, BASE = 8;

    public static AegisShape of(ShipFrame frame, BlockPos hive) {
        if (!frame.aboard()) return new AegisShape(Vec3.atCenterOf(hive), new Vec3(BASE, BASE, BASE));
        var hull = frame.hull();
        return new AegisShape(hull.getCenter(), new Vec3(half(hull.getXsize()), half(hull.getYsize()), half(hull.getZsize())));
    }

    private static double half(double size) {
        return Math.clamp(size * .5 * GROW + MARGIN, LEAST, MOST);
    }

    /** A point of the ship's plot in unit terms. */
    public Vec3 unitOfPlot(Vec3 plot) {
        Vec3 local = plot.subtract(centre);
        return new Vec3(local.x / radii.x, local.y / radii.y, local.z / radii.z);
    }

    /** A point of the world in unit terms. */
    public Vec3 unitOfWorld(ShipFrame frame, Vec3 world) {
        return unitOfPlot(frame.toPlot(world));
    }

    /** The point of the shell in the direction {@code unit} (a unit vector in unit terms), in the ship's plot. */
    public Vec3 plotOnShell(Vec3 unit) {
        return centre.add(unit.x * radii.x, unit.y * radii.y, unit.z * radii.z);
    }

    /** The point of the shell in the direction {@code unit}, in the world. */
    public Vec3 worldOnShell(ShipFrame frame, Vec3 unit) {
        return frame.toWorld(plotOnShell(unit));
    }

    /** The shell's outward normal at the direction {@code unit}, in the ship's own terms. */
    public Vec3 normalAt(Vec3 unit) {
        return new Vec3(unit.x / radii.x, unit.y / radii.y, unit.z / radii.z).normalize();
    }

    /** The longest half-width: every point of the shell is within this of its middle. */
    public double reach() {
        return Math.max(radii.x, Math.max(radii.y, radii.z));
    }

    /**
     * Where the straight path from {@code from} to {@code to} (unit terms) first comes in through the shell, as a share
     * of the path (0 to 1), or -1 if it does not: a path that starts inside never counts.
     */
    public static double entry(Vec3 from, Vec3 to) {
        double c = from.lengthSqr() - 1;
        if (c <= 0) return -1;
        Vec3 d = to.subtract(from);
        double a = d.lengthSqr(), b = 2 * from.dot(d);
        if (a < 1e-12) return -1;
        double disc = b * b - 4 * a * c;
        if (disc < 0) return -1;
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        return t >= 0 && t <= 1 ? t : -1;
    }
}
