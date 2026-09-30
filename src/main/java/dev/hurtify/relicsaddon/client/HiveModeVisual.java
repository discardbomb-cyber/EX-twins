package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.drone.HiveType;
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
 *   <li>Containment: the RF torus's lattice, the Mana ward's rhombi and circles over a glass bubble,
 *       the Twins rift spheres around a black hole with a violet accretion disk.</li>
 * </ul>
 * Positions are world space; {@code camera} is subtracted here.
 */
public final class HiveModeVisual {
    /** What one swarm looks like this frame. {@code drones[slot]} is null for an empty place. */
    public record Scene(AttackMode mode, HiveType type, int slots, int groups, int[] members, Vec3[] drones, Vec3 owner, Vec3 target,
                 double width, double height, double time, double cycleStart, int interval, boolean formed) { }

    public static void render(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m) {
        int color = color(s.type());
        switch (s.mode()) {
            case DROPLET -> droplets(s, camera, glow, fill, m, color);
            case BARRAGE -> clusters(s, camera, glow, fill, m, color);
            case CONTAINMENT -> {
                if (!s.formed()) return;
                switch (s.type()) {
                    case RF -> torus(s, camera, glow, m, color);
                    case MANA -> ward(s, camera, glow, fill, m, color);
                    case TWINS -> rifts(s, camera, glow, fill, m, color);
                }
            }
        }
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
                    Vec3 centre = HiveFormation.dropletCentre(s.owner(), s.target(), s.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
                    EffectLights.glow(centre, 7 + 6 * heat(s, group), HiveFormation.shapeSize(s.members()[group]));
                }
            }
            case BARRAGE -> {
                for (int group = 0; group < s.groups(); group++) {
                    if (s.members()[group] == 0) continue;
                    // Every clump glows a little; its charge brightens it as it builds.
                    double charge = charge(s, group);
                    Vec3 centre = HiveFormation.clusterCentre(s.type(), s.target(), s.width(), s.height(), group, s.groups(), s.time());
                    EffectLights.glow(centre, 4 + 10 * charge, HiveFormation.clumpRadius(s.members()[group]));
                }
            }
            case CONTAINMENT -> {
                if (!s.formed()) return;
                Vec3 core = HiveFormation.core(s.target(), s.height());
                switch (s.type()) {
                    case RF -> EffectLights.glow(core, 9, Math.max(.8, s.width()) * HiveFormation.CONTAINMENT_SCALE);
                    case MANA -> EffectLights.glow(core, 9, 1.45 * Math.max(1, s.height() / 1.8) * HiveFormation.CONTAINMENT_SCALE);
                    // The accretion disk reaches about four horizons out.
                    case TWINS -> EffectLights.glow(core, 11, horizon(s) * 4);
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
        Vec3 core = HiveFormation.core(s.target(), s.height());
        for (int group = 0; group < s.groups(); group++) {
            if (s.members()[group] == 0) continue;
            Vec3 home = HiveFormation.muster(s.owner(), s.target(), group, s.groups(), s.time());
            Vec3 centre = HiveFormation.dropletCentre(s.owner(), s.target(), s.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
            double sortie = HiveFormation.sortie(s.owner(), s.target(), s.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
            boolean flying = sortie > 0 && sortie < 1;
            double heat = heat(s, group);
            Vec3 facing = core.subtract(home);
            Vec3[] axes = HiveShapes.axes(facing);
            double size = HiveFormation.shapeSize(s.members()[group]);
            Vec3 c = centre.subtract(camera);
            // The figure's place in the fan: a faint ring it forms up in, brighter while it waits there.
            GlowBrush.circle(glow, m, home.subtract(camera), axes[1], axes[2], size * 1.9, 40, .012, color, sortie <= 0 ? 60 : 22);
            // A streak behind a figure on the move.
            if (sortie > 0 && sortie != 1) {
                Vec3 before = HiveFormation.dropletCentre(s.owner(), s.target(), s.height(), group, s.groups(), s.time() - 1.5,
                        s.cycleStart(), s.interval()).subtract(camera);
                if (before.distanceToSqr(c) > .04) {
                    Vec3 tail = c.add(before.subtract(c).scale(2.2));
                    GlowBrush.line(glow, m, c, tail, size * .45, .01, color, color, 110 * heat, 0);
                }
            }
            switch (s.type()) {
                case RF -> {
                    double spin = s.time() + group * 17;
                    Vec3[] corners = new Vec3[16];
                    for (int corner = 0; corner < 16; corner++) corners[corner] = c.add(HiveShapes.tesseractCorner(corner, spin, size));
                    for (int[] edge : HiveShapes.TESSERACT_EDGES) GlowBrush.beam(glow, m, corners[edge[0]], corners[edge[1]], .022, color, 80 + 140 * heat);
                    for (Vec3 corner : corners) GlowBrush.dot(glow, m, corner, .08, 0xD8FCFF, 120 + 100 * heat);
                    GlowBrush.dot(glow, m, c, size * 1.5, color, 25 + 45 * heat);
                }
                case MANA -> dropletShell(fill, glow, m, c, facing, size, s.time(), color, heat);
                case TWINS -> {
                    int rings = HiveShapes.hexagonCount(s.members()[group]);
                    Vec3[] centres = new Vec3[rings];
                    double spinTime = s.time() + group * 11;
                    for (int ring = 0; ring < rings; ring++) {
                        centres[ring] = c.add(HiveShapes.hexagonCentre(ring, rings, spinTime, facing, size));
                        double spin = spinTime * (ring % 2 == 0 ? .05 : -.05);
                        for (int side = 0; side < 6; side++) {
                            Vec3 a = centres[ring].add(HiveShapes.hexagonPoint(side, spin, facing, size * .45));
                            Vec3 b = centres[ring].add(HiveShapes.hexagonPoint(side + 1, spin, facing, size * .45));
                            GlowBrush.beam(glow, m, a, b, .018, color, 90 + 120 * heat);
                        }
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
                    if (flying && !GlowBrush.flat()) ShieldRefraction.queueLens(c.x, c.y, c.z, size * .4, size * 2.2, .8 * heat, false);
                }
            }
        }
    }

    /** How hot a droplet figure burns: .3 while it waits in the fan, from .45 up to 1 as it flies in, cooling on the way home. */
    private static double heat(Scene s, int group) {
        double sortie = HiveFormation.sortie(s.owner(), s.target(), s.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
        return sortie <= 0 ? .3 : sortie <= 1 ? .45 + .55 * sortie : Math.max(.3, 1 - (sortie - 1) * 1.4);
    }

    /** A glass drop around a Mana group: a lit rim and a faint body, tip towards the target. */
    private static void dropletShell(VertexConsumer fill, VertexConsumer glow, Matrix4f m, Vec3 c, Vec3 facing, double size, double time, int color, double heat) {
        Vec3[] axes = HiveShapes.axes(facing);
        int rows = 10, columns = 16;
        Vec3[][] points = new Vec3[rows + 1][columns + 1];
        for (int row = 0; row <= rows; row++) {
            double y = -1 + 2.0 * row / rows;
            double width = y > 0 ? Math.sqrt(Math.max(0, 1 - y * y)) * Math.pow(1 - y, .7) : Math.sqrt(Math.max(0, 1 - y * y));
            double length = y > 0 ? y * 2.1 : y;
            for (int column = 0; column <= columns; column++) {
                double angle = Math.PI * 2 * column / columns + time * .03;
                double r = width * size * 1.25 * (1 + .04 * Math.sin(time * .2 + column + row));
                points[row][column] = c.add(axes[0].scale(length * size * 1.25)).add(axes[1].scale(Math.cos(angle) * r)).add(axes[2].scale(Math.sin(angle) * r));
            }
        }
        Vec3 eye = GlowBrush.view(c);
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            Vec3 a = points[row][column], b = points[row + 1][column], d = points[row + 1][column + 1], e = points[row][column + 1];
            Vec3 normal = a.subtract(c).normalize();
            double fresnel = Math.pow(1 - Math.abs(normal.dot(eye)), 2);
            double alpha = 12 + 70 * fresnel + 40 * heat;
            int tint = GlowBrush.mix(0x0B5E62, color, fresnel);
            GlowBrush.quad(fill, m, a, b, d, e, tint, tint, tint, tint, alpha, alpha, alpha, alpha);
        }
        GlowBrush.dot(glow, m, c.add(axes[0].scale(size * 2.3)), .09, 0xE0FFF8, 120 + 100 * heat);
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
        double phase = HiveFormation.groupPhase(s.time(), s.cycleStart(), s.interval(), group, s.groups());
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

    /** RF: the torus's lattice, each drone joined to its neighbours along and across the rows. */
    private static void torus(Scene s, Vec3 camera, VertexConsumer glow, Matrix4f m, int color) {
        int rows = 6, count = s.slots(), perRow = Math.max(1, (count + rows - 1) / rows);
        for (int slot = 0; slot < count; slot++) {
            Vec3 drone = s.drones()[slot];
            if (drone == null) continue;
            Vec3 at = drone.subtract(camera);
            int along = slot + rows, across = slot / rows * rows + (slot % rows + 1) % rows;
            if (along >= count) along = slot % rows;
            for (int other : new int[]{along, across}) {
                if (other < count && s.drones()[other] != null) {
                    Vec3 to = s.drones()[other].subtract(camera);
                    // Neighbours only: the lattice spacing grows with the construct.
                    double reach = 1.5 * HiveFormation.CONTAINMENT_SCALE * HiveFormation.CONTAINMENT_SCALE;
                    if (to.distanceToSqr(at) < reach) GlowBrush.line(glow, m, at, to, .02, color, 110);
                }
            }
        }
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        GlowBrush.dot(glow, m, core, Math.max(.6, s.width()), color, 45);
    }

    /** Mana: the ward's three rhombi and two circles in light over a glass bubble. */
    private static void ward(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        double scale = Math.max(1, s.height() / 1.8) * HiveFormation.CONTAINMENT_SCALE, turn = s.time() * .008;
        double pulse = .75 + .25 * Math.sin(s.time() * .12);
        for (int rhombus = 0; rhombus < 3; rhombus++) {
            Vec3 previous = null;
            for (int step = 0; step <= 32; step++) {
                Vec3 point = core.add(HiveShapes.rhombusPoint(step / 32.0, turn + rhombus * Math.PI * 2 / 3).scale(scale));
                if (previous != null) GlowBrush.beam(glow, m, previous, point, .024, color, 130 * pulse);
                previous = point;
            }
        }
        for (int ring = 0; ring < 2; ring++) {
            GlowBrush.circle(glow, m, core.add(0, (ring == 0 ? 1.0 : -1.0) * scale, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 1.3 * scale, 72, .03, color, 150 * pulse);
        }
        GlowBrush.sphere(fill, m, core, 1.45 * scale, 0x0B5E62, color, 10, 80, 16);
    }

    /** Twins: hexagon-shelled rift spheres around a black hole with a violet accretion disk. */
    private static void rifts(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        double distance = Math.max(1.5, s.width() * .7 + 1.1) * HiveFormation.CONTAINMENT_SCALE;
        double radius = HiveShapes.RIFT_RADIUS * HiveFormation.CONTAINMENT_SCALE;
        for (int sphere = 0; sphere < 4; sphere++) {
            Vec3 centre = core.add(HiveShapes.riftCentre(sphere, s.time(), distance));
            double spin = HiveShapes.riftSpin(sphere, s.time());
            double cos = Math.cos(spin), sin = Math.sin(spin);
            for (ShieldHoneycomb.Cell cell : ShieldHoneycomb.SMALL) {
                float[] p = cell.perimeter();
                int corners = p.length / 3;
                for (int k = 0; k < corners; k++) {
                    int n = (k + 1) % corners;
                    Vec3 a = centre.add(spun(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], cos, sin).scale(radius * 1.04));
                    Vec3 b = centre.add(spun(p[n * 3], p[n * 3 + 1], p[n * 3 + 2], cos, sin).scale(radius * 1.04));
                    GlowBrush.line(glow, m, a, b, .016, color, 70);
                }
            }
            GlowBrush.dot(glow, m, centre, radius * .9, 0x2A0F45, 90);
        }
        blackHole(glow, fill, m, core, horizon(s), s.time());
    }

    /** The black hole is this many times its base size (its disk, rings and lens follow the horizon). */
    private static final double BLACK_HOLE_SCALE = 2;

    /** The black hole's horizon grows a little with the target it holds. */
    private static double horizon(Scene s) {
        return (.42 + .08 * Math.max(0, s.width() - .6)) * BLACK_HOLE_SCALE;
    }

    /**
     * A black hole: a black horizon ringed by a thin photon ring, a tilted violet accretion disk turning
     * round it (hot white at its inner edge), the lensed far side of the disk arching round the horizon,
     * and space pulled into a funnel.
     */
    static void blackHole(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 core, double horizon, double time) {
        GlowBrush.sphere(fill, m, core, horizon, 0x000000, 0x1A0630, 250, 235, 14);
        Vec3 eye = GlowBrush.view(core);
        Vec3 right = eye.cross(Math.abs(eye.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(eye);
        GlowBrush.circle(glow, m, core, right, up, horizon * 1.08, 64, horizon * .035, 0xF2DDFF, 220);
        // The far side of the disk, bent over and under the horizon.
        GlowBrush.circle(glow, m, core, right, up, horizon * 1.45, 64, horizon * .12, 0xA24BFF, 90);
        // The disk itself, tilted and turning; brighter where it swings towards the viewer.
        double tilt = .62;
        Vec3 u = new Vec3(1, 0, 0), v = new Vec3(0, Math.sin(tilt), Math.cos(tilt));
        int rings = 14, segments = 72;
        for (int ring = 0; ring < rings; ring++) {
            double t = ring / (double) (rings - 1);
            double radius = horizon * (1.6 + 2.6 * t);
            int ringColor = GlowBrush.mix(0xFFF0FF, 0x7A22D6, Math.pow(t, .6));
            Vec3 previous = null;
            for (int step = 0; step <= segments; step++) {
                double angle = Math.PI * 2 * step / segments;
                Vec3 point = core.add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
                if (previous != null) {
                    double swirl = .55 + .45 * Math.sin(angle * 3 - time * (.25 - .15 * t) + ring * .7);
                    Vec3 tangent = point.subtract(previous).normalize();
                    double doppler = 1 + .6 * tangent.dot(eye);
                    double alpha = (1 - t) * (1 - t) * 190 * swirl * doppler + 25;
                    GlowBrush.line(glow, m, previous, point, horizon * (.1 + .05 * (1 - t)), ringColor, alpha);
                }
                previous = point;
            }
        }
        // Matter spiralling in.
        for (int mote = 0; mote < 24; mote++) {
            double life = ((time * .02 + mote * .618) % 1);
            double radius = horizon * (4.2 - 2.8 * life), angle = mote * 2.4 + time * (.08 + .1 * life);
            Vec3 at = core.add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
            GlowBrush.dot(glow, m, at, horizon * .1, 0xE7C6FF, 160 * Math.sin(Math.PI * life));
        }
        if (!GlowBrush.flat()) ShieldRefraction.queueLens(core.x, core.y, core.z, horizon * 1.02, horizon * 4, 1.4, true);
    }

    private static Vec3 spun(double x, double y, double z, double cos, double sin) {
        return new Vec3(x * cos - z * sin, y, x * sin + z * cos);
    }

    private HiveModeVisual() {
    }
}
