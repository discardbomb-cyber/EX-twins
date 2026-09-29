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
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Screen-space refraction band that rides the hit wave of the Mana and Twins shields.
 *
 * <p>Shells queue their active waves while rendering; {@link #flush} then copies the colour of the
 * already-drawn scene once, and draws a thin spherical band per wave whose fragment shader samples
 * that copy with an offset taken from the band's screen-space slope. The shell glass is drawn after
 * this pass, so it sits on top of the bent image.
 */
public final class ShieldRefraction {
    private static final int RINGS = 10, SEGMENTS = 56;
    private static final double BAND = .42;
    private static final List<Job> JOBS = new ArrayList<>();
    private static ShaderInstance shader;
    private static TextureTarget sceneCopy;
    private static Boolean irisPresent;

    private record Job(double x, double y, double z, double radius, RelicRole role, List<ShieldImpact> impacts, double time) { }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "shield_refraction"), DefaultVertexFormat.POSITION_COLOR),
                loaded -> shader = loaded);
    }

    static void queue(RelicRole role, double x, double y, double z, double radius, List<ShieldImpact> impacts, double time, boolean low) {
        if (low || (role != RelicRole.MANA_SHIELD && role != RelicRole.TWINS_SHIELD) || !enabled()) return;
        List<ShieldImpact> live = impacts.stream().filter(impact -> impact.absorbed() > 0
                && time >= impact.gameTime() && time - impact.gameTime() < ShieldResponse.IMPACT_TICKS).toList();
        if (!live.isEmpty()) JOBS.add(new Job(x, y, z, radius, role, live, time));
    }

    static void flush(Matrix4f pose) {
        if (JOBS.isEmpty()) return;
        try {
            if (shader != null && enabled()) draw(pose);
        } finally {
            JOBS.clear();
        }
    }

    private static boolean enabled() {
        return AddonClientConfig.refraction() && AddonClientConfig.refractionStrength() > 0 && !shaderPackActive();
    }

    /** Iris/Oculus replace the pipeline; sampling the vanilla target there would show stale or black pixels. */
    private static boolean shaderPackActive() {
        if (irisPresent == null) irisPresent = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
        if (!irisPresent) return false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isShaderPackInUse").invoke(instance);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return true;
        }
    }

    private static void draw(Matrix4f pose) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (sceneCopy == null) {
            sceneCopy = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            sceneCopy.setFilterMode(GL11.GL_LINEAR);
        } else if (sceneCopy.width != main.width || sceneCopy.height != main.height) {
            sceneCopy.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneCopy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, sceneCopy.width, sceneCopy.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (Job job : JOBS) for (ShieldImpact impact : job.impacts) band(builder, pose, job, impact);
        MeshData mesh = builder.build();
        if (mesh == null) return;

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, sceneCopy.getColorTextureId());
        shader.safeGetUniform("RefractionGain").set((float) (220 * AddonClientConfig.refractionStrength()));
        int tint = JOBS.getFirst().role.color();
        shader.safeGetUniform("Tint").set((tint >> 16 & 255) / 255F, (tint >> 8 & 255) / 255F, (tint & 255) / 255F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** A spherical band centred on the wave front, bent by the same displacement as the glass. */
    private static void band(BufferBuilder builder, Matrix4f pose, Job job, ShieldImpact impact) {
        double age = job.time - impact.gameTime();
        double front = ShieldRipple.front(age);
        double from = Math.max(0, front - BAND), to = Math.min(Math.PI, front + BAND);
        if (to - from < .02) return;
        double strength = ShieldRipple.strength(impact.absorbed()) * ShieldRipple.fade(age);
        Vec3 n = impact.normal();
        Vec3 t1 = n.cross(Math.abs(n.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 t2 = n.cross(t1).normalize();
        Vec3[][] points = new Vec3[RINGS + 1][SEGMENTS + 1];
        int[][] colors = new int[RINGS + 1][SEGMENTS + 1];
        for (int ring = 0; ring <= RINGS; ring++) {
            double theta = from + (to - from) * ring / RINGS;
            // Soft window so the band has no visible edge.
            double window = Math.sin(Math.PI * ring / RINGS);
            double height = ShieldRipple.profile(Math.cos(theta), age) * ShieldRipple.strength(impact.absorbed());
            int red = (int) Math.round(Math.clamp(height * .5 + .5, 0, 1) * 255);
            int alpha = (int) Math.round(Math.clamp(strength * window * window, 0, 1) * 255);
            double sin = Math.sin(theta), cos = Math.cos(theta);
            for (int segment = 0; segment <= SEGMENTS; segment++) {
                double phi = Math.PI * 2 * segment / SEGMENTS;
                Vec3 dir = n.scale(cos).add(t1.scale(Math.cos(phi) * sin)).add(t2.scale(Math.sin(phi) * sin));
                double radius = job.radius * (1 + height * ShieldRipple.AMPLITUDE * AddonClientConfig.rippleStrength()) * 1.004;
                points[ring][segment] = new Vec3(job.x + dir.x * radius, job.y + dir.y * radius, job.z + dir.z * radius);
                colors[ring][segment] = alpha << 24 | red << 16;
            }
        }
        for (int ring = 0; ring < RINGS; ring++) for (int segment = 0; segment < SEGMENTS; segment++) {
            put(builder, pose, points[ring][segment], colors[ring][segment]);
            put(builder, pose, points[ring + 1][segment], colors[ring + 1][segment]);
            put(builder, pose, points[ring + 1][segment + 1], colors[ring + 1][segment + 1]);
            put(builder, pose, points[ring][segment], colors[ring][segment]);
            put(builder, pose, points[ring + 1][segment + 1], colors[ring + 1][segment + 1]);
            put(builder, pose, points[ring][segment + 1], colors[ring][segment + 1]);
        }
    }

    private static void put(BufferBuilder builder, Matrix4f pose, Vec3 point, int argb) {
        builder.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(argb >> 16 & 255, 128, 128, argb >>> 24);
    }

    private ShieldRefraction() {
    }
}
