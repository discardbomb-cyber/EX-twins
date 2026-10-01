package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import dev.hurtify.relicsaddon.shipshield.EmitterDrone;
import dev.hurtify.relicsaddon.shipshield.ShellMesh;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity;
import dev.hurtify.relicsaddon.shipshield.ShipFamily;
import dev.hurtify.relicsaddon.shipshield.ShipShieldView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The ship shield's shell as a wire mesh in the world, in the structure's own frame so it rides
 * and turns with the ship: a thin line along every edge of the outermost layer, fainter lines for
 * the layers within, a small cross where every drone is and a dot on every empty seat. The plain
 * look of the shape, for checking it; the shield's effects come later and replace this.
 */
public final class ShipShellRenderer {
    private static final RenderType LINES = RenderType.create("relic_ship_shell", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.DEBUG_LINES, 65536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));
    private static final double RANGE = 192;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || ShipDeviceBlockEntity.CLIENT_LOADED.isEmpty()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        VertexConsumer lines = minecraft.renderBuffers().bufferSource().getBuffer(LINES);
        boolean drew = false;
        for (ShipDeviceBlockEntity generator : new ArrayList<>(ShipDeviceBlockEntity.CLIENT_LOADED)) {
            if (generator.isRemoved() || generator.getLevel() != level) {
                ShipDeviceBlockEntity.CLIENT_LOADED.remove(generator);
                continue;
            }
            ShipShieldView view = generator.view();
            List<ShellMesh> layers = generator.clientLayers();
            if (!view.active() || layers.isEmpty()) continue;
            ShipFrame frame = ShipFrame.drawn(generator, partial);
            if (frame.centre().distanceToSqr(camera) > RANGE * RANGE) continue;
            int color = color(generator.family());
            for (int index = 0; index < layers.size(); index++) {
                boolean outer = index == layers.size() - 1;
                shell(lines, matrix, layers.get(index), frame, camera, color, outer ? 150 : 45);
            }
            seats(lines, matrix, view, frame, camera, color);
            drew = true;
        }
        if (drew) minecraft.renderBuffers().bufferSource().endBatch(LINES);
    }

    private static int color(ShipFamily family) {
        return switch (family) {
            case RF -> 0x58C8FF;
            case MANA -> 0x4AF0D8;
            case TWINS -> 0xC070FF;
        };
    }

    private static void shell(VertexConsumer lines, Matrix4f matrix, ShellMesh mesh, ShipFrame frame, Vec3 camera, int color, int alpha) {
        float[] vertices = mesh.vertices();
        int count = mesh.vertexCount();
        Vec3 origin = mesh.origin();
        // Every edge once, from the lower vertex index to the higher.
        for (int vertex = 0; vertex < count; vertex++) {
            Vec3 a = frame.toWorld(origin.add(vertices[vertex * 3], vertices[vertex * 3 + 1], vertices[vertex * 3 + 2])).subtract(camera);
            for (int other : mesh.neighbours(vertex)) {
                if (other < vertex) continue;
                Vec3 b = frame.toWorld(origin.add(vertices[other * 3], vertices[other * 3 + 1], vertices[other * 3 + 2])).subtract(camera);
                line(lines, matrix, a, b, color, alpha);
            }
        }
    }

    private static void seats(VertexConsumer lines, Matrix4f matrix, ShipShieldView view, ShipFrame frame, Vec3 camera, int color) {
        List<Float> seats = view.seats(), drones = view.drones();
        for (int seat = 0; seat < view.seatCount(); seat++) {
            Vec3 at = frame.toWorld(new Vec3(seats.get(seat * 3), seats.get(seat * 3 + 1), seats.get(seat * 3 + 2))).subtract(camera);
            cross(lines, matrix, at, .12, view.held(seat) ? 0xFFFFFF : color, view.held(seat) ? 255 : 120);
            if (seat < view.droneCount() && view.state(seat) != EmitterDrone.State.HOLDING) {
                Vec3 drone = frame.toWorld(new Vec3(drones.get(seat * 3), drones.get(seat * 3 + 1), drones.get(seat * 3 + 2))).subtract(camera);
                cross(lines, matrix, drone, .3, 0xFFD060, 255);
            }
        }
    }

    private static void cross(VertexConsumer lines, Matrix4f matrix, Vec3 at, double size, int color, int alpha) {
        line(lines, matrix, at.add(-size, 0, 0), at.add(size, 0, 0), color, alpha);
        line(lines, matrix, at.add(0, -size, 0), at.add(0, size, 0), color, alpha);
        line(lines, matrix, at.add(0, 0, -size), at.add(0, 0, size), color, alpha);
    }

    private static void line(VertexConsumer lines, Matrix4f matrix, Vec3 a, Vec3 b, int color, int alpha) {
        int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        lines.addVertex(matrix, (float) a.x, (float) a.y, (float) a.z).setColor(red, green, blue, alpha);
        lines.addVertex(matrix, (float) b.x, (float) b.y, (float) b.z).setColor(red, green, blue, alpha);
    }

    private ShipShellRenderer() {
    }
}
