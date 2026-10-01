package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The Eclipse scythe as it is drawn: folded, a technological hilt and nothing more; open, a long dark glass shaft
 * with its circuit lines lit, and a curved blade of dark glass with a bright edge, drawn out further in Overdrive.
 * Behind a swinging blade runs its jet-black aura: a ribbon of dense dark energy with a violet rim, from where the
 * blade was a few frames ago to where it is.
 */
final class EclipseScytheVisual {
    private static final int VIOLET = NoctisFx.VIOLET, BRIGHT = NoctisFx.BRIGHT, DEEP = NoctisFx.DEEP, INK = NoctisFx.INK, WHITE = NoctisFx.WHITE;
    /** The shaft's length, the hilt's (all that shows folded), and the blade's reach from the shaft's head. */
    static final double SHAFT = 1.9, HILT = .55, BLADE = 1.15;

    /** Where the blade's edge was one frame: its heel at the shaft and its tip, in the world. */
    private record Edge(Vec3 heel, Vec3 tip, double time) { }

    private static final Map<Integer, Deque<Edge>> TRAILS = new HashMap<>();
    private static final int TRAIL = 14;

    /**
     * The scythe with its base at {@code base}, its shaft up {@code up} and its blade out along {@code side}
     * (camera-space unit vectors), {@code open} of the way open (0 folded, 1 open), {@code overdrive} of the way
     * into Overdrive, its core {@code fullness} full. Returns the blade's heel and tip, for the aura.
     */
    static Vec3[] draw(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 base, Vec3 up, Vec3 side, double open, double overdrive,
            double fullness, double time, double alpha) {
        Vec3 forward = up.cross(side).normalize();
        // The hilt: a thicker prism with three bright bands, the grip of a thing that hides what it is.
        Vec3 hiltTop = base.add(up.scale(HILT));
        prism(glow, fill, m, base, hiltTop, up, side, forward, .05, 8, alpha, .2 + .3 * open);
        for (int band = 0; band < 3; band++) {
            Vec3 at = base.add(up.scale(.12 + band * .17));
            GlowBrush.circle(glow, m, at, side, forward, .055, 12, .008, open > .5 ? BRIGHT : VIOLET, (120 + 100 * open) * alpha);
        }
        if (open <= 0) {
            GlowBrush.dot(glow, m, hiltTop, .08, VIOLET, 60 * alpha);
            return new Vec3[]{hiltTop, hiltTop};
        }
        // The shaft, growing out of the hilt as it opens; its circuit lines run its length and race in Overdrive.
        double length = HILT + (SHAFT - HILT) * open;
        Vec3 head = base.add(up.scale(length));
        prism(glow, fill, m, hiltTop, head, up, side, forward, .032, 6, alpha, .5);
        double pulse = overdrive > 0 ? .5 + .5 * Math.sin(time * 2.4) : .5 + .5 * Math.sin(time * .25);
        int lines = 2 + (int) Math.round(fullness * 3);
        for (int line = 0; line < lines; line++) {
            double a = Math.PI * 2 * line / lines + time * .02;
            Vec3 off = side.scale(Math.cos(a) * .034).add(forward.scale(Math.sin(a) * .034));
            // Each line is lit over a stretch that travels up the shaft, the brighter the fuller the core.
            double from = ((time * (.02 + .1 * overdrive) + line * .3) % 1) * length, to = Math.min(length, from + .35 + .5 * fullness);
            GlowBrush.line(glow, m, hiltTop.add(off).add(up.scale(Math.max(0, from - HILT))), hiltTop.add(off).add(up.scale(Math.max(0, to - HILT))), .006,
                    overdrive > 0 && pulse > .5 ? WHITE : BRIGHT, (90 + 120 * fullness + 60 * overdrive * pulse) * alpha);
        }
        GlowBrush.line(glow, m, hiltTop, head, .012, .02, DEEP, VIOLET, 40 * alpha, (110 + 80 * fullness) * alpha);
        // The blade: an arc from the shaft's head out along the side and curving back down, wider in Overdrive.
        double reach = BLADE * (.4 + .6 * open) * (1 + .5 * overdrive), width = .3 * (1 + .3 * overdrive);
        int segments = 14;
        Vec3[] inner = new Vec3[segments + 1], outer = new Vec3[segments + 1];
        Vec3 centre = head.subtract(up.scale(reach * .7));
        for (int segment = 0; segment <= segments; segment++) {
            double k = segment / (double) segments, a = Math.PI * .5 + Math.PI * .55 * k;
            // The edge curves from the head out and down; the body tapers to the tip.
            Vec3 edge = centre.add(up.scale(Math.sin(a) * reach * .7)).add(side.scale(Math.cos(a) * reach));
            double thickness = width * Math.sin(Math.PI * Math.min(1, k * 1.3)) * (1 - k * .4) + .02;
            // The body lies inside the curve: in from the edge towards its centre.
            Vec3 in = edge.add(centre.subtract(edge).normalize().scale(thickness));
            outer[segment] = edge;
            inner[segment] = in;
        }
        for (int segment = 0; segment < segments; segment++) {
            face(fill, m, inner[segment], inner[segment + 1], outer[segment + 1], outer[segment], alpha * (.6 + .4 * open));
            GlowBrush.line(glow, m, outer[segment], outer[segment + 1], .009 + .004 * overdrive, overdrive > 0 && pulse > .5 ? WHITE : BRIGHT, 230 * alpha);
            GlowBrush.line(glow, m, inner[segment], inner[segment + 1], .005, VIOLET, 150 * alpha);
            if (overdrive > 0) GlowBrush.line(glow, m, outer[segment], outer[segment + 1], .05, VIOLET, 90 * overdrive * alpha);
        }
        GlowBrush.dot(glow, m, head, .12 + .05 * pulse, VIOLET, 120 * alpha);
        GlowBrush.dot(glow, m, outer[segments], .06, WHITE, 180 * alpha);
        return new Vec3[]{outer[0], outer[segments]};
    }

    /** A prism of dark glass from {@code a} to {@code b}, {@code sides} round, its edges lit by {@code lit}. */
    static void prism(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 a, Vec3 b, Vec3 up, Vec3 u, Vec3 v, double radius, int sides, double alpha, double lit) {
        for (int side = 0; side < sides; side++) {
            double p = Math.PI * 2 * side / sides, q = Math.PI * 2 * (side + 1) / sides;
            Vec3 ra = u.scale(Math.cos(p) * radius).add(v.scale(Math.sin(p) * radius)), rb = u.scale(Math.cos(q) * radius).add(v.scale(Math.sin(q) * radius));
            face(fill, m, a.add(ra), a.add(rb), b.add(rb), b.add(ra), alpha);
            GlowBrush.line(glow, m, a.add(ra), b.add(ra), .005, BRIGHT, 200 * lit * alpha);
        }
    }

    /** A glass face, darker face-on and brighter edge-on, as the swarms' glass is. */
    static void face(VertexConsumer fill, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, double alpha) {
        Vec3 normal = b.subtract(a).cross(c.subtract(a));
        double edgeOn = normal.lengthSqr() < 1e-14 ? 0 : 1 - Math.abs(normal.normalize().dot(GlowBrush.view(a)));
        int tint = GlowBrush.mix(DEEP, VIOLET, .2 + .6 * edgeOn);
        double body = (130 + 90 * edgeOn) * alpha;
        GlowBrush.quad(fill, m, a, b, c, d, tint, tint, tint, tint, body, body, body, body);
    }

    /** Notes where the swinging blade of {@code holder} is this frame (in the world), for its aura. */
    static void swung(int holder, Vec3 heel, Vec3 tip, double time) {
        Deque<Edge> trail = TRAILS.computeIfAbsent(holder, ignored -> new ArrayDeque<>());
        Edge last = trail.peekLast();
        if (last != null && last.tip.distanceToSqr(tip) < 1e-4) return;
        trail.addLast(new Edge(heel, tip, time));
        while (trail.size() > TRAIL) trail.removeFirst();
    }

    /** The jet-black aura behind every blade that swung lately: dense dark energy with a violet rim, fading as it is left. */
    static void trails(Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, double time) {
        TRAILS.values().removeIf(trail -> {
            trail.removeIf(edge -> time - edge.time > 6);
            return trail.isEmpty();
        });
        for (Deque<Edge> trail : TRAILS.values()) {
            Edge previous = null;
            for (Edge edge : trail) {
                if (previous != null) {
                    double fade = Math.max(0, 1 - (time - edge.time) / 6);
                    Vec3 a = previous.heel.subtract(camera), b = previous.tip.subtract(camera), c = edge.tip.subtract(camera), d = edge.heel.subtract(camera);
                    // The body is thick and dark near the blade, thinning to a haze where it was.
                    GlowBrush.quad(fill, m, a, b, c, d, INK, INK, INK, INK, 160 * fade, 230 * fade, 230 * fade, 160 * fade);
                    GlowBrush.line(glow, m, b, c, .05 * fade + .01, VIOLET, 220 * fade);
                    GlowBrush.line(glow, m, b.lerp(a, .5), c.lerp(d, .5), .03 * fade, VIOLET, 90 * fade);
                }
                previous = edge;
            }
        }
    }

    static void clear() {
        TRAILS.clear();
    }

    private EclipseScytheVisual() { }
}
