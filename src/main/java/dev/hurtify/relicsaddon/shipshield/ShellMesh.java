package dev.hurtify.relicsaddon.shipshield;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The shell of a ship shield as a mesh: the surface of {@link ShellField} traced by surface nets
 * over a grid, smoothed, and then set back exactly onto the offset surface, so no vertex is nearer
 * the structure than the offset. One outer surface only: the grid is flooded from outside, so a
 * cavity in the structure gets no shell of its own. Larger structures use a coarser grid so the
 * mesh never exceeds its cell limit. Vertices are kept as floats from an integer origin at the
 * structure's corner, so a shell millions of blocks out (a Sable plot) keeps its shape. Pure and
 * immutable; built off-thread and cached.
 */
public final class ShellMesh {
    /** Smoothing passes and how far each pass moves a vertex towards its neighbours' middle. */
    private static final int SMOOTH_PASSES = 3;
    private static final double SMOOTH = .5;
    /** The coarsest grid: beyond this a mesh is no shape at all, so a very large structure may exceed its cell limit instead. */
    private static final int MAX_STEP = 4;

    private final ShellField field;
    private final int step;
    private final int originX, originY, originZ;
    /** Vertex positions, three floats each, from the origin. */
    private final float[] vertices;
    /** Quads, four vertex indices each, wound so the normal points out. */
    private final int[] quads;
    private final int[][] neighbours;
    private final float[] normals;
    private final AABB bounds;

    private ShellMesh(ShellField field, int step, int originX, int originY, int originZ, float[] vertices, int[] quads, int[][] neighbours, float[] normals, AABB bounds) {
        this.field = field;
        this.step = step;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.vertices = vertices;
        this.quads = quads;
        this.neighbours = neighbours;
        this.normals = normals;
        this.bounds = bounds;
    }

    public ShellField field() { return field; }
    public double offset() { return field.offset(); }
    /** The grid spacing the shell was traced at: 1 for small structures, more when the cell limit asked for it. */
    public int step() { return step; }
    public int vertexCount() { return vertices.length / 3; }
    public int quadCount() { return quads.length / 4; }
    /** Vertex positions from the {@link #origin}, three floats each. */
    public float[] vertices() { return vertices; }
    /** The integer corner the vertices are measured from. */
    public Vec3 origin() { return new Vec3(originX, originY, originZ); }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    public int[] quads() { return quads; }
    public float[] normals() { return normals; }
    public int[] neighbours(int vertex) { return neighbours[vertex]; }
    public AABB bounds() { return bounds; }
    public boolean isEmpty() { return quads.length == 0; }

    /** A vertex in the structure's coordinates (origin plus float, added as doubles: a float sum would lose the fraction far out). */
    public Vec3 vertex(int index) { return new Vec3(originX + (double) vertices[index * 3], originY + (double) vertices[index * 3 + 1], originZ + (double) vertices[index * 3 + 2]); }
    public Vec3 normal(int index) { return new Vec3(normals[index * 3], normals[index * 3 + 1], normals[index * 3 + 2]); }

    /** The middle of a quad. */
    public Vec3 quadCentre(int quad) {
        double x = 0, y = 0, z = 0;
        for (int corner = 0; corner < 4; corner++) {
            int vertex = quads[quad * 4 + corner] * 3;
            x += vertices[vertex]; y += vertices[vertex + 1]; z += vertices[vertex + 2];
        }
        return new Vec3(originX + x * .25, originY + y * .25, originZ + z * .25);
    }

    /** The vertex nearest a point, or -1 on an empty mesh. */
    public int nearestVertex(double x, double y, double z) {
        x -= originX; y -= originY; z -= originZ;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            double dx = vertices[vertex * 3] - x, dy = vertices[vertex * 3 + 1] - y, dz = vertices[vertex * 3 + 2] - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = vertex;
            }
        }
        return best;
    }

    /**
     * {@code count} vertices spread as evenly as the mesh allows: the first is the vertex nearest
     * {@code seed}, each next one is the vertex farthest from all chosen so far. Fewer when the mesh
     * has fewer vertices.
     */
    public int[] spread(int count, Vec3 seed) {
        int total = vertexCount();
        count = Math.min(count, total);
        if (count <= 0) return new int[0];
        int[] chosen = new int[count];
        double[] nearest = new double[total];
        Arrays.fill(nearest, Double.MAX_VALUE);
        chosen[0] = nearestVertex(seed.x, seed.y, seed.z);
        for (int index = 0; index < count; index++) {
            int last = chosen[index];
            double lx = vertices[last * 3], ly = vertices[last * 3 + 1], lz = vertices[last * 3 + 2];
            int farthest = -1;
            double farthestDistance = -1;
            for (int vertex = 0; vertex < total; vertex++) {
                double dx = vertices[vertex * 3] - lx, dy = vertices[vertex * 3 + 1] - ly, dz = vertices[vertex * 3 + 2] - lz;
                nearest[vertex] = Math.min(nearest[vertex], dx * dx + dy * dy + dz * dz);
                if (nearest[vertex] > farthestDistance) {
                    farthestDistance = nearest[vertex];
                    farthest = vertex;
                }
            }
            if (index + 1 < count) chosen[index + 1] = farthest;
        }
        return chosen;
    }

    // --- building --------------------------------------------------------------------------------

    /** Traces the shell of {@code field}, at the finest grid that keeps the mesh within {@code cellLimit} quads. */
    public static ShellMesh build(ShellField field, int cellLimit) {
        cellLimit = Math.max(64, cellLimit);
        if (field.isEmpty()) return new ShellMesh(field, 1, 0, 0, 0, new float[0], new int[0], new int[0][], new float[0], new AABB(0, 0, 0, 0, 0, 0));
        int step = 1;
        while (true) {
            ShellMesh mesh = trace(field, step);
            if (mesh.quadCount() <= cellLimit || step >= MAX_STEP) return mesh;
            // Quads shrink with the square of the spacing; jump straight to a spacing that should fit, one step on at least.
            step = Math.min(MAX_STEP, Math.max(step + 1, (int) Math.floor(step * Math.sqrt(mesh.quadCount() / (double) cellLimit))));
        }
    }

    private static ShellMesh trace(ShellField field, int step) {
        double offset = field.offset();
        // Nodes reach from a margin outside the blocks: the corner node is always outside the shell.
        int margin = (int) Math.ceil(offset) + 2;
        AABB blocks = field.blockBounds();
        int originX = (int) Math.floor(blocks.minX) - margin, originY = (int) Math.floor(blocks.minY) - margin, originZ = (int) Math.floor(blocks.minZ) - margin;
        int nx = (int) Math.ceil((blocks.maxX + margin - originX) / (double) step) + 1;
        int ny = (int) Math.ceil((blocks.maxY + margin - originY) / (double) step) + 1;
        int nz = (int) Math.ceil((blocks.maxZ + margin - originZ) / (double) step) + 1;
        float[] distance = new float[nx * ny * nz];
        Arrays.fill(distance, Float.MAX_VALUE);
        // Exact distances near the blocks: each block writes into the nodes within reach of its cube.
        double reach = offset + 1;
        for (long packed : field.blocks()) {
            int bx = BlockPos.getX(packed), by = BlockPos.getY(packed), bz = BlockPos.getZ(packed);
            int i0 = Math.max(0, (int) Math.floor((bx - reach - originX) / step)), i1 = Math.min(nx - 1, (int) Math.ceil((bx + 1 + reach - originX) / step));
            int j0 = Math.max(0, (int) Math.floor((by - reach - originY) / step)), j1 = Math.min(ny - 1, (int) Math.ceil((by + 1 + reach - originY) / step));
            int k0 = Math.max(0, (int) Math.floor((bz - reach - originZ) / step)), k1 = Math.min(nz - 1, (int) Math.ceil((bz + 1 + reach - originZ) / step));
            for (int i = i0; i <= i1; i++) {
                double x = originX + i * (double) step;
                double dx = Math.max(0, Math.max(bx - x, x - (bx + 1)));
                for (int j = j0; j <= j1; j++) {
                    double y = originY + j * (double) step;
                    double dy = Math.max(0, Math.max(by - y, y - (by + 1)));
                    for (int k = k0; k <= k1; k++) {
                        double z = originZ + k * (double) step;
                        double dz = Math.max(0, Math.max(bz - z, z - (bz + 1)));
                        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        int index = (i * ny + j) * nz + k;
                        if (d < distance[index]) distance[index] = d;
                    }
                }
            }
        }
        // Outside: flooded from the corner through nodes beyond the offset. Nodes in sealed cavities stay "inside".
        boolean[] outside = new boolean[distance.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        outside[0] = true;
        queue.add(0);
        int[] strides = {ny * nz, nz, 1};
        int[] sizes = {nx, ny, nz};
        while (!queue.isEmpty()) {
            int node = queue.poll();
            int i = node / (ny * nz), j = node / nz % ny, k = node % nz;
            int[] at = {i, j, k};
            for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
                int coordinate = at[axis] + sign;
                if (coordinate < 0 || coordinate >= sizes[axis]) continue;
                int next = node + sign * strides[axis];
                if (outside[next] || distance[next] <= offset) continue;
                outside[next] = true;
                queue.add(next);
            }
        }
        // One vertex per grid cube the surface crosses, at the middle of its edge crossings.
        int cx = nx - 1, cy = ny - 1, cz = nz - 1;
        int[] cubeVertex = new int[cx * cy * cz];
        Arrays.fill(cubeVertex, -1);
        var positions = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        int[][] cubeCorners = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0}, {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}};
        int[][] cubeEdges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int i = 0; i < cx; i++) for (int j = 0; j < cy; j++) for (int k = 0; k < cz; k++) {
            int[] nodes = new int[8];
            boolean anyOut = false, anyIn = false;
            for (int corner = 0; corner < 8; corner++) {
                nodes[corner] = ((i + cubeCorners[corner][0]) * ny + j + cubeCorners[corner][1]) * nz + k + cubeCorners[corner][2];
                if (outside[nodes[corner]]) anyOut = true; else anyIn = true;
            }
            if (!anyOut || !anyIn) continue;
            double sx = 0, sy = 0, sz = 0;
            int crossings = 0;
            for (int[] edge : cubeEdges) {
                int a = nodes[edge[0]], b = nodes[edge[1]];
                if (outside[a] == outside[b]) continue;
                double va = outside[a] ? distance[a] : Math.min(distance[a], offset);
                double vb = outside[b] ? distance[b] : Math.min(distance[b], offset);
                double t = va == vb ? .5 : Math.clamp((offset - va) / (vb - va), 0, 1);
                int[] ca = cubeCorners[edge[0]], cb = cubeCorners[edge[1]];
                sx += ca[0] + (cb[0] - ca[0]) * t;
                sy += ca[1] + (cb[1] - ca[1]) * t;
                sz += ca[2] + (cb[2] - ca[2]) * t;
                crossings++;
            }
            cubeVertex[(i * cy + j) * cz + k] = positions.size() / 3;
            positions.add((float) ((i + sx / crossings) * step));
            positions.add((float) ((j + sy / crossings) * step));
            positions.add((float) ((k + sz / crossings) * step));
        }
        // A quad across every grid edge the surface crosses, between the four cubes round that edge, facing out.
        IntArrayList quads = new IntArrayList();
        for (int i = 0; i < nx; i++) for (int j = 0; j < ny; j++) for (int k = 0; k < nz; k++) {
            int node = (i * ny + j) * nz + k;
            for (int axis = 0; axis < 3; axis++) {
                int[] at = {i, j, k};
                if (at[axis] + 1 >= sizes[axis]) continue;
                int next = node + strides[axis];
                if (outside[node] == outside[next]) continue;
                // The four cubes sharing this edge: offsets along the two other axes.
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                int[] c = new int[4];
                int[][] around = {{0, 0}, {-1, 0}, {-1, -1}, {0, -1}};
                boolean complete = true;
                for (int corner = 0; corner < 4 && complete; corner++) {
                    int[] cube = {i, j, k};
                    cube[u] += around[corner][0];
                    cube[v] += around[corner][1];
                    if (cube[0] < 0 || cube[1] < 0 || cube[2] < 0 || cube[0] >= cx || cube[1] >= cy || cube[2] >= cz) { complete = false; break; }
                    c[corner] = cubeVertex[(cube[0] * cy + cube[1]) * cz + cube[2]];
                    if (c[corner] < 0) complete = false;
                }
                if (!complete) continue;
                // The cubes go round the edge counter-clockwise as seen from +axis, so that order faces +axis:
                // kept when the outside node lies ahead along the axis, reversed when it lies behind.
                if (outside[next]) { quads.add(c[0]); quads.add(c[1]); quads.add(c[2]); quads.add(c[3]); }
                else { quads.add(c[0]); quads.add(c[3]); quads.add(c[2]); quads.add(c[1]); }
            }
        }
        float[] vertices = positions.toFloatArray();
        int[] quadArray = quads.toIntArray();
        int[][] neighbours = neighbours(vertices.length / 3, quadArray);
        smooth(vertices, neighbours);
        settle(field, vertices, quadArray, neighbours, originX, originY, originZ);
        // The field's sealed cavities are worked out here, off-thread, so the first hit test need not.
        field.warm();
        float[] normals = normals(vertices, quadArray);
        return new ShellMesh(field, step, originX, originY, originZ, vertices, quadArray, neighbours, normals, boundsOf(vertices).move(originX, originY, originZ));
    }

    private static int[][] neighbours(int count, int[] quads) {
        IntArrayList[] lists = new IntArrayList[count];
        for (int vertex = 0; vertex < count; vertex++) lists[vertex] = new IntArrayList(6);
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        for (int quad = 0; quad < quads.length; quad += 4) {
            for (int corner = 0; corner < 4; corner++) {
                int a = quads[quad + corner], b = quads[quad + (corner + 1) % 4];
                long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                if (seen.put(key, 1) == 1) continue;
                lists[a].add(b);
                lists[b].add(a);
            }
        }
        int[][] result = new int[count][];
        for (int vertex = 0; vertex < count; vertex++) result[vertex] = lists[vertex].toIntArray();
        return result;
    }

    private static void smooth(float[] vertices, int[][] neighbours) {
        float[] next = new float[vertices.length];
        for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
            for (int vertex = 0; vertex < neighbours.length; vertex++) {
                int[] around = neighbours[vertex];
                if (around.length == 0) {
                    System.arraycopy(vertices, vertex * 3, next, vertex * 3, 3);
                    continue;
                }
                double mx = 0, my = 0, mz = 0;
                for (int other : around) { mx += vertices[other * 3]; my += vertices[other * 3 + 1]; mz += vertices[other * 3 + 2]; }
                mx /= around.length; my /= around.length; mz /= around.length;
                next[vertex * 3] = (float) (vertices[vertex * 3] + (mx - vertices[vertex * 3]) * SMOOTH);
                next[vertex * 3 + 1] = (float) (vertices[vertex * 3 + 1] + (my - vertices[vertex * 3 + 1]) * SMOOTH);
                next[vertex * 3 + 2] = (float) (vertices[vertex * 3 + 2] + (mz - vertices[vertex * 3 + 2]) * SMOOTH);
            }
            System.arraycopy(next, 0, vertices, 0, vertices.length);
        }
    }

    /**
     * Sets every vertex the offset away from the nearest block, along the way out from it, then
     * stands vertices further out wherever an edge's middle or a quad's middle between them sags
     * nearer than the offset (a chord across a curve), until no facet comes nearer than the offset.
     */
    private static void settle(ShellField field, float[] vertices, int[] quads, int[][] neighbours, int originX, int originY, int originZ) {
        double offset = field.offset();
        int count = vertices.length / 3;
        double[] near = new double[vertices.length], out = new double[vertices.length], want = new double[count];
        for (int vertex = 0; vertex < count; vertex++) {
            double x = originX + (double) vertices[vertex * 3], y = originY + (double) vertices[vertex * 3 + 1], z = originZ + (double) vertices[vertex * 3 + 2];
            ShellField.Nearest nearest = field.nearest(x, y, z);
            double dx = x - nearest.x(), dy = y - nearest.y(), dz = z - nearest.z();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1e-6) { dx = 0; dy = 0; dz = 0; length = 1; }
            near[vertex * 3] = nearest.x(); near[vertex * 3 + 1] = nearest.y(); near[vertex * 3 + 2] = nearest.z();
            out[vertex * 3] = dx / length; out[vertex * 3 + 1] = dy / length; out[vertex * 3 + 2] = dz / length;
            want[vertex] = offset + 2e-3;
        }
        // A coarse grid may drop a vertex inside a block: it takes its neighbours' way out, and goes along it until clear.
        for (int vertex = 0; vertex < count; vertex++) {
            if (out[vertex * 3] != 0 || out[vertex * 3 + 1] != 0 || out[vertex * 3 + 2] != 0) continue;
            double sx = 0, sy = 0, sz = 0;
            for (int other : neighbours[vertex]) { sx += out[other * 3]; sy += out[other * 3 + 1]; sz += out[other * 3 + 2]; }
            double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (length < 1e-6) { sx = 0; sy = 1; sz = 0; length = 1; }
            out[vertex * 3] = sx / length; out[vertex * 3 + 1] = sy / length; out[vertex * 3 + 2] = sz / length;
            for (int step = 0; step < 64 && field.distance(near[vertex * 3] + out[vertex * 3] * want[vertex], near[vertex * 3 + 1] + out[vertex * 3 + 1] * want[vertex],
                    near[vertex * 3 + 2] + out[vertex * 3 + 2] * want[vertex]) < offset; step++) {
                want[vertex] += .5;
            }
        }
        double[] at = new double[vertices.length];
        for (int pass = 0; pass < 40; pass++) {
            for (int vertex = 0; vertex < count; vertex++) for (int axis = 0; axis < 3; axis++) {
                at[vertex * 3 + axis] = near[vertex * 3 + axis] + out[vertex * 3 + axis] * want[vertex];
            }
            boolean moved = false;
            for (int vertex = 0; vertex < count; vertex++) {
                for (int other : neighbours[vertex]) {
                    if (other < vertex) continue;
                    double deficit = offset + 1e-3 - field.distance((at[vertex * 3] + at[other * 3]) * .5, (at[vertex * 3 + 1] + at[other * 3 + 1]) * .5, (at[vertex * 3 + 2] + at[other * 3 + 2]) * .5);
                    if (deficit > 0) {
                        want[vertex] += deficit * .7;
                        want[other] += deficit * .7;
                        moved = true;
                    }
                }
            }
            for (int quad = 0; quad < quads.length; quad += 4) {
                double mx = 0, my = 0, mz = 0;
                for (int corner = 0; corner < 4; corner++) { mx += at[quads[quad + corner] * 3]; my += at[quads[quad + corner] * 3 + 1]; mz += at[quads[quad + corner] * 3 + 2]; }
                double deficit = offset + 1e-3 - field.distance(mx * .25, my * .25, mz * .25);
                if (deficit > 0) {
                    for (int corner = 0; corner < 4; corner++) want[quads[quad + corner]] += deficit * .7;
                    moved = true;
                }
            }
            if (!moved) break;
        }
        for (int vertex = 0; vertex < count; vertex++) {
            vertices[vertex * 3] = (float) (near[vertex * 3] + out[vertex * 3] * want[vertex] - originX);
            vertices[vertex * 3 + 1] = (float) (near[vertex * 3 + 1] + out[vertex * 3 + 1] * want[vertex] - originY);
            vertices[vertex * 3 + 2] = (float) (near[vertex * 3 + 2] + out[vertex * 3 + 2] * want[vertex] - originZ);
        }
    }

    private static float[] normals(float[] vertices, int[] quads) {
        float[] normals = new float[vertices.length];
        for (int quad = 0; quad < quads.length; quad += 4) {
            int a = quads[quad] * 3, b = quads[quad + 1] * 3, c = quads[quad + 2] * 3, d = quads[quad + 3] * 3;
            // The quad's normal from its diagonals, which suits a non-planar quad.
            double ux = vertices[c] - vertices[a], uy = vertices[c + 1] - vertices[a + 1], uz = vertices[c + 2] - vertices[a + 2];
            double vx = vertices[d] - vertices[b], vy = vertices[d + 1] - vertices[b + 1], vz = vertices[d + 2] - vertices[b + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int corner : new int[]{a, b, c, d}) {
                normals[corner] += (float) nx; normals[corner + 1] += (float) ny; normals[corner + 2] += (float) nz;
            }
        }
        for (int vertex = 0; vertex < normals.length; vertex += 3) {
            double length = Math.sqrt(normals[vertex] * normals[vertex] + normals[vertex + 1] * normals[vertex + 1] + normals[vertex + 2] * normals[vertex + 2]);
            if (length < 1e-9) { normals[vertex + 1] = 1; continue; }
            normals[vertex] /= (float) length; normals[vertex + 1] /= (float) length; normals[vertex + 2] /= (float) length;
        }
        return normals;
    }

    private static AABB boundsOf(float[] vertices) {
        if (vertices.length == 0) return new AABB(0, 0, 0, 0, 0, 0);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int vertex = 0; vertex < vertices.length; vertex += 3) {
            x0 = Math.min(x0, vertices[vertex]); y0 = Math.min(y0, vertices[vertex + 1]); z0 = Math.min(z0, vertices[vertex + 2]);
            x1 = Math.max(x1, vertices[vertex]); y1 = Math.max(y1, vertices[vertex + 1]); z1 = Math.max(z1, vertices[vertex + 2]);
        }
        return new AABB(x0, y0, z0, x1, y1, z1);
    }
}
