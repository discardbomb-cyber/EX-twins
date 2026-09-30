package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveSupportState;
import dev.hurtify.relicsaddon.drone.HiveTarget;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client projection of synchronized hive state; it never creates entities, targets, damage or sounds.
 * Each fighter place is drawn where {@link HiveFormation} puts it: flown out from the hive by its
 * lane's current drone, while hit drones fly home. Up close drones are their 3D models; far away they
 * are points of light, so a swarm 128 blocks off still reads without the cost of hundreds of models.
 */
public final class HiveVisualRenderer {
    private record EquippedCache(long tick, Object level, List<HiveController.Equipped> hives, java.util.Set<HiveType> present) { }
    private record Visibility(boolean enabled, long changedAt) { }
    private static final Map<Player, EquippedCache> ACTIVE = new WeakHashMap<>();
    private static final Map<Player, EnumMap<HiveType, Visibility>> VISIBILITY = new WeakHashMap<>();
    /** Last drawn position of every drone, so a hit or recalled drone flies home from exactly where it was seen. */
    private static final Map<Player, EnumMap<HiveType, Vec3[]>> SEEN = new WeakHashMap<>();
    /** Drone models per frame, then how many of them may be the full model and how many the swarm model; the rest are the dense model. */
    private static final int MODEL_BUDGET = 900, FULL_BUDGET = 6, SWARM_BUDGET = 160;
    /** Beyond RANGE nothing is drawn, beyond MODEL_RANGE drones are points of light; FULL_RANGE and SWARM_RANGE pick the model's detail. */
    private static final double RANGE = 176, MODEL_RANGE = 48, FULL_RANGE = 4, SWARM_RANGE = 10;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            ACTIVE.clear(); VISIBILITY.clear(); SEEN.clear();
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long now = minecraft.level.getGameTime();
        double time = now + partial;
        Vec3 camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poses = event.getPoseStack();
        var matrix = poses.last().pose();
        GlowBrush.setPixelAngle(2 * Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2) / Math.max(1, minecraft.getWindow().getHeight()));
        var glow = ShieldGlow.consumer();
        int[] budget = {MODEL_BUDGET, FULL_BUDGET, SWARM_BUDGET};
        List<HiveModeVisual.Scene> scenes = new ArrayList<>();
        var players = new ArrayList<>(minecraft.level.players());
        players.sort(Comparator.comparingDouble(player -> player.distanceToSqr(camera)));
        for (var player : players) {
            if (player.isInvisible()) continue;
            EquippedCache cached = active(player, now, minecraft.level);
            for (var hive : cached.hives()) {
                renderHive(minecraft, event, player, hive, cached.present().contains(hive.type()), now, time, partial, camera, poses, glow, budget, scenes);
            }
        }
        // Black holes bend and darken the world behind them first, before the drones and the light go
        // over it, so those stay crisp and the drones stay where their hexagons are.
        for (HiveModeVisual.Scene scene : scenes) HiveModeVisual.lenses(scene, camera);
        BlackHoleLens.flush(matrix);
        // Drone models are drawn next; the glass of the constructs goes in a buffer taken only after
        // that batch ends, since ending it would also end (and invalidate) a buffer taken before.
        buffers.endBatch();
        var fill = buffers.getBuffer(ShieldVisualRenderer.renderType());
        for (HiveModeVisual.Scene scene : scenes) HiveModeVisual.render(scene, camera, glow, fill, matrix);
        if (EffectLights.enabled()) scenes.forEach(HiveModeVisual::light);
        // Horizons go in solid and write depth before any light, so nothing behind a black hole shows
        // through it; then space bends round blasts, and the glass and the light are laid over it.
        ShieldGlow.flushHorizons();
        ShieldRefraction.flush(matrix);
        buffers.endBatch(ShieldVisualRenderer.renderType());
        ShieldGlow.flush();
        buffers.endBatch(HiveCombatVisual.renderType());
    }

    private static void renderHive(Minecraft minecraft, RenderLevelStageEvent event, Player player, HiveController.Equipped hive, boolean present,
            long now, double time, float partial, Vec3 camera, PoseStack poses, com.mojang.blaze3d.vertex.VertexConsumer glow, int[] budget,
            List<HiveModeVisual.Scene> scenes) {
        ItemStack stack = hive.stack();
        HiveType type = hive.type();
        HiveStackState swarm = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        HiveCombatState combat = stack.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
        HiveSettings settings = HiveTaskController.settings(stack);
        boolean enabled = present && swarm.enabled();
        Visibility visibility = visibility(player, type, enabled, time);
        boolean fadingOut = !visibility.enabled() && time - visibility.changedAt() < 10;
        if (!enabled && !fadingOut) return;
        List<HiveStackState.Unit> units = swarm.units();
        int count = units.size();
        if (count == 0) return;
        int fighters = settings.fighters(count), slots = HiveSlots.fighterSlots(count, settings), healerSlots = HiveSlots.healerSlots(count, settings);
        Vec3 owner = new Vec3(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()), Mth.lerp(partial, player.zo, player.getZ()));
        float yaw = Mth.rotLerp(partial, player.yRotO, player.getYRot());
        double appear = visibility.enabled() ? Mth.clamp((time - visibility.changedAt()) / 10.0, 0, 1) : Mth.clamp(1 - (time - visibility.changedAt()) / 10.0, 0, 1);
        Vec3[] seen = SEEN.computeIfAbsent(player, ignored -> new EnumMap<>(HiveType.class)).compute(type,
                (ignored, old) -> old == null || old.length != count ? new Vec3[count] : old);

        // Every engaged creature where it is this frame (or where it was last recorded, if it is not loaded here).
        List<HiveTarget> targets = new ArrayList<>(combat.targets().size());
        boolean near = owner.distanceTo(camera) < RANGE;
        for (HiveTarget recorded : combat.targets()) {
            Entity entity = minecraft.level.getEntity(recorded.id());
            HiveTarget live = entity != null ? new HiveTarget(recorded.id(), entity.getPosition(partial), entity.getBbWidth(), entity.getBbHeight()) : recorded;
            targets.add(live);
            near |= live.feet().distanceTo(camera) < RANGE;
        }
        if (!near) return;
        long cycleStart = combat.changedAt() + combat.travel();
        int interval = HiveCombatController.strikeInterval(player, stack);

        if (combat.active() && slots > 0 && !targets.isEmpty()) {
            int groups = HiveSlots.groups(slots, combat.mode()), engaged = HiveFormation.engaged(targets.size(), groups);
            int[] members = new int[groups];
            Vec3[] drones = new Vec3[slots];
            for (int slot = 0; slot < slots; slot++) {
                int unit = HiveSlots.occupant(units, slot, slots, fighters, now);
                if (unit < 0) continue;
                long since = HiveSlots.since(units, slot, slots, fighters, unit, now);
                double launched = Math.max(since, combat.changedAt());
                int group = HiveSlots.group(slot, groups);
                Vec3 station = HiveFormation.engagedStation(combat.mode(), type, slot, slots, owner, targets, combat.previous(), combat.retargetedAt(),
                        time, cycleStart, interval);
                Vec3 at = HiveFormation.deployed(owner, yaw, station, unit, count, type, time, launched, combat.travel());
                HiveTarget target = targets.get(group % engaged);
                members[group]++;
                drones[slot] = at;
                seen[unit] = at;
                drawDrone(minecraft, event, player, type, at, HiveFormation.core(target.feet(), target.height()), appear, count, camera, poses, glow, budget);
            }
            scenes.add(new HiveModeVisual.Scene(combat.mode(), type, slots, groups, members, drones, owner, List.copyOf(targets), time, cycleStart,
                    interval, time >= combat.changedAt() + combat.travel() * .5, null, groups));
        }
        // Hit drones fly home from where they were struck; after a recall the whole swarm does.
        for (int unit = 0; unit < fighters; unit++) {
            HiveStackState.Unit state = units.get(unit);
            double homeFrom = !combat.active() && combat.changedAt() > 0 ? combat.changedAt() : state.lastHit();
            boolean recalled = !combat.active() && time - combat.changedAt() < HiveFormation.RETURN_TICKS && seen[unit] != null;
            boolean struck = state.lastHit() >= 0 && time - state.lastHit() < HiveFormation.RETURN_TICKS && !state.ready(now);
            if (!recalled && !struck) continue;
            double from = struck ? state.lastHit() : homeFrom;
            Vec3 start = seen[unit] != null ? seen[unit] : HiveFormation.belt(owner, yaw, type);
            Vec3 at = HiveFormation.returning(owner, yaw, start, unit, count, type, time, from);
            drawDrone(minecraft, event, player, type, at, null, appear * (struck ? .8 : 1), count, camera, poses, glow, budget);
            if (struck && ((long) time + unit) % 3 == 0) GlowBrush.dot(glow, poses.last().pose(), at.subtract(camera), .12, 0xFFB36B, 160);
        }
        if (!combat.active() && time - combat.changedAt() >= HiveFormation.RETURN_TICKS) java.util.Arrays.fill(seen, null);

        var support = stack.getOrDefault(ModDataComponents.HIVE_SUPPORT_STATE.get(), HiveSupportState.DEFAULT);
        double supportAge = time - (enabled ? support.changedAt() : visibility.changedAt());
        double supportProgress = support.active() && enabled ? Mth.clamp(supportAge / 12, 0, 1)
                : support.changedAt() > 0 ? Mth.clamp(1 - supportAge / 12, 0, 1) : 0;
        if (supportProgress > 0) for (int index = 0; index < healerSlots; index++) {
            Vec3 at = HiveFormation.healing(owner, yaw, index, healerSlots, type, time, supportProgress);
            drawDrone(minecraft, event, player, type, at, null, appear * Math.min(1, supportProgress * 4), count, camera, poses, glow, budget);
        }
        HiveCombatVisual.renderShots(combat.shots(), type, camera, poses.last().pose(), time);
    }

    private static void drawDrone(Minecraft minecraft, RenderLevelStageEvent event, Player player, HiveType type, Vec3 at, Vec3 facing,
            double appear, int count, Vec3 camera, PoseStack poses, com.mojang.blaze3d.vertex.VertexConsumer glow, int[] budget) {
        if (appear <= .01) return;
        if (!event.getFrustum().isVisible(new AABB(at, at).inflate(.45))) return;
        if (player == minecraft.player && minecraft.options.getCameraType().isFirstPerson() && at.distanceToSqr(camera) < 1) return;
        double distance = at.distanceTo(camera);
        if (distance > MODEL_RANGE || budget[0] <= 0) {
            GlowBrush.dot(glow, poses.last().pose(), at.subtract(camera), .09, HiveModeVisual.color(type), 200 * appear);
            return;
        }
        budget[0]--;
        // Detail follows distance: the full model for the nearest few, the swarm model nearby, the dense one further off.
        boolean full = distance < FULL_RANGE && budget[1] > 0;
        if (full) budget[1]--;
        boolean dense = !full && (distance > SWARM_RANGE || budget[2] <= 0);
        if (!full && !dense) budget[2]--;
        poses.pushPose();
        poses.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        float size = (count > 50 ? .22F : .30F) * (float) appear;
        poses.scale(size, size, size);
        // The models look along -Z (the RF optic sits on that side), so that side is turned towards the target.
        if (facing != null) {
            Vec3 direction = facing.subtract(at);
            poses.mulPose(Axis.YP.rotation((float) Math.atan2(-direction.x, -direction.z)));
            poses.mulPose(Axis.XP.rotation((float) Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z))));
        } else poses.mulPose(Axis.YP.rotationDegrees(180 - player.getYRot()));
        poses.translate(-.5, -.5, -.5);
        renderModel(type, poses, minecraft.renderBuffers().bufferSource(), full, dense);
        poses.popPose();
    }

    private static EquippedCache active(Player player, long tick, Object level) {
        EquippedCache cached = ACTIVE.get(player);
        if (cached == null || cached.tick() != tick || cached.level() != level) {
            List<HiveController.Equipped> current = HiveController.active(player);
            var present = java.util.EnumSet.noneOf(HiveType.class);
            for (var hive : current) present.add(hive.type());
            var retained = new ArrayList<>(current);
            if (cached != null && cached.level() == level) for (var old : cached.hives()) {
                if (!present.contains(old.type())) {
                    var fade = visibility(player, old.type(), false, tick);
                    if (tick - fade.changedAt() < 10) retained.add(old);
                }
            }
            if (cached != null && cached.level() == level && player.level() instanceof net.minecraft.world.level.Level world) {
                for (HiveType type : HiveType.values()) {
                    boolean was = cached.present().contains(type), is = present.contains(type);
                    if (was != is) dev.hurtify.relicsaddon.client.fx.ExFx.hiveSummon(world, player, type, is);
                }
            }
            cached = new EquippedCache(tick, level, List.copyOf(retained), present);
            ACTIVE.put(player, cached);
        }
        return cached;
    }

    private static Visibility visibility(Player player, HiveType type, boolean enabled, double time) {
        EnumMap<HiveType, Visibility> values = VISIBILITY.computeIfAbsent(player, ignored -> new EnumMap<>(HiveType.class));
        Visibility old = values.get(type);
        if (old == null || old.enabled() != enabled) {
            Visibility next = new Visibility(enabled, (long) time);
            values.put(type, next);
            return next;
        }
        return old;
    }

    public static void renderModel(HiveType type, PoseStack poses, net.minecraft.client.renderer.MultiBufferSource buffers, boolean detailed) {
        AnimatedRelicItemRenderer.getInstance().renderSwarm(type.drone, poses, buffers, detailed);
    }

    public static void renderModel(HiveType type, PoseStack poses, net.minecraft.client.renderer.MultiBufferSource buffers, boolean detailed, boolean dense) {
        AnimatedRelicItemRenderer.getInstance().renderSwarm(type.drone, poses, buffers, detailed, dense);
    }

    private HiveVisualRenderer() { }
}
