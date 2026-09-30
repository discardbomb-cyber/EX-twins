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
import dev.hurtify.relicsaddon.drone.Armageddon;
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

/**
 * The Armageddon supernova drawn into the finished world in three passes (see {@link ArmageddonVisual} for its
 * stages): the world is tinted and lit by the blast ({@code armageddon_light}: burnt dark red in the flash,
 * bright inside the ball of light and dark outside it, night round the eruption lit violet by its beam); the
 * blast's shapes go over it with sharp edges ({@code armageddon_blast}: the ball of light, the beam with its crown
 * of flames, black pillar and black spheres, and the shot's shell of light before any of it); and the light
 * hanging in the air, the flash's white among it, is marched at a third of the screen's size and laid over all of
 * that ({@code armageddon_volume}). All three read the world's depth and keep to the world's own axes, so
 * whatever of the world stands in front of the blast hides it and nothing turns as the camera turns. Blasts are
 * queued before the drones and the constructs' light go over the world, and drawn by {@link #flush}.
 */
public final class ArmageddonVolume {
    private static final List<Blast> BLASTS = new ArrayList<>();
    /** The air's volumes are marched at this fraction of the screen's size. */
    private static final int REDUCE = 3;
    private static ShaderInstance light, shapes, volume, composite;
    private static TextureTarget depth, reduced;

    /** The shot in flight this frame, if any: its shell of light is drawn with the blast's shapes. */
    private static Orb orb;

    private record Blast(Vec3 centre, double t) { }

    private record Orb(Vec3 centre, double radius, double glow, double age, boolean solid) { }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(shader(event, "armageddon_light"), loaded -> light = loaded);
        event.registerShader(shader(event, "armageddon_blast"), loaded -> shapes = loaded);
        event.registerShader(shader(event, "armageddon_volume"), loaded -> volume = loaded);
        event.registerShader(shader(event, "armageddon_composite"), loaded -> composite = loaded);
    }

    private static ShaderInstance shader(RegisterShadersEvent event, String name) throws IOException {
        return new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, name), DefaultVertexFormat.POSITION);
    }

    /** Queues a blast {@code t} ticks old round a camera-relative centre for the next {@link #flush}. */
    static void queue(Vec3 centre, double t) {
        if (t >= 0 && t <= ArmageddonVisual.BLAST_LIFE && BLASTS.size() < 4 && !ShieldRefraction.shaderPackActive()) BLASTS.add(new Blast(centre, t));
    }

    /** Queues the shot's ball (or shell, when not {@code solid}) of light round a camera-relative centre for the next {@link #flush}. */
    static void queueOrb(Vec3 centre, double radius, double glow, double age, boolean solid) {
        if (radius > 0 && glow > 0 && !ShieldRefraction.shaderPackActive()) orb = new Orb(centre, radius, glow, age, solid);
    }

    static void flush(Matrix4f pose) {
        if (BLASTS.isEmpty() && orb == null) return;
        try {
            if (light != null && shapes != null && volume != null && composite != null) draw(pose);
        } finally {
            BLASTS.clear();
            orb = null;
        }
    }

    private static void draw(Matrix4f pose) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (depth == null) depth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled() && !depth.isStencilEnabled()) depth.enableStencil();
        if (depth.width != main.width || depth.height != main.height) depth.resize(main.width, main.height, Minecraft.ON_OSX);
        int width = Math.max(1, main.width / REDUCE), height = Math.max(1, main.height / REDUCE);
        if (reduced == null) {
            reduced = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            reduced.setFilterMode(GL11.GL_LINEAR);
        } else if (reduced.width != width || reduced.height != height) reduced.resize(width, height, Minecraft.ON_OSX);
        depth.copyDepthFrom(main);

        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
        // The world's own axes as the camera sees them: everything drawn keeps to them, so nothing turns with the camera.
        Vector3f[] axes = {view.transformDirection(new Vector3f(0, 1, 0)).normalize(), view.transformDirection(new Vector3f(1, 0, 0)).normalize(),
                view.transformDirection(new Vector3f(0, 0, 1)).normalize()};
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        // How wide one pixel is a block from the eye.
        float pixelAngle = 2F / (Math.abs(projection.m11()) * main.height);
        int depthUnit = RenderSystem.getShaderTexture(1);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            // With no blast yet, the shot's shell of light still goes into a pass of the shapes of its own.
            List<Blast> blasts = BLASTS.isEmpty() ? List.of(new Blast(orb.centre, -1)) : BLASTS;
            boolean shellDrawn = false;
            for (Blast blast : blasts) {
                Vector3f centre = view.transformPosition(new Vector3f((float) blast.centre.x, (float) blast.centre.y, (float) blast.centre.z));
                double t = blast.t, near = ArmageddonVisual.near(blast.centre.length());
                boolean landed = t >= 0;
                main.bindWrite(true);
                RenderSystem.enableBlend();

                // The world darkened and lit by the blast: it becomes the world times (light + what is kept).
                if (landed) {
                    RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_ALPHA,
                            GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
                    begin(light, inverseProjection, centre, axes, main.width, main.height);
                    stageLight(t, near);
                    screen();
                }

                // The blast's shapes over it (and the shot's shell of light, once), with premultiplied alpha.
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
                begin(shapes, inverseProjection, centre, axes, main.width, main.height);
                shapes.safeGetUniform("Time").set((float) t);
                shapes.safeGetUniform("PixelAngle").set(pixelAngle);
                stageShapes(t);
                if (orb != null && !shellDrawn) {
                    Vector3f at = view.transformPosition(new Vector3f((float) orb.centre.x, (float) orb.centre.y, (float) orb.centre.z));
                    shapes.safeGetUniform("Orb").set(at.x, at.y, at.z);
                    set(shapes, "OrbRadius", orb.radius);
                    set(shapes, "OrbGlow", orb.glow);
                    set(shapes, "OrbTime", orb.age);
                    set(shapes, "OrbSolid", orb.solid ? 1 : 0);
                    shellDrawn = true;
                } else set(shapes, "OrbGlow", 0);
                screen();
                if (!landed) continue;

                // What hangs in the air, marched small, then laid over everything: its light added, the rest dimmed by what it lets through.
                reduced.bindWrite(true);
                RenderSystem.disableBlend();
                begin(volume, inverseProjection, centre, axes, width, height);
                volume.safeGetUniform("Time").set((float) t);
                stageVolume(t);
                screen();
                main.bindWrite(true);
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
                RenderSystem.setShader(() -> composite);
                RenderSystem.setShaderTexture(0, reduced.getColorTextureId());
                composite.safeGetUniform("TexelSize").set(1F / width, 1F / height);
                screen();
            }
        } finally {
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderTexture(1, depthUnit);
            main.bindWrite(true);
        }
    }

    private static void begin(ShaderInstance shader, Matrix4f inverseProjection, Vector3f centre, Vector3f[] axes, int width, int height) {
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(1, depth.getDepthTextureId());
        shader.safeGetUniform("InverseProj").set(inverseProjection);
        shader.safeGetUniform("TargetSize").set((float) width, (float) height);
        shader.safeGetUniform("Centre").set(centre.x, centre.y, centre.z);
        shader.safeGetUniform("Up").set(axes[0].x, axes[0].y, axes[0].z);
        shader.safeGetUniform("East").set(axes[1].x, axes[1].y, axes[1].z);
        shader.safeGetUniform("North").set(axes[2].x, axes[2].y, axes[2].z);
    }

    private static void screen() {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (float[] corner : corners) builder.addVertex(corner[0], corner[1], 0);
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /** The blast's light on the world (see {@link ArmageddonVisual#lightOnWorld}). */
    private static void stageLight(double t, double near) {
        double[][] on = ArmageddonVisual.lightOnWorld(t, near);
        vector(light, "World", on[0]);
        vector(light, "Sky", on[1]);
        vector(light, "Inside", on[2]);
        vector(light, "Glow", on[3]);
        set(light, "Reach", on[4][0]);
        set(light, "Front", Armageddon.ball(t));
    }

    private static void stageShapes(double t) {
    }

    private static void stageVolume(double t) {
        set(volume, "Flash", ArmageddonVisual.flash(t));
        set(volume, "Front", Armageddon.ball(t));
        set(volume, "Glare", ArmageddonVisual.glare(t));
        set(volume, "Wave", ArmageddonVisual.ballAlpha(t));
        set(volume, "Magenta", ArmageddonVisual.magenta(t));
        set(volume, "Pillar", ArmageddonVisual.beamRadius(t));
        set(volume, "PillarGlow", ArmageddonVisual.beamGlow(t));
        set(volume, "Dust", 0);
        set(volume, "Core", ArmageddonVisual.core(t));
        set(volume, "Spheres", ArmageddonVisual.spheres(t));
        set(volume, "Reach", Armageddon.RADIUS + 40);
    }

    private static void vector(ShaderInstance shader, String uniform, double[] value) {
        shader.safeGetUniform(uniform).set((float) value[0], (float) value[1], (float) value[2]);
    }

    private static void set(ShaderInstance shader, String uniform, double value) {
        shader.safeGetUniform(uniform).set((float) value);
    }

    private ArmageddonVolume() {
    }
}
