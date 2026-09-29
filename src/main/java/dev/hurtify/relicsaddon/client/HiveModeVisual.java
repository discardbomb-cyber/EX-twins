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
 *       lightning jumping between them and space bending round them as they dive.</li>
 *   <li>Barrage: each cluster's charge swells into a glowing ball traced with circuitry; Twins
 *       clusters are octagons with lightning running round them.</li>
 *   <li>Containment: the RF torus's lattice, the Mana ward's rhombi and circles over a glass bubble,
 *       the Twins rift spheres around a black hole with a violet accretion disk.</li>
 * </ul>
 * Positions are world space; {@code camera} is subtracted here.
 */
public final class HiveModeVisual {
    /** What one swarm looks like this frame. {@code drones[slot]} is null for an empty place. */
    public record Scene(AttackMode mode, HiveType type, int slots, int groups, int[] members, Vec3[] drones, Vec3 target,
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
                    Vec3 centre = HiveFormation.dropletCentre(s.target(), s.width(), s.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
                    EffectLights.glow(centre, 7 + 6 * heat(s, group), HiveFormation.shapeSize(s.members()[group]));
                }
            }
            case BARRAGE -> {
                for (int group = 0; group < s.groups(); group++) {
                    double charge = charge(s, group);
                    if (s.members()[group] == 0 || charge <= .02) continue;
                    Vec3 centre = HiveFormation.clusterCentre(s.target(), s.width(), s.height(), group, s.groups(), s.time());
                    EffectLights.glow(centre, 3 + 11 * charge, .12 + .38 * charge);
                }
            }
            case CONTAINMENT -> {
                if (!s.formed()) return;
                Vec3 core = HiveFormation.core(s.target(), s.height());
                switch (s.type()) {
                    case RF -> EffectLights.glow(core, 9, Math.max(.8, s.width()));
                    case MANA -> EffectLights.glow(core, 9, 1.45 * Math.max(1, s.height() / 1.8));
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
        for (int group = 0; group < s.groups(); group++) {
            if (s.members()[group] == 0) continue;
            Vec3 centre = HiveFormation.dropletCentre(s.target(), s.width(), s.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
            Vec3 post = HiveFormation.post(s.target(), s.width(), s.height(), group, s.groups(), s.time(), 4.2);
            Vec3 core = HiveFormation.core(s.target(), s.height());
            Vec3 facing = core.subtract(post);
            double heat = heat(s, group);
            boolean diving = heat >= .5;
            double size = HiveFormation.shapeSize(s.members()[group]);
            Vec3 c = centre.subtract(camera);
            switch (s.type()) {
                case RF -> {
                    double spin = s.time() + group * 17;
                    Vec3[] corners = new Vec3[16];
                    for (int corner = 0; corner < 16; corner++) corners[corner] = c.add(HiveShapes.tesseractCorner(corner, spin, size));
                    for (int[] edge : HiveShapes.TESSERACT_EDGES) GlowBrush.beam(glow, m, corners[edge[0]], corners[edge[1]], .018, color, 80 + 140 * heat);
                    for (Vec3 corner : corners) GlowBrush.dot(glow, m, corner, .07, 0xD8FCFF, 120 + 100 * heat);
                }
                case MANA -> {
                    dropletShell(fill, glow, m, c, facing, size, s.time(), color, heat);
                    if (diving) GlowBrush.line(glow, m, c, c.subtract(facing.normalize().scale(1.5 + 2 * heat)), .12 * size, .01, color, color, 150 * heat, 0);
                }
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
                            GlowBrush.beam(glow, m, a, b, .016, color, 90 + 120 * heat);
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
                    // Space bends round a diving group.
                    if (diving && !GlowBrush.flat()) ShieldRefraction.queueLens(c.x, c.y, c.z, size * .4, size * 2.2, .8 * heat, false);
                }
            }
        }
    }

    /** How hot a droplet group burns: .35 at its post, rising from .5 to 1 only while it dives. */
    private static double heat(Scene s, int group) {
        double phase = HiveFormation.groupPhase(s.time(), s.cycleStart(), s.interval(), group, s.groups());
        boolean diving = s.time() >= s.cycleStart() && phase >= HiveFormation.DIVE && phase < HiveFormation.IMPACT;
        return diving ? .5 + .5 * (phase - HiveFormation.DIVE) / (HiveFormation.IMPACT - HiveFormation.DIVE) : .35;
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
        for (int group = 0; group < s.groups(); group++) {
            if (s.members()[group] == 0) continue;
            Vec3 centre = HiveFormation.clusterCentre(s.target(), s.width(), s.height(), group, s.groups(), s.time()).subtract(camera);
            double charge = charge(s, group);
            // The cluster's own outline: members joined in order around their ring or octagon.
            Vec3 previous = null, first = null;
            int links = 0;
            for (int slot = group; slot < s.slots(); slot += s.groups()) {
                Vec3 drone = s.drones()[slot];
                if (drone == null) continue;
                Vec3 at = drone.subtract(camera);
                if (previous != null && at.distanceToSqr(previous) < 1.2) {
                    GlowBrush.beam(glow, m, previous, at, .014, color, 80 + 110 * charge);
                    links++;
                }
                if (first == null) first = at;
                previous = at;
            }
            if (previous != null && first != null && links > 1 && previous.distanceToSqr(first) < 1.2) GlowBrush.beam(glow, m, previous, first, .014, color, 80 + 110 * charge);
            if (charge > .02) chargeOrb(glow, m, centre, charge, group, s.time(), s.type(), color);
            if (s.type() == HiveType.TWINS && charge > .2) {
                // Lightning runs round the octagon while it charges.
                long flicker = (long) Math.floor(s.time() / 2);
                Vec3 facing = HiveFormation.core(s.target(), s.height()).subtract(camera).subtract(centre);
                double radius = (.45 + .07 * Math.sqrt(s.members()[group])) * 1.2;
                for (int side = 0; side < 8; side++) {
                    long seed = flicker * 131 + group * 17L + side;
                    if (new Random(seed).nextDouble() > .45 * charge) continue;
                    Vec3 a = centre.add(HiveShapes.hexagonPoint(0, s.time() * .02 + side * Math.PI / 4, facing, radius));
                    Vec3 b = centre.add(HiveShapes.hexagonPoint(0, s.time() * .02 + (side + 1) * Math.PI / 4, facing, radius));
                    GlowBrush.lightning(glow, m, a, b, seed, 3, .25, .01, 0xE7C6FF, 200 * charge);
                }
            }
        }
    }

    /** A barrage cluster's charge, 0 to 1: it builds until the cluster fires, then collapses within a few ticks. */
    private static double charge(Scene s, int group) {
        double phase = HiveFormation.groupPhase(s.time(), s.cycleStart(), s.interval(), group, s.groups());
        return phase < 0 ? 0 : phase < HiveFormation.FIRE ? phase / HiveFormation.FIRE
                : Math.max(0, 1 - (phase - HiveFormation.FIRE) / .06);
    }

    /**
     * A charge: a glowing ball traced with circuit lines, swelling as it charges (after the reference
     * ball of circuitry in smoke, in each family's colour).
     */
    private static void chargeOrb(VertexConsumer glow, Matrix4f m, Vec3 centre, double charge, int group, double time, HiveType type, int color) {
        double radius = .12 + .38 * charge;
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
                    if (to.distanceToSqr(at) < 1.5) GlowBrush.line(glow, m, at, to, .012, color, 110);
                }
            }
        }
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        GlowBrush.dot(glow, m, core, Math.max(.6, s.width()), color, 45);
    }

    /** Mana: the ward's three rhombi and two circles in light over a glass bubble. */
    private static void ward(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        double scale = Math.max(1, s.height() / 1.8), turn = s.time() * .008;
        double pulse = .75 + .25 * Math.sin(s.time() * .12);
        for (int rhombus = 0; rhombus < 3; rhombus++) {
            Vec3 previous = null;
            for (int step = 0; step <= 32; step++) {
                Vec3 point = core.add(HiveShapes.rhombusPoint(step / 32.0, turn + rhombus * Math.PI * 2 / 3).scale(scale));
                if (previous != null) GlowBrush.beam(glow, m, previous, point, .014, color, 130 * pulse);
                previous = point;
            }
        }
        for (int ring = 0; ring < 2; ring++) {
            GlowBrush.circle(glow, m, core.add(0, (ring == 0 ? 1.0 : -1.0) * scale, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 1.3 * scale, 48, .018, color, 150 * pulse);
        }
        GlowBrush.sphere(fill, m, core, 1.45 * scale, 0x0B5E62, color, 10, 80, 16);
    }

    /** Twins: hexagon-shelled rift spheres around a black hole with a violet accretion disk. */
    private static void rifts(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        double distance = Math.max(1.5, s.width() * .7 + 1.1);
        for (int sphere = 0; sphere < 4; sphere++) {
            Vec3 centre = core.add(HiveShapes.riftCentre(sphere, s.time(), distance));
            double spin = HiveShapes.riftSpin(sphere, s.time());
            double cos = Math.cos(spin), sin = Math.sin(spin);
            for (ShieldHoneycomb.Cell cell : ShieldHoneycomb.SMALL) {
                float[] p = cell.perimeter();
                int corners = p.length / 3;
                for (int k = 0; k < corners; k++) {
                    int n = (k + 1) % corners;
                    Vec3 a = centre.add(spun(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], cos, sin).scale(.52));
                    Vec3 b = centre.add(spun(p[n * 3], p[n * 3 + 1], p[n * 3 + 2], cos, sin).scale(.52));
                    GlowBrush.line(glow, m, a, b, .009, color, 70);
                }
            }
            GlowBrush.dot(glow, m, centre, .45, 0x2A0F45, 90);
        }
        blackHole(glow, fill, m, core, horizon(s), s.time());
    }

    /** The black hole's horizon grows a little with the target it holds. */
    private static double horizon(Scene s) {
        return .42 + .08 * Math.max(0, s.width() - .6);
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
