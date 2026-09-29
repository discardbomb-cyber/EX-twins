package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Additive light for the shields: a fresnel halo just outside the shell that brightens around hits
 * and along the ripple crest. It has its own buffer source so it can be filled while the translucent
 * shell batch is still open, and is flushed after the shells so the glow lands on top.
 */
public final class ShieldGlow {
    static final RenderType TYPE = RenderType.create("relic_shield_glow", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES, 1 << 16, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
    private static final Vec3[][] LOW_SPHERE = sphere(14), HIGH_SPHERE = sphere(24);

    static VertexConsumer consumer() {
        return BUFFERS.getBuffer(TYPE);
    }

    static void flush() {
        BUFFERS.endBatch(TYPE);
    }

    static int color(RelicRole role) {
        return switch (role) {
            case MANA_SHIELD -> 0x57D6F2;
            case TWINS_SHIELD -> 0xB25CFF;
            default -> 0x2FD3E6;
        };
    }

    /** Halo shell; {@code activity} is how awake the shield is (threats, recent hits, gathering). */
    static void halo(Matrix4f matrix, RelicRole role, double x, double y, double z, double radius, double activity,
            List<ShieldImpact> impacts, double time, Vec3 eye, boolean low) {
        if (activity <= .01) return;
        int color = color(role);
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        VertexConsumer consumer = consumer();
        double shell = radius * 1.018;
        // From inside, the halo would surround the camera; only hit spots glow there.
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

    private ShieldGlow() {
    }
}
