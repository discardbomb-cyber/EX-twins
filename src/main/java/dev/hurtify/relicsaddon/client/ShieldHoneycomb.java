package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The drawn honeycomb: a Goldberg polyhedron, the dual of a frequency-{@value #FREQUENCY} geodesic
 * icosahedron, so every cell is an even hexagon (twelve are pentagons, as on any closed honeycomb
 * sphere). One pentagon sits on top of the shell. The gameplay cells ({@link ShieldTopology}) are
 * irregular; each drawn cell takes the health, holes and gather motion of the gameplay cell under its
 * centre, so saves and damage stay exactly as they were. There are more drawn cells (492) than
 * gameplay cells (420), and a gameplay cell that no drawn centre falls on takes over the nearest drawn
 * cell of a gameplay cell that has several, so every gameplay cell shows, and a broken one is always
 * a hole.
 */
final class ShieldHoneycomb {
    static final int FREQUENCY = 7;
    static final List<Cell> CELLS = build(FREQUENCY, true);
    /** A coarse honeycomb (42 cells) for the small hexagon-shelled spheres of the Twins swarm. */
    static final List<Cell> SMALL = build(2, false);

    /** How many distinct corners {@link #CELLS} share: every corner belongs to three cells. */
    static final int CORNER_COUNT = CELLS.stream().flatMapToInt(cell -> java.util.Arrays.stream(cell.corners())).max().orElse(-1) + 1;

    /**
     * A drawn cell in the wearer's yaw frame: unit centre, corner ring (x, y, z triples), the gameplay cell
     * beneath it and, for every corner of the ring, its index among the corners its neighbours share
     * (the same index means the very same point, so work at a corner can be done once per shell).
     */
    record Cell(float[] center, float[] perimeter, int gameplay, int[] corners) { }

    /** With {@code cover}, every gameplay cell is given at least one drawn cell. */
    private static List<Cell> build(int frequency, boolean cover) {
        double t = (1 + Math.sqrt(5)) / 2;
        double[][] ico = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t},
                {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        int[][] faces = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2},
                {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11},
                {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        // Turn the icosahedron so vertex 0 points straight up: a pentagon crowns the shell.
        double[] top = normalize(ico[0]);
        double[][] basis = rotationTo(top, new double[]{0, 1, 0});
        for (int i = 0; i < ico.length; i++) ico[i] = rotate(basis, normalize(ico[i]));

        List<double[]> vertices = new ArrayList<>();
        Map<Long, Integer> index = new HashMap<>();
        List<int[]> triangles = new ArrayList<>();
        for (int[] face : faces) {
            double[] a = ico[face[0]], b = ico[face[1]], c = ico[face[2]];
            int[][] grid = new int[frequency + 1][];
            for (int i = 0; i <= frequency; i++) {
                grid[i] = new int[frequency + 1 - i];
                for (int j = 0; j <= frequency - i; j++) {
                    double u = i / (double) frequency, v = j / (double) frequency;
                    double[] point = normalize(new double[]{a[0] + (b[0] - a[0]) * u + (c[0] - a[0]) * v,
                            a[1] + (b[1] - a[1]) * u + (c[1] - a[1]) * v, a[2] + (b[2] - a[2]) * u + (c[2] - a[2]) * v});
                    grid[i][j] = vertex(vertices, index, point);
                }
            }
            for (int i = 0; i < frequency; i++) for (int j = 0; j < frequency - i; j++) {
                triangles.add(new int[]{grid[i][j], grid[i + 1][j], grid[i][j + 1]});
                if (j + 1 < frequency - i) triangles.add(new int[]{grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]});
            }
        }
        // Dual: each geodesic vertex becomes a cell whose corners are the centres of the triangles around it.
        List<List<double[]>> corners = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) corners.add(new ArrayList<>());
        for (int[] triangle : triangles) {
            double[] p = vertices.get(triangle[0]), q = vertices.get(triangle[1]), r = vertices.get(triangle[2]);
            double[] centre = normalize(new double[]{p[0] + q[0] + r[0], p[1] + q[1] + r[1], p[2] + q[2] + r[2]});
            for (int corner : triangle) corners.get(corner).add(centre);
        }
        int[] gameplay = new int[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            double[] c = vertices.get(i);
            gameplay[i] = ShieldTopology.INSTANCE.nearest(c[0], c[1], c[2]);
        }
        if (cover) cover(vertices, gameplay);
        // Rings share their corner arrays with their neighbours; the identity numbers each corner once.
        Map<double[], Integer> cornerIndex = new java.util.IdentityHashMap<>();
        List<Cell> cells = new ArrayList<>(vertices.size());
        for (int i = 0; i < vertices.size(); i++) {
            double[] centre = vertices.get(i);
            List<double[]> ring = corners.get(i);
            double[] helper = Math.abs(centre[1]) > .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            double[] e1 = normalize(cross(helper, centre)), e2 = cross(centre, e1);
            ring.sort((x, y) -> Double.compare(Math.atan2(dot(x, e2), dot(x, e1)), Math.atan2(dot(y, e2), dot(y, e1))));
            float[] perimeter = new float[ring.size() * 3];
            int[] shared = new int[ring.size()];
            for (int k = 0; k < ring.size(); k++) {
                for (int axis = 0; axis < 3; axis++) perimeter[k * 3 + axis] = (float) ring.get(k)[axis];
                shared[k] = cornerIndex.computeIfAbsent(ring.get(k), ignored -> cornerIndex.size());
            }
            float[] centreF = {(float) centre[0], (float) centre[1], (float) centre[2]};
            cells.add(new Cell(centreF, perimeter, gameplay[i], shared));
        }
        return List.copyOf(cells);
    }

    /**
     * Gives every gameplay cell a drawn cell: one that no drawn centre falls on takes the drawn cell
     * nearest its own centre among those whose gameplay cell has more than one.
     */
    private static void cover(List<double[]> centres, int[] gameplay) {
        var topology = ShieldTopology.INSTANCE.cells();
        int[] drawn = new int[topology.length];
        for (int owner : gameplay) drawn[owner]++;
        for (int cell = 0; cell < topology.length; cell++) {
            if (drawn[cell] > 0) continue;
            float[] target = topology[cell].center();
            int best = -1;
            double closest = -2;
            for (int i = 0; i < centres.size(); i++) {
                if (drawn[gameplay[i]] < 2) continue;
                double[] c = centres.get(i);
                double along = c[0] * target[0] + c[1] * target[1] + c[2] * target[2];
                if (along > closest) {
                    closest = along;
                    best = i;
                }
            }
            if (best < 0) continue;
            drawn[gameplay[best]]--;
            gameplay[best] = cell;
            drawn[cell]++;
        }
    }

    private static int vertex(List<double[]> vertices, Map<Long, Integer> index, double[] point) {
        long key = Math.round(point[0] * 1e6) * 4_000_037L * 4_000_037L + Math.round(point[1] * 1e6) * 4_000_037L + Math.round(point[2] * 1e6);
        return index.computeIfAbsent(key, ignored -> {
            vertices.add(point);
            return vertices.size() - 1;
        });
    }

    /** Rows of the rotation matrix that turns unit vector {@code from} onto unit vector {@code to}. */
    private static double[][] rotationTo(double[] from, double[] to) {
        double[] axis = cross(from, to);
        double sin = Math.sqrt(dot(axis, axis)), cos = dot(from, to);
        if (sin < 1e-12) return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        axis = new double[]{axis[0] / sin, axis[1] / sin, axis[2] / sin};
        double x = axis[0], y = axis[1], z = axis[2], c = 1 - cos;
        return new double[][]{
                {cos + x * x * c, x * y * c - z * sin, x * z * c + y * sin},
                {y * x * c + z * sin, cos + y * y * c, y * z * c - x * sin},
                {z * x * c - y * sin, z * y * c + x * sin, cos + z * z * c}};
    }

    private static double[] rotate(double[][] m, double[] v) {
        return new double[]{dot(m[0], v), dot(m[1], v), dot(m[2], v)};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(dot(v, v));
        return new double[]{v[0] / length, v[1] / length, v[2] / length};
    }

    private ShieldHoneycomb() {
    }
}
