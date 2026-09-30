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
        public int engaged() { return HiveFormation.engaged(targets.size(), groups); }
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
                    switch (s.type()) {
                        case RF -> torus(part, camera, glow, m, color);
                        case MANA -> ward(part, camera, glow, fill, m, color);
                        case TWINS -> rifts(part, camera, glow, fill, m, color);
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
                    switch (part.type()) {
                        case RF -> EffectLights.glow(centre(part), 9, HiveFormation.ringsRadius(part.width(), part.height(), false) * .8);
                        case MANA -> EffectLights.glow(centre(part), 9, 1.45 * HiveFormation.wardScale(part.width(), part.height()));
                        // The accretion disk reaches about three horizons out.
                        case TWINS -> EffectLights.glow(core, 11, horizon(part) * (DISK_INNER + DISK_SPAN));
                    }
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
        return HiveFormation.dropletCentre(s.owner(), s.target(), target.feet(), target.height(), group, s.groups(), time, s.cycleStart(), s.interval());
    }

    private static double sortie(Scene s, int group) {
        HiveTarget target = s.targetOf(group);
        return HiveFormation.sortie(s.owner(), s.target(), target.feet(), target.height(), group, s.groups(), s.time(), s.cycleStart(), s.interval());
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

    /** Beam bunches race round a collider at these speeds (radians a tick), the inner torus fastest. */
    private static final double[] BUNCH_SPEED = {.2, .17, .15};
    /** Ticks a collision's flash and spray last. */
    private static final double COLLISION_TICKS = 12;
    /** Colours of the tracks a collision throws out, on RF and on Twins. */
    private static final int[] RF_TRACKS = {0x38E8FF, 0xA8FFF6, 0xFFD27A}, TWINS_TRACKS = {0xB151FF, 0xFF7BE5, 0xE7C6FF};

    /** RF: three collider tori covered in hexagons, turning round the target like the rings of a Dyson swarm, the outer ones faster. */
    private static void torus(Scene s, Vec3 camera, VertexConsumer glow, Matrix4f m, int color) {
        tori(s, camera, glow, m, GlowBrush.mix(color, 0xFFFFFF, .35), color, RF_TRACKS, false, 105);
        GlowBrush.dot(glow, m, HiveFormation.core(s.target(), s.height()).subtract(camera), Math.max(.6, s.width()), color, 45);
    }

    /**
     * The three hexagon-covered tori of an RF or (denser) Twins containment. Each is a particle collider:
     * two beams race round inside its tube in opposite directions, crossing at four interaction points
     * where their bunches meet and burst, and the tube lights up round each burst.
     */
    private static void tori(Scene s, Vec3 camera, VertexConsumer glow, Matrix4f m, int line, int beam, int[] tracks, boolean dense, double alpha) {
        Vec3 core = centre(s).subtract(camera);
        double radius = HiveFormation.ringsRadius(s.width(), s.height(), dense), time = s.time();
        int rows = HiveShapes.ringRows(dense);
        for (int ring = 0; ring < 3; ring++) {
            Vec3[] frame = HiveShapes.ringFrame(ring, time);
            double tube = HiveShapes.ringTube(ring, radius, dense), major = radius * HiveShapes.RING_RADII[ring];
            double[][] bursts = collisions(ring, time);
            int columns = HiveShapes.ringColumns(ring, radius, dense);
            for (int column = 0; column < columns; column++) {
                // The tube lights up round a fresh burst.
                double u = (column + .25) / columns * Math.PI * 2, lit = 0;
                for (double[] burst : bursts) {
                    double apart = Math.abs(Math.IEEEremainder(u - burst[0], Math.PI * 2)) * major / (tube * 1.8);
                    lit += Math.exp(-burst[1] / 3 - apart * apart);
                }
                lit = Math.min(1, lit);
                int color = GlowBrush.mix(line, 0xFFFFFF, .3 * lit);
                for (int row = 0; row < rows; row++) {
                    Vec3 previous = null;
                    for (int corner = 0; corner <= 6; corner++) {
                        Vec3 at = core.add(HiveShapes.ringHexCorner(frame, ring, column, row, corner % 6, time, radius, dense));
                        if (previous != null) GlowBrush.line(glow, m, previous, at, dense ? .018 : .022, color, alpha * (1 + .6 * lit));
                        previous = at;
                    }
                }
            }
            collider(glow, m, core, frame, ring, radius, dense, time, beam, tracks, bursts);
        }
    }

    /**
     * The bursts still showing on collider {@code ring}: where along it (u), how old, and a seed for their
     * spray. Two bunches race each way round, so every quarter lap of theirs they meet at two opposite
     * interaction points, the other pair each time.
     */
    private static double[][] collisions(int ring, double time) {
        double speed = BUNCH_SPEED[ring], clock = time + ring * 23, period = Math.PI / (2 * speed);
        long event = (long) Math.floor(clock / period);
        double[][] bursts = new double[4][];
        int count = 0;
        for (long e = event; e >= event - 1; e--) {
            double age = clock - e * period;
            if (age < 0 || age >= COLLISION_TICKS) continue;
            for (int side = 0; side < 2; side++) {
                bursts[count++] = new double[]{Math.floorMod(e, 2) * Math.PI / 2 + side * Math.PI, age, e * 2 + side + ring * 1_000_003L};
            }
        }
        return java.util.Arrays.copyOf(bursts, count);
    }

    /** One torus's collider: its two beams and their bunches, the detectors ringing its interaction points, and its bursts. */
    private static void collider(VertexConsumer glow, Matrix4f m, Vec3 core, Vec3[] frame, int ring, double radius, boolean dense, double time,
            int color, int[] tracks, double[][] bursts) {
        double tube = HiveShapes.ringTube(ring, radius, dense), speed = BUNCH_SPEED[ring], clock = time + ring * 23;
        int hot = GlowBrush.mix(color, 0xFFFFFF, .55);
        for (int sign = -1; sign <= 1; sign += 2) {
            Vec3 previous = null;
            for (int step = 0; step <= 96; step++) {
                Vec3 at = core.add(beamPoint(frame, ring, sign, Math.PI * 2 * step / 96, time, radius, dense));
                if (previous != null) GlowBrush.line(glow, m, previous, at, tube * .08, color, 110);
                previous = at;
            }
            for (int bunch = 0; bunch < 2; bunch++) {
                double head = sign * speed * clock + bunch * Math.PI;
                Vec3 last = core.add(beamPoint(frame, ring, sign, head, time, radius, dense));
                GlowBrush.dot(glow, m, last, tube * .5, 0xFFFFFF, 255);
                GlowBrush.dot(glow, m, last, tube * .9, hot, 170);
                for (int k = 1; k <= 16; k++) {
                    Vec3 at = core.add(beamPoint(frame, ring, sign, head - sign * k * .05, time, radius, dense));
                    double fade = 1 - k / 17.0;
                    GlowBrush.line(glow, m, last, at, tube * .24 * fade, hot, 240 * fade);
                    last = at;
                }
            }
        }
        for (int point = 0; point < 4; point++) {
            double u = point * Math.PI / 2, flash = 0;
            for (double[] burst : bursts) {
                if (Math.abs(Math.IEEEremainder(u - burst[0], Math.PI * 2)) < 1e-6) flash = Math.max(flash, Math.exp(-burst[1] / 4));
            }
            Vec3[] axes = crossSection(frame, ring, u, time, radius, dense);
            Vec3 at = core.add(HiveShapes.ringPoint(frame, ring, u, 0, 0, time, radius, dense));
            GlowBrush.circle(glow, m, at, axes[1], axes[2], tube * 1.3, 20, tube * .06, GlowBrush.mix(color, 0xFFFFFF, .5 * flash), 70 + 170 * flash);
        }
        for (double[] burst : bursts) burst(glow, m, core, frame, ring, burst, radius, dense, time, tube, tracks);
    }

    /** Where beam {@code sign} (+1 or -1) runs at {@code u}: weaving across the tube, crossing the other beam at the four interaction points. */
    private static Vec3 beamPoint(Vec3[] frame, int ring, int sign, double u, double time, double radius, boolean dense) {
        return HiveShapes.ringPoint(frame, ring, u, 0, sign * .42 * Math.sin(2 * u), time, radius, dense);
    }

    /** The collider's own axes at {@code u}: along the beams, and two across the tube. */
    private static Vec3[] crossSection(Vec3[] frame, int ring, double u, double time, double radius, boolean dense) {
        Vec3 middle = HiveShapes.ringPoint(frame, ring, u, 0, 0, time, radius, dense);
        Vec3 along = HiveShapes.ringPoint(frame, ring, u + .01, 0, 0, time, radius, dense)
                .subtract(HiveShapes.ringPoint(frame, ring, u - .01, 0, 0, time, radius, dense)).normalize();
        Vec3 out = HiveShapes.ringPoint(frame, ring, u, 0, 1, time, radius, dense).subtract(middle).normalize();
        return new Vec3[]{along, out, along.cross(out)};
    }

    /** A burst where two bunches meet: a white flash, and the spray of particles it throws out curling in the collider's field. */
    private static void burst(VertexConsumer glow, Matrix4f m, Vec3 core, Vec3[] frame, int ring, double[] burst, double radius, boolean dense,
            double time, double tube, int[] tracks) {
        double age = burst[1], life = age / COLLISION_TICKS, fade = (1 - life) * (1 - life);
        long seed = (long) burst[2];
        Vec3 at = core.add(HiveShapes.ringPoint(frame, ring, burst[0], 0, 0, time, radius, dense));
        Vec3[] axes = crossSection(frame, ring, burst[0], time, radius, dense);
        GlowBrush.dot(glow, m, at, tube * (1.1 - .6 * life), 0xFFFFFF, 255 * Math.exp(-age / 2));
        GlowBrush.dot(glow, m, at, tube * 1.9, tracks[0], 90 * fade);
        double grown = Math.min(1, age / 3 + .2);
        for (int track = 0; track < 12; track++) {
            double spin = hash(seed, track, 1) * Math.PI * 2, bend = hash(seed, track, 3);
            // Most tracks bend gently; the slow few curl up tight.
            double curl = (hash(seed, track, 2) < .5 ? -1 : 1) * (.4 + 2.2 * bend * bend) / tube;
            double pitch = (hash(seed, track, 4) * 2 - 1) * .8, length = tube * (1.1 + 1.6 * hash(seed, track, 5)) * grown;
            Vec3 across = axes[1].scale(Math.cos(spin)).add(axes[2].scale(Math.sin(spin)));
            Vec3 aside = axes[1].scale(-Math.sin(spin)).add(axes[2].scale(Math.cos(spin)));
            int color = tracks[track % tracks.length];
            double heading = 0, step = length / 10;
            Vec3 point = at;
            for (int k = 1; k <= 10; k++) {
                // Losing energy, a track curls ever tighter.
                heading += curl * (1 + 1.6 * k / 10.0) * step;
                Vec3 next = point.add(across.scale(Math.cos(heading) * step)).add(aside.scale(Math.sin(heading) * step)).add(axes[0].scale(pitch * step));
                GlowBrush.line(glow, m, point, next, tube * .075, GlowBrush.mix(color, 0xFFFFFF, .4 * (1 - k / 10.0)), 255 * fade * (1 - .35 * k / 10.0));
                point = next;
            }
        }
    }

    private static double hash(long seed, int a, int b) {
        long h = seed * 0x9E3779B97F4A7C15L + a * 0xC2B2AE3D27D4EB4FL + b * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Mana: the ward's three rhombi and two circles in light over a glass bubble. */
    private static void ward(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        Vec3 core = centre(s).subtract(camera);
        double scale = HiveFormation.wardScale(s.width(), s.height()), turn = s.time() * .008;
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
        // The middle hexagon joining the rhombi's side corners.
        for (int side = 0; side < 6; side++) {
            Vec3 a = core.add(HiveShapes.wardHexagonPoint(side / 6.0, turn).scale(scale));
            Vec3 b = core.add(HiveShapes.wardHexagonPoint((side + 1) / 6.0, turn).scale(scale));
            GlowBrush.beam(glow, m, a, b, .024, color, 130 * pulse);
        }
        GlowBrush.sphere(fill, m, core, 1.45 * scale, 0x0B5E62, color, 10, 80, 16);
    }

    /** Twins: three dense purple collider tori round a black hole with a violet accretion disk. */
    private static void rifts(Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        tori(s, camera, glow, m, GlowBrush.mix(color, 0xE7C6FF, .3), GlowBrush.mix(color, 0xFF7BE5, .25), TWINS_TRACKS, true, 115);
        // In the world the horizon goes in solid and writes depth, so nothing behind it shines through.
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        blackHole(glow, GlowBrush.flat() ? fill : ShieldGlow.horizonConsumer(), m, core, horizon(s), s.time());
    }

    /** How far out a black hole pulls on the world behind it, in horizons: past the edge of its disk. */
    private static final double LENS_REACH = 3.6;
    /** The accretion disk's inner edge and its width, in horizons. */
    private static final double DISK_INNER = 1.35, DISK_SPAN = 1.55;

    /**
     * Queues the pull of each Twins black hole on the world behind it. The renderer draws it before the
     * drones and the light, which stay unbent over it.
     */
    public static void lenses(Scene s, Vec3 camera) {
        if (s.mode() != AttackMode.CONTAINMENT || s.type() != HiveType.TWINS || !s.formed() || GlowBrush.flat()) return;
        for (int index = 0; index < s.engaged(); index++) {
            Scene part = part(s, index);
            double horizon = horizon(part);
            BlackHoleLens.queue(HiveFormation.core(part.target(), part.height()).subtract(camera), horizon, horizon * LENS_REACH);
        }
    }

    /** Centre of the containment construct, resting on the ground under its target. */
    private static Vec3 centre(Scene s) {
        return HiveFormation.containmentCentre(s.type(), s.target(), s.width(), s.height(), s.slots());
    }

    /** The black hole's horizon: wider than the creature it swallows (its disk, rings and lens follow it). */
    private static double horizon(Scene s) {
        return HiveFormation.horizon(s.width(), s.height());
    }

    /**
     * A black hole: a solid black horizon ringed by a thin photon ring, a tilted violet accretion disk
     * turning round it (hot white at its inner edge) whose far side hides behind the horizon, and that far
     * side's lensed image arching round the horizon. Its pull on the world behind it is {@link BlackHoleLens}.
     */
    static void blackHole(VertexConsumer glow, VertexConsumer horizonFill, Matrix4f m, Vec3 core, double horizon, double time) {
        GlowBrush.sphere(horizonFill, m, core, horizon, 0x000000, 0x12031F, 255, 255, 20);
        Vec3 eye = GlowBrush.view(core);
        Vec3 right = eye.cross(Math.abs(eye.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(eye);
        GlowBrush.circle(glow, m, core, right, up, horizon * 1.03, 128, horizon * .02, 0xF2DDFF, 230);
        // The far side of the disk, bent over and under the horizon.
        GlowBrush.circle(glow, m, core, right, up, horizon * 1.22, 128, horizon * .06, 0xA24BFF, 90);
        // The disk itself, tilted and turning: a solid band of light, hot white inside and violet out,
        // brighter where it swings towards the viewer, with denser streams of matter over it.
        double tilt = .62;
        Vec3 u = new Vec3(1, 0, 0), v = new Vec3(0, Math.sin(tilt), Math.cos(tilt));
        int bands = 12, segments = 128;
        for (int band = 0; band < bands; band++) {
            double t0 = band / (double) bands, t1 = (band + 1) / (double) bands;
            double r0 = horizon * (DISK_INNER + DISK_SPAN * t0), r1 = horizon * (DISK_INNER + DISK_SPAN * t1);
            int c0 = GlowBrush.mix(0xFFF0FF, 0x7A22D6, Math.pow(t0, .6)), c1 = GlowBrush.mix(0xFFF0FF, 0x7A22D6, Math.pow(t1, .6));
            for (int step = 0; step < segments; step++) {
                double a0 = Math.PI * 2 * step / segments, a1 = Math.PI * 2 * (step + 1) / segments;
                Vec3 p00 = core.add(u.scale(Math.cos(a0) * r0)).add(v.scale(Math.sin(a0) * r0));
                Vec3 p01 = core.add(u.scale(Math.cos(a1) * r0)).add(v.scale(Math.sin(a1) * r0));
                Vec3 p11 = core.add(u.scale(Math.cos(a1) * r1)).add(v.scale(Math.sin(a1) * r1));
                Vec3 p10 = core.add(u.scale(Math.cos(a0) * r1)).add(v.scale(Math.sin(a0) * r1));
                GlowBrush.quad(glow, m, p00, p01, p11, p10, c0, c0, c1, c1,
                        diskAlpha(t0, a0, time, eye, u, v), diskAlpha(t0, a1, time, eye, u, v), diskAlpha(t1, a1, time, eye, u, v), diskAlpha(t1, a0, time, eye, u, v));
            }
        }
        int rings = 28;
        for (int ring = 0; ring < rings; ring++) {
            double t = ring / (double) (rings - 1);
            double radius = horizon * (DISK_INNER + .05 + (DISK_SPAN - .1) * t);
            int ringColor = GlowBrush.mix(0xFFF0FF, 0x7A22D6, Math.pow(t, .6));
            Vec3 previous = null;
            for (int step = 0; step <= segments; step++) {
                double angle = Math.PI * 2 * step / segments;
                Vec3 point = core.add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
                if (previous != null) {
                    double swirl = .55 + .45 * Math.sin(angle * 3 - time * (.25 - .15 * t) + ring * .7);
                    Vec3 tangent = point.subtract(previous).normalize();
                    double doppler = 1 + .6 * tangent.dot(eye);
                    double alpha = (1 - t) * (1 - t) * 150 * swirl * doppler + 20;
                    GlowBrush.line(glow, m, previous, point, horizon * (.035 + .025 * (1 - t)), ringColor, alpha);
                }
                previous = point;
            }
        }
        // Matter spiralling in.
        for (int mote = 0; mote < 64; mote++) {
            double life = ((time * .02 + mote * .618) % 1);
            double radius = horizon * (DISK_INNER + DISK_SPAN - (DISK_SPAN - .1) * life), angle = mote * 2.4 + time * (.08 + .1 * life);
            Vec3 at = core.add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
            GlowBrush.dot(glow, m, at, horizon * .045, 0xE7C6FF, 160 * Math.sin(Math.PI * life));
        }
    }

    /** Brightness of the disk at radius fraction {@code t} and angle {@code angle}: hot inside, swirling, Doppler-bright on the approaching side. */
    private static double diskAlpha(double t, double angle, double time, Vec3 eye, Vec3 u, Vec3 v) {
        Vec3 tangent = u.scale(-Math.sin(angle)).add(v.scale(Math.cos(angle)));
        double doppler = 1 + .55 * tangent.dot(eye);
        double swirl = .7 + .3 * Math.sin(angle * 4 - time * (.2 - .1 * t) + t * 9);
        double edge = Math.min(1, t * 8) * Math.pow(1 - t, 1.4);
        return 125 * edge * swirl * doppler;
    }

    private static Vec3 spun(double x, double y, double z, double cos, double sin) {
        return new Vec3(x * cos - z * sin, y, x * sin + z * cos);
    }

    private HiveModeVisual() {
    }
}
