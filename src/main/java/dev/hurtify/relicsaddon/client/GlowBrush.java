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
 *
 * <p>Every figure has a form taking {@link Vec3}s and one taking their coordinates; the coordinate forms
 * do the arithmetic in place and emit the very same vertices (the same operations in the same order,
 * {@code HiveVisualEquivalenceCheck}), without a {@code Vec3} per corner. Only the render thread draws, so
 * the scratch arrays are shared.
 */
public final class GlowBrush {
    private static double pixelAngle = .0011;
    /** A flat gallery's fixed view direction (towards the viewer); null in the world, where the camera sits at the origin. */
    private static Vec3 flatView;
    private static double flatX, flatY, flatZ;

    static void setPixelAngle(double radians) {
        if (Double.isFinite(radians) && radians > 0) pixelAngle = radians;
    }

    /** Draw for a flat (orthographic) gallery looking along {@code towardsViewer}; null returns to the world. */
    public static void setFlatView(Vec3 towardsViewer) {
        flatView = towardsViewer == null ? null : towardsViewer.normalize();
        if (flatView != null) {
            flatX = flatView.x;
            flatY = flatView.y;
            flatZ = flatView.z;
        }
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

    private static double pixel(double x, double y, double z) {
        return flatView != null ? 0 : Math.sqrt(x * x + y * y + z * z) * pixelAngle;
    }

    /** The view direction at a point, as {@link #view(Vec3)} works it out, into {@code out[0..2]}. */
    private static void view(double x, double y, double z, double[] out) {
        if (flatView != null) {
            out[0] = flatX; out[1] = flatY; out[2] = flatZ;
            return;
        }
        if (x * x + y * y + z * z < 1e-12) {
            out[0] = 0; out[1] = 0; out[2] = 1;
            return;
        }
        normalize(x * -1, y * -1, z * -1, out);
    }

    /** {@link Vec3#normalize()}: the unit vector, or zero for one shorter than 1e-4, into {@code out[0..2]}. */
    private static void normalize(double x, double y, double z, double[] out) {
        double d = Math.sqrt(x * x + y * y + z * z);
        if (d < 1.0E-4) {
            out[0] = 0; out[1] = 0; out[2] = 0;
        } else {
            out[0] = x / d; out[1] = y / d; out[2] = z / d;
        }
    }

    /**
     * How squarely a face of normal {@code (nx, ny, nz)} at {@code (x, y, z)} faces the viewer: the dot product
     * of the unit normal and {@link #view}, as {@code normal.normalize().dot(view(at))} gives it.
     */
    static double facing(double nx, double ny, double nz, double x, double y, double z) {
        double[] normal = UP;
        normalize(nx, ny, nz, normal);
        double[] view = VIEW;
        view(x, y, z, view);
        return normal[0] * view[0] + normal[1] * view[1] + normal[2] * view[2];
    }

    /** Scratch for a view, a side and an up vector. */
    private static final double[] VIEW = new double[3], SIDE = new double[3], UP = new double[3];

    public static void line(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double width, int color, double alpha) {
        line(c, m, a.x, a.y, a.z, b.x, b.y, b.z, width, width, color, color, alpha, alpha);
    }

    public static void line(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double widthA, double widthB, int colorA, int colorB, double alphaA, double alphaB) {
        line(c, m, a.x, a.y, a.z, b.x, b.y, b.z, widthA, widthB, colorA, colorB, alphaA, alphaB);
    }

    public static void line(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, double width, int color, double alpha) {
        line(c, m, ax, ay, az, bx, by, bz, width, width, color, color, alpha, alpha);
    }

    public static void line(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz,
            double widthA, double widthB, int colorA, int colorB, double alphaA, double alphaB) {
        if (alphaA < 1 && alphaB < 1) return;
        double least = pixel(ax, ay, az) * 1.2;
        if (widthA < least) { alphaA *= widthA / least; widthA = least; }
        least = pixel(bx, by, bz) * 1.2;
        if (widthB < least) { alphaB *= widthB / least; widthB = least; }
        if (alphaA < 1 && alphaB < 1) return;
        double[] view = VIEW;
        view((ax + bx) * .5, (ay + by) * .5, (az + bz) * .5, view);
        double vx = view[0], vy = view[1], vz = view[2];
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
        if (sx * sx + sy * sy + sz * sz < 1e-18) return;
        double[] side = SIDE;
        normalize(sx, sy, sz, side);
        sx = side[0]; sy = side[1]; sz = side[2];
        double alx = ax + sx * widthA, aly = ay + sy * widthA, alz = az + sz * widthA;
        double arx = ax - sx * widthA, ary = ay - sy * widthA, arz = az - sz * widthA;
        double blx = bx + sx * widthB, bly = by + sy * widthB, blz = bz + sz * widthB;
        double brx = bx - sx * widthB, bry = by - sy * widthB, brz = bz - sz * widthB;
        vertex(c, m, alx, aly, alz, colorA, 0); vertex(c, m, ax, ay, az, colorA, alphaA); vertex(c, m, bx, by, bz, colorB, alphaB);
        vertex(c, m, alx, aly, alz, colorA, 0); vertex(c, m, bx, by, bz, colorB, alphaB); vertex(c, m, blx, bly, blz, colorB, 0);
        vertex(c, m, ax, ay, az, colorA, alphaA); vertex(c, m, arx, ary, arz, colorA, 0); vertex(c, m, brx, bry, brz, colorB, 0);
        vertex(c, m, ax, ay, az, colorA, alphaA); vertex(c, m, brx, bry, brz, colorB, 0); vertex(c, m, bx, by, bz, colorB, alphaB);
    }

    /** A core line inside a wide soft glow. */
    public static void beam(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double width, int color, double alpha) {
        beam(c, m, a.x, a.y, a.z, b.x, b.y, b.z, width, color, alpha);
    }

    public static void beam(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, double width, int color, double alpha) {
        line(c, m, ax, ay, az, bx, by, bz, width, mix(color, 0xFFFFFF, .4), alpha * 1.2);
        line(c, m, ax, ay, az, bx, by, bz, width * 3.5, color, alpha * .35);
    }

    /** The twelve steps round a point of light. */
    private static final int DOT_SEGMENTS = 12;
    private static final double[] DOT_COS = new double[DOT_SEGMENTS + 1], DOT_SIN = new double[DOT_SEGMENTS + 1];

    static {
        for (int k = 0; k <= DOT_SEGMENTS; k++) {
            double angle = Math.PI * 2 * k / DOT_SEGMENTS;
            DOT_COS[k] = Math.cos(angle);
            DOT_SIN[k] = Math.sin(angle);
        }
    }

    /** A round point of light facing the camera, hot in the middle. */
    public static void dot(VertexConsumer c, Matrix4f m, Vec3 at, double size, int color, double alpha) {
        dot(c, m, at.x, at.y, at.z, size, color, alpha);
    }

    public static void dot(VertexConsumer c, Matrix4f m, double x, double y, double z, double size, int color, double alpha) {
        if (alpha < 1) return;
        double least = pixel(x, y, z) * 1.6;
        if (size < least) { alpha *= size / least; size = least; if (alpha < 1) return; }
        double[] view = VIEW;
        view(x, y, z, view);
        double vx = view[0], vy = view[1], vz = view[2];
        // right = view x (1,0,0) or view x (0,1,0), normalized; top = right x view.
        double ux = Math.abs(vy) > .95 ? 1 : 0, uy = Math.abs(vy) > .95 ? 0 : 1, uz = 0;
        double[] right = SIDE;
        normalize(vy * uz - vz * uy, vz * ux - vx * uz, vx * uy - vy * ux, right);
        double rx = right[0], ry = right[1], rz = right[2];
        double tx = ry * vz - rz * vy, ty = rz * vx - rx * vz, tz = rx * vy - ry * vx;
        int hot = mix(color, 0xFFFFFF, .45);
        double px = 0, py = 0, pz = 0;
        for (int k = 0; k <= DOT_SEGMENTS; k++) {
            double along = DOT_COS[k] * size, up = DOT_SIN[k] * size;
            double qx = x + rx * along + tx * up, qy = y + ry * along + ty * up, qz = z + rz * along + tz * up;
            if (k > 0) {
                vertex(c, m, x, y, z, hot, alpha);
                vertex(c, m, px, py, pz, color, 0);
                vertex(c, m, qx, qy, qz, color, 0);
            }
            px = qx; py = qy; pz = qz;
        }
    }

    /** A jagged bolt from {@code a} to {@code b}: the same {@code seed} gives the same shape, so bolts flicker only when re-seeded. */
    public static void lightning(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, long seed, int segments, double jag, double width, int color, double alpha) {
        lightning(c, m, a.x, a.y, a.z, b.x, b.y, b.z, seed, segments, jag, width, color, alpha, null);
    }

    public static void lightning(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, long seed, int segments,
            double jag, double width, int color, double alpha) {
        lightning(c, m, ax, ay, az, bx, by, bz, seed, segments, jag, width, color, alpha, null);
    }

    /** The same bolt, leaving out the stretches {@code hidden} says are out of sight (behind something drawn solid before it). */
    public static void lightning(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, long seed, int segments, double jag, double width, int color, double alpha,
            java.util.function.Predicate<Vec3> hidden) {
        lightning(c, m, a.x, a.y, a.z, b.x, b.y, b.z, seed, segments, jag, width, color, alpha, hidden);
    }

    private static void lightning(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, long seed, int segments,
            double jag, double width, int color, double alpha, java.util.function.Predicate<Vec3> hidden) {
        Random random = new Random(seed);
        double spx = bx - ax, spy = by - ay, spz = bz - az;
        double length = Math.sqrt(spx * spx + spy * spy + spz * spz);
        if (length < 1e-4) return;
        double inverse = 1 / length;
        double dx = spx * inverse, dy = spy * inverse, dz = spz * inverse;
        double ux = Math.abs(dy) > .9 ? 1 : 0, uy = Math.abs(dy) > .9 ? 0 : 1, uz = 0;
        double[] side = SIDE;
        normalize(dy * uz - dz * uy, dz * ux - dx * uz, dx * uy - dy * ux, side);
        double sx = side[0], sy = side[1], sz = side[2];
        double lx = sy * dz - sz * dy, ly = sz * dx - sx * dz, lz = sx * dy - sy * dx;
        double px = ax, py = ay, pz = az;
        for (int k = 1; k <= segments; k++) {
            double along = k / (double) segments;
            double qx = ax + spx * along, qy = ay + spy * along, qz = az + spz * along;
            if (k < segments) {
                double angle = random.nextDouble() * Math.PI * 2, offset = jag * length * (.3 + .7 * random.nextDouble());
                double out = Math.cos(angle) * offset, lift = Math.sin(angle) * offset;
                qx = qx + sx * out + lx * lift;
                qy = qy + sy * out + ly * lift;
                qz = qz + sz * out + lz * lift;
            }
            if (hidden == null || !hidden.test(new Vec3((px + qx) * .5, (py + qy) * .5, (pz + qz) * .5))) {
                beam(c, m, px, py, pz, qx, qy, qz, width, color, alpha);
            }
            px = qx; py = qy; pz = qz;
        }
    }

    /** The cosines and sines of the steps round a circle of each segment count, worked out once. */
    private static final int CACHED_SEGMENTS = 256;
    private static final double[][] CIRCLE_COS = new double[CACHED_SEGMENTS + 1][], CIRCLE_SIN = new double[CACHED_SEGMENTS + 1][];

    private static double[] circleCos(int segments) {
        double[] cos = CIRCLE_COS[segments];
        if (cos == null) ring(segments);
        return CIRCLE_COS[segments];
    }

    private static double[] circleSin(int segments) {
        return CIRCLE_SIN[segments];
    }

    private static void ring(int segments) {
        double[] cos = new double[segments + 1], sin = new double[segments + 1];
        for (int k = 0; k <= segments; k++) {
            double angle = Math.PI * 2 * k / segments;
            cos[k] = Math.cos(angle);
            sin[k] = Math.sin(angle);
        }
        CIRCLE_COS[segments] = cos;
        CIRCLE_SIN[segments] = sin;
    }

    /** A circle of radius {@code radius} around {@code centre}, in the plane spanned by {@code u} and {@code v}. */
    public static void circle(VertexConsumer c, Matrix4f m, Vec3 centre, Vec3 u, Vec3 v, double radius, int segments, double width, int color, double alpha) {
        circle(c, m, centre.x, centre.y, centre.z, u.x, u.y, u.z, v.x, v.y, v.z, radius, segments, width, color, alpha);
    }

    public static void circle(VertexConsumer c, Matrix4f m, double cx, double cy, double cz, double ux, double uy, double uz, double vx, double vy, double vz,
            double radius, int segments, double width, int color, double alpha) {
        if (segments <= 0) return;
        boolean cached = segments <= CACHED_SEGMENTS;
        double[] cos = cached ? circleCos(segments) : null, sin = cached ? circleSin(segments) : null;
        double px = 0, py = 0, pz = 0;
        for (int k = 0; k <= segments; k++) {
            double angle = cached ? 0 : Math.PI * 2 * k / segments;
            double along = (cached ? cos[k] : Math.cos(angle)) * radius, across = (cached ? sin[k] : Math.sin(angle)) * radius;
            double qx = cx + ux * along + vx * across, qy = cy + uy * along + vy * across, qz = cz + uz * along + vz * across;
            if (k > 0) line(c, m, px, py, pz, qx, qy, qz, width, width, color, color, alpha, alpha);
            px = qx; py = qy; pz = qz;
        }
    }

    /** Scratch for a sphere's grid: positions, alphas and colours by row-major index. */
    private static double[] spherePoints = new double[0], sphereAlphas = new double[0];
    private static int[] sphereColors = new int[0];
    /** The sines and cosines of a sphere's rows and columns, by its row count. */
    private static final int CACHED_ROWS = 64;
    private static final double[][] ROW_SIN = new double[CACHED_ROWS + 1][], ROW_COS = new double[CACHED_ROWS + 1][],
            COLUMN_SIN = new double[CACHED_ROWS + 1][], COLUMN_COS = new double[CACHED_ROWS + 1][];

    private static void grid(int rows) {
        int columns = rows * 2;
        double[] rowSin = new double[rows + 1], rowCos = new double[rows + 1], columnSin = new double[columns + 1], columnCos = new double[columns + 1];
        for (int row = 0; row <= rows; row++) {
            double theta = Math.PI * row / rows;
            rowSin[row] = Math.sin(theta);
            rowCos[row] = Math.cos(theta);
        }
        for (int column = 0; column <= columns; column++) {
            double phi = Math.PI * 2 * column / columns;
            columnSin[column] = Math.sin(phi);
            columnCos[column] = Math.cos(phi);
        }
        ROW_SIN[rows] = rowSin; ROW_COS[rows] = rowCos; COLUMN_SIN[rows] = columnSin; COLUMN_COS[rows] = columnCos;
    }

    /** A translucent sphere, bright at its rim (fresnel), drawn into a translucent batch. */
    public static void sphere(VertexConsumer c, Matrix4f m, Vec3 centre, double radius, int core, int rim, double coreAlpha, double rimAlpha, int rows) {
        sphere(c, m, centre.x, centre.y, centre.z, radius, core, rim, coreAlpha, rimAlpha, rows);
    }

    public static void sphere(VertexConsumer c, Matrix4f m, double cx, double cy, double cz, double radius, int core, int rim, double coreAlpha, double rimAlpha, int rows) {
        if (rows <= 0) return;
        int columns = rows * 2, stride = columns + 1, size = (rows + 1) * stride;
        if (sphereAlphas.length < size) {
            spherePoints = new double[size * 3];
            sphereAlphas = new double[size];
            sphereColors = new int[size];
        }
        double[] points = spherePoints, alphas = sphereAlphas;
        int[] colors = sphereColors;
        boolean cached = rows <= CACHED_ROWS;
        if (cached && ROW_SIN[rows] == null) grid(rows);
        double[] rowSin = cached ? ROW_SIN[rows] : null, rowCos = cached ? ROW_COS[rows] : null;
        double[] columnSin = cached ? COLUMN_SIN[rows] : null, columnCos = cached ? COLUMN_COS[rows] : null;
        double[] view = VIEW;
        view(cx, cy, cz, view);
        double ex = view[0], ey = view[1], ez = view[2];
        for (int row = 0; row <= rows; row++) {
            double theta = cached ? 0 : Math.PI * row / rows;
            double sinTheta = cached ? rowSin[row] : Math.sin(theta), cosTheta = cached ? rowCos[row] : Math.cos(theta);
            for (int column = 0; column <= columns; column++) {
                double phi = cached ? 0 : Math.PI * 2 * column / columns;
                double cosPhi = cached ? columnCos[column] : Math.cos(phi), sinPhi = cached ? columnSin[column] : Math.sin(phi);
                double nx = sinTheta * cosPhi, ny = cosTheta, nz = sinTheta * sinPhi;
                double fresnel = Math.pow(1 - Math.abs(nx * ex + ny * ey + nz * ez), 2);
                int at = row * stride + column;
                points[at * 3] = cx + nx * radius;
                points[at * 3 + 1] = cy + ny * radius;
                points[at * 3 + 2] = cz + nz * radius;
                alphas[at] = coreAlpha + (rimAlpha - coreAlpha) * fresnel;
                colors[at] = mix(core, rim, fresnel);
            }
        }
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            int a = row * stride + column, b = a + stride, d = b + 1, e = a + 1;
            quad(c, m, points[a * 3], points[a * 3 + 1], points[a * 3 + 2], points[b * 3], points[b * 3 + 1], points[b * 3 + 2],
                    points[d * 3], points[d * 3 + 1], points[d * 3 + 2], points[e * 3], points[e * 3 + 1], points[e * 3 + 2],
                    colors[a], colors[b], colors[d], colors[e], alphas[a], alphas[b], alphas[d], alphas[e]);
        }
    }

    public static void quad(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 d, Vec3 e, int ca, int cb, int cd, int ce,
            double aa, double ab, double ad, double ae) {
        quad(c, m, a.x, a.y, a.z, b.x, b.y, b.z, d.x, d.y, d.z, e.x, e.y, e.z, ca, cb, cd, ce, aa, ab, ad, ae);
    }

    public static void quad(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, double dx, double dy, double dz,
            double ex, double ey, double ez, int ca, int cb, int cd, int ce, double aa, double ab, double ad, double ae) {
        if (aa + ab + ad + ae < 2) return;
        vertex(c, m, ax, ay, az, ca, aa); vertex(c, m, bx, by, bz, cb, ab); vertex(c, m, dx, dy, dz, cd, ad);
        vertex(c, m, ax, ay, az, ca, aa); vertex(c, m, dx, dy, dz, cd, ad); vertex(c, m, ex, ey, ez, ce, ae);
    }

    static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, int color, double alpha) {
        vertex(c, m, p.x, p.y, p.z, color, alpha);
    }

    static void vertex(VertexConsumer c, Matrix4f m, double x, double y, double z, int color, double alpha) {
        c.addVertex(m, (float) x, (float) y, (float) z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, (int) Math.clamp(alpha, 0, 255));
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
