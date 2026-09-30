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

    private record Hole(Vec3 centre, double horizon, double reach) { }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "black_hole"), DefaultVertexFormat.POSITION),
                loaded -> shader = loaded);
    }

    /** Queues a hole at a camera-relative point for the next {@link #flush}; space is pulled out to {@code reach}. */
    static void queue(Vec3 centre, double horizon, double reach) {
        if (!(horizon > 0) || !(reach > horizon) || HOLES.size() >= 16 || !ShieldRefraction.enabled()) return;
        HOLES.add(new Hole(centre, horizon, reach));
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
        double strength = Math.min(3, AddonClientConfig.refractionStrength());
        int depthUnit = RenderSystem.getShaderTexture(1);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, copy.getColorTextureId());
        RenderSystem.setShaderTexture(1, copy.getDepthTextureId());
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            for (Hole hole : HOLES) {
                Vector3f centre = view.transformPosition(new Vector3f((float) hole.centre.x, (float) hole.centre.y, (float) hole.centre.z));
                // The halo's width: dark near the horizon, fading to next to nothing at the reach.
                double halo = Math.sqrt((hole.reach * hole.reach - hole.horizon * hole.horizon) / Math.log(DARKNESS / EDGE_DARKNESS));
                shader.safeGetUniform("InverseProj").set(inverseProjection);
                shader.safeGetUniform("Centre").set(centre.x, centre.y, centre.z);
                shader.safeGetUniform("Horizon").set((float) hole.horizon);
                shader.safeGetUniform("Einstein").set((float) (hole.horizon * EINSTEIN * Math.sqrt(strength)));
                shader.safeGetUniform("Reach").set((float) hole.reach);
                shader.safeGetUniform("Darkness").set((float) DARKNESS);
                shader.safeGetUniform("Halo").set((float) halo);
                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
                screen(builder);
                MeshData mesh = builder.build();
                if (mesh != null) BufferUploader.drawWithShader(mesh);
            }
        } finally {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderTexture(1, depthUnit);
        }
    }

    /**
     * The whole screen, in normalised device coordinates: a hole's reach can cover any part of it (all of
     * it when the camera is inside the reach), and the shader drops every pixel it does not.
     */
    private static void screen(BufferBuilder builder) {
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (float[] corner : corners) builder.addVertex(corner[0], corner[1], 0);
    }

    private BlackHoleLens() {
    }
}
