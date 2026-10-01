package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Soft light for the swarm's constructs, in camera-relative world space (the camera at the origin).
 * Lines and points are bright along their middle and fade to nothing at their edges, face the camera,
 * and are never drawn thinner than about a pixel (a thinner one is widened and dimmed), so they stay
 * smooth without multisampling. Draw into {@link ShieldGlow#consumer()} for additive light or the
 * shield batch for translucent fills.
 */
public final class GlowBrush {
    private static double pixelAngle = .0011;
    /** A flat gallery's fixed view direction (towards the viewer); null in the world, where the camera sits at the origin. */
    private static Vec3 flatView;

    static void setPixelAngle(double radians) {
        if (Double.isFinite(radians) && radians > 0) pixelAngle = radians;
    }

    /** Draw for a flat (orthographic) gallery looking along {@code towardsViewer}; null returns to the world. */
    public static void setFlatView(Vec3 towardsViewer) {
        flatView = towardsViewer == null ? null : towardsViewer.normalize();
    }

    static boolean flat() {
        return flatView != null;
    }

    /** Unit vector from {@code at} towards the viewer. */
    static Vec3 view(Vec3 at) {
        if (flatView != null) return flatView;
        return at.lengthSqr() < 1e-12 ? new Vec3(0, 0, 1) : at.scale(-1).normalize();
    }

    static double pixel(Vec3 at) {
        return flatView != null ? 0 : at.length() * pixelAngle;
    }

    public static void line(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double width, int color, double alpha) {
        line(c, m, a, b, width, width, color, color, alpha, alpha);
    }

    public static void line(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double widthA, double widthB, int colorA, int colorB, double alphaA, double alphaB) {
        if (alphaA < 1 && alphaB < 1) return;
        double least = pixel(a) * 1.2;
        if (widthA < least) { alphaA *= widthA / least; widthA = least; }
        least = pixel(b) * 1.2;
        if (widthB < least) { alphaB *= widthB / least; widthB = least; }
        if (alphaA < 1 && alphaB < 1) return;
        Vec3 view = view(a.add(b).scale(.5));
        Vec3 side = b.subtract(a).cross(view);
        if (side.lengthSqr() < 1e-18) return;
        side = side.normalize();
        Vec3 al = a.add(side.scale(widthA)), ar = a.subtract(side.scale(widthA));
        Vec3 bl = b.add(side.scale(widthB)), br = b.subtract(side.scale(widthB));
        vertex(c, m, al, colorA, 0); vertex(c, m, a, colorA, alphaA); vertex(c, m, b, colorB, alphaB);
        vertex(c, m, al, colorA, 0); vertex(c, m, b, colorB, alphaB); vertex(c, m, bl, colorB, 0);
        vertex(c, m, a, colorA, alphaA); vertex(c, m, ar, colorA, 0); vertex(c, m, br, colorB, 0);
        vertex(c, m, a, colorA, alphaA); vertex(c, m, br, colorB, 0); vertex(c, m, b, colorB, alphaB);
    }

    /** A core line inside a wide soft glow. */
    public static void beam(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double width, int color, double alpha) {
        line(c, m, a, b, width, mix(color, 0xFFFFFF, .4), alpha * 1.2);
        line(c, m, a, b, width * 3.5, color, alpha * .35);
    }

    /** A round point of light facing the camera, hot in the middle. */
    public static void dot(VertexConsumer c, Matrix4f m, Vec3 at, double size, int color, double alpha) {
        if (alpha < 1) return;
        double least = pixel(at) * 1.6;
        if (size < least) { alpha *= size / least; size = least; if (alpha < 1) return; }
        Vec3 view = view(at);
        Vec3 right = view.cross(Math.abs(view.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 top = right.cross(view);
        int hot = mix(color, 0xFFFFFF, .45);
        Vec3 previous = null;
        for (int k = 0; k <= 12; k++) {
            double angle = Math.PI * 2 * k / 12;
            Vec3 rim = at.add(right.scale(Math.cos(angle) * size)).add(top.scale(Math.sin(angle) * size));
            if (previous != null) {
                vertex(c, m, at, hot, alpha);
                vertex(c, m, previous, color, 0);
                vertex(c, m, rim, color, 0);
            }
            previous = rim;
        }
    }

    /** A jagged bolt from {@code a} to {@code b}: the same {@code seed} gives the same shape, so bolts flicker only when re-seeded. */
    public static void lightning(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, long seed, int segments, double jag, double width, int color, double alpha) {
        lightning(c, m, a, b, seed, segments, jag, width, color, alpha, point -> false);
    }

    /** The same bolt, leaving out the stretches {@code hidden} says are out of sight (behind something drawn solid before it). */
    public static void lightning(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, long seed, int segments, double jag, double width, int color, double alpha,
            java.util.function.Predicate<Vec3> hidden) {
        Random random = new Random(seed);
        Vec3 span = b.subtract(a);
        double length = span.length();
        if (length < 1e-4) return;
        Vec3 dir = span.scale(1 / length);
        Vec3 side = dir.cross(Math.abs(dir.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 lift = side.cross(dir);
        Vec3 previous = a;
        for (int k = 1; k <= segments; k++) {
            Vec3 point = a.add(span.scale(k / (double) segments));
            if (k < segments) {
                double angle = random.nextDouble() * Math.PI * 2, offset = jag * length * (.3 + .7 * random.nextDouble());
                point = point.add(side.scale(Math.cos(angle) * offset)).add(lift.scale(Math.sin(angle) * offset));
            }
            if (!hidden.test(previous.add(point).scale(.5))) beam(c, m, previous, point, width, color, alpha);
            previous = point;
        }
    }

    /** A circle of radius {@code radius} around {@code centre}, in the plane spanned by {@code u} and {@code v}. */
    public static void circle(VertexConsumer c, Matrix4f m, Vec3 centre, Vec3 u, Vec3 v, double radius, int segments, double width, int color, double alpha) {
        Vec3 previous = null;
        for (int k = 0; k <= segments; k++) {
            double angle = Math.PI * 2 * k / segments;
            Vec3 point = centre.add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
            if (previous != null) line(c, m, previous, point, width, color, alpha);
            previous = point;
        }
    }

    /** A translucent sphere, bright at its rim (fresnel), drawn into a translucent batch. */
    public static void sphere(VertexConsumer c, Matrix4f m, Vec3 centre, double radius, int core, int rim, double coreAlpha, double rimAlpha, int rows) {
        int columns = rows * 2;
        Vec3[][] points = new Vec3[rows + 1][columns + 1];
        double[][] alphas = new double[rows + 1][columns + 1];
        int[][] colors = new int[rows + 1][columns + 1];
        Vec3 eye = view(centre);
        for (int row = 0; row <= rows; row++) for (int column = 0; column <= columns; column++) {
            double theta = Math.PI * row / rows, phi = Math.PI * 2 * column / columns;
            Vec3 normal = new Vec3(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
            double fresnel = Math.pow(1 - Math.abs(normal.dot(eye)), 2);
            points[row][column] = centre.add(normal.scale(radius));
            alphas[row][column] = coreAlpha + (rimAlpha - coreAlpha) * fresnel;
            colors[row][column] = mix(core, rim, fresnel);
        }
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            quad(c, m, points[row][column], points[row + 1][column], points[row + 1][column + 1], points[row][column + 1],
                    colors[row][column], colors[row + 1][column], colors[row + 1][column + 1], colors[row][column + 1],
                    alphas[row][column], alphas[row + 1][column], alphas[row + 1][column + 1], alphas[row][column + 1]);
        }
    }

    public static void quad(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 d, Vec3 e, int ca, int cb, int cd, int ce,
            double aa, double ab, double ad, double ae) {
        if (aa + ab + ad + ae < 2) return;
        vertex(c, m, a, ca, aa); vertex(c, m, b, cb, ab); vertex(c, m, d, cd, ad);
        vertex(c, m, a, ca, aa); vertex(c, m, d, cd, ad); vertex(c, m, e, ce, ae);
    }

    static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, int color, double alpha) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, (int) Math.clamp(alpha, 0, 255));
    }

    static int mix(int a, int b, double t) {
        t = Math.clamp(t, 0, 1);
        int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private GlowBrush() {
    }
}
