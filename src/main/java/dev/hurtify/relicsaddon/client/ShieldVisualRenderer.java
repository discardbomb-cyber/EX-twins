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

    private static final class ImpactCache {
        final ArrayDeque<ShieldImpact> waves = new ArrayDeque<>();
        List<ShieldImpact> observed = List.of();

        void add(ShieldImpact impact) {
            if (impact == null) return;
            waves.addLast(impact);
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
        if (!state.gathering(time) && threats.isEmpty() && impacts.isEmpty()) return;
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
        if (!state.gathering(time) && threats.isEmpty() && impacts.isEmpty()) return;
        double rightX = -forwardZ;
        double rightZ = forwardX;
        boolean rippling = role == RelicRole.MANA_SHIELD || role == RelicRole.TWINS_SHIELD;
        if (rippling) ShieldRipple.begin(impacts, time);
        try {
        // Mana is smooth glass; Twins combine their glass membrane with a raised cell layer.
        if (role != RelicRole.MANA_SHIELD) {
            renderCells(role, state, impacts, threats, time, consumer, matrix, originX, originY, originZ,
                    forwardX, forwardZ, rightX, rightZ, low, eyeDirection, radius, bufferRatio);
        }
        if (role == RelicRole.MANA_SHIELD) ManaShieldVisual.render(consumer, matrix, originX, originY, originZ, state, impacts, threats, time,
                forwardX, forwardZ, low, eyeDirection, radius, bufferRatio);
        if (role == RelicRole.TWINS_SHIELD) TwinsShieldVisual.render(consumer, matrix, originX, originY, originZ, state, impacts, threats, time,
                forwardX, forwardZ, low, eyeDirection, radius, bufferRatio);
            double activity = threats.isEmpty() ? impacts.stream().mapToDouble(hit ->
                    ShieldField.fade(time - hit.gameTime(), ShieldResponse.IMPACT_TICKS)).max().orElse(0) : 1;
            if (state.gathering(time)) activity = Math.max(activity, .6);
            ShieldGlow.halo(matrix, role, originX, originY, originZ, radius, activity, impacts, time, eyeDirection, low);
            if (role == RelicRole.TWINS_SHIELD) {
                ShieldCircuitTraces.render(consumer, ShieldGlow.consumer(), matrix, originX, originY, originZ, radius, impacts, time, low);
            }
        } finally {
            ShieldRipple.end();
        }
        for (ShieldImpact impact : impacts) {
            renderInnerImpactPatch(role, impact, time, consumer, matrix, originX, originY, originZ, eyeDirection, radius, bufferRatio);
        }
    }

    private static void renderCells(RelicRole role, ShieldStackState state, List<ShieldImpact> impacts, List<ShieldResponse.Threat> threats, double time,
            VertexConsumer consumer, Matrix4f matrix, double originX, double originY, double originZ,
            double forwardX, double forwardZ, double rightX, double rightZ, boolean low, Vec3 eyeDirection,
            double radius, double bufferRatio) {
        int averageHp = (int) Math.ceil(state.totalIntegrity() / (double) dev.hurtify.relicsaddon.shield.ShieldTopology.CELL_COUNT);
        for (var cell : ShieldHexMesh.forQuality(low ? ShieldVisualQuality.LOW : ShieldVisualQuality.HIGH).cells()) {
            int integrity = state.cellHp(cell.id());
            boolean moving = state.moving(cell.id(), time);
            var rotation = moving ? ShieldCellVisual.relocation(state, cell.id(), time) : null;
            float[] center = moving ? ShieldCellVisual.transform(cell.center(), rotation) : cell.center();
            float[] perimeter = moving ? ShieldCellVisual.transform(cell.perimeter(), rotation) : cell.perimeter();
            Vec3 normal = new Vec3(center[0] * rightX + center[2] * forwardX, center[1], center[0] * rightZ + center[2] * forwardZ);
            int visualIntegrity = integrity == 0 && state.sharedBuffer() > 0 ? 1 : integrity;
            ShieldResponse.Light response = ShieldResponse.atMany(normal, threats, impacts, time, visualIntegrity);
            if (integrity > 0 && moving) {
                response = new ShieldResponse.Light(Math.max(.65, response.presence()), response.absorption(), response.destruction());
            }
            if (visualIntegrity == 0 && (response.destruction() < .008D || !wasJustBroken(impacts, cell.id(), forwardX, forwardZ))) continue;
            if (role == RelicRole.TWINS_SHIELD) {
                double activity = threats.isEmpty() ? impacts.stream().mapToDouble(hit ->
                        ShieldField.fade(time - hit.gameTime(), ShieldResponse.IMPACT_TICKS)).max().orElse(0) : 1;
                response = new ShieldResponse.Light(Math.max(activity * .38, response.presence()), response.absorption(), response.destruction());
            }
            if (response.presence() < .008D) continue;
            long color = panelColor(role, response, Math.min(averageHp, visualIntegrity));
            double cellRadius = radius + (ShieldImpactPulse.relief(cell.id()) + response.absorption() * .025
                    + (role == RelicRole.TWINS_SHIELD ? .035 : 0)) * radius / SHELL_RADIUS;
            renderCell(consumer, matrix, perimeter, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, false, eyeDirection, cellRadius, radius);
            renderCell(consumer, matrix, perimeter, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, true, eyeDirection, cellRadius, radius);
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

    private static boolean wasJustBroken(List<ShieldImpact> impacts, int cell, double forwardX, double forwardZ) {
        return impacts.stream().anyMatch(impact -> ShieldCellVisual.justBroken(impact, cell, forwardX, forwardZ));
    }

    private static void patchVertex(VertexConsumer consumer, Matrix4f matrix, Vec3 point, double x, double y, double z, int color, int alpha) {
        consumer.addVertex(matrix, (float) (x + point.x), (float) (y + point.y), (float) (z + point.z))
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha);
    }

    private static long panelColor(RelicRole role, ShieldResponse.Light response, int hp) {
        int base = switch (role) {
            case RF_SHIELD -> 0x26C6DA;
            case MANA_SHIELD -> 0x23CDAD;
            case TWINS_SHIELD -> 0xB55CFF;
            default -> role.color();
        };
        boolean twins = role == RelicRole.TWINS_SHIELD;
        base = twins ? ShieldCellVisual.violetHealth(base, hp) : ShieldCellVisual.color(base, hp);
        int red = base >> 16 & 0xFF;
        int green = base >> 8 & 0xFF;
        int blue = base & 0xFF;
        double brightening = response.absorption() * .78D;
        double broken = response.destruction();
        int highlightRed = clampColor((int) Math.round(red + (255 - red) * Math.max(brightening, broken)));
        int highlightGreen = clampColor((int) Math.round(green + (225 - green) * Math.max(brightening, broken * (twins ? .25D : .8D))));
        int highlightBlue = clampColor((int) Math.round(blue + (255 - blue) * Math.max(brightening, twins ? broken : 0) - blue * broken * (twins ? 0 : .7D)));
        int fillAlpha = clampColor((int) Math.round(response.presence() * 42 + response.absorption() * 52 + broken * 48));
        int borderAlpha = clampColor((int) Math.round(response.presence() * 160 + response.absorption() * 75 + broken * 80));
        return (long) highlightRed << 32 | (long) highlightGreen << 24 | (long) highlightBlue << 16
                | (long) fillAlpha << 8 | borderAlpha;
    }

    private static void renderCell(VertexConsumer consumer, Matrix4f matrix, float[] perimeter, float[] center, double originX, double originY,
            double originZ, double forwardX, double forwardZ, double rightX, double rightZ, long color, boolean border, Vec3 eyeDirection,
            double radius, double baseRadius) {
        int alpha = (int) (border ? color : color >> 8) & 0xFF;
        alpha = (int) (alpha * ShieldSurfaceLighting.visibility(center[0] * rightX + center[2] * forwardX,
                center[1], center[0] * rightZ + center[2] * forwardZ, eyeDirection));
        int corners = perimeter.length / 3;
        for (int edge = 0; edge < corners; edge++) {
            int next = (edge + 1) % corners;
            if (border) {
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.0F, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.0F, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.045F, radius);
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.0F, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.045F, radius);
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.045F, radius);
                // Short translucent side walls make the staggered panel heights visible at grazing angles.
                int sideAlpha = alpha / 3;
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, sideAlpha, 0, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, sideAlpha, 0, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, sideAlpha, 0, baseRadius);
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, sideAlpha, 0, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, sideAlpha, 0, baseRadius);
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, sideAlpha, 0, baseRadius);
            } else {
                vertex(consumer, matrix, center, 0, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.0F, radius);
                vertex(consumer, matrix, perimeter, edge, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.0F, radius);
                vertex(consumer, matrix, perimeter, next, center, originX, originY, originZ, forwardX, forwardZ, rightX, rightZ, color, alpha, 0.0F, radius);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float[] points, int point, float[] center, double originX, double originY,
            double originZ, double forwardX, double forwardZ, double rightX, double rightZ, long color, int alpha, float inset, double radius) {
        int index = point * 3;
        double localX = points[index];
        double localY = points[index + 1];
        double localZ = points[index + 2];
        if (inset > 0.0F) {
            localX += (center[0] - localX) * inset;
            localY += (center[1] - localY) * inset;
            localZ += (center[2] - localZ) * inset;
            double length = Math.sqrt(localX * localX + localY * localY + localZ * localZ);
            localX /= length;
            localY /= length;
            localZ /= length;
        }
        double dirX = localX * rightX + localZ * forwardX, dirZ = localX * rightZ + localZ * forwardZ;
        radius *= ShieldRipple.scale(dirX, localY, dirZ);
        double worldX = originX + dirX * radius;
        double worldY = originY + localY * radius;
        double worldZ = originZ + dirZ * radius;
        consumer.addVertex(matrix, (float) worldX, (float) worldY, (float) worldZ)
                .setColor((int) (color >> 32) & 0xFF, (int) (color >> 24) & 0xFF, (int) (color >> 16) & 0xFF, alpha);
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private ShieldVisualRenderer() {
    }
}
