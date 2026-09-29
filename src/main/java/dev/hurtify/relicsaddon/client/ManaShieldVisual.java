package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Smooth glass hemispheres face incoming projectiles; combat still uses the 420 hidden cells. */
final class ManaShieldVisual {
    private static final int MAX_VERTEX_SAMPLES = 16_384;
    private static final Vec3[][] LOW_GLASS = glassMesh(16);
    private static final Vec3[][] HIGH_GLASS = glassMesh(32);

    static void render(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z,
            ShieldStackState state, List<ShieldImpact> impacts, List<ShieldResponse.Threat> threats, double time,
            double forwardX, double forwardZ, boolean low, Vec3 eyeDirection, double shieldRadius, double bufferRatio) {
        var caps = ManaDomeSurface.caps(threats, impacts, time);
        if (caps.isEmpty()) return;
        var integrity = IntegrityField.forRender(state, time, forwardX, forwardZ);
        for (Vec3[] triangle : low ? LOW_GLASS : HIGH_GLASS) {
            for (var polygon : ManaDomeSurface.clip(triangle[0], triangle[1], triangle[2], caps)) {
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    vertex(consumer, matrix, polygon.getFirst(), x, y, z, shieldRadius, eyeDirection, caps, threats, impacts, time, integrity, bufferRatio);
                    vertex(consumer, matrix, polygon.get(i), x, y, z, shieldRadius, eyeDirection, caps, threats, impacts, time, integrity, bufferRatio);
                    vertex(consumer, matrix, polygon.get(i + 1), x, y, z, shieldRadius, eyeDirection, caps, threats, impacts, time, integrity, bufferRatio);
                }
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Vec3 normal, double x, double y, double z,
            double radius, Vec3 eye, List<ManaDomeSurface.Cap> caps, List<ShieldResponse.Threat> threats,
            List<ShieldImpact> impacts, double time, IntegrityField field, double bufferRatio) {
        double integrity = field.sample(normal);
        // The shared buffer is a continuous reserve, not a collection of replacement panels.
        double material = Math.max(integrity, bufferRatio * .62D);
        var response = ShieldResponse.atMany(normal, threats, impacts, time, material > .01D ? 1 : 0);
        double presence = ManaDomeSurface.presence(normal, caps) * material + response.destruction();
        double silhouette = eye.lengthSqr() < .01 ? .25 : Math.pow(1 - Math.abs(normal.dot(eye)), 3);
        double edge = ManaDomeSurface.rim(normal, caps);
        double flash = Math.max(response.absorption(), response.destruction());
        int alpha = (int) Math.clamp((presence * (20 + 48 * silhouette + 96 * edge) + flash * 92)
                * ShieldSurfaceLighting.visibility(normal.x, normal.y, normal.z, eye) * (.7 + .3 * bufferRatio), 0, 170);
        // Keep the hue glass-like. Health changes transparency, never a discrete panel color.
        int color = glassColor(integrity, bufferRatio);
        double ripple = ShieldRipple.active() ? ShieldRipple.height(normal.x, normal.y, normal.z) : 0;
        // The crest of the hit wave catches light; the trough behind it dims slightly.
        double highlight = Math.min(.85, flash * .65 + edge * .28 + Math.max(0, ripple) * .35);
        alpha = (int) Math.clamp(alpha * (1 + ripple * .25) + Math.abs(ripple) * presence * 30, 0, 170);
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        r += (int) ((220 - r) * highlight);
        g += (int) ((255 - g) * highlight);
        b += (int) ((255 - b) * highlight);
        // One continuous radius, bent per vertex by the travelling hit wave.
        double shellRadius = (radius + .006 * radius / ShieldField.RADIUS) * ShieldRipple.scale(normal.x, normal.y, normal.z);
        consumer.addVertex(matrix, (float) (x + normal.x * shellRadius), (float) (y + normal.y * shellRadius),
                (float) (z + normal.z * shellRadius)).setColor(r, g, b, alpha);
    }

    /**
     * Gameplay remains cell-based, but the membrane samples all cell health with a smooth angular kernel.
     * This makes a depleted area fade through its vertices instead of erasing the triangle that happened to
     * contain a cell center.
     */
    static double weightedIntegrity(Vec3 normal, float[][] centers, double[] health) {
        double weightedHp = 0, weight = 0;
        for (int cell = 0; cell < centers.length; cell++) {
            float[] center = centers[cell];
            double dot = normal.x * center[0] + normal.y * center[1] + normal.z * center[2];
            // A broad compact kernel overlaps neighboring 420-cell sites and has no ownership boundary.
            double sample = Math.max(0, (dot - .91D) / .09D);
            sample *= sample;
            weightedHp += sample * health[cell];
            weight += sample;
        }
        return weight == 0 ? 0 : weightedHp / weight;
    }

    private static final class IntegrityField {
        private final double uniform;
        private final float[][] centers;
        private final double[] health;
        private final double forwardX;
        private final double forwardZ;
        private final Map<Vec3, Double> samples = new HashMap<>();

        private IntegrityField(double uniform, float[][] centers, double[] health, double forwardX, double forwardZ) {
            this.uniform = uniform;
            this.centers = centers;
            this.health = health;
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
        }

        static IntegrityField forRender(ShieldStackState state, double time, double forwardX, double forwardZ) {
            // The shared pool is consumed before local cells, so it keeps the whole membrane intact.
            if (state.sharedBuffer() > 0) return new IntegrityField(1, null, null, forwardX, forwardZ);
            var cells = ShieldTopology.INSTANCE.cells();
            boolean intact = true;
            for (var cell : cells) if (state.cellHp(cell.id()) < ShieldStackState.MAX_PANEL_INTEGRITY) {
                intact = false;
                break;
            }
            if (intact) return new IntegrityField(1, null, null, forwardX, forwardZ);

            float[][] centers = new float[cells.length][];
            double[] health = new double[cells.length];
            boolean gathering = state.gathering(time);
            for (var cell : cells) {
                int id = cell.id();
                centers[id] = gathering && state.moving(id, time)
                        ? ShieldCellVisual.transform(cell.center(), ShieldCellVisual.relocation(state, id, time)) : cell.center();
                health[id] = state.cellHp(id) / (double) ShieldStackState.MAX_PANEL_INTEGRITY;
            }
            return new IntegrityField(-1, centers, health, forwardX, forwardZ);
        }

        double sample(Vec3 normal) {
            if (uniform >= 0) return uniform;
            Double known = samples.get(normal);
            if (known != null) return known;
            Vec3 local = new Vec3(-normal.x * forwardZ + normal.z * forwardX, normal.y,
                    normal.x * forwardX + normal.z * forwardZ);
            double value = weightedIntegrity(local, centers, health);
            if (samples.size() < MAX_VERTEX_SAMPLES) samples.put(normal, value);
            return value;
        }
    }

    private static int glassColor(double integrity, double bufferRatio) {
        double health = Math.max(integrity, bufferRatio * .55D);
        int red = (int) (18 + (40 - 18) * health);
        int green = (int) (116 + (211 - 116) * health);
        int blue = (int) (172 + (232 - 172) * health);
        return red << 16 | green << 8 | blue;
    }

    private static Vec3[][] glassMesh(int rows) {
        int columns = rows * 2;
        var triangles = new ArrayList<Vec3[]>(rows * columns * 2);
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            Vec3 a = sphere(Math.PI * row / rows, Math.PI * 2 * column / columns);
            Vec3 b = sphere(Math.PI * (row + 1) / rows, Math.PI * 2 * column / columns);
            Vec3 c = sphere(Math.PI * (row + 1) / rows, Math.PI * 2 * (column + 1) / columns);
            Vec3 d = sphere(Math.PI * row / rows, Math.PI * 2 * (column + 1) / columns);
            if (row > 0) triangles.add(new Vec3[]{a, c, d});
            if (row + 1 < rows) triangles.add(new Vec3[]{a, b, c});
        }
        return triangles.toArray(Vec3[][]::new);
    }

    private static Vec3 sphere(double theta, double phi) {
        return new Vec3(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
    }

    private ManaShieldVisual() { }
}
