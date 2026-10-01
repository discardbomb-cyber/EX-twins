package dev.hurtify.relicsaddon.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Indexed like the exported icosahedron; each armor facet keeps its own pivot and clock.
 * The facet basis (normal, tangent, pivots) never changes, so it is built once per index;
 * only {@link #offset}, {@link #tilt} and {@link #twist} depend on the time.
 */
public final class TwinsFacetPose {
    public static final int COUNT = 20;
    public static final double DRONE_SCALE = 1.35D;
    public static final double SHIELD_SCALE = 1.20D;
    private static final List<Point> CENTERS = centers();
    private static final Basis[] BASES = bases();

    public record Point(double x, double y, double z) {
        public Point scale(double scale) {
            return new Point(x * scale, y * scale, z * scale);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /** The time-independent frame of one facet: unit normal, unit tangent and the pivots of both items. */
    public record Basis(Point normal, Point tangent, Point dronePivot, Point shieldPivot) {
        public Point pivot(boolean shield) {
            return shield ? shieldPivot : dronePivot;
        }
    }

    public record Pose(Point normal, Point tangent, Point pivot, double offset, double tilt, double twist) {
    }

    public static double period(int index) {
        return 200.0D + index * 11.0D;
    }

    public static Basis basis(int index) {
        return BASES[index];
    }

    public static Pose sample(double time, int index, boolean shield) {
        Basis basis = BASES[index];
        return new Pose(basis.normal(), basis.tangent(), basis.pivot(shield),
                offset(time, index, shield), tilt(time, index), twist(time, index, shield));
    }

    /** Radial breathing of the facet, in blocks. */
    public static double offset(double time, int index, boolean shield) {
        return (.075D + .025D * Math.sin(phase(time, index))) * (shield ? .85D : 1.0D);
    }

    /** Breathing tilt around the facet's tangent, in degrees. */
    public static double tilt(double time, int index) {
        return 2.0D * Math.sin(phase(time, index) + .8D);
    }

    /**
     * Turn around the facet's own radial axis, in degrees. Every separated arc is a genuine layer:
     * it keeps a small breathing tilt while continuously turning around its own radial axis. The
     * core/cage is rendered in the rotating core layer, so no part of the Ex-Twins item stays
     * visually frozen.
     */
    public static double twist(double time, int index, boolean shield) {
        double ticks = ticks(time);
        double twist = shield
                ? (ticks * 360.0D / period(index) + index * 51.42857142857143D) % 360.0D
                : 3.5D * Math.sin(phase(time, index) + index * .37D);
        if (shield && twist < 0.0D) twist += 360.0D;
        return twist;
    }

    private static double ticks(double time) {
        return Double.isFinite(time) ? time : 0.0D;
    }

    private static double phase(double time, int index) {
        return (ticks(time) % period(index)) * Math.PI * 2.0D / period(index) + index * 2.399963229728653D;
    }

    private static Basis[] bases() {
        Basis[] bases = new Basis[COUNT];
        for (int index = 0; index < COUNT; index++) {
            Point center = CENTERS.get(index);
            Point normal = center.scale(1.0D / center.length());
            Point tangent = Math.abs(normal.z()) < .9D
                    ? new Point(-normal.y(), normal.x(), 0) : new Point(0, -normal.z(), normal.y());
            tangent = tangent.scale(1.0D / tangent.length());
            bases[index] = new Basis(normal, tangent, pivot(center, normal, DRONE_SCALE), pivot(center, normal, SHIELD_SCALE));
        }
        return bases;
    }

    private static Point pivot(Point center, Point normal, double scale) {
        return normal.scale((center.length() * 4.35D - .02D) * scale / 16.0D);
    }

    private static List<Point> centers() {
        double phi = (1.0D + Math.sqrt(5.0D)) / 2.0D;
        double radius = Math.sqrt(1 + phi * phi);
        List<Point> vertices = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int s : new int[] {-1, 1}) {
                for (int t : new int[] {-1, 1}) {
                    Point point = switch (axis) {
                        case 0 -> new Point(0, s, t * phi);
                        case 1 -> new Point(s, t * phi, 0);
                        default -> new Point(t * phi, 0, s);
                    };
                    vertices.add(point.scale(1.0D / radius));
                }
            }
        }
        List<Point> centers = new ArrayList<>();
        double edge = 2.0D / radius;
        for (int a = 0; a < vertices.size(); a++) {
            for (int b = a + 1; b < vertices.size(); b++) {
                for (int c = b + 1; c < vertices.size(); c++) {
                    Point p = vertices.get(a), q = vertices.get(b), r = vertices.get(c);
                    if (adjacent(p, q, edge) && adjacent(p, r, edge) && adjacent(q, r, edge)) {
                        centers.add(new Point((p.x() + q.x() + r.x()) / 3,
                                (p.y() + q.y() + r.y()) / 3, (p.z() + q.z() + r.z()) / 3));
                    }
                }
            }
        }
        if (centers.size() != COUNT) throw new IllegalStateException("Expected twenty Twins facets");
        return List.copyOf(centers);
    }

    private static boolean adjacent(Point a, Point b, double edge) {
        return Math.abs(new Point(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()).length() - edge) < 1e-6;
    }

    private TwinsFacetPose() {
    }
}
