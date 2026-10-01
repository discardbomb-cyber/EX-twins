package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.domain.shield.ShieldField;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The halo sphere of {@link ShieldGlow}: a latitude/longitude grid whose triangles are emitted in
 * a fixed order. Every grid direction is shaded once per shell and frame (waves, hit light, rim,
 * visibility) and the result is copied into each triangle that shares it, so the seam column and
 * the pole fans keep their own (duplicated) vertices and the picture stays exactly as it was.
 */
final class ShieldHalo {
    static final ShieldHalo LOW = new ShieldHalo(16), HIGH = new ShieldHalo(32);

    /** Unit directions of the grid, row-major with {@code columns + 1} entries per row (the seam is stored twice). */
    final double[] nx, ny, nz;
    /** Grid indices of the emitted vertices, three per triangle, in emission order. */
    final int[] triangles;

    // Per-call scratch: the grid never exceeds HIGH's size, and rendering is single-threaded.
    private static final float[] PX = new float[HIGH.nx.length], PY = new float[HIGH.nx.length], PZ = new float[HIGH.nx.length];
    private static final int[] ALPHA = new int[HIGH.nx.length];
    private static double[] hitX = new double[12], hitY = new double[12], hitZ = new double[12], hitFade = new double[12];

    private ShieldHalo(int rows) {
        int columns = rows * 2, stride = columns + 1;
        int grid = (rows + 1) * stride;
        nx = new double[grid];
        ny = new double[grid];
        nz = new double[grid];
        for (int row = 0; row <= rows; row++) for (int column = 0; column <= columns; column++) {
            double theta = Math.PI * row / rows, phi = Math.PI * 2 * column / columns;
            int index = row * stride + column;
            nx[index] = Math.sin(theta) * Math.cos(phi);
            ny[index] = Math.cos(theta);
            nz[index] = Math.sin(theta) * Math.sin(phi);
        }
        int[] order = new int[rows * columns * 2 * 3];
        int n = 0;
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            int a = row * stride + column, b = (row + 1) * stride + column, c = b + 1, d = a + 1;
            if (row > 0) {
                order[n++] = a;
                order[n++] = c;
                order[n++] = d;
            }
            if (row + 1 < rows) {
                order[n++] = a;
                order[n++] = b;
                order[n++] = c;
            }
        }
        triangles = java.util.Arrays.copyOf(order, n);
    }

    int vertexCount() {
        return triangles.length;
    }

    /**
     * Emits the halo: {@code activity} is how awake the shield is, {@code color} its packed RGB. The
     * active {@link ShieldRipple} waves, if any, must already be begun for this shell.
     */
    void render(VertexConsumer consumer, Matrix4f matrix, int color, double x, double y, double z, double shell, double activity,
            List<ShieldImpact> impacts, double time, Vec3 eye) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        // From inside, the halo would surround the camera; only hit spots glow there.
        boolean inside = ShieldSurfaceLighting.inside(eye);
        double ex = eye.x, ey = eye.y, ez = eye.z;
        int hits = 0;
        for (ShieldImpact impact : impacts) {
            double age = time - impact.gameTime();
            if (age < 0 || age >= ShieldResponse.IMPACT_TICKS || impact.absorbed() <= 0) continue;
            if (hits == hitX.length) grow();
            hitX[hits] = impact.normal().x;
            hitY[hits] = impact.normal().y;
            hitZ[hits] = impact.normal().z;
            hitFade[hits] = ShieldField.fade(age, 18);
            hits++;
        }
        boolean rippling = ShieldRipple.active();
        int grid = nx.length;
        for (int i = 0; i < grid; i++) {
            double px = nx[i], py = ny[i], pz = nz[i];
            double rim = inside ? .22 : Math.pow(1 - Math.abs(px * ex + py * ey + pz * ez), 2.4);
            double hit = 0;
            for (int k = 0; k < hits; k++) {
                hit = Math.max(hit, ShieldField.focus(px * hitX[k] + py * hitY[k] + pz * hitZ[k], .22) * hitFade[k]);
            }
            double bent;
            double ripple;
            if (rippling) {
                double height = ShieldRipple.height(px, py, pz);
                ripple = Math.max(0, height);
                bent = shell * ShieldRipple.scaleOf(height);
            } else {
                ripple = 0;
                bent = shell;
            }
            double light = inside ? hit * .6 : activity * (rim * .55 + .05) + hit * .95 + ripple * .6;
            ALPHA[i] = (int) Math.clamp(light * 150 * ShieldSurfaceLighting.visibility(px, py, pz, eye), 0, 220);
            PX[i] = (float) (x + px * bent);
            PY[i] = (float) (y + py * bent);
            PZ[i] = (float) (z + pz * bent);
        }
        int[] order = triangles;
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            consumer.addVertex(matrix, PX[i], PY[i], PZ[i]).setColor(r, g, b, ALPHA[i]);
        }
    }

    private static void grow() {
        int size = hitX.length * 2;
        hitX = java.util.Arrays.copyOf(hitX, size);
        hitY = java.util.Arrays.copyOf(hitY, size);
        hitZ = java.util.Arrays.copyOf(hitZ, size);
        hitFade = java.util.Arrays.copyOf(hitFade, size);
    }
}
