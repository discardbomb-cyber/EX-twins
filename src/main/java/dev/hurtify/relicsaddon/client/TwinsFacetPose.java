package dev.hurtify.relicsaddon.client;

import java.util.ArrayList;
import java.util.List;

/** Indexed like the exported icosahedron; each armor facet keeps its own pivot and clock. */
public final class TwinsFacetPose {
    public static final int COUNT = 20;
    public static final double DRONE_SCALE = 1.35D;
    public static final double SHIELD_SCALE = 1.20D;
    private static final List<Point> CENTERS = centers();

    public record Point(double x, double y, double z) {
        public Point scale(double scale) {
            return new Point(x * scale, y * scale, z * scale);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    public record Pose(Point normal, Point tangent, Point pivot, double offset, double tilt, double twist) {
    }

    public static double period(int index) {
        return 200.0D + index * 11.0D;
    }

    public static Pose sample(double time, int index, boolean shield) {
        Point center = CENTERS.get(index);
        Point normal = center.scale(1.0D / center.length());
        Point tangent = Math.abs(normal.z()) < .9D
                ? new Point(-normal.y(), normal.x(), 0) : new Point(0, -normal.z(), normal.y());
        tangent = tangent.scale(1.0D / tangent.length());
        double ticks = Double.isFinite(time) ? time : 0.0D;
        double phase = (ticks % period(index)) * Math.PI * 2.0D / period(index) + index * 2.399963229728653D;
        double scale = shield ? SHIELD_SCALE : DRONE_SCALE;
        double offset = (.075D + .025D * Math.sin(phase)) * (shield ? .85D : 1.0D);
        Point pivot = normal.scale((center.length() * 4.35D - .02D) * scale / 16.0D);
        // Every separated arc is a genuine layer: it keeps a small breathing tilt while
        // continuously turning around its own radial axis. The core/cage is rendered in
        // the rotating core layer, so no part of the Ex-Twins item stays visually frozen.
        double twist = shield
                ? (ticks * 360.0D / period(index) + index * 51.42857142857143D) % 360.0D
                : 3.5D * Math.sin(phase + index * .37D);
        if (shield && twist < 0.0D) twist += 360.0D;
        return new Pose(normal, tangent, pivot, offset, 2.0D * Math.sin(phase + .8D), twist);
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
