package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.RelicsAddon;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The pull of the Twins black holes on the world round them: light passing near a horizon is bent round
 * it and swallowed, so the world behind a black hole wraps round it and darkens the nearer it gets.
 *
 * <p>Holes are queued before the drones and the constructs' light are drawn. {@link #flush} then copies
 * the scene's colour and depth once and draws, per hole, a quad over the whole screen whose fragment
 * shader re-samples that copy through a point-mass lens and dims it by the optical depth of a halo round
 * the hole, leaving every pixel whose view ray passes the hole further out than its reach untouched.
 * Surfaces in front of a hole are left alone, so nothing is bent or darkened across it.
 */
public final class BlackHoleLens {
    /** Optical depth of a ray grazing the horizon (it keeps about 7% of its light), and at the reach (none to speak of). */
    private static final double DARKNESS = 2.6, EDGE_DARKNESS = .02;
    /** The Einstein ring's radius, in horizons: a little outside the horizon, so the lensed ring shows round it. */
    private static final double EINSTEIN = 1.1;
    private static final List<Hole> HOLES = new ArrayList<>();
    private static ShaderInstance shader;
    private static TextureTarget copy;

    /**
     * A warp of space round a camera-relative centre: a black hole's pull ({@code einstein}, {@code darkness})
     * or a shock shell's rim ({@code ring} with its width and strength), out to {@code reach}.
     */
    private record Hole(Vec3 centre, double horizon, double reach, double einstein, double darkness, double ring, double ringWidth, double ringStrength) { }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "black_hole"), DefaultVertexFormat.POSITION),
                loaded -> shader = loaded);
    }

    /** Queues a hole at a camera-relative point for the next {@link #flush}; space is pulled out to {@code reach}. */
    static void queue(Vec3 centre, double horizon, double reach) {
        queue(centre, horizon, reach, DARKNESS);
    }

    /** As {@link #queue(Vec3, double, double)}, darkening the world round it by {@code darkness} (none at 0). */
    static void queue(Vec3 centre, double horizon, double reach, double darkness) {
        queue(centre, horizon, reach, darkness, EINSTEIN);
    }

    /** As above, with its Einstein ring {@code einstein} horizons out: the further, the harder it bends the world. */
    static void queue(Vec3 centre, double horizon, double reach, double darkness, double einstein) {
        if (!(horizon > 0) || !(reach > horizon) || HOLES.size() >= 24 || !ShieldRefraction.enabled()) return;
        double strength = Math.min(3, AddonClientConfig.refractionStrength());
        HOLES.add(new Hole(centre, horizon, reach, horizon * einstein * Math.sqrt(strength), darkness, 0, 1, 0));
    }

    /** Queues a shock shell of {@code radius} round a camera-relative point, bending the world behind its rim by up to about {@code strength} widths. */
    static void queueShock(Vec3 centre, double radius, double width, double strength) {
        if (!(radius > 0) || !(width > 0) || HOLES.size() >= 24 || !ShieldRefraction.enabled()) return;
        double gain = Math.min(3, AddonClientConfig.refractionStrength());
        HOLES.add(new Hole(centre, radius * .3, radius + width * 3, 0, 0, radius, width, strength * gain));
    }

    static void flush(Matrix4f pose) {
        if (HOLES.isEmpty()) return;
        try {
            if (shader != null && ShieldRefraction.enabled()) draw(pose);
        } finally {
            HOLES.clear();
        }
    }

    private static void draw(Matrix4f pose) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (copy == null) {
            copy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            copy.setFilterMode(GL11.GL_LINEAR);
        }
        // The depth copy is a blit, which needs both targets to keep depth the same way.
        if (main.isStencilEnabled() && !copy.isStencilEnabled()) copy.enableStencil();
        if (copy.width != main.width || copy.height != main.height) copy.resize(main.width, main.height, Minecraft.ON_OSX);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, copy.width, copy.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        copy.copyDepthFrom(main);
        main.bindWrite(false);

        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
        Matrix4f inverseProjection = new Matrix4f(RenderSystem.getProjectionMatrix()).invert();
        int depthUnit = RenderSystem.getShaderTexture(1);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, copy.getColorTextureId());
        RenderSystem.setShaderTexture(1, copy.getDepthTextureId());
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] previousScissor = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previousScissor);
        try {
            for (Hole hole : HOLES) {
                Vector3f centre = view.transformPosition(new Vector3f((float) hole.centre.x, (float) hole.centre.y, (float) hole.centre.z));
                LensScreenBounds.Bounds bounds = LensScreenBounds.of(centre, hole.reach, RenderSystem.getProjectionMatrix(), main.width, main.height);
                if (bounds.empty()) continue;
                int left = bounds.leftPixel(main.width), bottom = bounds.bottomPixel(main.height);
                int right = bounds.rightPixel(main.width), top = bounds.topPixel(main.height);
                if (scissorEnabled) {
                    left = Math.max(left, previousScissor[0]); bottom = Math.max(bottom, previousScissor[1]);
                    right = Math.min(right, previousScissor[0] + previousScissor[2]);
                    top = Math.min(top, previousScissor[1] + previousScissor[3]);
                }
                if (left >= right || bottom >= top) continue;
                RenderSystem.enableScissor(left, bottom, right - left, top - bottom);
                // The halo's width: dark near the horizon, fading to next to nothing at the reach.
                double halo = hole.darkness > 0 ? Math.sqrt((hole.reach * hole.reach - hole.horizon * hole.horizon) / Math.log(hole.darkness / EDGE_DARKNESS)) : 1;
                shader.safeGetUniform("InverseProj").set(inverseProjection);
                shader.safeGetUniform("Centre").set(centre.x, centre.y, centre.z);
                shader.safeGetUniform("Horizon").set((float) hole.horizon);
                shader.safeGetUniform("Einstein").set((float) hole.einstein);
                shader.safeGetUniform("Reach").set((float) hole.reach);
                shader.safeGetUniform("Darkness").set((float) hole.darkness);
                shader.safeGetUniform("Halo").set((float) halo);
                shader.safeGetUniform("Ring").set((float) hole.ring);
                shader.safeGetUniform("RingWidth").set((float) hole.ringWidth);
                shader.safeGetUniform("RingStrength").set((float) hole.ringStrength);
                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
                screen(builder);
                MeshData mesh = builder.build();
                if (mesh != null) BufferUploader.drawWithShader(mesh);
            }
        } finally {
            RenderSystem.enableScissor(previousScissor[0], previousScissor[1], previousScissor[2], previousScissor[3]);
            if (!scissorEnabled) RenderSystem.disableScissor();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderTexture(1, depthUnit);
        }
    }

    /**
     * Keep the original full-screen triangles and interpolation. A conservative scissor removes
     * fragments outside Reach without changing the view rays along the lens's visible boundary.
     */
    private static void screen(BufferBuilder builder) {
        builder.addVertex(-1, -1, 0);
        builder.addVertex(1, -1, 0);
        builder.addVertex(1, 1, 0);
        builder.addVertex(-1, -1, 0);
        builder.addVertex(1, 1, 0);
        builder.addVertex(-1, 1, 0);
    }

    private BlackHoleLens() {
    }
}
