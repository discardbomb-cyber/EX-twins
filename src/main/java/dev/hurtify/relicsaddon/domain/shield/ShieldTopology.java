package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.shield.ShieldStackState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Cached spherical Voronoi cells; construction happens once and gameplay reads cached tables. */
public final class ShieldTopology {
    public static final int CELL_COUNT = 420;
    public static final int LEGACY_CELL_COUNT = 42;
    private static final int CANDIDATE_NEIGHBORS = 16;
    private static final double EPSILON = 1.0E-6D;
    public static final ShieldTopology INSTANCE = create();

    private final Cell[] cells;
    private final int[][] nearest;
    private final int[][] neighbors;
    private final boolean[][] adjacent;
    private final float[][] legacyCenters;
    private final int[] legacyToCurrent;

    private ShieldTopology(Cell[] cells, int[][] nearest, int[][] neighbors, boolean[][] adjacent, float[][] legacyCenters, int[] legacyToCurrent) {
        this.cells = cells;
        this.nearest = nearest;
        this.neighbors = neighbors;
        this.adjacent = adjacent;
        this.legacyCenters = legacyCenters;
        this.legacyToCurrent = legacyToCurrent;
    }

    public Cell[] cells() { return cells; }

    public int nearest(double x, double y, double z) {
        int selected = 0;
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < cells.length; i++) {
            float[] c = cells[i].center();
            double dot = x * c[0] + y * c[1] + z * c[2];
            if (dot > best) { best = dot; selected = i; }
        }
        return selected;
    }

    /** All other cell IDs, sorted nearest first; returned defensively for callers that sort/filter it. */
    public int[] nearestTo(int cell) { return nearest[cell].clone(); }

    /** Exact cached spherical-Delaunay neighbors, sorted nearest first. Do not mutate the returned array. */
    public int[] neighbors(int cell) { return neighbors[cell]; }

    /** Defensive variant for code that needs to reorder/filter a neighbor list. */
    public int[] neighborsOf(int cell) { return neighbors[cell].clone(); }

    public boolean adjacent(int first, int second) {
        return first >= 0 && second >= 0 && first < CELL_COUNT && second < CELL_COUNT && adjacent[first][second];
    }

    /** Maps a saved 42-cell ID to the current cell nearest that historical region's center. */
    public int migrateLegacyCell(int legacyCell) { return legacyToCurrent[legacyCell]; }

    public int legacyRegionFor(float[] point) { return nearest(point, legacyCenters); }

    private static ShieldTopology create() {
        float[][] sites = relaxedSites(CELL_COUNT);
        int[][] nearest = nearestOrder(sites);
        boolean[][] adjacent = new boolean[CELL_COUNT][CELL_COUNT];
        Cell[] cells = new Cell[CELL_COUNT];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            List<Vertex> vertices = voronoiVertices(cell, sites, nearest[cell], adjacent);
            if (vertices.size() < 3) throw new IllegalStateException("Incomplete spherical Voronoi cell " + cell);
            float[] site = sites[cell];
            vertices.sort(Comparator.comparingDouble(vertex -> angleAround(site, vertex.point())));
            float[] perimeter = new float[vertices.size() * 3];
            for (int point = 0; point < vertices.size(); point++) System.arraycopy(vertices.get(point).point(), 0, perimeter, point * 3, 3);
            cells[cell] = new Cell(cell, panelFor(sites[cell]), perimeter, sites[cell]);
        }
        float[][] legacyCenters = legacyCenters();
        int[] legacyToCurrent = new int[LEGACY_CELL_COUNT];
        for (int legacy = 0; legacy < LEGACY_CELL_COUNT; legacy++) legacyToCurrent[legacy] = nearest(legacyCenters[legacy], sites);
        int[][] neighbors = new int[CELL_COUNT][];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            final int origin = cell;
            neighbors[cell] = Arrays.stream(nearest[cell]).filter(other -> adjacent[origin][other]).toArray();
        }
        return new ShieldTopology(cells, nearest, neighbors, adjacent, legacyCenters, legacyToCurrent);
    }

    private static List<Vertex> voronoiVertices(int cell, float[][] sites, int[] ordering, boolean[][] adjacency) {
        int candidateCount = Math.min(CANDIDATE_NEIGHBORS, ordering.length);
        List<Vertex> result = new ArrayList<>(8);
        for (int first = 0; first < candidateCount; first++) {
            int neighborA = ordering[first];
            for (int second = first + 1; second < candidateCount; second++) {
                int neighborB = ordering[second];
                float[] cross = cross(subtract(sites[neighborA], sites[cell]), subtract(sites[neighborB], sites[cell]));
                double length = length(cross);
                if (length < EPSILON) continue;
                for (int direction : new int[] {1, -1}) {
                    float[] point = normalize(scale(cross, direction / length));
                    if (!isVoronoiVertex(cell, point, sites)) continue;
                    addVertex(result, point);
                    adjacency[cell][neighborA] = adjacency[neighborA][cell] = true;
                    adjacency[cell][neighborB] = adjacency[neighborB][cell] = true;
                    adjacency[neighborA][neighborB] = adjacency[neighborB][neighborA] = true;
                }
            }
        }
        return result;
    }

    private static void addVertex(List<Vertex> vertices, float[] point) {
        for (Vertex known : vertices) if (dot(known.point(), point) > 1.0D - EPSILON) return;
        vertices.add(new Vertex(point));
    }

    private static boolean isVoronoiVertex(int cell, float[] point, float[][] sites) {
        double own = dot(point, sites[cell]);
        for (float[] site : sites) if (dot(point, site) > own + EPSILON) return false;
        return true;
    }

    private static int[][] nearestOrder(float[][] sites) {
        int[][] result = new int[sites.length][sites.length - 1];
        for (int cell = 0; cell < sites.length; cell++) {
            final int origin = cell;
            Integer[] ordered = new Integer[sites.length - 1];
            int cursor = 0;
            for (int other = 0; other < sites.length; other++) if (other != cell) ordered[cursor++] = other;
            Arrays.sort(ordered, Comparator.<Integer>comparingDouble(other -> -dot(sites[origin], sites[other])).thenComparingInt(Integer::intValue));
            for (int index = 0; index < ordered.length; index++) result[cell][index] = ordered[index];
        }
        return result;
    }

    private static float[][] fibonacciSites(int count) {
        float[][] result = new float[count][3];
        double goldenAngle = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int index = 0; index < count; index++) {
            double y = 1.0D - 2.0D * (index + .5D) / count;
            double radius = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
            double theta = index * goldenAngle;
            result[index] = new float[] {(float) (Math.cos(theta) * radius), (float) y, (float) (Math.sin(theta) * radius)};
        }
        return result;
    }

    /** Tangential relaxation removes the short edges of a raw Fibonacci lattice, once at startup. */
    private static float[][] relaxedSites(int count) {
        float[][] initial = fibonacciSites(count);
        double[][] points = new double[count][3];
        double[][] forces = new double[count][3];
        for (int i = 0; i < count; i++) for (int axis = 0; axis < 3; axis++) points[i][axis] = initial[i][axis];
        for (int iteration = 0; iteration < 160; iteration++) {
            for (double[] force : forces) Arrays.fill(force, 0);
            for (int first = 0; first < count; first++) {
                for (int second = first + 1; second < count; second++) {
                    double x = points[first][0] - points[second][0];
                    double y = points[first][1] - points[second][1];
                    double z = points[first][2] - points[second][2];
                    double distanceSquared = x * x + y * y + z * z;
                    double inverseCube = 1.0D / (distanceSquared * Math.sqrt(distanceSquared));
                    x *= inverseCube; y *= inverseCube; z *= inverseCube;
                    forces[first][0] += x; forces[second][0] -= x;
                    forces[first][1] += y; forces[second][1] -= y;
                    forces[first][2] += z; forces[second][2] -= z;
                }
            }
            for (int i = 0; i < count; i++) {
                double radial = 0;
                for (int axis = 0; axis < 3; axis++) radial += forces[i][axis] * points[i][axis];
                double lengthSquared = 0;
                for (int axis = 0; axis < 3; axis++) {
                    points[i][axis] += .0005D * (forces[i][axis] - radial * points[i][axis]);
                    lengthSquared += points[i][axis] * points[i][axis];
                }
                double length = Math.sqrt(lengthSquared);
                for (int axis = 0; axis < 3; axis++) points[i][axis] /= length;
            }
        }
        float[][] result = new float[count][3];
        for (int i = 0; i < count; i++) for (int axis = 0; axis < 3; axis++) result[i][axis] = (float) points[i][axis];
        return result;
    }

    private static float[][] legacyCenters() {
        List<Vector> vertices = icosahedronVertices();
        List<Face> faces = subdivide(vertices, icosahedronFaces());
        float[][] result = new float[vertices.size()][3];
        for (int index = 0; index < vertices.size(); index++) result[index] = vertices.get(index).toFloatArray();
        if (faces.size() != 80 || result.length != LEGACY_CELL_COUNT) throw new IllegalStateException("Unexpected legacy shield topology");
        return result;
    }

    private static List<Face> subdivide(List<Vector> vertices, List<Face> faces) {
        java.util.Map<Long, Integer> midpoints = new java.util.HashMap<>(faces.size() * 2);
        List<Face> result = new ArrayList<>(faces.size() * 4);
        for (Face face : faces) {
            int ab = midpoint(vertices, midpoints, face.a(), face.b()), bc = midpoint(vertices, midpoints, face.b(), face.c()), ca = midpoint(vertices, midpoints, face.c(), face.a());
            result.add(new Face(face.a(), ab, ca)); result.add(new Face(face.b(), bc, ab)); result.add(new Face(face.c(), ca, bc)); result.add(new Face(ab, bc, ca));
        }
        return result;
    }

    private static int midpoint(List<Vector> vertices, java.util.Map<Long, Integer> midpoints, int first, int second) {
        int low = Math.min(first, second), high = Math.max(first, second);
        long key = (long) low << 32 | high & 0xFFFFFFFFL;
        Integer known = midpoints.get(key);
        if (known != null) return known;
        int index = vertices.size();
        vertices.add(vertices.get(first).add(vertices.get(second)).normalize());
        midpoints.put(key, index);
        return index;
    }

    private static double angleAround(float[] center, float[] point) {
        float[] reference = Math.abs(center[1]) > .92D ? new float[] {1, 0, 0} : new float[] {0, 1, 0};
        float[] east = normalize(cross(reference, center)), north = normalize(cross(center, east));
        float[] tangent = normalize(subtract(point, scale(center, dot(point, center))));
        return Math.atan2(dot(tangent, north), dot(tangent, east));
    }

    private static int panelFor(float[] center) {
        double azimuth = Math.atan2(center[0], center[2]);
        if (azimuth >= -Math.PI / 4.0D && azimuth < Math.PI / 4.0D) return ShieldStackState.PANEL_FRONT;
        if (azimuth >= Math.PI / 4.0D && azimuth < Math.PI * 3.0D / 4.0D) return ShieldStackState.PANEL_RIGHT;
        if (azimuth >= -Math.PI * 3.0D / 4.0D && azimuth < -Math.PI / 4.0D) return ShieldStackState.PANEL_LEFT;
        return ShieldStackState.PANEL_BACK;
    }

    private static int nearest(float[] point, float[][] candidates) {
        int selected = 0; double best = -Double.MAX_VALUE;
        for (int index = 0; index < candidates.length; index++) {
            double score = dot(point, candidates[index]);
            if (score > best) { best = score; selected = index; }
        }
        return selected;
    }

    private static float[] subtract(float[] first, float[] second) { return new float[] {first[0] - second[0], first[1] - second[1], first[2] - second[2]}; }
    private static float[] scale(float[] vector, double scalar) { return new float[] {(float) (vector[0] * scalar), (float) (vector[1] * scalar), (float) (vector[2] * scalar)}; }
    private static float[] cross(float[] first, float[] second) { return new float[] {first[1] * second[2] - first[2] * second[1], first[2] * second[0] - first[0] * second[2], first[0] * second[1] - first[1] * second[0]}; }
    private static double dot(float[] first, float[] second) { return first[0] * second[0] + first[1] * second[1] + first[2] * second[2]; }
    private static double length(float[] vector) { return Math.sqrt(dot(vector, vector)); }
    private static float[] normalize(float[] vector) { return scale(vector, 1.0D / length(vector)); }

    private static List<Vector> icosahedronVertices() {
        double goldenRatio = (1.0D + Math.sqrt(5.0D)) / 2.0D;
        return new ArrayList<>(List.of(
                new Vector(-1.0D, goldenRatio, 0.0D).normalize(), new Vector(1.0D, goldenRatio, 0.0D).normalize(), new Vector(-1.0D, -goldenRatio, 0.0D).normalize(), new Vector(1.0D, -goldenRatio, 0.0D).normalize(),
                new Vector(0.0D, -1.0D, goldenRatio).normalize(), new Vector(0.0D, 1.0D, goldenRatio).normalize(), new Vector(0.0D, -1.0D, -goldenRatio).normalize(), new Vector(0.0D, 1.0D, -goldenRatio).normalize(),
                new Vector(goldenRatio, 0.0D, -1.0D).normalize(), new Vector(goldenRatio, 0.0D, 1.0D).normalize(), new Vector(-goldenRatio, 0.0D, -1.0D).normalize(), new Vector(-goldenRatio, 0.0D, 1.0D).normalize()
        ));
    }

    private static List<Face> icosahedronFaces() {
        return List.of(new Face(0, 11, 5), new Face(0, 5, 1), new Face(0, 1, 7), new Face(0, 7, 10), new Face(0, 10, 11), new Face(1, 5, 9), new Face(5, 11, 4), new Face(11, 10, 2), new Face(10, 7, 6), new Face(7, 1, 8), new Face(3, 9, 4), new Face(3, 4, 2), new Face(3, 2, 6), new Face(3, 6, 8), new Face(3, 8, 9), new Face(4, 9, 5), new Face(2, 4, 11), new Face(6, 2, 10), new Face(8, 6, 7), new Face(9, 8, 1));
    }

    public record Cell(int id, int panel, float[] perimeter, float[] center) { }
    private record Vertex(float[] point) { }
    private record Face(int a, int b, int c) { }
    private record Vector(double x, double y, double z) {
        Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        Vector scale(double scalar) { return new Vector(x * scalar, y * scalar, z * scalar); }
        double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
        Vector normalize() { return scale(1.0D / Math.sqrt(dot(this))); }
        float[] toFloatArray() { return new float[] {(float) x, (float) y, (float) z}; }
    }
}
