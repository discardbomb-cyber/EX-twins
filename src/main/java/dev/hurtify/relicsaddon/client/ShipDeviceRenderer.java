package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ShipBlocks;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlock;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the moving parts of the ship shield generators and drone docks. The block model is only the
 * device's static body (and a generator's lit floor ring); the parts that move - the RF tower's
 * masts and antenna, the Mana cube's six plates and inner star, the Ex-Twins ring of galaxy orbs
 * and gold threads, the RF dock's sliding modules, the Mana dock's ring and shards, the Ex-Twins
 * dock's plexus - are OBJ groups of the same model (tools/build_ship_device_meshes.mjs), loaded as
 * standalone part models and posed here each frame.
 *
 * <p>A generator eases open when it is switched on (masts unfold one by one, the cube opens into
 * a star, the ring spins up and the threads reach the sphere) and eases shut when switched off; a
 * dock eases into its "drone aboard" pose while it holds drones. Lit parts report dynamic light
 * through {@link EffectLights}.
 */
@OnlyIn(Dist.CLIENT)
public final class ShipDeviceRenderer implements BlockEntityRenderer<ShipDeviceBlockEntity> {
    /** Ticks a generator takes to open, and a dock to bring its modules out. */
    private static final double OPEN_TICKS = 60, DOCK_TICKS = 20;
    private static final int MANA_PLATES = 6, RF_MASTS = 4, RF_DOCK_MODULES = 3, MANA_DOCK_SHARDS = 3;
    private static final Map<ShipDeviceBlockEntity, View> VIEWS = new WeakHashMap<>();

    /** What each device looks like right now: how far open it is and how fast its ring turns. */
    private static final class View {
        double open = Double.NaN, spin, drawnAt = Double.NaN;
        /** Ticks since the last frame, for motion that accumulates (spins). */
        double dt;
        long sparkTick = Long.MIN_VALUE;
    }

    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ShipBlocks.DEVICE.get(), context -> new ShipDeviceRenderer());
    }

    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (RelicRole role : RelicRole.values()) {
            if (!role.isShipDevice()) continue;
            event.register(ModelResourceLocation.standalone(partId(role, "core")));
            event.register(ModelResourceLocation.standalone(partId(role, "fx")));
            for (int index = 0; index < shellCount(role); index++) event.register(ModelResourceLocation.standalone(partId(role, "shell_" + index)));
        }
    }

    @Override public boolean shouldRenderOffScreen(ShipDeviceBlockEntity device) { return true; }
    @Override public int getViewDistance() { return 96; }

    @Override
    public void render(ShipDeviceBlockEntity device, float partial, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        Level level = device.getLevel();
        if (level == null) return;
        BlockState state = device.getBlockState();
        if (!(state.getBlock() instanceof ShipDeviceBlock block)) return;
        RelicRole role = block.role();
        double time = level.getGameTime() + partial;
        View view = VIEWS.computeIfAbsent(device, key -> new View());
        boolean active = role.isShipGenerator() ? state.hasProperty(ShipDeviceBlock.LIT) && state.getValue(ShipDeviceBlock.LIT) : device.state().drones() > 0;
        advance(view, active, role.isShipGenerator() ? OPEN_TICKS : DOCK_TICKS, time);
        double open = view.open;
        Vec3 centre = Vec3.atCenterOf(device.getBlockPos());

        poses.pushPose();
        // The block model turns with the facing (blockstate y rotation); the parts turn the same way.
        Direction facing = state.hasProperty(ShipDeviceBlock.FACING) ? state.getValue(ShipDeviceBlock.FACING) : Direction.NORTH;
        poses.translate(.5, .5, .5);
        poses.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180)));
        poses.translate(-.5, -.5, -.5);
        VertexConsumer buffer = buffers.getBuffer(Sheets.cutoutBlockSheet());
        int bright = LightTexture.FULL_BRIGHT;
        switch (role) {
            case RF_SHIP_GENERATOR -> rfGenerator(device, role, state, view, open, time, poses, buffer, light, bright, overlay, centre, level);
            case MANA_SHIP_GENERATOR -> manaGenerator(role, state, open, time, poses, buffer, light, bright, overlay, centre);
            case TWINS_SHIP_GENERATOR -> twinsGenerator(role, state, view, open, time, poses, buffer, light, bright, overlay, centre);
            case RF_DRONE_DOCK -> rfDock(role, state, open, time, poses, buffer, light, bright, overlay, centre);
            case MANA_DRONE_DOCK -> manaDock(role, state, view, open, time, poses, buffer, light, bright, overlay, centre);
            case TWINS_DRONE_DOCK -> twinsDock(role, state, open, time, poses, buffer, light, bright, overlay, centre);
            default -> { }
        }
        poses.popPose();
    }

    /** Eases the view's openness towards the device's state; a device first seen in its final state starts there. */
    private static void advance(View view, boolean active, double ticks, double time) {
        boolean fresh = Double.isNaN(view.open) || Double.isNaN(view.drawnAt) || time < view.drawnAt || time - view.drawnAt > 200;
        view.dt = fresh ? 0 : time - view.drawnAt;
        if (fresh) view.open = active ? 1 : 0;
        else view.open = Mth.clamp(view.open + (active ? 1 : -1) * view.dt / ticks, 0, 1);
        view.drawnAt = time;
    }

    private static double ease(double t) { return t * t * (3 - 2 * t); }

    /** The share of a staggered opening that part {@code index} of {@code count} has done, eased. */
    private static double stagger(double open, int index, int count) {
        return ease(Mth.clamp(open * count - index, 0, 1));
    }

    // --- the RF emitter tower: masts unfold one by one, sparks run along them; lamps blink at rest ----
    private void rfGenerator(ShipDeviceBlockEntity device, RelicRole role, BlockState state, View view, double open, double time, PoseStack poses,
                             VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre, Level level) {
        poses.pushPose();
        about(poses, .5, .5, 0, 1, 0, (float) (time * 1.5));
        part(role, "core", state, poses, buffer, light, overlay);
        poses.popPose();
        boolean lamps = open > 0 || (Math.floorMod((long) (time / 4), 20) == 0);
        if (lamps) part(role, "fx", state, poses, buffer, bright, overlay);
        for (int index = 0; index < RF_MASTS; index++) {
            double share = stagger(open, index, RF_MASTS);
            double angle = index * Math.PI / 2 + Math.PI / 4;
            float dx = (float) Math.cos(angle), dz = (float) Math.sin(angle);
            float px = .5F + dx * .21F, py = .81F, pz = .5F + dz * .21F;
            poses.pushPose();
            poses.translate(px, py, pz);
            poses.mulPose(Axis.of(new org.joml.Vector3f(-dz, 0, dx)).rotation((float) (-2.1 * share)));
            poses.translate(-px, -py, -pz);
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
            // Sparks run down a mast while it swings up.
            long tick = (long) time;
            if (share > 0 && share < 1 && tick != view.sparkTick) {
                view.sparkTick = tick;
                double along = .1 + level.random.nextDouble() * .45;
                Vec3 mast = mastPoint(device, state, angle, -2.1 * share, along);
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, mast.x, mast.y, mast.z, 0, 0, 0);
            }
        }
        if (open > 0) EffectLights.glow(centre.add(0, .4, 0), 6 + 6 * open, 3 + 2 * open);
    }

    /** A point {@code along} blocks down mast {@code angle} swung by {@code swing} radians, in world space. */
    private static Vec3 mastPoint(ShipDeviceBlockEntity device, BlockState state, double angle, double swing, double along) {
        Direction facing = state.hasProperty(ShipDeviceBlock.FACING) ? state.getValue(ShipDeviceBlock.FACING) : Direction.NORTH;
        double yaw = Math.toRadians(-(facing.toYRot() + 180));
        // In model space: pivot plus the mast direction (down, swung about the tangent).
        double dx = Math.cos(angle), dz = Math.sin(angle);
        double down = -Math.cos(swing), out = Math.sin(swing);
        // Swinging about the tangent (-dz, 0, dx) by -swing tips the down vector outward along (dx, 0, dz).
        double x = .5 + dx * .21 + dx * out * along, y = .81 + down * along, z = .5 + dz * .21 + dz * out * along;
        double rx = .5 + (x - .5) * Math.cos(yaw) + (z - .5) * Math.sin(yaw), rz = .5 - (x - .5) * Math.sin(yaw) + (z - .5) * Math.cos(yaw);
        return Vec3.atLowerCornerOf(device.getBlockPos()).add(rx, y, rz);
    }

    // --- the Mana holocron: the cube turns slowly; switched on, its plates swing out and the star inside lights ----
    private void manaGenerator(RelicRole role, BlockState state, double open, double time, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        float bob = (float) (Math.sin(time * .05) * .02);
        poses.pushPose();
        poses.translate(0, bob, 0);
        about(poses, .5, .6, 0, 1, 0, (float) (time * (1 + 2 * open)));
        if (open > 0) {
            poses.pushPose();
            about(poses, .5, .6, 1, .4F, .6F, (float) (time * 3));
            float scale = (float) (.4 + .6 * ease(open));
            poses.translate(.5, .6, .5);
            poses.scale(scale, scale, scale);
            poses.translate(-.5, -.6, -.5);
            part(role, "core", state, poses, buffer, bright, overlay);
            poses.popPose();
        }
        float[][] normals = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int index = 0; index < MANA_PLATES; index++) {
            float[] n = normals[index];
            double share = ease(open);
            poses.pushPose();
            poses.translate(n[0] * .24 * share, n[1] * .24 * share, n[2] * .24 * share);
            about(poses, .5, .6, n[0], n[1], n[2], (float) (45 * share));
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
        }
        poses.popPose();
        part(role, "fx", state, poses, buffer, open > 0 ? bright : light, overlay);
        if (open > 0) EffectLights.glow(centre.add(0, .1, 0), 5 + 9 * open, 3 + 3 * open);
    }

    // --- the Ex-Twins sphere: the ring of orbs spins up and the gold threads reach the sphere ----
    private void twinsGenerator(RelicRole role, BlockState state, View view, double open, double time, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        view.spin = (view.spin + ease(open) * 2.5 * view.dt) % 360;
        poses.pushPose();
        about(poses, .5, .62, 0, 1, 0, (float) view.spin);
        part(role, "core", state, poses, buffer, open > .5 ? bright : light, overlay);
        poses.popPose();
        if (open > .02) {
            poses.pushPose();
            poses.translate(0, .3, 0);
            poses.scale(1, (float) ease(open), 1);
            poses.translate(0, -.3, 0);
            part(role, "fx", state, poses, buffer, bright, overlay);
            poses.popPose();
            EffectLights.glow(centre.add(0, .12, 0), 4 + 8 * open, 3 + 2 * open);
        }
    }

    // --- the RF charging base: modules slide out and the rings burn brighter with drones aboard ----
    private void rfDock(RelicRole role, BlockState state, double open, double time, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        double share = ease(open);
        part(role, "fx", state, poses, buffer, light, overlay);
        if (open > 0) {
            poses.pushPose();
            float pulse = (float) (1 + .04 * Math.sin(time * .3));
            poses.translate(.5, 0, .5);
            poses.scale(pulse, 1, pulse);
            poses.translate(-.5, 0, -.5);
            part(role, "core", state, poses, buffer, bright, overlay);
            poses.popPose();
        }
        for (int index = 0; index < RF_DOCK_MODULES; index++) {
            double angle = index * 2 * Math.PI / 3 + Math.PI / 2;
            poses.pushPose();
            poses.translate(Math.cos(angle) * .13 * share, 0, Math.sin(angle) * .13 * share);
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
        }
        EffectLights.glow(centre.add(0, -.3, 0), 3 + 7 * open, 2 + 2 * open);
    }

    // --- the Mana rune dock: ring and shards circle, faster with drones aboard, and the runes burn ----
    private void manaDock(RelicRole role, BlockState state, View view, double open, double time, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        view.spin = (view.spin + (.4 + 3 * ease(open)) * view.dt) % 360;
        poses.pushPose();
        about(poses, .5, .62, 0, 1, 0, (float) view.spin);
        part(role, "shell_0", state, poses, buffer, light, overlay);
        poses.popPose();
        poses.pushPose();
        float breathe = (float) (1 + .06 * Math.sin(time * .15));
        poses.translate(.5, .62, .5);
        poses.scale(breathe, breathe, breathe);
        poses.translate(-.5, -.62, -.5);
        part(role, "core", state, poses, buffer, bright, overlay);
        poses.popPose();
        for (int index = 1; index <= MANA_DOCK_SHARDS; index++) {
            poses.pushPose();
            poses.translate(0, Math.sin(time * .08 + index * 2.1) * .03 * (1 + open), 0);
            about(poses, .5, .5, 0, 1, 0, (float) (-view.spin * 1.6));
            about(poses, .5, .9, (float) Math.cos(index * 2.1), 0, (float) Math.sin(index * 2.1), (float) (8 * Math.sin(time * .1 + index)));
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
        }
        if (open > 0) part(role, "fx", state, poses, buffer, bright, overlay);
        EffectLights.glow(centre.add(0, .12, 0), 5 + 7 * open, 2.5 + 2 * open);
    }

    // --- the Ex-Twins lab sphere: the violet light pulses and the plexus draws in with drones aboard ----
    private void twinsDock(RelicRole role, BlockState state, double open, double time, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        double pulse = .5 + .5 * Math.sin(time * (.12 + .25 * open));
        float scale = (float) (1 - .18 * ease(open) + .05 * pulse);
        poses.pushPose();
        poses.translate(.5, .5, .5);
        poses.scale(scale, scale, scale);
        poses.mulPose(Axis.YP.rotationDegrees((float) (time * (.8 + 3 * open))));
        poses.mulPose(Axis.XP.rotationDegrees((float) (time * .5)));
        poses.translate(-.5, -.5, -.5);
        part(role, "core", state, poses, buffer, bright, overlay);
        poses.popPose();
        part(role, "fx", state, poses, buffer, bright, overlay);
        EffectLights.glow(centre, 3 + 5 * open + 4 * pulse * (0.3 + open), 2.5 + 2 * open);
    }

    // --- helpers ---------------------------------------------------------------------------------
    private static void about(PoseStack poses, double x, double y, double axisX, double axisY, double axisZ, float degrees) {
        poses.translate(x, y, .5);
        poses.mulPose(Axis.of(new org.joml.Vector3f((float) axisX, (float) axisY, (float) axisZ).normalize()).rotationDegrees(degrees));
        poses.translate(-x, -y, -.5);
    }

    private static void part(RelicRole role, String part, BlockState state, PoseStack poses, VertexConsumer buffer, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getModelManager().getModel(ModelResourceLocation.standalone(partId(role, part)));
        minecraft.getBlockRenderer().getModelRenderer().renderModel(poses.last(), buffer, state, model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
    }

    private static ResourceLocation partId(RelicRole role, String part) {
        return ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "block/ship/" + role.itemId() + "_" + part);
    }

    private static int shellCount(RelicRole role) {
        return switch (role) {
            case RF_SHIP_GENERATOR -> RF_MASTS;
            case MANA_SHIP_GENERATOR -> MANA_PLATES;
            case RF_DRONE_DOCK -> RF_DOCK_MODULES;
            case MANA_DRONE_DOCK -> 1 + MANA_DOCK_SHARDS;
            default -> 0;
        };
    }
}
