package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static unit-sphere meshes for the shield shells, in the wearer's local (yaw) frame, each vertex
 * and face tagged with the gameplay cell it covers so broken cells show as holes.
 *
 * <p>The dome is a latitude/longitude sphere used for smooth glass; the hexagonal cells come straight
 * from {@link ShieldTopology}.
 */
final class ShieldGeometry {
    static final Mesh DOME_HIGH = uvSphere(40), DOME_LOW = uvSphere(18);

    /** Unit vertices, triangles, unique edges with their two faces, and cell ids per vertex/face. */
    record Mesh(double[][] vertices, int[][] triangles, int[][] edges, int[] vertexCell, int[] triangleCell) { }

    private static Mesh uvSphere(int rows) {
        int columns = rows * 2;
        List<double[]> vertices = new ArrayList<>();
        vertices.add(new double[]{0, 1, 0});
        for (int row = 1; row < rows; row++) for (int column = 0; column < columns; column++) {
            double theta = Math.PI * row / rows, phi = Math.PI * 2 * column / columns;
            vertices.add(new double[]{Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)});
        }
        vertices.add(new double[]{0, -1, 0});
        int bottom = vertices.size() - 1;
        List<int[]> faces = new ArrayList<>();
        for (int column = 0; column < columns; column++) {
            int next = (column + 1) % columns;
            faces.add(new int[]{0, 1 + next, 1 + column});
            int base = 1 + (rows - 2) * columns;
            faces.add(new int[]{bottom, base + column, base + next});
        }
        for (int row = 0; row < rows - 2; row++) for (int column = 0; column < columns; column++) {
            int next = (column + 1) % columns;
            int a = 1 + row * columns + column, b = 1 + row * columns + next;
            int c = 1 + (row + 1) * columns + column, d = 1 + (row + 1) * columns + next;
            faces.add(new int[]{a, b, d});
            faces.add(new int[]{a, d, c});
        }
        return build(vertices, faces);
    }

    private static Mesh build(List<double[]> vertices, List<int[]> faces) {
        double[][] v = vertices.toArray(double[][]::new);
        int[][] f = faces.toArray(int[][]::new);
        Map<Long, int[]> edges = new HashMap<>();
        for (int face = 0; face < f.length; face++) for (int k = 0; k < 3; k++) {
            int a = f[face][k], b = f[face][(k + 1) % 3];
            long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
            int[] edge = edges.computeIfAbsent(key, ignored -> new int[]{Math.min(a, b), Math.max(a, b), -1, -1});
            if (edge[2] < 0) edge[2] = face; else edge[3] = face;
        }
        int[] vertexCell = new int[v.length];
        for (int i = 0; i < v.length; i++) vertexCell[i] = ShieldTopology.INSTANCE.nearest((float) v[i][0], (float) v[i][1], (float) v[i][2]);
        int[] triangleCell = new int[f.length];
        for (int i = 0; i < f.length; i++) {
            double[] a = v[f[i][0]], b = v[f[i][1]], c = v[f[i][2]];
            double[] mid = normalize(new double[]{a[0] + b[0] + c[0], a[1] + b[1] + c[1], a[2] + b[2] + c[2]});
            triangleCell[i] = ShieldTopology.INSTANCE.nearest((float) mid[0], (float) mid[1], (float) mid[2]);
        }
        return new Mesh(v, f, edges.values().toArray(int[][]::new), vertexCell, triangleCell);
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / length, v[1] / length, v[2] / length};
    }

    private ShieldGeometry() {
    }
}
