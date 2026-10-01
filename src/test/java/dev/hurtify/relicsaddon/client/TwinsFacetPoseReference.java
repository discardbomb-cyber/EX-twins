package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.client.TwinsFacetPose.Point;
import dev.hurtify.relicsaddon.client.TwinsFacetPose.Pose;
import java.util.ArrayList;
import java.util.List;

/**
 * TwinsFacetPose.sample as it was before the facet bases were tabulated: the normal, tangent and
 * pivot rebuilt from the icosahedron centre on every sample. Kept as the reference the check
 * compares the tabulated poses against, bit for bit.
 */
final class TwinsFacetPoseReference {
    private static final List<Point> CENTERS = centers();

    static Pose sample(double time, int index, boolean shield) {
        Point center = CENTERS.get(index);
        Point normal = center.scale(1.0D / center.length());
        Point tangent = Math.abs(normal.z()) < .9D
                ? new Point(-normal.y(), normal.x(), 0) : new Point(0, -normal.z(), normal.y());
        tangent = tangent.scale(1.0D / tangent.length());
        double ticks = Double.isFinite(time) ? time : 0.0D;
        double phase = (ticks % TwinsFacetPose.period(index)) * Math.PI * 2.0D / TwinsFacetPose.period(index) + index * 2.399963229728653D;
        double scale = shield ? TwinsFacetPose.SHIELD_SCALE : TwinsFacetPose.DRONE_SCALE;
        double offset = (.075D + .025D * Math.sin(phase)) * (shield ? .85D : 1.0D);
        Point pivot = normal.scale((center.length() * 4.35D - .02D) * scale / 16.0D);
        double twist = shield
                ? (ticks * 360.0D / TwinsFacetPose.period(index) + index * 51.42857142857143D) % 360.0D
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
        if (centers.size() != TwinsFacetPose.COUNT) throw new IllegalStateException("Expected twenty Twins facets");
        return List.copyOf(centers);
    }

    private static boolean adjacent(Point a, Point b, double edge) {
        return Math.abs(new Point(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()).length() - edge) < 1e-6;
    }

    private TwinsFacetPoseReference() { }
}
