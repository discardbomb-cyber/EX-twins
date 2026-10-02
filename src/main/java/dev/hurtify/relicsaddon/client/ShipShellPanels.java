package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shipshield.ShellMesh;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** As for ShieldHoneycomb, polygon cells are the dual of a triangulated surface, here the hull rather than a sphere. */
final class ShipShellPanels {
    record Panel(Vec3 centre, Vec3 normal, List<Vec3> corners) { }

    static List<Panel> build(ShellMesh mesh, boolean honeycomb) {
        List<Panel> result = new ArrayList<>();
        int[] indices = mesh.quads();
        if (!honeycomb) {
            for (int q = 0; q < mesh.quadCount(); q++) {
                List<Vec3> corners = new ArrayList<>();
                Vec3 normal = Vec3.ZERO;
                for (int i = 0; i < 4; i++) { corners.add(mesh.vertex(indices[q * 4 + i])); normal = normal.add(mesh.normal(indices[q * 4 + i])); }
                result.add(new Panel(mesh.quadCentre(q), normal.normalize(), List.copyOf(corners)));
            }
            return List.copyOf(result);
        }
        List<List<Vec3>> rings = new ArrayList<>();
        for (int v = 0; v < mesh.vertexCount(); v++) rings.add(new ArrayList<>());
        for (int q = 0; q < mesh.quadCount(); q++) {
            for (int[] triangle : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                Vec3 corner = Vec3.ZERO;
                for (int i : triangle) corner = corner.add(mesh.vertex(indices[q * 4 + i]));
                corner = corner.scale(1.0 / 3);
                for (int i : triangle) rings.get(indices[q * 4 + i]).add(corner);
            }
        }
        for (int v = 0; v < mesh.vertexCount(); v++) {
            Vec3 centre = mesh.vertex(v), normal = mesh.normal(v);
            Vec3 tangent = normal.cross(Math.abs(normal.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
            Vec3 bitangent = normal.cross(tangent);
            List<Vec3> ring = rings.get(v);
            ring.sort(Comparator.comparingDouble(p -> Math.atan2(p.subtract(centre).dot(bitangent), p.subtract(centre).dot(tangent))));
            if (ring.size() >= 3) result.add(new Panel(centre, normal, List.copyOf(ring)));
        }
        return List.copyOf(result);
    }
    private ShipShellPanels() { }
}
