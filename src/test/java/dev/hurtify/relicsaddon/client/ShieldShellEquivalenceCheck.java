package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The shield shells after the wave/geometry reuse must emit exactly the vertices they emitted before:
 * the old, per-vertex code is kept here as the reference and both are run over the same shells,
 * waves and cameras. Also counts wave-profile evaluations and allocated bytes per frame, before and after.
 */
public final class ShieldShellEquivalenceCheck {
    private static final Vec3 HIT = new Vec3(.35, .1, .93).normalize();
    private static final Vec3 OUTSIDE = new Vec3(-Math.sin(.20), 0, Math.cos(.20));
    private static final Vec3 ABOVE = new Vec3(.2, .9, -.3).normalize();

    public static void main(String[] args) {
        List<ShieldImpact> none = List.of();
        List<ShieldImpact> one = List.of(new ShieldImpact(HIT, 252, 0, 6, false));
        List<ShieldImpact> two = List.of(new ShieldImpact(HIT, 252, 0, 6, false), new ShieldImpact(new Vec3(-.8, .45, .5).normalize(), 265, 0, 6, false));
        var twelve = new ArrayList<ShieldImpact>();
        for (int k = 0; k < 12; k++) {
            twelve.add(new ShieldImpact(new Vec3(Math.sin(k * 2.1), Math.cos(k * 1.3) * .6, Math.cos(k * 2.1)).normalize(), 240 + k * 2, 0, 3 + k, k % 5 == 0));
        }
        long checked = 0;
        for (Vec3 eye : new Vec3[]{OUTSIDE, ABOVE, Vec3.ZERO}) for (boolean low : new boolean[]{false, true}) {
            for (List<ShieldImpact> impacts : List.of(none, one, two, twelve)) for (double time : new double[]{240, 255.4, 270.25, 300}) {
                for (RelicRole role : new RelicRole[]{RelicRole.MANA_SHIELD, RelicRole.TWINS_SHIELD, RelicRole.RF_SHIELD}) {
                    checked += compareHalo(role, low, impacts, time, eye);
                }
            }
        }
        require(checked > 1_000_000, "Enough halo vertices compared: " + checked);
        long shells = compareShells(none, one, two, twelve);
        measureHalo(twelve);
        measureShells(twelve);
        System.out.println("Shield shells: halo identical over " + checked + " vertices, shells over " + shells
                + " (waves, hits, threats, holes, gathering, inside/outside, low/high)");
    }

    // --- shells (dome, honeycomb, light) --------------------------------------------------------------

    private static long compareShells(List<ShieldImpact> none, List<ShieldImpact> one, List<ShieldImpact> two, List<ShieldImpact> twelve) {
        int struck = ShieldTopology.INSTANCE.nearest(-HIT.x, HIT.y, HIT.z);
        List<ShieldImpact> broken = List.of(new ShieldImpact(HIT, 250, 0, 6, true, List.of(struck)));
        ShieldStackState whole = ShieldStackState.DEFAULT;
        ShieldStackState hit = whole.damageCell(struck, 12, 6, 250);
        var health = new ArrayList<>(whole.cells());
        for (int id = 0; id < ShieldTopology.CELL_COUNT; id++) health.set(id, id * 17 % ShieldTopology.CELL_COUNT < 210 ? (id % 3 == 0 ? 3 : 12) : 0);
        ShieldStackState holes = whole.withCellsAndBuffer(health, 0, List.of(), -1);
        ShieldStackState gathering = ShieldCellDefense.gather(holes.damageCell(struck, 12, 0, 0), struck, 3, 248);
        require(gathering.gathering(252), "A gathering state is needed to cover moving cells");
        List<ShieldResponse.Threat> noThreat = List.of();
        List<ShieldResponse.Threat> oneThreat = List.of(new ShieldResponse.Threat(new Vec3(.85, .15, .5).normalize(), 1.5));
        List<ShieldResponse.Threat> everyThreat = java.util.Arrays.stream(ShieldTopology.INSTANCE.cells())
                .map(cell -> new ShieldResponse.Threat(new Vec3(-cell.center()[0], cell.center()[1], cell.center()[2]), 0)).toList();
        record Scene(ShieldStackState state, List<ShieldImpact> impacts, List<ShieldResponse.Threat> threats, double time) { }
        List<Scene> scenes = List.of(
                new Scene(whole, none, noThreat, 100),
                new Scene(whole, none, oneThreat, 100.5),
                new Scene(whole, one, noThreat, 255.4),
                new Scene(whole, two, everyThreat, 270.25),
                new Scene(hit, broken, noThreat, 252.75),
                new Scene(holes, twelve, oneThreat, 270.25),
                new Scene(gathering, one, noThreat, 252),
                new Scene(holes, none, noThreat, 300));
        long vertices = 0;
        ShieldShellVisual.setPixelAngle(.0011);
        ShieldShellVisualReference.setPixelAngle(.0011);
        for (Scene scene : scenes) for (Vec3 eye : new Vec3[]{OUTSIDE, ABOVE, Vec3.ZERO}) for (boolean perspective : new boolean[]{false, true}) {
            for (boolean low : new boolean[]{false, true}) for (RelicRole role : new RelicRole[]{RelicRole.RF_SHIELD, RelicRole.MANA_SHIELD, RelicRole.TWINS_SHIELD}) {
                double x = perspective ? 1.5 : 0, y = perspective ? .9 : 0, z = perspective ? -3.2 : 0;
                double fx = .6, fz = .8, radius = perspective ? 2.4 : 2.0, presence = .7;
                Matrix4f matrix = new Matrix4f();
                var fillBefore = new Recorder();
                var glowBefore = new Recorder();
                var fillAfter = new Recorder();
                var glowAfter = new Recorder();
                boolean rippling = role != RelicRole.RF_SHIELD;
                if (rippling) ShieldRipple.begin(scene.impacts(), scene.time(), ShieldRipple.roleScale(role), 1.0);
                try {
                    ShieldShellVisualReference.render(role, fillBefore, glowBefore,
                            new ShieldShellVisualReference.Frame(matrix, x, y, z, radius, fx, fz, eye, perspective),
                            scene.state(), scene.impacts(), scene.threats(), scene.time(), presence, low);
                    ShieldShellVisual.render(role, fillAfter, glowAfter,
                            new ShieldShellVisual.Frame(matrix, x, y, z, radius, fx, fz, eye, perspective),
                            scene.state(), scene.impacts(), scene.threats(), scene.time(), presence, low);
                } finally {
                    ShieldRipple.end();
                }
                String what = role + (low ? " low" : " high") + " t=" + scene.time() + " waves=" + scene.impacts().size()
                        + " threats=" + scene.threats().size() + " eye=" + eye + (perspective ? " world" : " gallery");
                fillBefore.requireSame(fillAfter, "shell fill " + what);
                glowBefore.requireSame(glowAfter, "shell glow " + what);
                require(fillBefore.count() > 0 && glowBefore.count() > 0, "Both layers drawn: " + what);
                vertices += fillBefore.count() + glowBefore.count();
            }
        }
        return vertices;
    }

    private static void measureShells(List<ShieldImpact> twelve) {
        List<ShieldResponse.Threat> threat = List.of(new ShieldResponse.Threat(new Vec3(.85, .15, .5).normalize(), 1.5));
        var fill = new Recorder();
        var glow = new Recorder();
        Matrix4f matrix = new Matrix4f();
        for (RelicRole role : new RelicRole[]{RelicRole.MANA_SHIELD, RelicRole.TWINS_SHIELD, RelicRole.RF_SHIELD}) {
            boolean rippling = role != RelicRole.RF_SHIELD;
            long[] before = measure(() -> {
                if (rippling) {
                    ShieldRipple.begin(twelve, 270.25, ShieldRipple.roleScale(role), 1.0);
                    require(ShieldRipple.waves() == 12, "All twelve waves active");
                }
                ShieldShellVisualReference.render(role, fill, glow, new ShieldShellVisualReference.Frame(matrix, 1.5, .9, -3.2, 2.4, .6, .8, OUTSIDE, true),
                        ShieldStackState.DEFAULT, twelve, threat, 270.25, .8, false);
                ShieldRipple.end();
            });
            long[] after = measure(() -> {
                if (rippling) {
                    ShieldRipple.begin(twelve, 270.25, ShieldRipple.roleScale(role), 1.0);
                    require(ShieldRipple.waves() == 12, "All twelve waves active");
                }
                ShieldShellVisual.render(role, fill, glow, new ShieldShellVisual.Frame(matrix, 1.5, .9, -3.2, 2.4, .6, .8, OUTSIDE, true),
                        ShieldStackState.DEFAULT, twelve, threat, 270.25, .8, false);
                ShieldRipple.end();
            });
            System.out.printf(Locale.ROOT, "%s shell, 12 waves, high detail: wave profiles/frame %,d -> %,d; allocated bytes/frame %,d -> %,d%n",
                    role, before[0], after[0], before[1], after[1]);
            if (rippling) require(after[0] * 3 < before[0] * 2, "Dome wave evaluations must drop by a third at least for " + role);
            require(after[1] * 4 < before[1], "Shell allocations must drop by three quarters at least for " + role);
        }
    }

    // --- halo ---------------------------------------------------------------------------------------

    private static long compareHalo(RelicRole role, boolean low, List<ShieldImpact> impacts, double time, Vec3 eye) {
        double activity = .8, radius = 2.0;
        var before = new Recorder();
        var after = new Recorder();
        Matrix4f matrix = new Matrix4f();
        ShieldRipple.begin(impacts, time, ShieldRipple.roleScale(role), 1.0);
        try {
            referenceHalo(before, matrix, role, .3, .92, -.1, radius, activity, impacts, time, eye, low);
            (low ? ShieldHalo.LOW : ShieldHalo.HIGH).render(after, matrix, color(role), .3, .92, -.1, radius * 1.018, activity, impacts, time, eye);
        } finally {
            ShieldRipple.end();
        }
        before.requireSame(after, "halo " + role + (low ? " low" : " high") + " waves=" + impacts.size() + " t=" + time + " eye=" + eye);
        return before.count();
    }

    private static void measureHalo(List<ShieldImpact> twelve) {
        var sink = new Recorder();
        Matrix4f matrix = new Matrix4f();
        long[] before = measure(() -> {
            ShieldRipple.begin(twelve, 270.25, 1, 1.0);
            require(ShieldRipple.waves() == 12, "All twelve waves active");
            referenceHalo(sink, matrix, RelicRole.MANA_SHIELD, 0, 0, 0, 2, .8, twelve, 270.25, OUTSIDE, false);
            ShieldRipple.end();
        });
        long[] after = measure(() -> {
            ShieldRipple.begin(twelve, 270.25, 1, 1.0);
            require(ShieldRipple.waves() == 12, "All twelve waves active");
            ShieldHalo.HIGH.render(sink, matrix, color(RelicRole.MANA_SHIELD), 0, 0, 0, 2 * 1.018, .8, twelve, 270.25, OUTSIDE);
            ShieldRipple.end();
        });
        System.out.printf(Locale.ROOT, "Halo, 12 waves, high detail: wave profiles/frame %,d -> %,d; allocated bytes/frame %,d -> %,d%n",
                before[0], after[0], before[1], after[1]);
        require(after[0] * 10 < before[0], "Halo wave evaluations must drop by at least 90%");
    }

    /** Runs {@code frame} warmed up and returns {wave profile evaluations, allocated bytes} of one run. */
    private static long[] measure(Runnable frame) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        for (int k = 0; k < 40; k++) frame.run();
        long bytes = Long.MAX_VALUE, waves = 0;
        for (int k = 0; k < 5; k++) {
            ShieldRipple.resetEvaluations();
            long start = threads.getThreadAllocatedBytes(id);
            frame.run();
            bytes = Math.min(bytes, threads.getThreadAllocatedBytes(id) - start);
            waves = ShieldRipple.evaluations();
        }
        return new long[]{waves, bytes};
    }

    private static final Vec3[][] LOW_SPHERE = sphere(16), HIGH_SPHERE = sphere(32);

    /** ShieldGlow.halo as it was before the halo grid: per emitted vertex, waves summed twice. */
    static void referenceHalo(VertexConsumer consumer, Matrix4f matrix, RelicRole role, double x, double y, double z, double radius, double activity,
            List<ShieldImpact> impacts, double time, Vec3 eye, boolean low) {
        if (activity <= .01) return;
        int color = color(role);
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        double shell = radius * 1.018;
        boolean inside = ShieldSurfaceLighting.inside(eye);
        for (Vec3[] triangle : low ? LOW_SPHERE : HIGH_SPHERE) {
            for (Vec3 n : triangle) {
                double rim = eye.lengthSqr() < .01 ? .22 : Math.pow(1 - Math.abs(n.dot(eye)), 2.4);
                double hit = 0;
                for (ShieldImpact impact : impacts) {
                    double age = time - impact.gameTime();
                    if (age < 0 || age >= ShieldResponse.IMPACT_TICKS || impact.absorbed() <= 0) continue;
                    hit = Math.max(hit, ShieldField.focus(n.dot(impact.normal()), .22) * ShieldField.fade(age, 18));
                }
                double ripple = ShieldRipple.active() ? Math.max(0, ShieldRipple.height(n.x, n.y, n.z)) : 0;
                double light = inside ? hit * .6 : activity * (rim * .55 + .05) + hit * .95 + ripple * .6;
                int alpha = (int) Math.clamp(light * 150 * ShieldSurfaceLighting.visibility(n.x, n.y, n.z, eye), 0, 220);
                double bent = shell * ShieldRipple.scale(n.x, n.y, n.z);
                consumer.addVertex(matrix, (float) (x + n.x * bent), (float) (y + n.y * bent), (float) (z + n.z * bent))
                        .setColor(r, g, b, alpha);
            }
        }
    }

    private static Vec3[][] sphere(int rows) {
        int columns = rows * 2;
        var triangles = new ArrayList<Vec3[]>(rows * columns * 2);
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            Vec3 a = point(Math.PI * row / rows, Math.PI * 2 * column / columns);
            Vec3 b = point(Math.PI * (row + 1) / rows, Math.PI * 2 * column / columns);
            Vec3 c = point(Math.PI * (row + 1) / rows, Math.PI * 2 * (column + 1) / columns);
            Vec3 d = point(Math.PI * row / rows, Math.PI * 2 * (column + 1) / columns);
            if (row > 0) triangles.add(new Vec3[]{a, c, d});
            if (row + 1 < rows) triangles.add(new Vec3[]{a, b, c});
        }
        return triangles.toArray(Vec3[][]::new);
    }

    private static Vec3 point(double theta, double phi) {
        return new Vec3(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
    }

    // --- recording consumer -------------------------------------------------------------------------

    /** Records every vertex's position and colour, in order, so two renderings can be compared exactly. */
    static final class Recorder implements VertexConsumer {
        private float[] xyz = new float[3 * 4096];
        private int[] rgba = new int[4096];
        private int count;

        int count() {
            return count;
        }

        void reset() {
            count = 0;
        }

        void requireSame(Recorder other, String what) {
            require(count == other.count, what + ": vertex count " + count + " vs " + other.count);
            for (int i = 0; i < count; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float a = xyz[i * 3 + axis], b = other.xyz[i * 3 + axis];
                    require(Float.floatToIntBits(a) == Float.floatToIntBits(b) || Math.abs(a - b) <= 1e-6,
                            what + ": vertex " + i + " axis " + axis + " " + a + " vs " + b);
                }
                require(rgba[i] == other.rgba[i], what + ": vertex " + i + " colour " + Integer.toHexString(rgba[i]) + " vs " + Integer.toHexString(other.rgba[i]));
            }
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (count == rgba.length) {
                xyz = java.util.Arrays.copyOf(xyz, xyz.length * 2);
                rgba = java.util.Arrays.copyOf(rgba, rgba.length * 2);
            }
            xyz[count * 3] = x;
            xyz[count * 3 + 1] = y;
            xyz[count * 3 + 2] = z;
            rgba[count] = 0;
            count++;
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            rgba[count - 1] = a << 24 | r << 16 | g << 8 | b;
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    /** ShieldGlow.color, repeated here so the check never initialises that class (its render types need the game). */
    private static int color(RelicRole role) {
        return switch (role) {
            case MANA_SHIELD -> 0x57D6F2;
            case TWINS_SHIELD -> 0xB25CFF;
            default -> 0x2FD3E6;
        };
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
