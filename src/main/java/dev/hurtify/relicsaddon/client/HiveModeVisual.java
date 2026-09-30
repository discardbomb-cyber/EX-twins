package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveTarget;
import dev.hurtify.relicsaddon.drone.HiveType;
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
public final class HiveModeVisual {
    /**
     * What one swarm looks like this frame. {@code drones[slot]} is null for an empty place. Strike group
     * g attacks {@code targets[g % n]}. {@code timing} maps a group to the swarm-wide group whose clock it
     * keeps (null: itself) and {@code timingGroups} is the swarm's group count, so a part of the scene
     * cut out round one target still charges and strikes in step with the server.
     */
    public record Scene(AttackMode mode, HiveType type, int slots, int groups, int[] members, Vec3[] drones, Vec3 owner, List<HiveTarget> targets,
                 double time, double cycleStart, int interval, boolean formed, int[] timing, int timingGroups) {
        /** The first (or, in a part, the only) target's feet, width and height. */
        public Vec3 target() { return targets.getFirst().feet(); }
        public double width() { return targets.getFirst().width(); }
        public double height() { return targets.getFirst().height(); }
        public int engaged() { return HiveFormation.engaged(targets.size(), HiveSlots.figures(groups, mode, type)); }
        public HiveTarget targetOf(int group) { return targets.get(group % engaged()); }
        public int timingGroup(int group) { return timing == null ? group : timing[group]; }
    }

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
                    HiveConstructVisual.render(part, camera, glow, fill, m, color);
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

    /**
     * The light these constructs cast on the world ({@link EffectLights}): each strike group's shape,
     * each charge while it builds, the containment construct and the Twins black hole's disk. Only the
     * world renderer calls this; gallery scenes have no world to light.
     */
    public static void light(Scene s) {
        switch (s.mode()) {
            case DROPLET -> {
                for (int group = 0; group < s.groups(); group++) {
                    if (s.members()[group] == 0) continue;
                    EffectLights.glow(dropletCentre(s, group, s.time()), 7 + 6 * heat(s, group), HiveFormation.shapeSize(s.members()[group]));
                }
            }
            case BARRAGE -> {
                for (int index = 0; index < s.engaged(); index++) {
                    Scene part = part(s, index);
                    for (int group = 0; group < part.groups(); group++) {
                        if (part.members()[group] == 0) continue;
                        // Every clump glows a little; its charge brightens it as it builds.
                        double charge = charge(part, group);
                        Vec3 centre = HiveFormation.clusterCentre(part.type(), part.target(), part.width(), part.height(), group, part.groups(), part.time());
                        EffectLights.glow(centre, 4 + 10 * charge, HiveFormation.clumpRadius(part.members()[group]));
                    }
                }
            }
            case CONTAINMENT -> {
                if (!s.formed()) return;
                for (int index = 0; index < s.engaged(); index++) {
                    Scene part = part(s, index);
                    Vec3 core = HiveFormation.core(part.target(), part.height());
                    EffectLights.glow(core, part.type() == HiveType.TWINS ? 8 : 10, HiveFormation.enclosure(part.width(), part.height()) * 1.3);
                }
            }
        }
    }

    public static int color(HiveType type) {
        return switch (type) {
            case RF -> 0x38E8FF;
            case MANA -> 0x42E6C8;
            case TWINS -> 0xB151FF;
        };
    }

    // --- droplet ---------------------------------------------------------------------------------

    private static void droplets(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        for (int group = 0; group < s.groups(); group++) {
            if (s.members()[group] == 0) continue;
            HiveTarget target = s.targetOf(group);
            Vec3 core = HiveFormation.core(target.feet(), target.height());
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
            Vec3[] axes = HiveShapes.axes(facing);
            double size = HiveFormation.shapeSize(s.members()[group]);
            Vec3 c = centre.subtract(camera);
            // The figure's place in the fan: a faint ring it forms up in, brighter while it waits there.
            GlowBrush.circle(glow, m, home.subtract(camera), axes[1], axes[2], size * 1.9, 40, .012, color, sortie <= 0 ? 60 : 22);
            // A streak behind a figure on the move.
            if (sortie > 0 && sortie != 1) {
                Vec3 before = dropletCentre(s, group, s.time() - 1.5).subtract(camera);
                if (before.distanceToSqr(c) > .04) {
                    Vec3 tail = c.add(before.subtract(c).scale(2.2));
                    GlowBrush.line(glow, m, c, tail, size * .45, .01, color, color, 110 * heat, 0);
                }
            }
            switch (s.type()) {
                case RF -> {
                    for (int[] edge : HiveShapes.TESSERACT_EDGES) {
                        Vec3 a = drone.apply(edge[0]), b = drone.apply(edge[1]);
                        if (a != null && b != null) GlowBrush.beam(glow, m, a, b, .022, color, 80 + 140 * heat);
                    }
                    for (int corner = 0; corner < HiveShapes.TESSERACT_CORNERS; corner++) {
                        Vec3 at = drone.apply(corner);
                        if (at != null) GlowBrush.dot(glow, m, at, .08, 0xD8FCFF, 120 + 100 * heat);
                    }
                    GlowBrush.dot(glow, m, c, size * 1.5, color, 25 + 45 * heat);
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
                            if (a != null && b != null) GlowBrush.beam(glow, m, a, b, .018, color, 90 + 120 * heat);
                        }
                        centres[ring] = seen == 0 ? c : sum.scale(1.0 / seen);
                    }
                    // Lightning leaps between neighbouring hexagons and re-forks every other tick.
                    long flicker = (long) Math.floor(s.time() / 2);
                    for (int ring = 0; ring < rings; ring++) {
                        long seed = flicker * 31 + group * 7919L + ring;
                        if (new Random(seed).nextDouble() < .6) {
                            GlowBrush.lightning(glow, m, centres[ring], centres[(ring + 1) % rings], seed, 5, .18, .012, 0xE7C6FF, 170 * heat + 40);
                        }
                    }
                    GlowBrush.dot(glow, m, c, size * (.5 + .15 * Math.sin(s.time() * .9)), color, 60 + 120 * heat);
                    // Space bends round a figure in flight.
                    if (flying && !GlowBrush.flat()) ShieldRefraction.queueLens(c.x, c.y, c.z, size * .4, size * 2.2, .8 * heat);
                }
            }
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
        Vec3[] rifts = HiveFormation.dropletRifts(home, core, group);
        double out = sortie <= 1 ? sortie : 2 - sortie;
        double[] marks = {HiveFormation.RIFT_IN, HiveFormation.RIFT_OUT};
        for (int index = 0; index < 2; index++) {
            double open = 1 - Math.abs(out - marks[index]) / .14;
            if (open <= 0) continue;
            Vec3 across = index == 0 ? core.subtract(home) : core.subtract(rifts[1]);
            tear(glow, fill, m, rifts[index].subtract(camera), across, 1.7 * Math.sqrt(open), open, s.time(), group * 2 + index, color);
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
        int edge = GlowBrush.mix(color, 0xE7C6FF, .4);
        for (int k = 0; k < points - 1; k++) {
            GlowBrush.quad(fill, m, left[k], left[k + 1], right[k + 1], right[k], 0x05010A, 0x05010A, 0x05010A, 0x05010A, 235 * open, 235 * open, 235 * open, 235 * open);
            GlowBrush.beam(glow, m, left[k], left[k + 1], .02, edge, 200 * open);
            GlowBrush.beam(glow, m, right[k], right[k + 1], .02, edge, 200 * open);
        }
        for (int star = 0; star < 7; star++) {
            double t = .15 + .7 * hashOf(seed * 7 + star, 11), u = hashOf(seed * 7 + star, 12) - .5;
            Vec3 point = left[0].lerp(left[points - 1], t).lerp(right[0].lerp(right[points - 1], t), .5 + u * .6);
            GlowBrush.dot(glow, m, point, .025, 0xFFFFFF, 190 * open * (.6 + .4 * Math.sin(time * .4 + star)));
        }
        if (!GlowBrush.flat()) ShieldRefraction.queueLens(at.x, at.y, at.z, height * .15 * open, height * .9, .9 * open);
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
            double edgeOn = normal.lengthSqr() < 1e-12 ? 0 : 1 - Math.abs(normal.normalize().dot(GlowBrush.view(a.add(b).add(d).scale(1 / 3.0))));
            int tint = GlowBrush.mix(0x0B5E62, color, .3 + .6 * edgeOn);
            double alpha = 16 + 70 * edgeOn * edgeOn + 30 * heat;
            GlowBrush.quad(fill, m, a, b, d, d, tint, tint, tint, tint, alpha, alpha, alpha, alpha);
            GlowBrush.line(glow, m, a, b, .012, GlowBrush.mix(color, 0xFFFFFF, .5), 70 + 90 * heat);
        }
        GlowBrush.dot(glow, m, c, .25, color, 30 + 60 * heat);
    }

    // --- barrage ---------------------------------------------------------------------------------

    private static void clusters(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int groups = s.groups();
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        Vec3[] centres = new Vec3[groups];
        double[] charges = new double[groups];
        for (int group = 0; group < groups; group++) {
            centres[group] = HiveFormation.clusterCentre(s.type(), s.target(), s.width(), s.height(), group, groups, s.time()).subtract(camera);
            charges[group] = charge(s, group);
        }
        // The pattern the clumps make, with pulses of light running along its lines.
        int light = GlowBrush.mix(color, 0xFFFFFF, .45);
        for (int[] link : HiveFormation.clusterLinks(s.type(), groups)) {
            int a = link[0], b = link[1];
            if (s.members()[a] == 0 || s.members()[b] == 0) continue;
            double charge = (charges[a] + charges[b]) / 2;
            GlowBrush.beam(glow, m, centres[a], centres[b], .022, color, 45 + 85 * charge);
            double run = (s.time() * .025 + a * .37 + b * .11) % 1;
            GlowBrush.dot(glow, m, centres[a].lerp(centres[b], run), .1, light, 150 * Math.sin(Math.PI * run));
        }
        for (int group = 0; group < groups; group++) {
            if (s.members()[group] == 0) continue;
            Vec3 centre = centres[group];
            double charge = charges[group], radius = HiveFormation.clumpRadius(s.members()[group]);
            // A soft halo so a clump reads from far off, then its charge glowing through the drones.
            GlowBrush.dot(glow, m, centre, radius * 2.8, color, 28 + 52 * charge);
            chargeOrb(glow, m, centre, Math.max(.1, charge), radius * .8, group, s.time(), color);
            if (s.type() == HiveType.TWINS && charge > .15) {
                // Lightning runs round the octagon's rim while it charges.
                long flicker = (long) Math.floor(s.time() / 2);
                Vec3 facing = core.subtract(centre);
                double spin = (s.time() + group * 29) * .02, rim = radius * 1.2;
                for (int side = 0; side < 8; side++) {
                    long seed = flicker * 131 + group * 17L + side;
                    if (new Random(seed).nextDouble() > .5 * charge + .1) continue;
                    Vec3 a = centre.add(HiveShapes.hexagonPoint(0, spin + side * Math.PI / 4, facing, rim));
                    Vec3 b = centre.add(HiveShapes.hexagonPoint(0, spin + (side + 1) * Math.PI / 4, facing, rim));
                    GlowBrush.lightning(glow, m, a, b, seed, 3, .25, .012, 0xE7C6FF, 210 * charge);
                }
            }
        }
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
        GlowBrush.dot(glow, m, centre, radius * 2.4, color, 40 + 90 * charge);
        GlowBrush.dot(glow, m, centre, radius * 1.1, GlowBrush.mix(color, 0xFFFFFF, .5), 120 + 120 * charge);
        Random random = new Random(group * 977L + 13);
        double spin = time * .05;
        for (int trace = 0; trace < 9; trace++) {
            double lat = (random.nextDouble() - .5) * Math.PI * .9, lon = random.nextDouble() * Math.PI * 2;
            Vec3 previous = orbPoint(centre, radius, lat, lon + spin);
            for (int step = 0; step < 4; step++) {
                if (random.nextBoolean()) lat += (random.nextBoolean() ? 1 : -1) * .35;
                else lon += (random.nextBoolean() ? 1 : -1) * .45;
                Vec3 next = orbPoint(centre, radius, lat, lon + spin);
                GlowBrush.line(glow, m, previous, next, radius * .045, GlowBrush.mix(color, 0xFFFFFF, .3), 150 * charge);
                previous = next;
            }
            GlowBrush.dot(glow, m, previous, radius * .12, 0xFFFFFF, 180 * charge);
        }
    }

    private static Vec3 orbPoint(Vec3 centre, double radius, double lat, double lon) {
        return centre.add(Math.cos(lat) * Math.cos(lon) * radius, Math.sin(lat) * radius, Math.cos(lat) * Math.sin(lon) * radius);
    }

    // --- containment -----------------------------------------------------------------------------

    /**
     * Queues the pull of each Twins rift on the world round it. The renderer draws it before the drones and
     * the light, which stay unbent over it.
     */
    public static void lenses(Scene s, Vec3 camera) {
        if (s.mode() != AttackMode.CONTAINMENT || s.type() != HiveType.TWINS || !s.formed() || GlowBrush.flat()) return;
        for (int index = 0; index < s.engaged(); index++) HiveConstructVisual.lens(part(s, index), camera);
    }

    private HiveModeVisual() {
    }
}
