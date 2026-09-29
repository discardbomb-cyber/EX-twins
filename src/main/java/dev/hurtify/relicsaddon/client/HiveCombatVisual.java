package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Immediate, bounded combat accents.  They are projections of the synchronized combat state, never damage sources. */
public final class HiveCombatVisual {
    private static final int[][][] TWINS_LINKS = new int[HiveType.MAX_DRONES + 1][][];
    private static final RenderType TYPE = RenderType.create("relic_hive_combat", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.DEBUG_LINES, 4096, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));

    public static void renderFormation(HiveType type, List<Vec3> points, Vec3 target, Vec3 camera,
            MultiBufferSource buffers, Matrix4f matrix, double time) {
        if (points.isEmpty() || target == null) return;
        VertexConsumer consumer = buffers.getBuffer(TYPE);
        int color = color(type);
        if (type == HiveType.RF) {
            for (Vec3 point : points) emitter(consumer, matrix, point, target, camera, points.size() > 50 ? .12 : .17, color, 120);
        } else if (type == HiveType.MANA) {
            // HiveFormation interleaves rings: index = ring + slot * ringCount.
            int ringCount = Math.max(2, (int) Math.ceil(Math.sqrt(points.size() / 3.0)));
            for (int index = 0; index < points.size(); index++) {
                int ring = index % ringCount;
                int population = (points.size() - 1 - ring) / ringCount + 1;
                int slot = index / ringCount;
                int next = ring + ((slot + 1) % population) * ringCount;
                line(consumer, matrix, points.get(index), points.get(next), camera, color, 105);
            }
        } else {
            // Topology stays fixed as the cage moves, so the nearest-link search only runs once per population.
            int[][] links = twinsLinks(points.size());
            for (int index = 0; index < points.size(); index++) {
                for (int other : links[index]) if (other > index) line(consumer, matrix, points.get(index), points.get(other), camera, color, 125);
            }
        }
    }

    /**
     * Shots are Photon effects now: each synchronized shot starts its beam, bolt or lightning once,
     * and its impact flash fires when the shot lands. Keys expire with the shot, so replays of the
     * same synchronized list never duplicate an effect.
     */
    public static void renderShots(List<HiveCombatState.Shot> shots, Vec3 camera, MultiBufferSource buffers, Matrix4f matrix, double time) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        SHOT_FX.values().removeIf(state -> now - state[0] > 200);
        for (HiveCombatState.Shot shot : shots) {
            if (time < shot.firedAt()) continue;
            long key = shot.firedAt() * 1_000_003L + shot.unit() * 31L + shot.kind()
                    + Double.doubleToLongBits(shot.startX() + shot.startZ() * 3);
            long[] state = SHOT_FX.get(key);
            Vec3 start = new Vec3(shot.startX(), shot.startY(), shot.startZ());
            Vec3 end = new Vec3(shot.endX(), shot.endY(), shot.endZ());
            HiveType type = switch (shot.kind()) { case 0 -> HiveType.RF; case 1 -> HiveType.MANA; default -> HiveType.TWINS; };
            if (state == null) {
                if (time - shot.firedAt() > 20) continue;
                state = new long[]{now, 0};
                SHOT_FX.put(key, state);
                int travel = (int) Math.max(1, shot.impactAt() - shot.firedAt());
                dev.hurtify.relicsaddon.client.fx.ExFx.hiveShot(level, start, end, type, shot.kind(), travel);
            }
            if (state[1] == 0 && time >= shot.impactAt()) {
                state[1] = 1;
                dev.hurtify.relicsaddon.client.fx.ExFx.hiveImpact(level, end, type, shot.kind());
            }
        }
    }

    private static final java.util.Map<Long, long[]> SHOT_FX = new java.util.HashMap<>();

    public static RenderType renderType() { return TYPE; }

    public static void renderTravel(HiveType type, Vec3 owner, float yaw, Vec3 target, double targetHeight,
            double progress, Vec3 camera, MultiBufferSource buffers, Matrix4f matrix, double time) {
        double strength = HiveFormation.travelWeight(progress);
        if (strength < .01) return;
        var consumer = buffers.getBuffer(TYPE);
        for (int wave = 0; wave < 3; wave++) {
            double polar = ((time * .045 + wave / 3.0) % 1) * Math.PI;
            int alpha = (int) (54 * Math.sin(polar) * strength);
            Vec3 previous = HiveFormation.travelPoint(owner, yaw, target, targetHeight, progress, polar, 0, time);
            for (int step = 1; step <= 28; step++) {
                Vec3 next = HiveFormation.travelPoint(owner, yaw, target, targetHeight, progress, polar, step * Math.PI / 14, time);
                line(consumer, matrix, previous, next, camera, color(type), alpha);
                previous = next;
            }
        }
    }

    private static void emitter(VertexConsumer c, Matrix4f m, Vec3 point, Vec3 target, Vec3 camera, double radius, int rgb, int alpha) {
        Vec3 forward = target.subtract(point).normalize();
        Vec3 up = Math.abs(forward.y) > .85 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(up).normalize();
        Vec3 normalUp = right.cross(forward).normalize();
        Vec3 previous = point.add(right.scale(radius));
        for (int side = 1; side <= 6; side++) {
            double angle = side * Math.PI / 3;
            Vec3 next = point.add(right.scale(Math.cos(angle) * radius)).add(normalUp.scale(Math.sin(angle) * radius));
            line(c, m, previous, next, camera, rgb, alpha);
            previous = next;
        }
    }

    private static int[] nearest(List<Vec3> points, int index, int limit) {
        int[] result = new int[limit]; java.util.Arrays.fill(result, -1);
        double[] distances = new double[limit]; java.util.Arrays.fill(distances, Double.POSITIVE_INFINITY);
        for (int other = 0; other < points.size(); other++) if (other != index) {
            double distance = points.get(index).distanceToSqr(points.get(other));
            for (int slot = 0; slot < limit; slot++) if (distance < distances[slot]) {
                for (int move = limit - 1; move > slot; move--) { distances[move] = distances[move - 1]; result[move] = result[move - 1]; }
                distances[slot] = distance; result[slot] = other; break;
            }
        }
        return result;
    }

    private static int[][] twinsLinks(int count) {
        if (TWINS_LINKS[count] == null) {
            var points = new java.util.ArrayList<Vec3>(count);
            for (int index = 0; index < count; index++) points.add(HiveFormation.combat(Vec3.ZERO, .6, 1.8, index, count, HiveType.TWINS, 0));
            int[][] links = new int[count][];
            for (int index = 0; index < count; index++) links[index] = nearest(points, index, 3);
            TWINS_LINKS[count] = links;
        }
        return TWINS_LINKS[count];
    }

    private static int color(HiveType type) { return switch (type) { case RF -> 0x38E8FF; case MANA -> 0x46A9FF; case TWINS -> 0xB151FF; }; }
    private static void line(VertexConsumer c, Matrix4f m, Vec3 first, Vec3 second, Vec3 camera, int rgb, int alpha) {
        vertex(c, m, first.subtract(camera), rgb, alpha); vertex(c, m, second.subtract(camera), rgb, alpha);
    }
    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 point, int rgb, int alpha) {
        c.addVertex(m, (float) point.x, (float) point.y, (float) point.z).setColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
    }
    private HiveCombatVisual() { }
}
