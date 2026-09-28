package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Smooth glass hemispheres face incoming projectiles; combat still uses the 420 hidden cells. */
final class ManaShieldVisual {
    private static final Vec3[][] LOW_GLASS = glassMesh(16);
    private static final Vec3[][] HIGH_GLASS = glassMesh(32);

    static void render(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z,
            ShieldStackState state, List<ShieldImpact> impacts, List<ShieldResponse.Threat> threats, double time,
            double forwardX, double forwardZ, boolean low, Vec3 eyeDirection, double shieldRadius, double bufferRatio) {
        var caps = ManaDomeSurface.caps(threats, impacts, time);
        if (caps.isEmpty()) return;
        var frame = ShieldCellVisual.Frame.at(state, time);
        for (Vec3[] triangle : low ? LOW_GLASS : HIGH_GLASS) {
            for (var polygon : ManaDomeSurface.clip(triangle[0], triangle[1], triangle[2], caps)) {
                Vec3 normal = polygon.getFirst().add(polygon.get(1)).add(polygon.get(2)).normalize();
                int cell = ShieldCellVisual.cellAt(-normal.x * forwardZ + normal.z * forwardX, normal.y,
                        normal.x * forwardX + normal.z * forwardZ, frame);
                int hp = cell < 0 ? 0 : state.cellHp(cell);
                int visualHp = hp == 0 && state.sharedBuffer() > 0 ? 1 : hp;
                var response = ShieldResponse.atMany(normal, threats, impacts, time, visualHp);
                if (visualHp == 0 && (response.destruction() < .008 || impacts.stream().noneMatch(
                        hit -> ShieldCellVisual.justBroken(hit, cell, forwardX, forwardZ)))) continue;
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    vertex(consumer, matrix, polygon.getFirst(), x, y, z, shieldRadius, eyeDirection, caps, threats, impacts, time, visualHp, bufferRatio);
                    vertex(consumer, matrix, polygon.get(i), x, y, z, shieldRadius, eyeDirection, caps, threats, impacts, time, visualHp, bufferRatio);
                    vertex(consumer, matrix, polygon.get(i + 1), x, y, z, shieldRadius, eyeDirection, caps, threats, impacts, time, visualHp, bufferRatio);
                }
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Vec3 normal, double x, double y, double z,
            double radius, Vec3 eye, List<ManaDomeSurface.Cap> caps, List<ShieldResponse.Threat> threats,
            List<ShieldImpact> impacts, double time, int hp, double bufferRatio) {
        var response = ShieldResponse.atMany(normal, threats, impacts, time, hp);
        double presence = hp > 0 ? ManaDomeSurface.presence(normal, caps) : response.destruction();
        double silhouette = eye.lengthSqr() < .01 ? .25 : Math.pow(1 - Math.abs(normal.dot(eye)), 3);
        double edge = ManaDomeSurface.rim(normal, caps);
        double flash = Math.max(response.absorption(), response.destruction());
        int alpha = (int) Math.clamp((presence * (14 + 34 * silhouette + 70 * edge) + flash * 92)
                * ShieldSurfaceLighting.visibility(normal.x, normal.y, normal.z, eye) * (.7 + .3 * bufferRatio), 0, 155);
        int color = ShieldCellVisual.color(0x24C9C2, hp);
        double highlight = Math.min(.85, flash * .65 + edge * .28);
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        r += (int) ((220 - r) * highlight);
        g += (int) ((255 - g) * highlight);
        b += (int) ((255 - b) * highlight);
        // A single radius keeps the glass continuous while the wave changes its shading.
        double shellRadius = radius + .006 * radius / ShieldField.RADIUS;
        consumer.addVertex(matrix, (float) (x + normal.x * shellRadius), (float) (y + normal.y * shellRadius),
                (float) (z + normal.z * shellRadius)).setColor(r, g, b, alpha);
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
