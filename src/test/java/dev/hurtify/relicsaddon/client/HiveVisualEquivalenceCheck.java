package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveFormation;
import dev.hurtify.relicsaddon.domain.hive.HiveSlots;
import dev.hurtify.relicsaddon.domain.hive.HiveTarget;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The swarm's light after the geometry reuse must emit exactly the vertices it emitted before: the old code
 * ({@code GlowBrushReference}, {@code HiveModeVisualReference}, {@code HiveConstructVisualReference},
 * {@code HiveJuiceReference}, {@code HiveProjectilesReference}) is kept as the reference and both are run over
 * the same scenes, every family in every mode through its cycle, round one, two and three creatures, after a
 * retarget, from near and far cameras and in the flat gallery, with every kind of blow and shot in flight.
 * Positions and colours must match bit for bit. Also counts allocated bytes and vertices per frame, before and after.
 */
public final class HiveVisualEquivalenceCheck {
    private static final int SLOTS = HiveType.MAX_DEPLOYED, UNITS = HiveType.MAX_DRONES, TRAVEL = 20, INTERVAL = 40;
    private static final double COMBAT_START = 1000, CYCLE_START = COMBAT_START + TRAVEL;
    private static final Vec3 OWNER = new Vec3(-8, 64, 1.5);
    private static final Vec3[] CAMERAS = {new Vec3(-6, 65.5, -4), new Vec3(30, 70, 40), new Vec3(-120, 90, 60)};
    private static final Vec3 FLAT = new Vec3(-Math.cos(.30) * Math.sin(.65), Math.sin(.30), Math.cos(.30) * Math.cos(.65));

    public static void main(String[] args) throws Exception {
        stubMinecraft();
        GlowBrush.setPixelAngle(.0011);
        GlowBrushReference.setPixelAngle(.0011);
        checkProjectileGeometry();
        long brush = compareBrush();
        long scenes = compareScenes();
        long blows = compareBlows();
        System.out.println("Hive visuals: brush identical over " + brush + " vertices, scenes over " + scenes + ", blows and shots over " + blows);
        measure();
    }

    private static void checkProjectileGeometry() {
        // The Twins shot: a glass icosahedron, its twelve corners on its sphere, turning without coming apart.
        for (double spin : new double[]{0, 1.3, 1e4}) {
            for (int corner = 0; corner < 12; corner++) {
                Vec3 at = HiveProjectiles.icosahedronCorner(corner, HiveProjectiles.RADIUS, spin);
                require(Double.isFinite(at.x) && Double.isFinite(at.y) && Double.isFinite(at.z), "icosahedron corner is finite");
                require(Math.abs(at.length() - HiveProjectiles.RADIUS) < 1e-9, "an icosahedron's corners lie on its sphere");
            }
            require(HiveProjectiles.icosahedronFaces().length == 20, "an icosahedron has twenty faces");
        }
    }

    // --- the brush itself -----------------------------------------------------------------------------

    private static long compareBrush() {
        long vertices = 0;
        Matrix4f m = new Matrix4f();
        Vec3[] points = {new Vec3(.4, .2, -3), new Vec3(5, 1, 2), new Vec3(-40, 12, 60), new Vec3(0, 0, 0), new Vec3(.00005, 0, 0),
                new Vec3(.000001, .000002, 0), new Vec3(0, 3, 0), new Vec3(0, -9, .1), new Vec3(2.5, 2.5, 2.5)};
        for (boolean flat : new boolean[]{false, true}) {
            setFlat(flat);
            for (Vec3 a : points) for (Vec3 b : points) {
                Tape before = new Tape(), after = new Tape();
                for (double width : new double[]{.004, .02, .3}) for (double alpha : new double[]{0, .5, 40, 200, 300}) {
                    GlowBrushReference.line(before, m, a, b, width, 0x38E8FF, alpha);
                    GlowBrush.line(after, m, a, b, width, 0x38E8FF, alpha);
                    GlowBrushReference.line(before, m, a, b, width, width * 3, 0x38E8FF, 0xFFB347, alpha, alpha * .3);
                    GlowBrush.line(after, m, a, b, width, width * 3, 0x38E8FF, 0xFFB347, alpha, alpha * .3);
                    GlowBrushReference.beam(before, m, a, b, width, 0xB151FF, alpha);
                    GlowBrush.beam(after, m, a, b, width, 0xB151FF, alpha);
                    GlowBrushReference.dot(before, m, a, width * 4, 0x42E6C8, alpha);
                    GlowBrush.dot(after, m, a, width * 4, 0x42E6C8, alpha);
                    for (int segments : new int[]{3, 5, 8}) {
                        GlowBrushReference.lightning(before, m, a, b, 17 + segments, segments, .2, width, 0xE7C6FF, alpha);
                        GlowBrush.lightning(after, m, a, b, 17 + segments, segments, .2, width, 0xE7C6FF, alpha);
                        GlowBrushReference.lightning(before, m, a, b, 5, segments, .3, width, 0xE7C6FF, alpha, p -> p.y > a.y + .1);
                        GlowBrush.lightning(after, m, a, b, 5, segments, .3, width, 0xE7C6FF, alpha, p -> p.y > a.y + .1);
                    }
                    for (int segments : new int[]{12, 28, 40, 48, 64, 160, 300}) {
                        GlowBrushReference.circle(before, m, a, new Vec3(1, 0, 0), new Vec3(0, 0, 1), width * 20, segments, width, 0xFFFFFF, alpha);
                        GlowBrush.circle(after, m, a, new Vec3(1, 0, 0), new Vec3(0, 0, 1), width * 20, segments, width, 0xFFFFFF, alpha);
                        Vec3 u = b.subtract(a).lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : b.subtract(a).normalize(), v = u.cross(new Vec3(0, 1, 0));
                        GlowBrushReference.circle(before, m, a, u, v, width * 50, segments, width * .5, 0xF2DDFF, alpha);
                        GlowBrush.circle(after, m, a, u, v, width * 50, segments, width * .5, 0xF2DDFF, alpha);
                    }
                    for (int rows : new int[]{1, 6, 32, 70}) {
                        GlowBrushReference.sphere(before, m, a, width * 30, 0x0A0612, 0x3A0F66, alpha, alpha * .3, rows);
                        GlowBrush.sphere(after, m, a, width * 30, 0x0A0612, 0x3A0F66, alpha, alpha * .3, rows);
                    }
                    GlowBrushReference.quad(before, m, a, b, a.add(0, 1, 0), b.add(0, 1, 0), 1, 2, 3, 4, alpha, alpha, 1, 0);
                    GlowBrush.quad(after, m, a, b, a.add(0, 1, 0), b.add(0, 1, 0), 1, 2, 3, 4, alpha, alpha, 1, 0);
                }
                before.requireSame(after, "brush a=" + a + " b=" + b + (flat ? " flat" : " world"));
                vertices += before.count();
            }
        }
        setFlat(false);
        require(vertices > 2_000_000, "Enough brush vertices compared: " + vertices);
        return vertices;
    }

    // --- the figures of each mode ---------------------------------------------------------------------

    /** A swarm of {@code SLOTS} drones round its targets at {@code time}, as the renderer lays it out. */
    private static HiveModeVisual.Scene scene(AttackMode mode, HiveType type, List<HiveTarget> targets, List<HiveTarget> previous, double retargetedAt, double time) {
        int groups = HiveSlots.groups(SLOTS, mode, type);
        int[] members = new int[groups];
        Vec3[] drones = new Vec3[SLOTS];
        int engaged = SwarmMath.engaged(targets.size(), HiveSlots.figures(groups, mode, type));
        for (int slot = 0; slot < SLOTS; slot++) {
            // A few places empty, as when their drones are away.
            if (slot % 23 == 7) continue;
            Vec3 station = SwarmMath.engagedStation(mode, type, slot, SLOTS, OWNER, targets, previous, retargetedAt, time, CYCLE_START, INTERVAL);
            Vec3 at = SwarmMath.deployed(OWNER, -90, station, slot, UNITS, type, time, COMBAT_START, TRAVEL);
            int group = HiveSlots.group(slot, groups);
            if (mode == AttackMode.DROPLET && type == HiveType.TWINS) {
                HiveTarget target = targets.get(group % engaged);
                if (SwarmMath.dropletHidden(type, SwarmMath.sortie(OWNER, McVectors.toMc(targets.getFirst().feet()), McVectors.toMc(target.feet()), target.height(), group, groups,
                        time, CYCLE_START, INTERVAL))) continue;
            }
            members[group]++;
            drones[slot] = at;
        }
        return new HiveModeVisual.Scene(mode, type, SLOTS, groups, members, drones, OWNER, List.copyOf(targets), time, CYCLE_START, INTERVAL,
                time >= COMBAT_START + TRAVEL * .5, null, groups);
    }

    private static long compareScenes() {
        List<HiveTarget> one = List.of(new HiveTarget(1, McVectors.toDomain(new Vec3(0, 64, 0)), .6, 1.8));
        List<HiveTarget> two = List.of(new HiveTarget(1, McVectors.toDomain(new Vec3(0, 64, 0)), .6, 1.8), new HiveTarget(2, McVectors.toDomain(new Vec3(6, 64, -3)), .9, 2.4));
        List<HiveTarget> three = List.of(new HiveTarget(1, McVectors.toDomain(new Vec3(0, 64, 0)), .6, 1.8), new HiveTarget(2, McVectors.toDomain(new Vec3(6, 64, -3)), .9, 2.4),
                new HiveTarget(3, McVectors.toDomain(new Vec3(-2, 65, 7)), 1.4, .9));
        List<HiveTarget> before = List.of(new HiveTarget(4, McVectors.toDomain(new Vec3(3, 64, 5)), .6, 1.8));
        record Lineup(List<HiveTarget> targets, List<HiveTarget> previous, double retargetedAt) { }
        List<Lineup> lineups = List.of(new Lineup(one, List.of(), -1), new Lineup(two, List.of(), -1), new Lineup(three, before, CYCLE_START + 3));
        // Through a strike cycle: waiting, flying out, the blow and the shot, the way home; and the construct closing and locked.
        double[] offsets = {-6, 0, 2.5, 5.25, 12.5, 20.75, 23.5, 24.5, 27.1, 28.6, 30.75, 32.4, 34, 36.3, 39.9, 44.7, 61.5, 83.25};
        long vertices = 0;
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (Lineup lineup : lineups) {
            for (double offset : offsets) {
                double time = CYCLE_START + offset;
                HiveModeVisual.Scene s = scene(mode, type, lineup.targets(), lineup.previous(), lineup.retargetedAt(), time);
                for (int view = -1; view < CAMERAS.length; view++) {
                    boolean flat = view < 0;
                    Vec3 camera = flat ? Vec3.ZERO : CAMERAS[view];
                    setFlat(flat);
                    Matrix4f m = new Matrix4f();
                    Tape glowBefore = new Tape(), fillBefore = new Tape(), glowAfter = new Tape(), fillAfter = new Tape();
                    HiveModeVisualReference.render(s, camera, glowBefore, fillBefore, m);
                    HiveProjectilesReference.flush(camera, glowBefore, fillBefore, m);
                    HiveModeVisual.render(s, camera, glowAfter, fillAfter, m);
                    HiveProjectiles.flush(camera, glowAfter, fillAfter, m);
                    HiveModeVisual.endFrame();
                    String what = mode + " " + type + " targets=" + lineup.targets().size() + " t=" + offset + (flat ? " flat" : " camera=" + camera);
                    glowBefore.requireSame(glowAfter, "scene glow " + what);
                    fillBefore.requireSame(fillAfter, "scene fill " + what);
                    vertices += glowBefore.count() + fillBefore.count();
                }
            }
            // Constructs that came apart burst into their blows, in both worlds alike.
            HiveConstructVisualReference.sweep(CYCLE_START + 200);
            HiveConstructVisual.sweep(CYCLE_START + 200);
        }
        setFlat(false);
        require(vertices > 5_000_000, "Enough scene vertices compared: " + vertices);
        return vertices;
    }

    // --- blows and shots ------------------------------------------------------------------------------

    private static long compareBlows() {
        HiveJuice.clear();
        HiveJuiceReference.clear();
        int[] styles = {HiveJuice.STRIKE, HiveJuice.CHARGE, HiveJuice.ZAP, HiveJuice.SPARK, HiveJuice.GROUNDED, HiveJuice.REFLECTED, HiveJuice.PUFF};
        long key = 1;
        double start = 500;
        for (HiveType type : HiveType.values()) for (int style : styles) for (int k = 0; k < 2; k++) {
            Vec3 at = new Vec3(3 + k * 7 + style, 64.4 + type.ordinal(), -2 + style * 1.5);
            Vec3 normal = k == 0 ? new Vec3(.3, -.8, .5) : new Vec3(0, 1, 0);
            double strength = k == 0 ? 1 : .45;
            HiveJuice.impact(at, normal, type, style, start + k * .5, strength, key);
            HiveJuiceReference.impact(at, normal, type, style, start + k * .5, strength, key);
            key++;
        }
        long vertices = 0;
        for (double age : new double[]{.25, 1.2, 2.5, 4.75, 6.5, 8.9, 12.3, 17, 23.5, 30}) {
            for (int view = -1; view < CAMERAS.length; view++) {
                boolean flat = view < 0;
                Vec3 camera = flat ? Vec3.ZERO : CAMERAS[view];
                setFlat(flat);
                Matrix4f m = new Matrix4f();
                Tape glowBefore = new Tape(), fillBefore = new Tape(), glowAfter = new Tape(), fillAfter = new Tape();
                HiveJuiceReference.render(camera, glowBefore, fillBefore, m, start + age);
                HiveJuice.render(camera, glowAfter, fillAfter, m, start + age);
                // Aim circles closing, and shots of both kinds in flight.
                for (double closing : new double[]{0, .3, .8, 1}) {
                    HiveJuiceReference.aim(new Vec3(2, 64, 3), 1.6, closing, start + age, 0x38E8FF, 0xFFB347, camera, glowBefore, m);
                    HiveJuice.aim(new Vec3(2, 64, 3), 1.6, closing, start + age, 0x38E8FF, 0xFFB347, camera, glowAfter, m);
                }
                for (int shot = 0; shot < 6; shot++) {
                    HiveType type = shot % 2 == 0 ? HiveType.RF : HiveType.TWINS;
                    Vec3 at = new Vec3(1 + shot * 2, 65, 2 - shot), heading = new Vec3(shot - 2, .2 * shot - .5, 3);
                    HiveProjectilesReference.fly(type, at, heading, start + age, 31 + shot);
                    HiveProjectiles.fly(type, at, heading, start + age, 31 + shot);
                }
                HiveProjectilesReference.flush(camera, glowBefore, fillBefore, m);
                HiveProjectiles.flush(camera, glowAfter, fillAfter, m);
                String what = "blows age=" + age + (flat ? " flat" : " camera=" + camera);
                glowBefore.requireSame(glowAfter, what + " glow");
                fillBefore.requireSame(fillAfter, what + " fill");
                vertices += glowBefore.count() + fillBefore.count();
            }
        }
        setFlat(false);
        require(vertices > 500_000, "Enough blow vertices compared: " + vertices);
        return vertices;
    }

    // --- measurement ----------------------------------------------------------------------------------

    private static void measure() {
        List<HiveTarget> three = List.of(new HiveTarget(1, McVectors.toDomain(new Vec3(0, 64, 0)), .6, 1.8), new HiveTarget(2, McVectors.toDomain(new Vec3(6, 64, -3)), .9, 2.4),
                new HiveTarget(3, McVectors.toDomain(new Vec3(-2, 65, 7)), 1.4, .9));
        Matrix4f m = new Matrix4f();
        Vec3 camera = CAMERAS[0];
        record Frame(String name, double offset) { }
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) {
            double offset = switch (mode) {
                case DROPLET -> 27.1;
                case BARRAGE -> 30.75;
                case CONTAINMENT -> 61.5;
            };
            HiveModeVisual.Scene s = scene(mode, type, three, List.of(), -1, CYCLE_START + offset);
            Sink glow = new Sink(), fill = new Sink();
            long[] before = measure(() -> {
                HiveModeVisualReference.render(s, camera, glow, fill, m);
                HiveProjectilesReference.flush(camera, glow, fill, m);
            }, glow, fill);
            long[] after = measure(() -> {
                HiveModeVisual.render(s, camera, glow, fill, m);
                HiveProjectiles.flush(camera, glow, fill, m);
                HiveModeVisual.endFrame();
            }, glow, fill);
            System.out.printf(Locale.ROOT, "%-11s %-5s 250 drones, 3 creatures: vertices/frame %,d -> %,d; allocated bytes/frame %,d -> %,d%n",
                    mode, type, before[0], after[0], before[1], after[1]);
            require(after[0] == before[0], "The same vertex count for " + mode + " " + type);
            require(after[1] * 2 < before[1], "Allocations must at least halve for " + mode + " " + type);
        }
        HiveJuice.clear();
        HiveJuiceReference.clear();
        for (int blow = 0; blow < 48; blow++) {
            HiveType type = HiveType.values()[blow % 3];
            int style = blow % 7;
            Vec3 at = new Vec3(blow * .7, 64.4, blow % 5), normal = new Vec3(.3, -.8, .5);
            HiveJuice.impact(at, normal, type, style, 500, 1, blow);
            HiveJuiceReference.impact(at, normal, type, style, 500, 1, blow);
        }
        Sink glow = new Sink(), fill = new Sink();
        long[] before = measure(() -> HiveJuiceReference.render(camera, glow, fill, m, 506.5), glow, fill);
        long[] after = measure(() -> HiveJuice.render(camera, glow, fill, m, 506.5), glow, fill);
        System.out.printf(Locale.ROOT, "48 blows at 6.5 ticks: vertices/frame %,d -> %,d; allocated bytes/frame %,d -> %,d%n", before[0], after[0], before[1], after[1]);
        require(after[0] == before[0], "The same vertex count for the blows");
        require(after[1] * 2 < before[1], "Blow allocations must at least halve");
        Vec3 core = new Vec3(4, 3, -12);
        before = measure(() -> {
            GlowBrushReference.sphere(fill, m, core, 2.5, 0x000000, 0x0A0214, 255, 255, 32);
            GlowBrushReference.circle(glow, m, core, new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2.55, 160, .3, 0xF2DDFF, 235);
        }, glow, fill);
        after = measure(() -> {
            GlowBrush.sphere(fill, m, core, 2.5, 0x000000, 0x0A0214, 255, 255, 32);
            GlowBrush.circle(glow, m, core, new Vec3(1, 0, 0), new Vec3(0, 1, 0), 2.55, 160, .3, 0xF2DDFF, 235);
        }, glow, fill);
        System.out.printf(Locale.ROOT, "A horizon (sphere of 32 rows, circle of 160): vertices/frame %,d -> %,d; allocated bytes/frame %,d -> %,d%n",
                before[0], after[0], before[1], after[1]);
        require(after[1] * 10 < before[1], "Horizon allocations must drop by 90%");
    }

    /** Runs {@code frame} warmed up and returns {vertices, allocated bytes} of one run. */
    private static long[] measure(Runnable frame, Sink glow, Sink fill) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        for (int k = 0; k < 60; k++) frame.run();
        long bytes = Long.MAX_VALUE, vertices = 0;
        for (int k = 0; k < 5; k++) {
            glow.count = 0;
            fill.count = 0;
            long start = threads.getThreadAllocatedBytes(id);
            frame.run();
            bytes = Math.min(bytes, threads.getThreadAllocatedBytes(id) - start);
            vertices = glow.count + fill.count;
        }
        return new long[]{vertices, bytes};
    }

    // --- plumbing -------------------------------------------------------------------------------------

    private static void setFlat(boolean flat) {
        GlowBrush.setFlatView(flat ? FLAT : null);
        GlowBrushReference.setFlatView(flat ? FLAT : null);
    }

    /**
     * An empty {@code Minecraft}: no level and no player, so sounds, ground probes and lights the figures ask for
     * answer as they do before a world is loaded, and the drawing runs without a game.
     */
    private static void stubMinecraft() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        Object minecraft = unsafe.allocateInstance(net.minecraft.client.Minecraft.class);
        var instance = net.minecraft.client.Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, minecraft);
        // No shader pack either: the figures' lenses ask whether one has the frame, and the mod list is not up.
        var iris = ShieldRefraction.class.getDeclaredField("irisPresent");
        iris.setAccessible(true);
        iris.set(null, Boolean.FALSE);
    }

    /** Counts vertices and nothing more. */
    private static final class Sink implements VertexConsumer {
        long count;

        @Override public VertexConsumer addVertex(float x, float y, float z) { count++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    /** Records every vertex's position and colour, in order, so two renderings can be compared bit for bit. */
    private static final class Tape implements VertexConsumer {
        private int[] bits = new int[4 * 4096];
        private int count;

        int count() {
            return count;
        }

        void requireSame(Tape other, String what) {
            require(count == other.count, what + ": vertex count " + count + " vs " + other.count);
            for (int i = 0; i < count; i++) for (int part = 0; part < 4; part++) {
                int a = bits[i * 4 + part], b = other.bits[i * 4 + part];
                if (a == b) continue;
                // A zero of either sign is the same point.
                if (part < 3 && Float.intBitsToFloat(a) == 0 && Float.intBitsToFloat(b) == 0) continue;
                require(false, what + ": vertex " + i + (part < 3 ? " axis " + part + " " + Float.intBitsToFloat(a) + " vs " + Float.intBitsToFloat(b)
                        : " colour " + Integer.toHexString(a) + " vs " + Integer.toHexString(b)));
            }
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (count * 4 == bits.length) bits = java.util.Arrays.copyOf(bits, bits.length * 2);
            bits[count * 4] = Float.floatToRawIntBits(x);
            bits[count * 4 + 1] = Float.floatToRawIntBits(y);
            bits[count * 4 + 2] = Float.floatToRawIntBits(z);
            bits[count * 4 + 3] = 0;
            count++;
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            bits[(count - 1) * 4 + 3] = a << 24 | r << 16 | g << 8 | b;
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private HiveVisualEquivalenceCheck() { }
}
