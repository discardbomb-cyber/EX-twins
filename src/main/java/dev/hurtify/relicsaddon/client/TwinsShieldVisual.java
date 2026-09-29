package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Continuous violet-black glass below the raised cell layer, with suspended motes and restrained seals. */
final class TwinsShieldVisual {
    static void render(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z,
            ShieldStackState state, List<ShieldImpact> impacts, List<ShieldResponse.Threat> threats, double time,
            double forwardX, double forwardZ, boolean low, Vec3 eyeDirection, double shieldRadius, double bufferRatio) {
        double impactActivity = impacts.stream().mapToDouble(impact -> ShieldField.fade(time - impact.gameTime(), ShieldResponse.IMPACT_TICKS)).max().orElse(0);
        double activity = threats.isEmpty() ? impactActivity : 1;
        boolean circuitHit = impacts.stream().anyMatch(impact -> impact.absorbed() > 0 && time >= impact.gameTime()
                && time - impact.gameTime() < ShieldResponse.IMPACT_TICKS);
        if (activity <= 0) return;
        var mesh = TwinsShieldGlyphMesh.forQuality(low);
        int averageHp = (int) Math.ceil(state.totalIntegrity() / (double) ShieldTopology.CELL_COUNT);
        for (var cell : ShieldTopology.INSTANCE.cells()) {
            int hp = state.cellHp(cell.id());
            int visualHp = hp == 0 && state.sharedBuffer() > 0 ? 1 : hp;
            float[] center = cell.center();
            Vec3 normal = new Vec3(-center[0] * forwardZ + center[2] * forwardX, center[1],
                    center[0] * forwardX + center[2] * forwardZ);
            var response = ShieldResponse.atMany(normal, threats, impacts, time, visualHp);
            if (visualHp == 0 && (response.destruction() <= .008 || !wasJustBroken(impacts, cell.id(), forwardX, forwardZ))) continue;
            double presence = visualHp == 0 ? response.destruction() : Math.max(activity * .32, response.presence());
            int warningHp = Math.min(visualHp, averageHp);
            for (var triangle : mesh.cell(cell.id())) {
                int material = triangle.material();
                // The membrane supplies the subtle black-violet faceted shell. Circuit tracks are intentionally
                // absent until a server-confirmed absorption, so the field is quiet outside combat.
                if (material == TwinsShieldGlyphMesh.CIRCUIT && !circuitHit) continue;
                double pulse = .5 + .5 * Math.cos(triangle.phase() - time * .075);
                double flare = Math.max(response.absorption(), response.destruction());
                int healthy = material == TwinsShieldGlyphMesh.CIRCUIT ? 0xC66CFF : material == TwinsShieldGlyphMesh.MEMBRANE ? 0x140C20
                        : material == TwinsShieldGlyphMesh.ORBIT ? 0x5E268D : 0xA84FE0;
                int color = ShieldCellVisual.violetHealth(healthy, warningHp);
                double highlight = Math.min(1, flare * .85 + Math.pow(pulse, 12) * .30);
                int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
                r += (int) ((255 - r) * highlight);
                g += (int) ((255 - g) * highlight);
                b += (int) ((255 - b) * highlight);
                double opacity = switch (material) {
                    case TwinsShieldGlyphMesh.CIRCUIT -> 92 + 118 * Math.pow(pulse, 16);
                    case TwinsShieldGlyphMesh.MEMBRANE -> 44 + 16 * Math.pow(pulse, 5);
                    case TwinsShieldGlyphMesh.GLOW -> low ? 2 : 4;
                    case TwinsShieldGlyphMesh.ORBIT -> 18 + 20 * Math.pow(pulse, 6);
                    default -> 14 + 22 * pulse;
                };
                var midpoint = triangle.a().add(triangle.b()).add(triangle.c()).scale(1.0 / 3);
                double facing = ShieldSurfaceLighting.visibility(-midpoint.x() * forwardZ + midpoint.z() * forwardX,
                        midpoint.y(), midpoint.x() * forwardX + midpoint.z() * forwardZ, eyeDirection);
                double surfacePresence = material == TwinsShieldGlyphMesh.CIRCUIT
                        ? Math.max(response.absorption(), response.destruction()) : presence;
                int alpha = (int) Math.clamp(surfacePresence * opacity * facing * (.65D + .35D * bufferRatio), 0, 255);
                if (alpha < 1) continue;
                double facet = material == TwinsShieldGlyphMesh.MEMBRANE ? 0 : .008;
                double radius = shieldRadius + (facet + (material == TwinsShieldGlyphMesh.MEMBRANE ? 0 : response.absorption() * .012)
                        + (material == TwinsShieldGlyphMesh.ORBIT ? .024 : material == TwinsShieldGlyphMesh.MEMBRANE ? 0 : .009)
                        + (visualHp == 0 ? response.destruction() * .06 : 0)) * shieldRadius / ShieldField.RADIUS;
                vertex(consumer, matrix, triangle.a(), x, y, z, forwardX, forwardZ, radius, r, g, b, alpha);
                vertex(consumer, matrix, triangle.b(), x, y, z, forwardX, forwardZ, radius, r, g, b, alpha);
                vertex(consumer, matrix, triangle.c(), x, y, z, forwardX, forwardZ, radius, r, g, b, alpha);
            }
            if (visualHp > 0 && cell.id() % (low ? 8 : 4) == 0) {
                mote(consumer, matrix, normal, cell.id(), x, y, z, time, shieldRadius, presence, eyeDirection);
            }
        }
    }

    private static void mote(VertexConsumer consumer, Matrix4f matrix, Vec3 normal, int id,
            double x, double y, double z, double time, double radius, double presence, Vec3 eye) {
        Vec3 side = normal.cross(Math.abs(normal.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = normal.cross(side).normalize();
        double phase = id * 2.399963 + time * .025;
        Vec3 center = normal.scale(radius * ShieldRipple.scale(normal.x, normal.y, normal.z) * (.962 + .009 * Math.sin(phase)))
                .add(up.scale(Math.sin(phase * .7) * radius * .015));
        double size = radius * (.0025 + .002 * Math.pow(.5 + .5 * Math.sin(phase), 3));
        int alpha = (int) (presence * (90 + 140 * Math.pow(.5 + .5 * Math.sin(phase), 2))
                * ShieldSurfaceLighting.visibility(normal.x, normal.y, normal.z, eye));
        Vec3 a = center.add(up.scale(size)), b = center.add(side.scale(size));
        Vec3 c = center.add(up.scale(-size)), d = center.add(side.scale(-size));
        for (Vec3 point : new Vec3[]{a, b, c, a, c, d}) {
            consumer.addVertex(matrix, (float) (x + point.x), (float) (y + point.y), (float) (z + point.z))
                    .setColor(206, 125, 255, alpha);
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, TwinsShieldGlyphMesh.Point p,
            double x, double y, double z, double fx, double fz, double radius, int r, int g, int b, int alpha) {
        double wx = -p.x() * fz + p.z() * fx, wy = p.y(), wz = p.x() * fx + p.z() * fz;
        // Glyphs, orbits and membrane bend together so the seals ride the hit wave.
        double bent = radius * ShieldRipple.scale(wx, wy, wz);
        consumer.addVertex(matrix, (float) (x + wx * bent), (float) (y + wy * bent), (float) (z + wz * bent))
                .setColor(r, g, b, alpha);
    }

    private static boolean wasJustBroken(List<ShieldImpact> impacts, int cell, double forwardX, double forwardZ) {
        return impacts.stream().anyMatch(impact -> ShieldCellVisual.justBroken(impact, cell, forwardX, forwardZ));
    }

    private TwinsShieldVisual() { }
}
