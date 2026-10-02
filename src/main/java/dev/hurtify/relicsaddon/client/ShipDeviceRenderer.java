package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ShipBlocks;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlock;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the moving parts of the ship shield generators and drone docks. The block model is only the
 * device's static body (and a generator's lit floor ring); the parts that move - the RF tower's
 * masts and antenna, the Mana cube's six plates and inner star, the Ex-Twins ring of galaxy orbs
 * and gold threads, the RF dock's sliding modules, the Mana dock's ring and shards, the Ex-Twins
 * dock's plexus - are OBJ groups of the same model (tools/build_ship_device_meshes.mjs), loaded as
 * standalone part models and posed here each frame.
 *
 * <p>A generator eases open when it is switched on (masts unfold one by one with sparks running
 * down them, the cube opens into a star, the ring spins up and the threads reach the sphere) and
 * eases shut when switched off; a dock eases into its "drone aboard" pose while it holds drones.
 * Lit parts report dynamic light through {@link EffectLights}, at the device's place in the world
 * even when it rides a ship.
 */
@OnlyIn(Dist.CLIENT)
public final class ShipDeviceRenderer implements BlockEntityRenderer<ShipDeviceBlockEntity> {
    /** Ticks a generator takes to open, and a dock to bring its modules out. */
    private static final double OPEN_TICKS = 60, DOCK_TICKS = 20;
    private static final int MANA_PLATES = 3, RF_MASTS = 4, RF_DOCK_MODULES = 3, MANA_DOCK_SHARDS = 3;
    /** The RF masts: hinge height and radius, how far each swings up (radians) and how long it is. */
    private static final double MAST_HINGE_Y = .81, MAST_HINGE_R = .21, MAST_SWING = 2.1, MAST_LENGTH = .55;
    private static final int SPARK_COLOR = 0x8EEBFF, SPARK_TICKS = 6;
    private static final Map<ShipDeviceBlockEntity, View> VIEWS = new WeakHashMap<>();

    /** What each device looks like right now: how far open it is, and the motion that accumulates. */
    private static final class View {
        double open = Double.NaN, drawnAt = Double.NaN;
        /** Ticks since the last frame, for motion whose speed changes (spins and pulses add speed x dt). */
        double dt;
        double spin, turn, pulse;
        long sparkTick = Long.MIN_VALUE;
        final List<Spark> sparks = new ArrayList<>();
    }

    /** A spark running down an RF mast: which mast, how far along it (0 at the hinge), when it was born. */
    private record Spark(int mast, double along, double born) { }

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
        // Lights are reported where the device really is: on a ship, carried out of the plot by the ship's pose.
        Vec3 centre = ShipFrame.drawn(device, partial).centre();

        poses.pushPose();
        // The block model turns with the facing (blockstate y rotation); the parts turn the same way.
        Direction facing = state.hasProperty(ShipDeviceBlock.FACING) ? state.getValue(ShipDeviceBlock.FACING) : Direction.NORTH;
        poses.translate(.5, .5, .5);
        poses.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180)));
        poses.translate(-.5, -.5, -.5);
        VertexConsumer buffer = buffers.getBuffer(Sheets.cutoutBlockSheet());
        int bright = LightTexture.FULL_BRIGHT;
        switch (role) {
            case RF_SHIP_GENERATOR -> rfGenerator(role, state, view, open, time, poses, buffer, light, bright, overlay, centre, level.random);
            case MANA_SHIP_GENERATOR -> manaGenerator(role, state, view, open, time, poses, buffer, light, bright, overlay, centre);
            case TWINS_SHIP_GENERATOR -> twinsGenerator(role, state, view, open, poses, buffer, light, bright, overlay, centre);
            case RF_DRONE_DOCK -> rfDock(role, state, open, time, poses, buffer, light, bright, overlay, centre);
            case MANA_DRONE_DOCK -> manaDock(role, state, view, open, time, poses, buffer, light, bright, overlay, centre);
            case TWINS_DRONE_DOCK -> twinsDock(role, state, view, open, poses, buffer, light, bright, overlay, centre);
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

    // --- the RF emitter tower: masts unfold one by one, sparks run down them; lamps blink at rest ----
    private void rfGenerator(RelicRole role, BlockState state, View view, double open, double time, PoseStack poses,
                             VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre, RandomSource random) {
        poses.pushPose();
        about(poses, .5, .5, 0, 1, 0, (float) (time * 1.5));
        part(role, "core", state, poses, buffer, light, overlay);
        poses.popPose();
        // At rest the lamps flash for a few ticks every four seconds; switched on they stay lit.
        boolean lamps = open > 0 || Math.floorMod((long) (time / 4), 20) == 0;
        if (lamps) part(role, "fx", state, poses, buffer, bright, overlay);
        long tick = (long) time;
        boolean newTick = tick != view.sparkTick;
        view.sparkTick = tick;
        for (int index = 0; index < RF_MASTS; index++) {
            double share = stagger(open, index, RF_MASTS);
            double angle = mastAngle(index);
            float dx = (float) Math.cos(angle), dz = (float) Math.sin(angle);
            float px = (float) (.5 + dx * MAST_HINGE_R), py = (float) MAST_HINGE_Y, pz = (float) (.5 + dz * MAST_HINGE_R);
            poses.pushPose();
            poses.translate(px, py, pz);
            poses.mulPose(Axis.of(new Vector3f(-dz, 0, dx)).rotation((float) (MAST_SWING * share)));
            poses.translate(-px, -py, -pz);
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
            // Two sparks a tick set out from the hinge of a mast that is swinging.
            if (newTick && share > 0 && share < 1) {
                for (int k = 0; k < 2; k++) view.sparks.add(new Spark(index, random.nextDouble() * .3, time));
            }
        }
        sparks(view, open, time, poses);
        if (lamps) EffectLights.glow(centre.add(0, .4, 0), open > 0 ? 6 + 6 * open : 4, 2.5 + 2 * open);
    }

    private static double mastAngle(int index) { return index * Math.PI / 2 + Math.PI / 4; }

    /** A point {@code along} blocks down mast {@code index} swung by {@code swing} radians, in model space. */
    private static Vec3 mastPoint(int index, double swing, double along) {
        double angle = mastAngle(index), dx = Math.cos(angle), dz = Math.sin(angle);
        // Swinging about the tangent (-dz, 0, dx) tips the hanging mast outward along (dx, 0, dz).
        double down = -Math.cos(swing), out = Math.sin(swing);
        return new Vec3(.5 + dx * MAST_HINGE_R + dx * out * along, MAST_HINGE_Y + down * along, .5 + dz * MAST_HINGE_R + dz * out * along);
    }

    /**
     * Sparks as small bright needles on the masts, in the world like everything else here: each runs
     * down its mast for a few ticks and fades. Drawn into the additive glow buffer, flushed after the
     * translucent shells.
     */
    private static void sparks(View view, double open, double time, PoseStack poses) {
        if (view.sparks.isEmpty()) return;
        view.sparks.removeIf(spark -> time - spark.born() > SPARK_TICKS || time < spark.born());
        VertexConsumer glow = ShieldGlow.consumer();
        Matrix4f matrix = poses.last().pose();
        for (Spark spark : view.sparks) {
            double age = time - spark.born(), fade = 1 - age / SPARK_TICKS;
            double swing = MAST_SWING * stagger(open, spark.mast(), RF_MASTS);
            double along = Math.min(MAST_LENGTH, spark.along() + age * .06);
            Vec3 a = mastPoint(spark.mast(), swing, along), b = mastPoint(spark.mast(), swing, Math.min(MAST_LENGTH, along + .05));
            needle(glow, matrix, a, b, .012 * fade, SPARK_COLOR, fade);
        }
    }

    /** A thin octahedral needle from a to b: eight triangles, white in the middle, coloured at the pointed ends. */
    private static void needle(VertexConsumer glow, Matrix4f matrix, Vec3 a, Vec3 b, double width, int color, double alpha) {
        Vec3 axis = b.subtract(a);
        if (axis.lengthSqr() < 1e-10) return;
        Vec3 u = axis.cross(Math.abs(axis.y) > .9 * axis.length() ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(width);
        Vec3 v = axis.normalize().cross(u).normalize().scale(width);
        Vec3 mid = a.add(b).scale(.5);
        Vec3[] ring = {mid.add(u), mid.add(v), mid.subtract(u), mid.subtract(v)};
        int r = color >> 16 & 255, g = color >> 8 & 255, bl = color & 255, alphaByte = (int) (alpha * 255);
        for (int k = 0; k < 4; k++) {
            Vec3 p = ring[k], q = ring[(k + 1) % 4];
            vertex(glow, matrix, a, r, g, bl, alphaByte);
            vertex(glow, matrix, p, 255, 255, 255, alphaByte);
            vertex(glow, matrix, q, 255, 255, 255, alphaByte);
            vertex(glow, matrix, b, r, g, bl, alphaByte);
            vertex(glow, matrix, q, 255, 255, 255, alphaByte);
            vertex(glow, matrix, p, 255, 255, 255, alphaByte);
        }
    }

    private static void vertex(VertexConsumer glow, Matrix4f matrix, Vec3 at, int r, int g, int b, int alpha) {
        glow.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(r, g, b, alpha);
    }

    // --- the Mana reactor: three curved petals uncover a rotating crystalline sphere ----
    private void manaGenerator(RelicRole role, BlockState state, View view, double open, double time, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        view.turn = (view.turn + (1 + 2 * open) * view.dt) % 360;
        view.spin = (view.spin + 3 * open * view.dt) % 360;
        float bob = (float) (Math.sin(time * .05) * .02);
        poses.pushPose();
        poses.translate(0, bob, 0);
        about(poses, .5, .62, 0, 1, 0, (float) view.turn);
        if (open > 0) {
            poses.pushPose();
            about(poses, .5, .62, 1, .4, .6, (float) view.spin);
            float scale = (float) (.4 + .6 * ease(open));
            poses.translate(.5, .62, .5);
            poses.scale(scale, scale, scale);
            poses.translate(-.5, -.62, -.5);
            part(role, "core", state, poses, buffer, bright, overlay);
            poses.popPose();
        }
        float[][] normals = {{1, 0, 0}, {-.5F, 0, .8660254F}, {-.5F, 0, -.8660254F}};
        double share = ease(open);
        for (int index = 0; index < MANA_PLATES; index++) {
            float[] n = normals[index];
            poses.pushPose();
            poses.translate(n[0] * .13 * share, n[1] * .13 * share, n[2] * .13 * share);
            about(poses, .5, .62, n[0], n[1], n[2], (float) (18 * share));
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
        }
        poses.popPose();
        part(role, "fx", state, poses, buffer, open > 0 ? bright : light, overlay);
        if (open > 0) EffectLights.glow(centre.add(0, .1, 0), 5 + 9 * open, 3 + 3 * open);
    }

    // --- the Ex-Twins sphere: the ring of orbs spins up and the gold threads reach the sphere ----
    private void twinsGenerator(RelicRole role, BlockState state, View view, double open, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
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

    // --- the RF charging base: modules slide out and the hub lights with drones aboard ----
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
            about(poses, .5, .9, Math.cos(index * 2.1), 0, Math.sin(index * 2.1), (float) (8 * Math.sin(time * .1 + index)));
            part(role, "shell_" + index, state, poses, buffer, light, overlay);
            poses.popPose();
        }
        if (open > 0) part(role, "fx", state, poses, buffer, bright, overlay);
        EffectLights.glow(centre.add(0, .12, 0), 5 + 7 * open, 2.5 + 2 * open);
    }

    // --- the Ex-Twins lab sphere: the violet light pulses and the plexus draws in with drones aboard ----
    private void twinsDock(RelicRole role, BlockState state, View view, double open, PoseStack poses, VertexConsumer buffer, int light, int bright, int overlay, Vec3 centre) {
        view.pulse = (view.pulse + (.12 + .25 * open) * view.dt) % (2 * Math.PI);
        view.turn = (view.turn + (.8 + 3 * open) * view.dt) % 360;
        view.spin = (view.spin + .5 * view.dt) % 360;
        double pulse = .5 + .5 * Math.sin(view.pulse);
        float scale = (float) (1 - .18 * ease(open) + .05 * pulse);
        poses.pushPose();
        poses.translate(.5, .5, .5);
        poses.scale(scale, scale, scale);
        poses.mulPose(Axis.YP.rotationDegrees((float) view.turn));
        poses.mulPose(Axis.XP.rotationDegrees((float) view.spin));
        poses.translate(-.5, -.5, -.5);
        part(role, "core", state, poses, buffer, bright, overlay);
        poses.popPose();
        part(role, "fx", state, poses, buffer, bright, overlay);
        EffectLights.glow(centre, 3 + 5 * open + 4 * pulse * (.3 + open), 2.5 + 2 * open);
    }

    // --- helpers ---------------------------------------------------------------------------------
    private static void about(PoseStack poses, double x, double y, double axisX, double axisY, double axisZ, float degrees) {
        poses.translate(x, y, .5);
        poses.mulPose(Axis.of(new Vector3f((float) axisX, (float) axisY, (float) axisZ).normalize()).rotationDegrees(degrees));
        poses.translate(-x, -y, -.5);
    }

    private static void part(RelicRole role, String part, BlockState state, PoseStack poses, VertexConsumer buffer, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getModelManager().getModel(ModelResourceLocation.standalone(partId(role, part)));
        // The item path keeps each quad's baked colour (the OBJ material's Kd) and light emission; the block
        // model renderer's renderModel would replace the colour with white.
        minecraft.getItemRenderer().renderModelLists(model, ItemStack.EMPTY, light, overlay, poses, buffer);
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
