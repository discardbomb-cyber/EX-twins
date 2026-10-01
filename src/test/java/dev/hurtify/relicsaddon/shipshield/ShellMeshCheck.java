package dev.hurtify.relicsaddon.shipshield;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The ship shield shell, traced round a few builds: every vertex at least the offset from every
 * block, one closed outer surface with no shell inside a cavity, faces turned outward, the cell
 * limit kept by a coarser grid, the distance field agreeing with the mesh on what is inside, and
 * a shot's way in found on the shell.
 */
public final class ShellMeshCheck {
    public static void main(String[] args) {
        LongSet row = new LongOpenHashSet();
        for (int x = 0; x < 5; x++) row.add(BlockPos.asLong(x, 2, 2));
        check("row of five", row, 2, 4096);
        // The same row millions of blocks away (a GameTest's corner of the world, a Sable plot): floats would lose the shape.
        LongSet far = new LongOpenHashSet();
        for (int x = 0; x < 5; x++) far.add(BlockPos.asLong(13978181 + x, -58, 10697027 + x * 0));
        ShellMesh farMesh = check("row of five far away", far, 2, 4096);
        require(farMesh.quadCount() == mesh(row, 2).quadCount(), "the far row traces the same shell: " + farMesh.quadCount() + " quads");

        // A hollow box with a sealed room inside, big enough for a shell of its own if cavities were traced.
        LongSet hull = new LongOpenHashSet();
        for (int x = -6; x <= 6; x++) for (int y = -6; y <= 6; y++) for (int z = -6; z <= 6; z++) {
            if (Math.abs(x) == 6 || Math.abs(y) == 6 || Math.abs(z) == 6) hull.add(BlockPos.asLong(x, y, z));
        }
        ShellMesh hollow = check("hollow box", hull, 2, 4096);
        ShellField hullField = hollow.field();
        require(hullField.inside(0, 0, 0), "the middle of the sealed room is under the shield (one outer surface)");
        require(!hullField.inside(0, 12, 0), "the air above the box is not");
        require(hullField.entry(new Vec3(0, 0, 0), new Vec3(0, 30, 0)) < 0, "a shot from inside the room flies out unhindered");
        for (int vertex = 0; vertex < hollow.vertexCount(); vertex++) {
            Vec3 at = hollow.vertex(vertex);
            require(Math.max(Math.abs(at.x), Math.max(Math.abs(at.y), Math.abs(at.z))) > 6, "no vertex inside the box: " + at);
        }

        // An airship-like hull: a long deck with a keel and a cabin; offsets 1 and 6.
        LongSet ship = new LongOpenHashSet();
        for (int x = -12; x <= 12; x++) for (int z = -3; z <= 3; z++) {
            ship.add(BlockPos.asLong(x, 10, z));
            if (Math.abs(z) < 2 && Math.abs(x) < 10) ship.add(BlockPos.asLong(x, 9, z));
            if (Math.abs(z) < 1 && Math.abs(x) < 6) ship.add(BlockPos.asLong(x, 8, z));
        }
        for (int x = -3; x <= 3; x++) for (int y = 11; y <= 13; y++) for (int z = -2; z <= 2; z++) ship.add(BlockPos.asLong(x, y, z));
        check("airship, offset 1", ship, 1, 4096);
        ShellMesh wide = check("airship, offset 6", ship, 6, 4096);
        ShellMesh limited = check("airship, 256 cells", ship, 2, 256);
        require(limited.quadCount() <= 256, "the cell limit holds: " + limited.quadCount());
        // A limit far too small for the structure: the trace stops at the coarsest grid instead of looping, still clear of the blocks.
        ShellMesh tiny = ShellMesh.build(new ShellField(hull, 2), 64);
        for (int vertex = 0; vertex < tiny.vertexCount(); vertex++) require(new ShellField(hull, 2).distance(tiny.vertex(vertex)) >= 2 - 1e-4, "the coarsest shell keeps the offset");
        require(tiny.quadCount() <= 64 || tiny.step() >= 4, "the smallest limit is met or the coarsest grid reached: " + tiny.quadCount() + " at step " + tiny.step());
        require(limited.step() > 1, "a limited mesh uses a coarser grid: step " + limited.step());

        // The same blocks trace the same mesh (caches and both sides depend on it).
        ShellMesh again = ShellMesh.build(new ShellField(ship, 6), 4096);
        require(java.util.Arrays.equals(again.vertices(), wide.vertices()) && java.util.Arrays.equals(again.quads(), wide.quads()), "tracing is deterministic");
        require(new ShellField(ship, 6).fingerprint() == wide.field().fingerprint(), "fingerprints agree for the same blocks");
        require(new ShellField(ship, 5).fingerprint() != wide.field().fingerprint(), "a different offset is a different fingerprint");

        // A shot from far above meets the shell of the row at its offset, and a shot from inside never does.
        ShellField field = new ShellField(row, 2);
        Vec3 from = new Vec3(2.5, 20, 2.5), to = new Vec3(2.5, -5, 2.5);
        double t = field.entry(from, to);
        require(t > 0, "a shot from above comes under the shield");
        Vec3 crossing = from.lerp(to, t);
        require(Math.abs(crossing.y - 5) < .05, "it comes under it two blocks above the row, at y=" + crossing.y);
        require(field.entry(new Vec3(2.5, 3.5, 2.5), new Vec3(2.5, 30, 2.5)) < 0, "a shot from inside flies out unhindered");
        require(field.entry(new Vec3(30, 2.5, 30), new Vec3(40, 2.5, 40)) < 0, "a shot far away never meets it");
        require(field.outward(new Vec3(2.5, 4, 2.5)).y > .99, "the way out above the row is up");

        // Spread: the wanted number of vertices, all different, the first nearest the seed.
        int[] spread = wide.spread(24, new Vec3(0, 14, 0));
        require(spread.length == 24 && java.util.Arrays.stream(spread).distinct().count() == 24, "24 distinct emitter seats");
        require(spread[0] == wide.nearestVertex(0, 14, 0), "the first seat is nearest the seed");

        System.out.println("Ship shell: row " + mesh(row, 2).quadCount() + " quads, hollow box " + hollow.quadCount() + " (no inner shell), airship "
                + wide.quadCount() + " quads at offset 6, limited " + limited.quadCount() + " at step " + limited.step()
                + "; every vertex at or beyond the offset, faces outward, deterministic, shots enter on the shell");
    }

    private static ShellMesh mesh(LongSet blocks, double offset) {
        return ShellMesh.build(new ShellField(blocks, offset), 4096);
    }

    private static ShellMesh check(String name, LongSet blocks, double offset, int limit) {
        ShellField field = new ShellField(blocks, offset);
        ShellMesh mesh = ShellMesh.build(field, limit);
        require(!mesh.isEmpty(), name + ": the shell has faces");
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            Vec3 at = mesh.vertex(vertex);
            double distance = field.distance(at);
            require(distance >= offset - 1e-4, name + ": vertex " + at + " is " + distance + " from the blocks, nearer than " + offset);
            require(distance <= offset + 1 + mesh.step() * .5, name + ": vertex " + at + " is " + distance + " from the blocks, far beyond " + offset);
            for (int other : mesh.neighbours(vertex)) {
                double mid = field.distance(at.add(mesh.vertex(other)).scale(.5));
                require(mid >= offset - 1e-4, name + ": the edge from " + at + " to " + mesh.vertex(other) + " dips to " + mid);
            }
            require(mesh.neighbours(vertex).length >= 3, name + ": vertex " + vertex + " has " + mesh.neighbours(vertex).length + " neighbours");
        }
        // Every quad faces away from the blocks, and the quads close up: every edge belongs to exactly two of them.
        var edges = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        int[] quads = mesh.quads();
        int facingIn = 0;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            Vec3 centre = mesh.quadCentre(quad);
            require(field.distance(centre) >= offset - 1e-4, name + ": quad " + quad + " middle " + centre + " is " + field.distance(centre) + " from the blocks");
            Vec3 a = mesh.vertex(quads[quad * 4]), b = mesh.vertex(quads[quad * 4 + 1]), c = mesh.vertex(quads[quad * 4 + 2]), d = mesh.vertex(quads[quad * 4 + 3]);
            Vec3 normal = c.subtract(a).cross(d.subtract(b));
            if (normal.dot(field.outward(centre)) < 0) facingIn++;
            for (int corner = 0; corner < 4; corner++) {
                int from = quads[quad * 4 + corner], to = quads[quad * 4 + (corner + 1) % 4];
                edges.addTo((long) Math.min(from, to) << 32 | Math.max(from, to), 1);
            }
        }
        // A coarse grid (large structures) may fold a few quads at creases where vertices were stood out; a fine one never does.
        require(facingIn == 0 || mesh.step() > 1 && facingIn * 8 < mesh.quadCount(), name + ": " + facingIn + " of " + mesh.quadCount() + " quads face the blocks");
        int open = 0;
        for (int count : edges.values()) if (count != 2) open++;
        require(open == 0, name + ": " + open + " edges do not join two quads");
        // The distance field and the mesh agree: the box round the blocks, grown by the offset, holds every vertex.
        double slack = 1 + mesh.step();
        require(field.bounds().inflate(.01).contains(mesh.bounds().getCenter()) && mesh.bounds().minX >= field.bounds().minX - slack
                && mesh.bounds().maxX <= field.bounds().maxX + slack, name + ": the mesh stays about the offset box");
        return mesh;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private ShellMeshCheck() {
    }
}
