package dev.hurtify.relicsaddon.adapter.out.persistence;

import dev.hurtify.relicsaddon.domain.hive.*;

import java.util.ArrayList;
import java.util.List;
import dev.hurtify.relicsaddon.domain.math.Vec3d;

/**
 * The new figures' pure geometry: the geodesic cage, the lotus and the rift at every stage of closing, the
 * tesseract collapsing into a cube, and the droplet paths of the Mana rain and the Twins rift jump. Nothing
 * is NaN, everything stays in its bounds, and the paths fly without jumps and strike their target.
 */
public final class HiveConstructsCheck {
    public static void main(String[] args) {
        for (int f = 1; f <= 5; f++) {
            HiveConstructs.Geodesic sphere = HiveConstructs.geodesic(f);
            require(sphere.corners().size() == HiveConstructs.geodesicCorners(f), "a frequency " + f + " geodesic sphere has 10f^2+2 corners");
            require(sphere.edges().size() == 30 * f * f && sphere.faces().size() == 20 * f * f, "and 30f^2 edges, 20f^2 faces");
            for (Vec3d corner : sphere.corners()) require(Math.abs(corner.length() - 1) < 1e-9, "its corners lie on the unit sphere");
            for (int k = 0; k < 12; k++) require(sphere.corners().get(k).distanceTo(HiveConstructs.geodesic(1).corners().get(k)) < 1e-9,
                    "every geodesic sphere starts with the icosahedron's corners");
        }
        for (int count : new int[]{1, 11, 12, 19, 24, 37, 42, 60, 92, 162, 200, 250}) {
            for (double age : new double[]{-40, -5, 0, 6, 12, 18, 24, 60, 1_000}) for (double time : new double[]{0, 333.3, 1e6}) {
                for (int s = 0; s < count; s++) {
                    double room = 1.3;
                    bounded(HiveConstructs.cage(s, count, age, time, room), room * HiveConstructs.CAGE_SCALE * 1.6, "cage");
                    bounded(HiveConstructs.lotus(s, count, age, time, room), room * HiveConstructs.LOTUS_SCALE * 2.2, "lotus");
                    bounded(HiveConstructs.rift(s, count, age, time, room), HiveConstructs.riftReach(room) * 1.3, "rift");
                }
            }
            for (int[] edge : HiveConstructs.cageEdges(count)) require(edge[0] < count && edge[1] < count && edge[0] != edge[1], "cage edges join two of its drones");
            for (int[] face : HiveConstructs.cageFaces(count)) require(face[0] < count && face[1] < count && face[2] < count, "cage panels are between its drones");
            for (int[] edge : HiveConstructs.lotusEdges(count)) require(edge[0] < count && edge[1] < count, "lotus edges join two of its drones");
            int shards = HiveConstructs.shards(count);
            require(shards >= HiveConstructs.MIN_SHARDS && shards <= HiveConstructs.MAX_SHARDS, "a rift has four to twelve shards");
        }
        // A closed cage's drones stand on its sphere; while it closes they all rise from the ground up, none below it.
        for (int s = 0; s < 42; s++) {
            Vec3d closed = HiveConstructs.cage(s, 42, 1_000, 0, 1);
            require(Math.abs(closed.length() - HiveConstructs.CAGE_SCALE) < 1e-9, "a closed cage is a sphere");
            require(HiveConstructs.cage(s, 42, 0, 0, 1).y <= closed.y + 1e-9, "a closing cage's drones rise into place");
        }
        // The tesseract closes into a cube: its sixteen corners fall onto eight.
        for (double time : new double[]{0, 17.3, 400.9}) {
            List<Vec3d> cube = new ArrayList<>();
            for (int corner = 0; corner < 16; corner++) {
                Vec3d at = HiveShapes.tesseractCorner(corner, time, 1, 1);
                finite(at, "collapsed tesseract");
                if (cube.stream().noneMatch(seen -> seen.distanceTo(at) < 1e-6)) cube.add(at);
            }
            require(cube.size() == 8, "a collapsed tesseract is a cube: " + cube.size() + " corners");
            for (int m = 0; m < 80; m++) finite(HiveShapes.tesseract(m, 80, time, 1, .5), "half-collapsed tesseract");
        }
        // The droplet paths: from the fan to the blow without a jump, striking the target itself.
        Vec3d owner = new Vec3d(3, 64, -2), target = new Vec3d(-4, 63, 7);
        for (HiveType type : HiveType.values()) for (int group = 0; group < 6; group++) {
            Vec3d core = HiveFormation.core(target, 1.9), previous = null;
            double previousSortie = 0;
            for (double t = 100; t < 260; t += .25) {
                Vec3d at = HiveFormation.dropletCentre(type, owner, target, target, 1.9, group, 6, t, 100, 40);
                double sortie = HiveFormation.sortie(owner, target, target, 1.9, group, 6, t, 100, 40);
                finite(at, type + " droplet path");
                boolean hidden = HiveFormation.dropletHidden(type, sortie) || HiveFormation.dropletHidden(type, previousSortie);
                if (previous != null && !hidden) require(previous.distanceTo(at) < 2.6, type + " figure " + group + " jumps " + previous.distanceTo(at) + " at " + t);
                if (Math.abs(sortie - 1) < 1e-9) require(at.distanceTo(core) < 1e-6, type + " figure strikes its target");
                previous = at;
                previousSortie = sortie;
            }
            for (double sortie = 0; sortie <= 2; sortie += .01) {
                boolean hidden = HiveFormation.dropletHidden(type, sortie);
                require(type == HiveType.TWINS || !hidden, "only the Twins jump through rifts");
            }
        }
        System.out.println("Hive constructs: geodesic cages whole, lotus and rift bounded at every stage, the tesseract closes into a cube, "
                + "droplet paths fly without jumps and strike home");
    }

    private static void bounded(Vec3d at, double reach, String what) {
        finite(at, what);
        require(at.length() <= reach, what + " strays " + at.length() + " of " + reach);
    }

    private static void finite(Vec3d at, String what) {
        require(Double.isFinite(at.x) && Double.isFinite(at.y) && Double.isFinite(at.z), what + " is not finite");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
