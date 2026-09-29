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
    /** Most Mana rings strung with light; a big swarm flies more rings than that, and all of them lit is a tangle. */
    private static final int MAX_LIT_RINGS = 6;
    private static final RenderType TYPE = RenderType.create("relic_hive_combat", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.DEBUG_LINES, 4096, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));

    /**
     * Light accents on a formed swarm, kept sparse so a big swarm stays readable: a few Mana rings are
     * strung with light. RF and Twins drones need no lines; their shots say enough. {@code points} are
     * the fighters in index order.
     */
    public static void renderFormation(HiveType type, List<Vec3> points, Vec3 target, Vec3 camera,
            MultiBufferSource buffers, Matrix4f matrix, double time) {
        if (points.size() < 2 || target == null || type != HiveType.MANA) return;
        VertexConsumer consumer = buffers.getBuffer(TYPE);
        int color = color(type);
        int count = points.size();
        // HiveFormation interleaves rings: index = ring + slot * rings.
        int rings = HiveFormation.rings(count), every = Math.max(1, (rings + MAX_LIT_RINGS - 1) / MAX_LIT_RINGS);
        for (int index = 0; index < count; index++) {
            int ring = index % rings, slot = index / rings;
            int population = (count - 1 - ring) / rings + 1;
            if (ring % every != 0 || population < 3) continue;
            int next = ring + ((slot + 1) % population) * rings;
            line(consumer, matrix, points.get(index), points.get(next), camera, color, 85);
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

    private static int color(HiveType type) { return switch (type) { case RF -> 0x38E8FF; case MANA -> 0x46A9FF; case TWINS -> 0xB151FF; }; }
    private static void line(VertexConsumer c, Matrix4f m, Vec3 first, Vec3 second, Vec3 camera, int rgb, int alpha) {
        vertex(c, m, first.subtract(camera), rgb, alpha); vertex(c, m, second.subtract(camera), rgb, alpha);
    }
    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 point, int rgb, int alpha) {
        c.addVertex(m, (float) point.x, (float) point.y, (float) point.z).setColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
    }
    private HiveCombatVisual() { }
}
