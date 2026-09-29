package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The drawn honeycomb: a Goldberg polyhedron, the dual of a frequency-{@value #FREQUENCY} geodesic
 * icosahedron, so every cell is an even hexagon (twelve are pentagons, as on any closed honeycomb
 * sphere). One pentagon sits on top of the shell. The gameplay cells ({@link ShieldTopology}) are
 * irregular; each drawn cell takes the health, holes and gather motion of the gameplay cell under its
 * centre, so saves and damage stay exactly as they were.
 */
final class ShieldHoneycomb {
    static final int FREQUENCY = 6;
    static final List<Cell> CELLS = build(FREQUENCY);
    /** A coarse honeycomb (42 cells) for the small hexagon-shelled spheres of the Twins swarm. */
    static final List<Cell> SMALL = build(2);

    /** A drawn cell in the wearer's yaw frame: unit centre, corner ring (x, y, z triples) and the gameplay cell beneath it. */
    record Cell(float[] center, float[] perimeter, int gameplay) { }

    private static List<Cell> build(int frequency) {
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
        List<Cell> cells = new ArrayList<>(vertices.size());
        for (int i = 0; i < vertices.size(); i++) {
            double[] centre = vertices.get(i);
            List<double[]> ring = corners.get(i);
            double[] helper = Math.abs(centre[1]) > .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            double[] e1 = normalize(cross(helper, centre)), e2 = cross(centre, e1);
            ring.sort((x, y) -> Double.compare(Math.atan2(dot(x, e2), dot(x, e1)), Math.atan2(dot(y, e2), dot(y, e1))));
            float[] perimeter = new float[ring.size() * 3];
            for (int k = 0; k < ring.size(); k++) for (int axis = 0; axis < 3; axis++) perimeter[k * 3 + axis] = (float) ring.get(k)[axis];
            float[] centreF = {(float) centre[0], (float) centre[1], (float) centre[2]};
            cells.add(new Cell(centreF, perimeter, ShieldTopology.INSTANCE.nearest(centreF[0], centreF[1], centreF[2])));
        }
        return List.copyOf(cells);
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
