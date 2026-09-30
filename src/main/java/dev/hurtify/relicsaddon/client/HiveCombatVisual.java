package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.client.fx.ExFx;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The swarm's synchronized events brought to life: each one starts its Photon effect once (keys expire
 * with the event, so replays of the same list never repeat it), and blasts keep bending space for a
 * few ticks after they land. Charges in flight carry their own glow. Every blow and charge also lights
 * the world through {@link EffectLights}.
 */
public final class HiveCombatVisual {
    private static final RenderType TYPE = RenderType.create("relic_hive_combat", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.DEBUG_LINES, 4096, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));
    /** Started effects by event key: [first seen, landed]. */
    private static final Map<Long, long[]> STARTED = new HashMap<>();
    private static final int WARP_TICKS = 12;

    public static RenderType renderType() { return TYPE; }

    public static void renderShots(List<HiveCombatState.Shot> shots, HiveType type, Vec3 camera, Matrix4f matrix, double time) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        STARTED.values().removeIf(state -> now - state[0] > 200);
        var glow = ShieldGlow.consumer();
        for (HiveCombatState.Shot shot : shots) {
            if (time < shot.firedAt()) continue;
            long key = shot.firedAt() * 1_000_003L + shot.unit() * 31L + shot.kind() * 7919L
                    + Double.doubleToLongBits(shot.startX() + shot.startZ() * 3) + type.ordinal();
            long[] state = STARTED.get(key);
            Vec3 start = new Vec3(shot.startX(), shot.startY(), shot.startZ());
            Vec3 end = new Vec3(shot.endX(), shot.endY(), shot.endZ());
            if (state == null) {
                if (time - shot.firedAt() > 20) continue;
                state = new long[]{now, 0};
                STARTED.put(key, state);
                begin(level, shot, type, start, end, time);
            }
            if (state[1] == 0 && time >= shot.impactAt() && shot.kind() == HiveCombatState.BALL) {
                state[1] = 1;
                ExFx.swarmBlast(level, end, type, 1.2f);
                EffectLights.flash(end, 15, 1.2, 8);
                // Mana's barrage stays as it was.
                if (type != HiveType.MANA) HiveJuice.impact(end, end.subtract(start), type, HiveJuice.CHARGE, time, .8, group(type, shot));
                if (type == HiveType.TWINS) ExFx.swarmSmoke(level, end);
            }
            // Blasts ring out through space for a moment after they land.
            double age = time - shot.impactAt();
            if ((shot.kind() == HiveCombatState.DROPLET || shot.kind() == HiveCombatState.BALL) && age >= 0 && age < WARP_TICKS) {
                Vec3 at = end.subtract(camera);
                double grow = age / WARP_TICKS;
                ShieldRefraction.queueLens(at.x, at.y, at.z, grow * 1.8, .6 + grow * 3.2, .9 * (1 - grow));
            }
            // A charge in flight glows along its path.
            if (shot.kind() == HiveCombatState.BALL && time < shot.impactAt()) {
                double t = (time - shot.firedAt()) / Math.max(1, shot.impactAt() - shot.firedAt());
                Vec3 flying = start.lerp(end, Math.clamp(t, 0, 1));
                Vec3 at = flying.subtract(camera);
                if (type == HiveType.MANA) {
                    GlowBrush.dot(glow, matrix, at, .55, HiveModeVisual.color(type), 150);
                    GlowBrush.dot(glow, matrix, at, .22, 0xFFFFFF, 200);
                } else {
                    // The ring of lightning or the glass icosahedron, drawn with the constructs.
                    HiveProjectiles.fly(type, flying, end.subtract(start), time, key);
                }
                EffectLights.glow(flying, 12, .55);
            }
        }
    }

    /** Names a blow's strike group, so its next blow cuts this one's tail short. */
    private static long group(HiveType type, HiveCombatState.Shot shot) {
        return (type.ordinal() + 1) * 1_000_003L + shot.unit() * 31L + shot.kind();
    }

    private static void begin(net.minecraft.client.multiplayer.ClientLevel level, HiveCombatState.Shot shot, HiveType type, Vec3 start, Vec3 end, double time) {
        switch (shot.kind()) {
            case HiveCombatState.DROPLET -> {
                ExFx.swarmBlast(level, end, type, 1.5f);
                EffectLights.flash(end, 15, 1, 8);
                // A Mana drop falls on its target from above.
                HiveJuice.impact(end, type == HiveType.MANA ? new Vec3(0, -1, 0) : end.subtract(start), type, HiveJuice.STRIKE, time, 1, group(type, shot));
                if (type == HiveType.TWINS) ExFx.voidPulse(level, end);
            }
            case HiveCombatState.BALL -> {
                ExFx.chargeBall(level, start, end, (int) Math.max(1, shot.impactAt() - shot.firedAt()), type);
                if (type == HiveType.TWINS) ExFx.swarmSmoke(level, start);
            }
            case HiveCombatState.ZAP -> {
                ExFx.swarmZap(level, start, end, type);
                EffectLights.flash(end, 10, .5, 4);
                HiveJuice.impact(end, end.subtract(start), type, HiveJuice.ZAP, time, .35, group(type, shot));
            }
            case HiveCombatState.VOID -> {
                ExFx.voidPulse(level, end);
                EffectLights.flash(end, 9, 1, 6);
                HiveJuice.impact(end, new Vec3(0, 1, 0), type, HiveJuice.ZAP, time, .45, group(type, shot));
            }
            case HiveCombatState.WARD -> {
                ExFx.wardFlash(level, end);
                EffectLights.flash(end, 10, 1, 5);
                HiveJuice.impact(end, new Vec3(0, 1, 0), type, HiveJuice.ZAP, time, .45, group(type, shot) + (long) (end.x * 7 + end.z * 13));
            }
            case HiveCombatState.INTERCEPT, HiveCombatState.DRONE_HIT -> {
                ExFx.swarmSpark(level, end, type);
                EffectLights.flash(end, 7, .3, 4);
                HiveJuice.impact(end, new Vec3(0, 1, 0), type, HiveJuice.SPARK, time, .3, group(type, shot) + (long) (end.x * 7 + end.z * 13));
            }
            default -> { }
        }
    }

    private HiveCombatVisual() { }
}
