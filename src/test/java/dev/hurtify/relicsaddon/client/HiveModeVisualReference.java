package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveTarget;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.client.HiveModeVisual.Scene;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The light of the swarm's constructs, drawn around the drones themselves:
 * <ul>
 *   <li>Droplet: RF tesseracts with glowing edges, Mana droplets of glass, Twins hexagons with
 *       lightning jumping between them and space bending round them in flight. Each figure waits in
 *       a faint ring in its owner's fan and streaks when it flies.</li>
 *   <li>Barrage: dense clumps in a soft halo, joined into their pattern by lines of light; each
 *       clump's charge swells inside it into a glowing ball traced with circuitry, and Twins clumps
 *       are octagons with lightning running round their rims.</li>
 *   <li>Containment: the RF tori's hexagons with a particle collider running inside each, the Mana
 *       ward's rhombi and circles over a glass bubble, and the Twins' denser collider tori round a
 *       black hole with a violet accretion disk that bends and darkens the world behind it.</li>
 * </ul>
 * Positions are world space; {@code camera} is subtracted here.
 */
final class HiveModeVisualReference {
    public static void render(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m) {
        int color = color(s.type());
        switch (s.mode()) {
            case DROPLET -> droplets(s, camera, glow, fill, m, color);
            case BARRAGE -> {
                for (int index = 0; index < s.engaged(); index++) clusters(part(s, index), camera, glow, fill, m, color);
            }
            case CONTAINMENT -> {
                if (!s.formed()) return;
                for (int index = 0; index < s.engaged(); index++) {
                    Scene part = part(s, index);
                    HiveConstructVisualReference.render(part, camera, glow, fill, m, color);
                    // A heads-up circle turns slowly on the ground under what is held.
                    if (HiveJuice.detail() != HiveJuice.Detail.LOW) {
                        HiveJuiceReference.aim(part.target(), Math.max(1.2, HiveFormation.enclosure(part.width(), part.height()) * 1.3), .5, s.time(), color, accent(s.type()),
                                camera, glow, m);
                    }
                }
            }
        }
    }

    /**
     * The part of a scene round target {@code index}: its strike groups and places renumbered the way
     * {@link HiveFormation} lays them out round that target, each group keeping its swarm-wide clock.
     */
    static Scene part(Scene s, int index) {
        int engaged = s.engaged();
        if (engaged <= 1) return s;
        int groups = HiveSlots.localGroups(index, engaged, s.groups()), slots = HiveSlots.localSlots(index, engaged, s.slots(), s.groups());
        int[] members = new int[groups], timing = new int[groups];
        for (int local = 0; local < groups; local++) {
            int group = index + local * engaged;
            members[local] = s.members()[group];
            timing[local] = s.timingGroup(group);
        }
        Vec3[] drones = new Vec3[slots];
        for (int local = 0; local < slots; local++) {
            int slot = HiveSlots.globalSlot(index, engaged, local, groups, s.groups());
            drones[local] = slot < s.drones().length ? s.drones()[slot] : null;
        }
        return new Scene(s.mode(), s.type(), slots, groups, members, drones, s.owner(), List.of(s.targets().get(index)), s.time(),
                s.cycleStart(), s.interval(), s.formed(), timing, s.timingGroups());
    }

    public static int color(HiveType type) {
        return switch (type) {
            case RF -> 0x38E8FF;
            case MANA -> 0x42E6C8;
            case TWINS -> 0xB151FF;
        };
    }

    // --- droplet ---------------------------------------------------------------------------------

    /** The second colour of a family's heads-up marks: orange by the RF blue, gold by the Mana teal, pink by the Twins violet. */
    static int accent(HiveType type) {
        return switch (type) {
            case RF -> 0xFFB347;
            case MANA -> 0xFFD27A;
            case TWINS -> 0xFF7BE5;
        };
    }

    /** Names the player whose swarm a scene is (the one standing where its owner does), for its sounds. */
    private static long ownerKey(Scene s) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        var player = level == null ? null : level.getNearestPlayer(s.owner().x, s.owner().y, s.owner().z, 3, false);
        return player == null ? 0 : player.getId();
    }

    /** Ticks until a group's cycle next passes {@code mark} (its blow or its shot). */
    private static double until(Scene s, int group, double mark) {
        double phase = HiveFormation.groupPhase(s.time(), s.cycleStart(), s.interval(), s.timingGroup(group), s.timingGroups());
        if (phase < 0) return Double.MAX_VALUE;
        return (phase < mark ? mark - phase : 1 + mark - phase) * s.interval();
    }

    /** Ticks before a blow or a shot its aim circle shows, closing on the target. */
    private static final double AIM_TICKS = 10;

    private static void droplets(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        long owner = GlowBrushReference.flat() ? 0 : ownerKey(s);
        for (int group = 0; group < s.groups(); group++) {
            if (s.members()[group] == 0) continue;
            HiveTarget target = s.targetOf(group);
            Vec3 core = HiveFormation.core(target.feet(), target.height());
            double coming = until(s, group, HiveFormation.IMPACT);
            if (coming <= AIM_TICKS && HiveJuice.detail() != HiveJuice.Detail.LOW) {
                HiveJuiceReference.aim(target.feet(), Math.max(1, target.width() * 1.6), 1 - coming / AIM_TICKS, s.time() + group * 13, color, accent(s.type()), camera, glow, m);
            }
            Vec3 home = HiveFormation.muster(s.owner(), s.target(), group, s.groups(), s.time());
            Vec3 centre = dropletCentre(s, group, s.time());
            double sortie = sortie(s, group);
            boolean flying = sortie > 0 && sortie < 1;
            double heat = heat(s, group);
            Vec3 facing = core.subtract(home);
            int groups = s.groups(), g = group;
            // A figure's drones, relative to the camera: its lines run from drone to drone and go out with them.
            java.util.function.IntFunction<Vec3> drone = member -> {
                int slot = member * groups + g;
                return slot < s.drones().length && s.drones()[slot] != null ? s.drones()[slot].subtract(camera) : null;
            };
            if (s.type() == HiveType.TWINS) rifts(s, group, home, core, sortie, camera, glow, fill, m, color);
            if (HiveFormation.dropletHidden(s.type(), sortie)) continue;
            if (sortie > 0 && sortie < 2 && !GlowBrushReference.flat()) HiveLoopSounds.flying(owner << 8 | (long) s.type().ordinal() << 5 | group, s.type(), centre, s.time());
            Vec3[] axes = HiveShapes.axes(facing);
            double size = HiveFormation.shapeSize(s.members()[group]);
            Vec3 c = centre.subtract(camera);
            // The figure's place in the fan: a faint ring it forms up in, brighter while it waits there.
            GlowBrushReference.circle(glow, m, home.subtract(camera), axes[1], axes[2], size * 1.9, 40, .012, color, sortie <= 0 ? 60 : 22);
            // A streak behind a figure on the move.
            // (None where it came out of a rift a moment ago, and never longer than a few blocks.)
            double earlier = HiveFormation.sortie(s.owner(), s.target(), target.feet(), target.height(), group, s.groups(), s.time() - 1.5, s.cycleStart(), s.interval());
            if (sortie > 0 && sortie != 1 && !HiveFormation.dropletHidden(s.type(), earlier)) {
                Vec3 before = dropletCentre(s, group, s.time() - 1.5).subtract(camera);
                if (before.distanceToSqr(c) > .04) {
                    Vec3 back = before.subtract(c).scale(2.2);
                    if (back.length() > 2.5) back = back.normalize().scale(2.5);
                    Vec3 tail = c.add(back);
                    GlowBrushReference.line(glow, m, c, tail, size * .45, .01, color, color, 110 * heat, 0);
                }
            }
            switch (s.type()) {
                case RF -> {
                    // Flying apart after the blow, the tesseract's lines go out, and light again as it gathers.
                    double whole = sortie > 1 && sortie < 2 ? 1 - Math.sin(Math.PI * Math.min(1, (sortie - 1) * 1.6)) : 1;
                    for (int[] edge : HiveShapes.TESSERACT_EDGES) {
                        Vec3 a = drone.apply(edge[0]), b = drone.apply(edge[1]);
                        if (a != null && b != null) GlowBrushReference.beam(glow, m, a, b, .022, color, (80 + 140 * heat) * whole);
                    }
                    for (int corner = 0; corner < HiveShapes.TESSERACT_CORNERS; corner++) {
                        Vec3 at = drone.apply(corner);
                        if (at != null) GlowBrushReference.dot(glow, m, at, .08, 0xD8FCFF, 120 + 100 * heat);
                    }
                    GlowBrushReference.dot(glow, m, c, size * 1.5, color, 25 + 45 * heat);
                }
                case MANA -> dropletFacets(drone, fill, glow, m, c, color, heat);
                case TWINS -> {
                    int rings = HiveShapes.hexagonCount(s.members()[group]);
                    Vec3[] centres = new Vec3[rings];
                    for (int ring = 0; ring < rings; ring++) {
                        Vec3 sum = Vec3.ZERO;
                        int seen = 0;
                        for (int side = 0; side < 6; side++) {
                            Vec3 a = drone.apply(ring + side * rings), b = drone.apply(ring + (side + 1) % 6 * rings);
                            if (a != null) { sum = sum.add(a); seen++; }
                            if (a != null && b != null) GlowBrushReference.beam(glow, m, a, b, .018, color, 90 + 120 * heat);
                        }
                        centres[ring] = seen == 0 ? c : sum.scale(1.0 / seen);
                    }
                    // Lightning leaps between neighbouring hexagons and re-forks every other tick.
                    long flicker = (long) Math.floor(s.time() / 2);
                    for (int ring = 0; ring < rings; ring++) {
                        long seed = flicker * 31 + group * 7919L + ring;
                        if (new Random(seed).nextDouble() < .6) {
                            GlowBrushReference.lightning(glow, m, centres[ring], centres[(ring + 1) % rings], seed, 5, .18, .012, 0xE7C6FF, 170 * heat + 40);
                        }
                    }
                    GlowBrushReference.dot(glow, m, c, size * (.5 + .15 * Math.sin(s.time() * .9)), color, 60 + 120 * heat);
                    // Space bends round a figure in flight.
                    if (flying && !GlowBrushReference.flat()) ShieldRefraction.queueLens(c.x, c.y, c.z, size * .4, size * 2.2, .8 * heat);
                }
            }
            if (sortie <= 0 && HiveJuice.detail() != HiveJuice.Detail.LOW) idle(s, group, drone, glow, m, color);
        }
    }

    /**
     * A figure waiting in the fan is not dead: a light runs along its lines from drone to drone, and every two
     * seconds or so a spark leaps between two of its drones.
     */
    private static void idle(Scene s, int group, java.util.function.IntFunction<Vec3> drone, VertexConsumer glow, Matrix4f m, int color) {
        int corners = switch (s.type()) {
            case RF -> HiveShapes.TESSERACT_CORNERS;
            case MANA -> HiveShapes.DROPLET_CORNERS;
            case TWINS -> HiveShapes.MIN_HEXAGONS * HiveShapes.HEXAGON_CORNERS;
        };
        corners = Math.min(corners, s.members()[group]);
        if (corners < 2) return;
        double time = s.time() + group * 7.3;
        long step = (long) Math.floor(time / 8);
        int from = (int) Math.floorMod(step * 7 + group, corners), to = (int) Math.floorMod(step * 7 + group + 1 + step % 3, corners);
        Vec3 a = drone.apply(from), b = drone.apply(to);
        if (a != null && b != null) {
            double run = time / 8 - step;
            GlowBrushReference.dot(glow, m, a.lerp(b, run), .06, GlowBrushReference.mix(color, 0xFFFFFF, .5), 200 * Math.sin(Math.PI * run));
        }
        long beat = (long) Math.floor(time / 40);
        if (time - beat * 40 < 5) {
            Vec3 p = drone.apply((int) Math.floorMod(beat * 5 + group, corners)), q = drone.apply((int) Math.floorMod(beat * 5 + group + corners / 2, corners));
            if (p != null && q != null) GlowBrushReference.lightning(glow, m, p, q, beat * 131 + group, 5, .2, .008, GlowBrushReference.mix(color, 0xFFFFFF, .4), 170);
        }
    }

    /** How hot a droplet figure burns: .3 while it waits in the fan, from .45 up to 1 as it flies in, cooling on the way home. */
    private static double heat(Scene s, int group) {
        double sortie = sortie(s, group);
        return sortie <= 0 ? .3 : sortie <= 1 ? .45 + .55 * sortie : Math.max(.3, 1 - (sortie - 1) * 1.4);
    }

    /** A droplet figure's centre: it forms up in the fan facing the first target and strikes its own. */
    private static Vec3 dropletCentre(Scene s, int group, double time) {
        HiveTarget target = s.targetOf(group);
        return HiveFormation.dropletCentre(s.type(), s.owner(), s.target(), target.feet(), target.height(), group, s.groups(), time, s.cycleStart(),
                s.interval());
    }

    private static double sortie(Scene s, int group) {
        HiveTarget target = s.targetOf(group);
        return HiveFormation.sortie(s.owner(), s.target(), target.feet(), target.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
    }

    /**
     * Twins: the rifts a figure jumps through, a tear behind its owner and another by its target, each open
     * only round the moment the figure goes in or comes out.
     */
    private static void rifts(Scene s, int group, Vec3 home, Vec3 core, double sortie, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        if (sortie <= 0) return;
        double[] marks = HiveFormation.RIFT_MARKS;
        for (int index = 0; index < marks.length; index++) {
            double open = 1 - Math.abs(sortie - marks[index]) / .14;
            if (open <= 0) continue;
            Vec3 at = HiveFormation.arcPath(home, core, marks[index], group);
            Vec3 across = HiveFormation.arcPath(home, core, marks[index] + .02, group).subtract(at);
            tear(glow, fill, m, at.subtract(camera), across, 1.7 * Math.sqrt(open), open, s.time(), group * 4 + index, color);
        }
    }

    /**
     * A tear in space facing along {@code across}: a ragged upright slit with violet edges and the void and its
     * stars inside, bending the world round it. The same edges and void as the rift round a held creature.
     */
    static void tear(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 at, Vec3 across, double height, double open, double time, int seed, int color) {
        Vec3 up = new Vec3(0, 1, 0), flat = new Vec3(across.x, 0, across.z);
        Vec3 side = (flat.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : flat.normalize()).cross(up).normalize();
        int points = 9;
        Vec3[] left = new Vec3[points], right = new Vec3[points];
        for (int k = 0; k < points; k++) {
            double t = k / (points - 1.0), y = (t - .5) * height, width = Math.sin(Math.PI * t) * height * .22 * open;
            double jag = (hashOf(seed, k) - .5) * height * .08;
            left[k] = at.add(0, y, 0).add(side.scale(-width + jag));
            right[k] = at.add(0, y, 0).add(side.scale(width + jag * .6));
        }
        int edge = GlowBrushReference.mix(color, 0xE7C6FF, .4);
        for (int k = 0; k < points - 1; k++) {
            GlowBrushReference.quad(fill, m, left[k], left[k + 1], right[k + 1], right[k], 0x05010A, 0x05010A, 0x05010A, 0x05010A, 235 * open, 235 * open, 235 * open, 235 * open);
            GlowBrushReference.beam(glow, m, left[k], left[k + 1], .02, edge, 200 * open);
            GlowBrushReference.beam(glow, m, right[k], right[k + 1], .02, edge, 200 * open);
        }
        for (int star = 0; star < 7; star++) {
            double t = .15 + .7 * hashOf(seed * 7 + star, 11), u = hashOf(seed * 7 + star, 12) - .5;
            Vec3 point = left[0].lerp(left[points - 1], t).lerp(right[0].lerp(right[points - 1], t), .5 + u * .6);
            GlowBrushReference.dot(glow, m, point, .025, 0xFFFFFF, 190 * open * (.6 + .4 * Math.sin(time * .4 + star)));
        }
        if (!GlowBrushReference.flat()) ShieldRefraction.queueLens(at.x, at.y, at.z, height * .15 * open, height * .9, .9 * open);
    }

    private static double hashOf(int seed, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * A Mana drop of glass between its drones, as on the reference's faceted icosahedron: its tip, its two rings
     * and its back are drones, its facets glass, teal at the heart and bright at the edges it is seen along.
     */
    private static void dropletFacets(java.util.function.IntFunction<Vec3> drone, VertexConsumer fill, VertexConsumer glow, Matrix4f m, Vec3 c, int color, double heat) {
        int ring = 6, tip = 0, back = 1 + 2 * ring;
        java.util.List<int[]> faces = new java.util.ArrayList<>();
        for (int k = 0; k < ring; k++) {
            int a = 1 + k, b = 1 + (k + 1) % ring, a2 = 1 + ring + k, b2 = 1 + ring + (k + 1) % ring;
            faces.add(new int[]{tip, a, b});
            faces.add(new int[]{a, a2, b});
            faces.add(new int[]{b, a2, b2});
            faces.add(new int[]{a2, back, b2});
        }
        for (int[] face : faces) {
            Vec3 a = drone.apply(face[0]), b = drone.apply(face[1]), d = drone.apply(face[2]);
            if (a == null || b == null || d == null) continue;
            Vec3 normal = b.subtract(a).cross(d.subtract(a));
            double edgeOn = normal.lengthSqr() < 1e-12 ? 0 : 1 - Math.abs(normal.normalize().dot(GlowBrushReference.view(a.add(b).add(d).scale(1 / 3.0))));
            int tint = GlowBrushReference.mix(0x0B5E62, color, .3 + .6 * edgeOn);
            double alpha = 16 + 70 * edgeOn * edgeOn + 30 * heat;
            GlowBrushReference.quad(fill, m, a, b, d, d, tint, tint, tint, tint, alpha, alpha, alpha, alpha);
            GlowBrushReference.line(glow, m, a, b, .012, GlowBrushReference.mix(color, 0xFFFFFF, .5), 70 + 90 * heat);
        }
        GlowBrushReference.dot(glow, m, c, .25, color, 30 + 60 * heat);
    }

    // --- barrage ---------------------------------------------------------------------------------

    private static void clusters(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int groups = s.groups();
        double soonest = Double.MAX_VALUE;
        for (int group = 0; group < groups; group++) if (s.members()[group] > 0) soonest = Math.min(soonest, until(s, group, HiveFormation.FIRE));
        if (soonest <= AIM_TICKS && HiveJuice.detail() != HiveJuice.Detail.LOW && s.type() != HiveType.MANA) {
            HiveJuiceReference.aim(s.target(), Math.max(1, s.width() * 1.6), 1 - soonest / AIM_TICKS, s.time(), color, accent(s.type()), camera, glow, m);
        }
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        Vec3[] centres = new Vec3[groups];
        double[] charges = new double[groups];
        for (int group = 0; group < groups; group++) {
            centres[group] = HiveFormation.clusterCentre(s.type(), s.target(), s.width(), s.height(), group, groups, s.time()).subtract(camera);
            charges[group] = charge(s, group);
        }
        // The pattern the clumps make, with pulses of light running along its lines.
        int light = GlowBrushReference.mix(color, 0xFFFFFF, .45);
        for (int[] link : HiveFormation.clusterLinks(s.type(), groups)) {
            int a = link[0], b = link[1];
            if (s.members()[a] == 0 || s.members()[b] == 0) continue;
            double charge = (charges[a] + charges[b]) / 2;
            GlowBrushReference.beam(glow, m, centres[a], centres[b], .022, color, 45 + 85 * charge);
            double run = (s.time() * .025 + a * .37 + b * .11) % 1;
            GlowBrushReference.dot(glow, m, centres[a].lerp(centres[b], run), .1, light, 150 * Math.sin(Math.PI * run));
        }
        for (int group = 0; group < groups; group++) {
            if (s.members()[group] == 0) continue;
            Vec3 centre = centres[group];
            double charge = charges[group], radius = HiveFormation.clumpRadius(s.members()[group]);
            // A soft halo so a clump reads from far off, then its charge glowing through the drones.
            GlowBrushReference.dot(glow, m, centre, radius * 2.8, color, 28 + 52 * charge);
            if (s.type() == HiveType.MANA) {
                chargeOrb(glow, m, centre, Math.max(.1, charge), radius * .8, group, s.time(), color);
                continue;
            }
            pattern(s, group, centre, core, charge, camera, glow, fill, m, color);
        }
        // Now and then lightning leaps between neighbouring clumps, where their drones hop.
        if (s.type() != HiveType.MANA) {
            long beat = (long) Math.floor(s.time() / 24);
            int[][] links = HiveFormation.clusterLinks(s.type(), groups);
            if (links.length > 0 && s.time() - beat * 24 < 4) {
                int[] link = links[(int) Math.floorMod(beat * 7, links.length)];
                if (s.members()[link[0]] > 0 && s.members()[link[1]] > 0) {
                    GlowBrushReference.lightning(glow, m, centres[link[0]], centres[link[1]], beat * 53, 7, .12, .01, GlowBrushReference.mix(color, 0xFFFFFF, .5), 200);
                }
            }
        }
    }

    /**
     * An RF or Twins clump: its drones stand on the corners of a crown or an octagon, joined by lines of light,
     * and its shot builds in the middle (a ring of lightning, a glass icosahedron) with thin discharges running
     * from the corners into it. Lightning runs round the Twins octagon's rim as it charges.
     */
    private static void pattern(Scene s, int group, Vec3 centre, Vec3 core, double charge, Vec3 camera, VertexConsumer glow, VertexConsumer fill,
            Matrix4f m, int color) {
        int groups = s.groups(), members = s.members()[group], ring = Math.min(HiveShapes.CLUMP_RING, members);
        Vec3[] corners = new Vec3[ring];
        for (int corner = 0; corner < ring; corner++) {
            int slot = corner * groups + group;
            corners[corner] = slot < s.drones().length && s.drones()[slot] != null ? s.drones()[slot].subtract(camera) : null;
        }
        long flicker = (long) Math.floor(s.time() / 2);
        double reach = HiveFormation.clumpRadius(members) * 3;
        for (int corner = 0; corner < ring; corner++) {
            Vec3 a = corners[corner], b = corners[(corner + 1) % ring];
            // A corner hopping to another clump takes its lines with it only as far as its own clump.
            if (a == null || b == null || a.distanceTo(centre) > reach || b.distanceTo(centre) > reach) continue;
            GlowBrushReference.beam(glow, m, a, b, .014, color, 70 + 90 * charge);
            if (s.type() == HiveType.TWINS && charge > .15 && hashOf((int) flicker * 131 + group * 17, corner) < .5 * charge + .1) {
                GlowBrushReference.lightning(glow, m, a, b, flicker * 131 + group * 17L + corner, 3, .25, .01, 0xE7C6FF, 210 * charge);
            }
            // Thin discharges from the corners into the charge.
            if (charge > .2 && a.lengthSqr() < 32 * 32 && hashOf((int) flicker * 7 + group, corner + 40) < .35) {
                GlowBrushReference.lightning(glow, m, a, centre, flicker * 17 + group * 5L + corner, 3, .2, .005, GlowBrushReference.mix(color, 0xFFFFFF, .5), 150 * charge);
            }
        }
        if (s.type() == HiveType.RF) HiveProjectilesReference.ring(glow, m, centre, core.subtract(centre), Math.max(.05, charge), s.time(), group * 977L + 13);
        else HiveProjectilesReference.icosahedron(glow, fill, m, centre, HiveProjectilesReference.RADIUS * (.35 + .65 * charge), s.time() * .06 + group, .4 + .6 * charge);
    }

    /** A barrage clump's charge, 0 to 1: it builds until the clump fires, then collapses within a few ticks. */
    private static double charge(Scene s, int group) {
        double phase = HiveFormation.groupPhase(s.time(), s.cycleStart(), s.interval(), s.timingGroup(group), s.timingGroups());
        return phase < 0 ? 0 : phase < HiveFormation.FIRE ? phase / HiveFormation.FIRE
                : Math.max(0, 1 - (phase - HiveFormation.FIRE) / .06);
    }

    /**
     * A charge: a glowing ball traced with circuit lines, swelling to {@code size} as it charges (after
     * the reference ball of circuitry in smoke, in each family's colour).
     */
    private static void chargeOrb(VertexConsumer glow, Matrix4f m, Vec3 centre, double charge, double size, int group, double time, int color) {
        double radius = size * (.2 + .8 * charge);
        GlowBrushReference.dot(glow, m, centre, radius * 2.4, color, 40 + 90 * charge);
        GlowBrushReference.dot(glow, m, centre, radius * 1.1, GlowBrushReference.mix(color, 0xFFFFFF, .5), 120 + 120 * charge);
        Random random = new Random(group * 977L + 13);
        double spin = time * .05;
        for (int trace = 0; trace < 9; trace++) {
            double lat = (random.nextDouble() - .5) * Math.PI * .9, lon = random.nextDouble() * Math.PI * 2;
            Vec3 previous = orbPoint(centre, radius, lat, lon + spin);
            for (int step = 0; step < 4; step++) {
                if (random.nextBoolean()) lat += (random.nextBoolean() ? 1 : -1) * .35;
                else lon += (random.nextBoolean() ? 1 : -1) * .45;
                Vec3 next = orbPoint(centre, radius, lat, lon + spin);
                GlowBrushReference.line(glow, m, previous, next, radius * .045, GlowBrushReference.mix(color, 0xFFFFFF, .3), 150 * charge);
                previous = next;
            }
            GlowBrushReference.dot(glow, m, previous, radius * .12, 0xFFFFFF, 180 * charge);
        }
    }

    private static Vec3 orbPoint(Vec3 centre, double radius, double lat, double lon) {
        return centre.add(Math.cos(lat) * Math.cos(lon) * radius, Math.sin(lat) * radius, Math.cos(lat) * Math.sin(lon) * radius);
    }

    // --- containment -----------------------------------------------------------------------------

    private HiveModeVisualReference() {
    }
}
