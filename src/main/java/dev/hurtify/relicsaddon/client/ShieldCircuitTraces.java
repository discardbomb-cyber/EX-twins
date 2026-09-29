package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Violet circuit-board traces that race outward from each absorbed hit on the Twins shield:
 * straight runs on a 45-degree grid with turns, forks and square pads, grown over the shell
 * and lit by a signal pulse running along them.
 *
 * <p>Traces are generated in a flat tangent map around the hit (angular units, so they are
 * independent of the shield radius), then wrapped onto the sphere. Each hit gets its own
 * deterministic pattern from its time and direction.
 */
final class ShieldCircuitTraces {
    private static final double STEP = .07, MAX_REACH = 1.15, GROWTH = .06;
    private static final int CACHE = 64;
    private static final Map<Long, Pattern> PATTERNS = new LinkedHashMap<>(CACHE, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Pattern> eldest) { return size() > CACHE; }
    };

    private record Segment(double u0, double v0, double u1, double v1, double s0, double s1) { }
    private record Pad(double u, double v, double s, double size) { }
    private record Pattern(List<Segment> segments, List<Pad> pads) { }

    static void render(VertexConsumer core, VertexConsumer glow, Matrix4f matrix, double x, double y, double z,
            double radius, List<ShieldImpact> impacts, double time, boolean low, boolean inside) {
        double view = inside ? ShieldSurfaceLighting.INSIDE : 1;
        for (ShieldImpact impact : impacts) {
            double age = time - impact.gameTime();
            if (impact.absorbed() <= 0 || age < 0 || age >= ShieldResponse.IMPACT_TICKS) continue;
            Pattern pattern = PATTERNS.computeIfAbsent(seed(impact), ShieldCircuitTraces::generate);
            double reach = age * GROWTH;
            double fade = (age < 10 ? 1 : ShieldField.fade(age - 10, ShieldResponse.IMPACT_TICKS - 10)) * view;
            Vec3 n = impact.normal();
            Vec3 t1 = n.cross(Math.abs(n.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
            Vec3 t2 = n.cross(t1).normalize();
            Frame frame = new Frame(n, t1, t2, x, y, z, radius);
            for (Segment segment : pattern.segments) {
                if (segment.s0 >= reach) continue;
                double visible = Math.min(1, (reach - segment.s0) / (segment.s1 - segment.s0));
                double u1 = segment.u0 + (segment.u1 - segment.u0) * visible, v1 = segment.v0 + (segment.v1 - segment.v0) * visible;
                // A bright signal runs along the growing edge of every trace.
                double signal = Math.max(0, 1 - Math.abs(reach - segment.s1) / .12);
                int coreAlpha = (int) Math.clamp((150 + 105 * signal) * fade, 0, 255);
                int glowAlpha = (int) Math.clamp((70 + 90 * signal) * fade, 0, 255);
                frame.strip(core, matrix, segment.u0, segment.v0, u1, v1, .0055, 236, 196, 255, coreAlpha);
                if (!low) frame.strip(glow, matrix, segment.u0, segment.v0, u1, v1, .02, 150, 60, 255, glowAlpha);
            }
            for (Pad pad : pattern.pads) {
                if (pad.s > reach) continue;
                double pop = Math.min(1, (reach - pad.s) / .05);
                int alpha = (int) Math.clamp(230 * fade * pop, 0, 255);
                frame.pad(core, matrix, pad.u, pad.v, pad.size * pop, 244, 214, 255, alpha);
                if (!low) frame.pad(glow, matrix, pad.u, pad.v, pad.size * 2.6 * pop, 150, 60, 255, alpha / 2);
            }
        }
    }

    private static long seed(ShieldImpact impact) {
        Vec3 n = impact.normal();
        return impact.gameTime() * 0x9E3779B97F4A7C15L ^ Double.doubleToLongBits(n.x * 31 + n.y * 17 + n.z * 7);
    }

    private static Pattern generate(long seed) {
        Random random = new Random(seed);
        List<Segment> segments = new ArrayList<>();
        List<Pad> pads = new ArrayList<>();
        int traces = 7 + random.nextInt(4);
        int firstDirection = random.nextInt(8);
        for (int trace = 0; trace < traces; trace++) {
            // Spread the roots evenly around the hit, then let each wander.
            int direction = (firstDirection + trace * 8 / traces) % 8;
            double[] start = offset(0, 0, direction, .035);
            grow(random, segments, pads, start[0], start[1], direction, 4 + random.nextInt(7), .035, 0);
        }
        return new Pattern(List.copyOf(segments), List.copyOf(pads));
    }

    private static void grow(Random random, List<Segment> segments, List<Pad> pads, double u, double v, int direction,
            int steps, double s, int depth) {
        for (int step = 0; step < steps; step++) {
            double roll = random.nextDouble();
            if (roll < .16) direction = (direction + 1) % 8;
            else if (roll < .32) direction = (direction + 7) % 8;
            double length = STEP * (.6 + random.nextDouble() * .9) * (direction % 2 == 1 ? 1.2 : 1);
            double[] next = offset(u, v, direction, length);
            if (Math.hypot(next[0], next[1]) > MAX_REACH) break;
            segments.add(new Segment(u, v, next[0], next[1], s, s + length));
            u = next[0];
            v = next[1];
            s += length;
            if (depth < 2 && random.nextDouble() < .22) {
                pads.add(new Pad(u, v, s, .009));
                grow(random, segments, pads, u, v, (direction + (random.nextBoolean() ? 2 : 6)) % 8, Math.max(2, steps / 2), s, depth + 1);
            }
        }
        pads.add(new Pad(u, v, s, .014));
    }

    private static double[] offset(double u, double v, int direction, double length) {
        double angle = direction * Math.PI / 4;
        return new double[]{u + Math.cos(angle) * length, v + Math.sin(angle) * length};
    }

    /** Wraps the flat tangent map around the hit direction onto the (rippling) shell. */
    private record Frame(Vec3 n, Vec3 t1, Vec3 t2, double x, double y, double z, double radius) {
        Vec3 direction(double u, double v) {
            double distance = Math.hypot(u, v);
            if (distance < 1e-6) return n;
            double sin = Math.sin(distance) / distance;
            return n.scale(Math.cos(distance)).add(t1.scale(u * sin)).add(t2.scale(v * sin));
        }

        Vec3 surface(Vec3 direction, double lift) {
            double bent = radius * (1.014 + lift) * ShieldRipple.scale(direction.x, direction.y, direction.z);
            return new Vec3(x + direction.x * bent, y + direction.y * bent, z + direction.z * bent);
        }

        void strip(VertexConsumer consumer, Matrix4f matrix, double u0, double v0, double u1, double v1, double width,
                int r, int g, int b, int alpha) {
            if (alpha <= 0) return;
            double du = u1 - u0, dv = v1 - v0, length = Math.hypot(du, dv);
            if (length < 1e-5) return;
            double su = -dv / length * width, sv = du / length * width;
            Vec3 leftStart = surface(direction(u0 + su, v0 + sv), 0), leftEnd = surface(direction(u1 + su, v1 + sv), 0);
            Vec3 rightStart = surface(direction(u0 - su, v0 - sv), 0), rightEnd = surface(direction(u1 - su, v1 - sv), 0);
            quad(consumer, matrix, leftStart, rightStart, rightEnd, leftEnd, r, g, b, alpha);
        }

        void pad(VertexConsumer consumer, Matrix4f matrix, double u, double v, double size, int r, int g, int b, int alpha) {
            if (alpha <= 0 || size <= 0) return;
            Vec3 a = surface(direction(u - size, v - size), .001), bb = surface(direction(u + size, v - size), .001);
            Vec3 c = surface(direction(u + size, v + size), .001), d = surface(direction(u - size, v + size), .001);
            quad(consumer, matrix, a, bb, c, d, r, g, b, alpha);
        }

        private static void quad(VertexConsumer consumer, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                int red, int green, int blue, int alpha) {
            for (Vec3 p : new Vec3[]{a, b, c, a, c, d}) {
                consumer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(red, green, blue, alpha);
            }
        }
    }

    private ShieldCircuitTraces() {
    }
}
