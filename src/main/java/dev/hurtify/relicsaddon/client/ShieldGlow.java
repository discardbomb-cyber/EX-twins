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
    /** A black hole's horizon: solid, and writing depth so that no light from behind it shows through. */
    static final RenderType HORIZON = RenderType.create("relic_black_hole_horizon", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES, 1 << 12, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
    private static final MultiBufferSource.BufferSource HORIZONS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));

    public static VertexConsumer consumer() {
        return BUFFERS.getBuffer(TYPE);
    }

    public static void flush() {
        BUFFERS.endBatch(TYPE);
    }

    /** Black hole horizons: filled during a frame, flushed before any light, which they then hide behind them. */
    public static VertexConsumer horizonConsumer() {
        return HORIZONS.getBuffer(HORIZON);
    }

    public static void flushHorizons() {
        HORIZONS.endBatch(HORIZON);
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
        (low ? ShieldHalo.LOW : ShieldHalo.HIGH).render(consumer(), matrix, color(role), x, y, z, radius * 1.018, activity, impacts, time, eye);
    }

    private ShieldGlow() {
    }
}
