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
import dev.hurtify.relicsaddon.drone.ManaArmageddon;
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
 *
 * <p>A Mana blast (see {@link ManaArmageddonVisual}) is drawn later, once the clouds and the weather are in (see
 * {@link #flushMana}), so that its white sky covers the clouds and its column stands behind or before them as it
 * should; it goes in four passes: the same light on the world; its far
 * surfaces at full size ({@code mana_shell}: the back of the sphere of runes, the sun in it, the seal on the ground,
 * the column's far wall); the haze marched small and laid over them ({@code mana_volume}: the vortex, the fog in the
 * sphere, the flash, the ring of dust, the dome of light, the column's haze, the white air); and its near surfaces over
 * that (the front of the sphere, the column's near wall, the crescent moon), so the haze lies between.
 */
public final class ArmageddonVolume {
    private static final List<Blast> BLASTS = new ArrayList<>();
    private static final List<ManaBlast> MANA = new ArrayList<>();
    /** The air's volumes are marched at this fraction of the screen's size. */
    private static final int REDUCE = 3;
    private static ShaderInstance light, shapes, volume, composite, manaShell, manaVolume;
    private static TextureTarget depth, reduced;

    /** The shot in flight this frame, if any: its shell of light is drawn with the blast's shapes. */
    private static Orb orb;

    private record Blast(Vec3 centre, double t) { }

    private record Orb(Vec3 centre, double radius, double glow, double age, boolean solid) { }

    private record ManaBlast(Vec3 centre, ManaStage stage) { }

    /**
     * A Mana blast as it stands this frame (see {@link ManaArmageddonVisual#stage}): {@code t} ticks from its burst, the
     * level way back towards where it was fired from ({@code seam}), and every part's size and strength, with its light
     * on the world ({@code light}, as {@link ArmageddonVisual#lightOnWorld} gives it) and the moon's place and light
     * relative to the camera.
     */
    record ManaStage(double t, Vec3 seam, double sphere, double written, double pressure, double sphereGlow, double sphereTurn,
                     double sun, double sunGlow, double seal, double sealGlow, double sealWave, double sealTurn,
                     double column, double columnGlow, double columnRise, double columnFog, double sphereFog, double groundHaze,
                     double flash, double shockRadius, double shock, double darkCore, double front, double wave, double glare, double white,
                     double vortex, double moonGlow, Vec3 moonAt, Vec3 moonLight, double[][] light) { }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(shader(event, "armageddon_light"), loaded -> light = loaded);
        event.registerShader(shader(event, "armageddon_blast"), loaded -> shapes = loaded);
        event.registerShader(shader(event, "armageddon_volume"), loaded -> volume = loaded);
        event.registerShader(shader(event, "armageddon_composite"), loaded -> composite = loaded);
        event.registerShader(shader(event, "mana_shell"), loaded -> manaShell = loaded);
        event.registerShader(shader(event, "mana_volume"), loaded -> manaVolume = loaded);
    }

    private static ShaderInstance shader(RegisterShadersEvent event, String name) throws IOException {
        return new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, name), DefaultVertexFormat.POSITION);
    }

    /** Queues a blast {@code t} ticks old round a camera-relative centre for the next {@link #flush}. */
    static void queue(Vec3 centre, double t) {
        if (t >= 0 && t <= ArmageddonVisual.BLAST_LIFE && BLASTS.size() < 4 && !ShieldRefraction.shaderPackActive()) BLASTS.add(new Blast(centre, t));
    }

    /** Queues a Mana blast round a camera-relative centre, as it stands this frame, for the next {@link #flush}. */
    static void queueMana(Vec3 centre, ManaStage stage) {
        if (MANA.size() < 4 && !ShieldRefraction.shaderPackActive()) MANA.add(new ManaBlast(centre, stage));
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

    /** Draws the Mana blasts queued this frame: called once the clouds and the weather are in. */
    static void flushMana(Matrix4f pose) {
        if (MANA.isEmpty()) return;
        try {
            if (light != null && composite != null && manaShell != null && manaVolume != null) drawManaAll(pose);
        } finally {
            MANA.clear();
        }
    }

    /** The depth copy and the small target the haze is marched into, made to the screen's size, and the scene's depth copied as it stands now. */
    private static RenderTarget targets() {
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
        return main;
    }

    private static void drawManaAll(Matrix4f pose) {
        RenderTarget main = targets();
        int width = reduced.width, height = reduced.height;
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
        Vector3f[] axes = {view.transformDirection(new Vector3f(0, 1, 0)).normalize(), view.transformDirection(new Vector3f(1, 0, 0)).normalize(),
                view.transformDirection(new Vector3f(0, 0, 1)).normalize()};
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        float pixelAngle = 2F / (Math.abs(projection.m11()) * main.height);
        int depthUnit = RenderSystem.getShaderTexture(1);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            for (ManaBlast blast : MANA) drawMana(blast, view, axes, inverseProjection, pixelAngle, main, width, height);
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

    private static void draw(Matrix4f pose) {
        RenderTarget main = targets();
        int width = reduced.width, height = reduced.height;

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

    /** A Mana blast's four passes: its light on the world, its far surfaces, its haze (marched small), its near surfaces. */
    private static void drawMana(ManaBlast blast, Matrix4f view, Vector3f[] axes, Matrix4f inverseProjection, float pixelAngle, RenderTarget main, int width, int height) {
        ManaStage s = blast.stage;
        Vector3f centre = view.transformPosition(new Vector3f((float) blast.centre.x, (float) blast.centre.y, (float) blast.centre.z));
        Vector3f seam = view.transformDirection(new Vector3f((float) s.seam().x, 0, (float) s.seam().z)).normalize();
        main.bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        begin(light, inverseProjection, centre, axes, main.width, main.height);
        vector(light, "World", s.light()[0]);
        vector(light, "Sky", s.light()[1]);
        vector(light, "Inside", s.light()[2]);
        vector(light, "Glow", s.light()[3]);
        set(light, "Reach", s.light()[4][0]);
        set(light, "Front", s.front());
        screen();

        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        shell(s, 0, view, centre, seam, axes, inverseProjection, pixelAngle, main);
        screen();

        reduced.bindWrite(true);
        RenderSystem.disableBlend();
        begin(manaVolume, inverseProjection, centre, axes, width, height);
        manaVolume.safeGetUniform("Seam").set(seam.x, seam.y, seam.z);
        manaVolume.safeGetUniform("Time").set((float) s.t());
        set(manaVolume, "Reach", ManaArmageddon.RADIUS + 60);
        set(manaVolume, "Vortex", s.vortex());
        set(manaVolume, "SphereRadius", s.sphere());
        set(manaVolume, "SphereFog", s.sphereFog());
        set(manaVolume, "SunRadius", s.sun());
        set(manaVolume, "SunLift", ManaArmageddon.SUN_LIFT);
        set(manaVolume, "SunGlow", s.sunGlow());
        set(manaVolume, "Flash", s.flash());
        set(manaVolume, "ShockRadius", s.shockRadius());
        set(manaVolume, "Shock", s.shock());
        set(manaVolume, "DarkCore", s.darkCore());
        set(manaVolume, "Front", s.front());
        set(manaVolume, "Wave", s.wave());
        set(manaVolume, "Glare", s.glare());
        set(manaVolume, "ColumnRadius", s.column());
        set(manaVolume, "ColumnFog", s.columnFog());
        set(manaVolume, "ColumnTop", ManaArmageddon.COLUMN_TOP);
        set(manaVolume, "SealRadius", s.seal());
        set(manaVolume, "GroundHaze", s.groundHaze());
        set(manaVolume, "White", s.white());
        screen();
        main.bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(() -> composite);
        RenderSystem.setShaderTexture(0, reduced.getColorTextureId());
        composite.safeGetUniform("TexelSize").set(1F / width, 1F / height);
        screen();

        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        shell(s, 1, view, centre, seam, axes, inverseProjection, pixelAngle, main);
        screen();
    }

    /** The Mana shell pass for one side (0 the far surfaces, 1 the near ones), its runes read from the Mana atlas. */
    private static void shell(ManaStage s, int side, Matrix4f view, Vector3f centre, Vector3f seam, Vector3f[] axes, Matrix4f inverseProjection,
            float pixelAngle, RenderTarget main) {
        begin(manaShell, inverseProjection, centre, axes, main.width, main.height);
        RenderSystem.setShaderTexture(0, ManaRunes.textureId());
        manaShell.safeGetUniform("Seam").set(seam.x, seam.y, seam.z);
        manaShell.safeGetUniform("Time").set((float) s.t());
        set(manaShell, "PixelAngle", pixelAngle);
        set(manaShell, "Side", side);
        set(manaShell, "SphereRadius", s.sphere());
        set(manaShell, "SphereWritten", s.written());
        set(manaShell, "SpherePressure", s.pressure());
        set(manaShell, "SphereGlow", s.sphereGlow());
        set(manaShell, "SphereTurn", s.sphereTurn());
        set(manaShell, "SunRadius", s.sun());
        set(manaShell, "SunGlow", s.sunGlow());
        set(manaShell, "SunLift", ManaArmageddon.SUN_LIFT);
        set(manaShell, "SealRadius", s.seal());
        set(manaShell, "SealGlow", s.sealGlow());
        set(manaShell, "SealWave", s.sealWave());
        set(manaShell, "SealTurn", s.sealTurn());
        set(manaShell, "ColumnRadius", s.column());
        set(manaShell, "ColumnGlow", s.columnGlow());
        set(manaShell, "ColumnTop", ManaArmageddon.COLUMN_TOP);
        set(manaShell, "ColumnRise", s.columnRise());
        Vector3f moon = view.transformPosition(new Vector3f((float) s.moonAt().x, (float) s.moonAt().y, (float) s.moonAt().z));
        Vector3f moonLight = view.transformDirection(new Vector3f((float) s.moonLight().x, (float) s.moonLight().y, (float) s.moonLight().z)).normalize();
        manaShell.safeGetUniform("MoonAt").set(moon.x, moon.y, moon.z);
        manaShell.safeGetUniform("MoonLight").set(moonLight.x, moonLight.y, moonLight.z);
        set(manaShell, "MoonRadius", ManaArmageddonVisual.MOON_RADIUS);
        set(manaShell, "MoonGlow", s.moonGlow());
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
