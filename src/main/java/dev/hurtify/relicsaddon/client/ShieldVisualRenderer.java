package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldImpactHistory;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public final class ShieldVisualRenderer {
    private static final double SHELL_RADIUS = ShieldField.RADIUS;
    private static final double SHELL_CENTER_Y = ShieldField.CENTER_Y;
    private static final int MAX_IMPACT_WAVES = 12;
    private static final RenderType SHIELD_RENDER_TYPE = createShieldType();
    private static final Map<UUID, ImpactCache> IMPACT_WAVES = new HashMap<>();
    private static ClientLevel waveLevel;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            IMPACT_WAVES.clear();
            waveLevel = null;
            return;
        }
        if (waveLevel != level) {
            IMPACT_WAVES.clear();
            waveLevel = level;
        }
        ShieldVisualQuality quality = selectQuality(level);
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(SHIELD_RENDER_TYPE);
        Matrix4f matrix = event.getPoseStack().last().pose();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        var presentPlayers = new HashSet<UUID>();
        for (AbstractClientPlayer player : level.players()) {
            presentPlayers.add(player.getUUID());
            if (!player.isAlive() || player.isInvisible()) {
                IMPACT_WAVES.remove(player.getUUID());
                continue;
            }
            if (player.distanceToSqr(camera) > quality.renderDistanceSqr()) {
                continue;
            }
            ItemStack shield = findRenderableShield(player);
            if (shield.isEmpty()) {
                IMPACT_WAVES.remove(player.getUUID());
                continue;
            }
            ShieldStackState state = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            if (state.enabled()) {
                ShieldImpactHistory authoritative = shield.getOrDefault(ModDataComponents.SHIELD_IMPACTS.get(), ShieldImpactHistory.EMPTY);
                List<ShieldImpact> waves = trackImpacts(player.getUUID(), authoritative.impacts(),
                        shield.get(ModDataComponents.SHIELD_IMPACT.get()), level.getGameTime());
                spawnImpactEffects(level, player, shield, shieldRole(shield), state);
                renderShield(player, shield, shieldRole(shield), state, waves,
                        ShieldThreatTracker.threats(player.getUUID()), level.getGameTime(), partialTick, camera, consumer, matrix, quality);
            } else {
                IMPACT_WAVES.remove(player.getUUID());
            }
        }
        IMPACT_WAVES.keySet().removeIf(id -> !presentPlayers.contains(id));
        // Refraction samples the scene before the translucent shells are drawn over it.
        ShieldRefraction.flush(matrix);
        buffers.endBatch(SHIELD_RENDER_TYPE);
        ShieldGlow.flush();
    }

    /** The network list is authoritative; the local deque only lets received fronts finish their 36-tick journey. */
    private static List<ShieldImpact> trackImpacts(UUID owner, List<ShieldImpact> authoritative, ShieldImpact latest, long gameTime) {
        ImpactCache cache = IMPACT_WAVES.computeIfAbsent(owner, ignored -> new ImpactCache());
        cache.waves.removeIf(impact -> gameTime - impact.gameTime() >= ShieldResponse.IMPACT_TICKS);
        if (!cache.observed.equals(authoritative)) {
            var unmatchedPrevious = new ArrayList<>(cache.observed);
            for (ShieldImpact impact : authoritative) {
                int known = indexOf(unmatchedPrevious, impact);
                if (known >= 0) unmatchedPrevious.remove(known);
                else cache.add(impact);
            }
            cache.observed = List.copyOf(authoritative);
        }
        // Compatibility only: a server/client pair that has not sent the new history still shows its latest impact.
        if (latest != null && authoritative.stream().noneMatch(impact -> sameImpact(impact, latest))) cache.add(latest);
        if (cache.waves.isEmpty() && authoritative.stream().allMatch(impact -> gameTime - impact.gameTime() >= ShieldResponse.IMPACT_TICKS)) {
            IMPACT_WAVES.remove(owner);
        }
        return List.copyOf(cache.waves);
    }

    private static int indexOf(List<ShieldImpact> impacts, ShieldImpact target) {
        for (int index = 0; index < impacts.size(); index++) if (sameImpact(impacts.get(index), target)) return index;
        return -1;
    }

    private static boolean sameImpact(ShieldImpact a, ShieldImpact b) {
        return a.gameTime() == b.gameTime() && a.normal().dot(b.normal()) > .999999D
                && Math.abs(a.distance() - b.distance()) < 1e-4D && a.absorbed() == b.absorbed();
    }

    /** Photon sparks, shards and the collapse nova for hits first seen this frame. */
    private static void spawnImpactEffects(ClientLevel level, AbstractClientPlayer player, ItemStack shield, RelicRole role, ShieldStackState state) {
        ImpactCache cache = IMPACT_WAVES.get(player.getUUID());
        if (cache == null || cache.fresh.isEmpty()) return;
        Vec3 center = player.position().add(0, SHELL_CENTER_Y, 0);
        double radius = ShieldParameters.radius(player, shield);
        for (ShieldImpact impact : cache.fresh) {
            if (level.getGameTime() - impact.gameTime() > 3) continue;
            Vec3 point = center.add(impact.normal().scale(impact.distance() >= 0 ? impact.distance() : radius));
            if (impact.absorbed() > 0) dev.hurtify.relicsaddon.client.fx.ExFx.shieldAbsorb(level, point, impact.normal(), role, impact.absorbed());
            if (impact.broken()) dev.hurtify.relicsaddon.client.fx.ExFx.shieldCellBreak(level, point, impact.normal(), role);
        }
        if (state.totalIntegrity() == 0) dev.hurtify.relicsaddon.client.fx.ExFx.shieldCollapse(level, center, radius, role);
        cache.fresh.clear();
    }

    private static final class ImpactCache {
        final ArrayDeque<ShieldImpact> waves = new ArrayDeque<>();
        final List<ShieldImpact> fresh = new ArrayList<>();
        List<ShieldImpact> observed = List.of();

        void add(ShieldImpact impact) {
            if (impact == null) return;
            waves.addLast(impact);
            fresh.add(impact);
            while (waves.size() > MAX_IMPACT_WAVES) waves.removeFirst();
        }
    }

    private static RenderType createShieldType() {
        return RenderType.create("relic_shield", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 4096, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

    private static ShieldVisualQuality selectQuality(ClientLevel level) {
        int playerCount = level.players().size();
        if (playerCount > 12) {
            return ShieldVisualQuality.LOW;
        }
        return playerCount > 4 ? ShieldVisualQuality.BALANCED : ShieldVisualQuality.HIGH;
    }

    private static ItemStack findRenderableShield(AbstractClientPlayer player) {
        try {
            return EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
        } catch (RuntimeException exception) {
            return ItemStack.EMPTY;
        }
    }

    private static RelicRole shieldRole(ItemStack shield) {
        if (shield.getItem() instanceof AutonomousRelicItem relic) {
            return relic.role();
        }
        return RelicRole.RF_SHIELD;
    }

    private static void renderShield(AbstractClientPlayer player, ItemStack shield, RelicRole role, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, long gameTime,
            float partialTick, Vec3 camera, VertexConsumer consumer, Matrix4f matrix,
            ShieldVisualQuality quality) {
        double time = gameTime + partialTick;
        if (AddonClientConfig.idleOpacity() <= 0 && !state.gathering(time) && threats.isEmpty() && impacts.isEmpty()) return;
        double originX = Mth.lerp(partialTick, player.xo, player.getX()) - camera.x();
        double originY = Mth.lerp(partialTick, player.yo, player.getY()) - camera.y() + SHELL_CENTER_Y;
        double originZ = Mth.lerp(partialTick, player.zo, player.getZ()) - camera.z();
        Vec3 forward = Vec3.directionFromRotation(0.0F, Mth.rotLerp(partialTick, player.yRotO, player.getYRot()));
        double forwardX = forward.x();
        double forwardZ = forward.z();
        double radius = ShieldParameters.radius(player, shield);
        double bufferRatio = state.sharedBuffer() / (double) Math.max(1, ShieldParameters.capacity(player, shield));
        Vec3 eyeDirection = originX * originX + originY * originY + originZ * originZ > radius * radius
                ? new Vec3(-originX, -originY, -originZ).normalize() : Vec3.ZERO;
        ShieldRefraction.queue(role, originX, originY, originZ, radius, impacts, time, quality == ShieldVisualQuality.LOW);
        renderField(role, state, impacts, threats, time, consumer, matrix, originX, originY, originZ,
                forwardX, forwardZ, quality == ShieldVisualQuality.LOW, eyeDirection, radius, bufferRatio);
    }

    /** Flushes the additive light layer; callers drawing shells outside the world pass (galleries) need it. */
    public static void flushGlow() {
        ShieldGlow.flush();
    }

    public static RenderType renderType() {
        return SHIELD_RENDER_TYPE;
    }

    public static void renderField(RelicRole role, ShieldStackState state, ShieldImpact impact,
            List<ShieldResponse.Threat> threats, double time, VertexConsumer consumer, Matrix4f matrix,
            double originX, double originY, double originZ, double forwardX, double forwardZ, boolean low) {
        renderField(role, state, impact, threats, time, consumer, matrix, originX, originY, originZ, forwardX, forwardZ, low, Vec3.ZERO);
    }

    public static void renderField(RelicRole role, ShieldStackState state, ShieldImpact impact,
            List<ShieldResponse.Threat> threats, double time, VertexConsumer consumer, Matrix4f matrix,
            double originX, double originY, double originZ, double forwardX, double forwardZ, boolean low, Vec3 eyeDirection) {
        renderField(role, state, impact == null ? List.of() : List.of(impact), threats, time, consumer, matrix, originX, originY, originZ,
                forwardX, forwardZ, low, eyeDirection, SHELL_RADIUS,
                state.sharedBuffer() / (double) ShieldStackState.MAX_SHARED_BUFFER);
    }

    /** Native gallery entry point for overlapping authoritative impacts at the standard two-block radius. */
    public static void renderWaves(RelicRole role, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, VertexConsumer consumer, Matrix4f matrix,
            double originX, double originY, double originZ, double forwardX, double forwardZ, boolean low, Vec3 eyeDirection) {
        renderField(role, state, impacts == null ? List.of() : List.copyOf(impacts), threats, time, consumer, matrix,
                originX, originY, originZ, forwardX, forwardZ, low, eyeDirection, SHELL_RADIUS,
                state.sharedBuffer() / (double) ShieldStackState.MAX_SHARED_BUFFER);
    }

    private static void renderField(RelicRole role, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, VertexConsumer consumer, Matrix4f matrix,
            double originX, double originY, double originZ, double forwardX, double forwardZ, boolean low, Vec3 eyeDirection,
            double radius, double bufferRatio) {
        double idle = AddonClientConfig.idleOpacity();
        if (idle <= 0 && !state.gathering(time) && threats.isEmpty() && impacts.isEmpty()) return;
        boolean rippling = role == RelicRole.MANA_SHIELD || role == RelicRole.TWINS_SHIELD;
        if (rippling) ShieldRipple.begin(impacts, time, ShieldRipple.roleScale(role));
        try {
            double activity = threats.isEmpty() ? impacts.stream().mapToDouble(hit ->
                    ShieldField.fade(time - hit.gameTime(), ShieldResponse.IMPACT_TICKS)).max().orElse(0) : 1;
            if (state.gathering(time)) activity = Math.max(activity, .6);
            // The shell is always faintly there; combat brings it to full strength.
            double presence = Math.max(idle, activity);
            ShieldShellVisual.render(role, consumer, new ShieldShellVisual.Frame(matrix, originX, originY, originZ, radius, forwardX, forwardZ,
                    eyeDirection), state, impacts, threats, time, presence, low);
            ShieldGlow.halo(matrix, role, originX, originY, originZ, radius, activity, impacts, time, eyeDirection, low);
            if (role == RelicRole.TWINS_SHIELD) {
                ShieldCircuitTraces.render(consumer, ShieldGlow.consumer(), matrix, originX, originY, originZ, radius, impacts, time, low,
                        ShieldSurfaceLighting.inside(eyeDirection));
            }
        } finally {
            ShieldRipple.end();
        }
        for (ShieldImpact impact : impacts) {
            renderInnerImpactPatch(role, impact, time, consumer, matrix, originX, originY, originZ, eyeDirection, radius, bufferRatio);
        }
    }

    private static void renderInnerImpactPatch(RelicRole role, ShieldImpact impact, double time, VertexConsumer consumer,
            Matrix4f matrix, double originX, double originY, double originZ, Vec3 eyeDirection, double radius, double bufferRatio) {
        if (impact == null || impact.distance() < 0 || impact.distance() >= radius || time < impact.gameTime()
                || time - impact.gameTime() >= ShieldResponse.IMPACT_TICKS) return;
        Vec3 normal = impact.normal();
        Vec3 helper = Math.abs(normal.y) > .88D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = normal.cross(helper).normalize();
        Vec3 v = normal.cross(u).normalize();
        double fade = ShieldField.fade(time - impact.gameTime(), ShieldResponse.IMPACT_TICKS);
        double patchRadius = (.20D + .15D * fade) * Math.max(.75D, Math.min(1.35D, radius / SHELL_RADIUS));
        int color = switch (role) {
            case MANA_SHIELD -> 0x62FFE0;
            case TWINS_SHIELD -> 0xC86CFF;
            default -> 0x7CF7FF;
        };
        int alpha = (int) Math.clamp((105 + 110 * fade) * Math.max(.35D, bufferRatio), 0, 230);
        if (role == RelicRole.MANA_SHIELD) {
            renderManaImpactPulse(consumer, matrix, normal, u, v, impact.distance() + .012D, patchRadius,
                    originX, originY, originZ, color, alpha);
            return;
        }
        int sides = 6;
        Vec3 center = normal.scale(impact.distance() + .012D);
        for (int side = 0; side < sides; side++) {
            double a = Math.PI * 2 * side / sides;
            double b = Math.PI * 2 * (side + 1) / sides;
            Vec3 pa = center.add(u.scale(Math.cos(a) * patchRadius)).add(v.scale(Math.sin(a) * patchRadius));
            Vec3 pb = center.add(u.scale(Math.cos(b) * patchRadius)).add(v.scale(Math.sin(b) * patchRadius));
            patchVertex(consumer, matrix, center, originX, originY, originZ, color, alpha);
            patchVertex(consumer, matrix, pa, originX, originY, originZ, color, alpha);
            patchVertex(consumer, matrix, pb, originX, originY, originZ, color, alpha);
        }
    }

    /** A translucent radial falloff removes the mechanical polygon edge from Mana's impact pulse. */
    private static void renderManaImpactPulse(VertexConsumer consumer, Matrix4f matrix, Vec3 normal, Vec3 u, Vec3 v,
            double distance, double radius, double x, double y, double z, int color, int alpha) {
        final int sides = 24;
        final double[] rings = {0, .38D, .72D, 1};
        final double[] opacity = {1, .66D, .18D, 0};
        for (int ring = 0; ring + 1 < rings.length; ring++) for (int side = 0; side < sides; side++) {
            double a = Math.PI * 2 * side / sides;
            double b = Math.PI * 2 * (side + 1) / sides;
            patchVertex(consumer, matrix, pulsePoint(normal, u, v, distance, radius * rings[ring], a), x, y, z, color, (int) (alpha * opacity[ring]));
            patchVertex(consumer, matrix, pulsePoint(normal, u, v, distance, radius * rings[ring + 1], a), x, y, z, color, (int) (alpha * opacity[ring + 1]));
            patchVertex(consumer, matrix, pulsePoint(normal, u, v, distance, radius * rings[ring + 1], b), x, y, z, color, (int) (alpha * opacity[ring + 1]));
            patchVertex(consumer, matrix, pulsePoint(normal, u, v, distance, radius * rings[ring], a), x, y, z, color, (int) (alpha * opacity[ring]));
            patchVertex(consumer, matrix, pulsePoint(normal, u, v, distance, radius * rings[ring + 1], b), x, y, z, color, (int) (alpha * opacity[ring + 1]));
            patchVertex(consumer, matrix, pulsePoint(normal, u, v, distance, radius * rings[ring], b), x, y, z, color, (int) (alpha * opacity[ring]));
        }
    }

    private static Vec3 pulsePoint(Vec3 normal, Vec3 u, Vec3 v, double distance, double radius, double angle) {
        if (radius == 0) return normal.scale(distance);
        return normal.scale(distance).add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius)).normalize().scale(distance);
    }

    private static void patchVertex(VertexConsumer consumer, Matrix4f matrix, Vec3 point, double x, double y, double z, int color, int alpha) {
        consumer.addVertex(matrix, (float) (x + point.x), (float) (y + point.y), (float) (z + point.z))
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha);
    }

    private ShieldVisualRenderer() {
    }
}
