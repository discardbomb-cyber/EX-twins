package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveFlightPlan;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveSupportState;
import dev.hurtify.relicsaddon.drone.HiveTarget;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.ManaArmageddon;
import dev.hurtify.relicsaddon.drone.RfArmageddon;
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
    /** Blocks an Armageddon black hole is swallowing, by the shot's start: a sample of them, noted before they went. */
    private static final Map<Long, List<Prey>> PREY = new java.util.HashMap<>();

    /** A block the black hole takes: where it was, what it was, and when it is torn away. */
    private record Prey(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, double takenAt) { }

    /** A few of each deployed drone's last places, half a tick apart, for the thin trail behind it. */
    private static final Map<Player, EnumMap<HiveType, Trail[]>> TRAILS = new WeakHashMap<>();

    private static final class Trail {
        final Vec3[] points = new Vec3[3];
        double sampled = -1;

        /** Notes {@code at} if half a tick has passed since the last note; returns the oldest place noted. */
        Vec3 note(Vec3 at, double time) {
            if (sampled < 0 || time - sampled >= .5 || time < sampled) {
                System.arraycopy(points, 0, points, 1, points.length - 1);
                points[0] = at;
                sampled = time;
            }
            return points[points.length - 1];
        }
    }

    /** Where each drone was when an Armageddon shot began, so it flies into the cannon from there. */
    private static final Map<Player, EnumMap<HiveType, Vec3[]>> LAUNCHES = new WeakHashMap<>();
    /** Drone models per frame, then how many of them may be the full model and how many the swarm model; the rest are the dense model. */
    private static final int MODEL_BUDGET = 900, FULL_BUDGET = 6, SWARM_BUDGET = 160;
    /** Beyond RANGE nothing is drawn, beyond MODEL_RANGE drones are points of light; FULL_RANGE and SWARM_RANGE pick the model's detail. */
    private static final double RANGE = 176, MODEL_RANGE = 48, FULL_RANGE = 4, SWARM_RANGE = 10;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            afterLevel(event);
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            // The world's depth as it stands now, before Fabulous graphics put the frame together over a cleared one.
            ArmageddonVolume.captureDepth();
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            ACTIVE.clear(); VISIBILITY.clear(); SEEN.clear(); LAUNCHES.clear(); PREY.clear();
            HiveJuice.clear();
            ArmageddonVisual.BLASTS.clear();
            ManaArmageddonVisual.BLASTS.clear();
            RfArmageddonVisual.clear();
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long now = minecraft.level.getGameTime();
        double time = now + partial;
        RfArmageddonVisual.startFrame(time);
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
        ArmageddonVisual.debris(minecraft, time, camera, poses);
        ArmageddonVisual.flushDebris();
        ManaArmageddonVisual.debris(minecraft, time, camera, poses);
        ManaArmageddonVisual.flushDebris();
        RfArmageddonVisual.debris(minecraft, time, camera, poses);
        RfArmageddonVisual.flushDebris();
        // Black holes bend and darken the world behind them first, before the drones and the light go
        // over it, so those stay crisp and the drones stay where their hexagons are.
        for (HiveModeVisual.Scene scene : scenes) HiveModeVisual.lenses(scene, camera);
        ArmageddonVisual.lenses(time, camera);
        ManaArmageddonVisual.volumes(time, camera, glow, ManaRunes.consumer(), matrix);
        RfArmageddonVisual.volumes(time, camera);
        BlackHoleLens.flush(matrix);
        ArmageddonVolume.flush(matrix);
        // Drone models are drawn next; the glass of the constructs goes in a buffer taken only after
        // that batch ends, since ending it would also end (and invalidate) a buffer taken before.
        buffers.endBatch();
        var fill = buffers.getBuffer(ShieldVisualRenderer.renderType());
        for (HiveModeVisual.Scene scene : scenes) HiveModeVisual.render(scene, camera, glow, fill, matrix);
        HiveJuice.render(camera, glow, fill, matrix, time);
        HiveProjectiles.flush(camera, glow, fill, matrix);
        RfArmageddonVisual.scorches(time, camera, fill, matrix);
        if (EffectLights.enabled()) scenes.forEach(HiveModeVisual::light);
        // Horizons go in solid and write depth before any light, so nothing behind a black hole shows
        // through it; then space bends round blasts, and the glass and the light are laid over it.
        ArmageddonVisual.blasts(time, camera, glow, matrix);
        ShieldGlow.flushHorizons();
        ShieldRefraction.flush(matrix);
        buffers.endBatch(ShieldVisualRenderer.renderType());
        ShieldGlow.flush();
        ManaRunes.flush();
        buffers.endBatch(HiveCombatVisual.renderType());
    }

    /**
     * Once the whole level is drawn, clouds, weather and (with Fabulous graphics) its transparent layers put together:
     * Mana Armageddon's passes and the light it throws round the target, so its white sky covers the clouds, its column
     * stands in front of or behind them as it should, and its light goes over all; and RF Armageddon's, its grading of
     * the whole world, its ball and dome, and their light over them. The level's view no longer stands on the render
     * system here, so it is carried in the matrix everything is drawn with.
     */
    private static void afterLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        double time = minecraft.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var matrix = new org.joml.Matrix4f(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        ArmageddonVolume.flushLate(matrix);
        ManaArmageddonVisual.blasts(time, camera, ShieldGlow.consumer(), ManaRunes.consumer(), matrix);
        RfArmageddonVisual.late(time, camera, ShieldGlow.consumer(), matrix);
        ShieldGlow.flush();
        ManaRunes.flush();
    }

    private static void renderHive(Minecraft minecraft, RenderLevelStageEvent event, Player player, HiveController.Equipped hive, boolean present,
            long now, double time, float partial, Vec3 camera, PoseStack poses, com.mojang.blaze3d.vertex.VertexConsumer glow, int[] budget,
            List<HiveModeVisual.Scene> scenes) {
        ItemStack stack = hive.stack();
        HiveType type = hive.type();
        HiveStackState swarm = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        HiveCombatState combat = stack.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
        boolean enabled = present && swarm.enabled();
        Visibility visibility = visibility(player, type, enabled, time);
        boolean fadingOut = !visibility.enabled() && time - visibility.changedAt() < 10;
        if (!enabled && !fadingOut) return;
        List<HiveStackState.Unit> units = swarm.units();
        int count = units.size();
        if (count == 0) return;
        HiveFlightPlan plan = HiveCombatController.plan(stack, type, count);
        int fighters = plan.fighters(), slots = plan.slots(), healerSlots = plan.healerSlots();
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
        int interval = HiveCombatController.strikeInterval(player, stack);

        ArmageddonState armageddon = stack.get(ModDataComponents.HIVE_ARMAGEDDON.get());
        if (armageddon != null && armageddon.running(now)) {
            armageddon(minecraft, event, player, type, armageddon, units, plan, count, owner, yaw, appear, seen, now, time, partial,
                    camera, poses, glow, budget);
        } else if (combat.active() && slots > 0 && !targets.isEmpty()) {
            // Each wing flies its own places round the creatures it takes on, in its own order.
            int held = HiveCombatController.held(type, combat, targets.size());
            for (HiveFlightPlan.Wing wing : plan.wings()) {
                HiveCombatState.Wing flying = combat.wing(wing.mode());
                if (!flying.out() || !wing.flies()) continue;
                List<HiveTarget> wingTargets = HiveFormation.wingTargets(wing.mode(), targets, held);
                List<HiveTarget> previous = HiveFormation.wingTargets(wing.mode(), combat.previous(), combat.previousHeld());
                long cycleStart = HiveCombatController.cycleStart(combat, flying);
                double start = Math.max(combat.changedAt(), flying.since());
                int groups = wing.groups(), engaged = HiveFormation.engaged(wingTargets.size(), wing.figures());
                int[] members = new int[groups];
                Vec3[] drones = new Vec3[wing.slots()];
                int[] occupants = wing.occupants(units, now);
                for (int slot = 0; slot < wing.slots(); slot++) {
                    int unit = occupants[slot];
                    if (unit < 0) continue;
                    double launched = Math.max(wing.since(units, slot, unit, now), start);
                    int group = HiveSlots.group(slot, groups);
                    Vec3 station = HiveFormation.engagedStation(wing.mode(), type, slot, wing.slots(), owner, wingTargets, previous, combat.retargetedAt(),
                            time, cycleStart, interval);
                    Vec3 at = HiveFormation.deployed(owner, yaw, station, unit, count, type, time, launched, combat.travel());
                    HiveTarget target = wingTargets.get(group % engaged);
                    members[group]++;
                    seen[unit] = at;
                    // A Twins figure between the rifts of its jump is nowhere to be seen.
                    if (wing.mode() == AttackMode.DROPLET && type == HiveType.TWINS && HiveFormation.dropletHidden(type, HiveFormation.sortie(owner,
                            wingTargets.getFirst().feet(), target.feet(), target.height(), group, groups, time, cycleStart, interval))) continue;
                    drones[slot] = at;
                    trail(player, type, unit, count, at, time, camera, glow, poses);
                    drawDrone(minecraft, event, player, type, at, HiveFormation.core(target.feet(), target.height()), appear, count, camera, poses, glow, budget);
                }
                scenes.add(new HiveModeVisual.Scene(wing.mode(), type, wing.slots(), groups, members, drones, owner, List.copyOf(wingTargets), time,
                        cycleStart, interval, time >= start + combat.travel() * .5, null, groups));
            }
        }
        // Hit drones fly home from where they were struck; a wing that ran short of drones flies home whole, and after a recall the whole swarm does.
        for (int unit = 0; unit < fighters; unit++) {
            HiveStackState.Unit state = units.get(unit);
            double benchedAt = -1;
            if (combat.active()) for (HiveFlightPlan.Wing wing : plan.wings()) {
                if (wing.owns(unit) && !combat.wing(wing.mode()).out()) benchedAt = combat.wing(wing.mode()).since();
            }
            if (benchedAt >= 0 && time - benchedAt >= HiveFormation.RETURN_TICKS) seen[unit] = null;
            boolean recalled = !combat.active() && time - combat.changedAt() < HiveFormation.RETURN_TICKS && seen[unit] != null;
            boolean benched = benchedAt >= 0 && time >= benchedAt && seen[unit] != null;
            boolean struck = state.lastHit() >= 0 && time - state.lastHit() < HiveFormation.RETURN_TICKS && !state.ready(now);
            if (!recalled && !benched && !struck) continue;
            double from = struck ? state.lastHit() : recalled ? combat.changedAt() : benchedAt;
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

    /** Where each drone was when {@code shot} began, noted once per shot (the last element marks which shot it is). */
    private static Vec3[] launches(Player player, HiveType type, ArmageddonState shot, Vec3[] seen, int count) {
        return LAUNCHES.computeIfAbsent(player, ignored -> new EnumMap<>(HiveType.class)).compute(type, (ignored, old) -> {
            if (old != null && old.length == count + 1 && old[count] != null && (long) old[count].x == shot.startedAt()) return old;
            Vec3[] start = java.util.Arrays.copyOf(seen, count + 1);
            start[count] = new Vec3(shot.startedAt(), 0, 0);
            return start;
        });
    }

    /**
     * The swarm in the Armageddon cannon: every drone flies from where it was to its place in it, faces
     * the target while it charges and fires, and flies home once it comes apart.
     */
    private static void armageddon(Minecraft minecraft, RenderLevelStageEvent event, Player player, HiveType type, ArmageddonState shot,
            List<HiveStackState.Unit> units, HiveFlightPlan plan, int count, Vec3 owner, float yaw, double appear, Vec3[] seen, long now,
            double time, float partial, Vec3 camera, PoseStack poses, com.mojang.blaze3d.vertex.VertexConsumer glow, int[] budget) {
        if (shot.type() == HiveType.MANA) {
            manaArmageddon(minecraft, event, player, type, shot, units, plan, count, owner, yaw, appear, seen, now, time, partial,
                    camera, poses, glow, budget);
            return;
        }
        if (shot.type() == HiveType.RF) {
            rfArmageddon(minecraft, event, player, type, shot, units, plan, count, owner, yaw, appear, seen, now, time, partial,
                    camera, poses, glow, budget);
            return;
        }
        // The whole swarm flies in the Armageddon, whatever its modes: every fighter, as many as fit in the air.
        HiveFlightPlan.Wing swarm = plan.whole();
        int slots = swarm.slots();
        int[] occupants = swarm.occupants(units, now);
        Vec3[] from = launches(player, type, shot, seen, count);
        double recover = shot.startedAt() + Armageddon.RECOVER;
        // The escort is flung off when the containment breaks and lost until the cannon comes apart and calls it home.
        boolean lost = time > shot.startedAt() + Armageddon.BROKEN + 4 && time < recover;
        int escort = Armageddon.cores(slots) + Armageddon.ringed(slots);
        for (int slot = 0; slot < slots; slot++) {
            int unit = occupants[slot];
            if (unit < 0 || lost && slot >= escort) continue;
            Vec3 station = Armageddon.station(shot, slot, slots, Math.min(time, recover));
            Vec3 at;
            if (time >= recover) at = HiveFormation.returning(owner, yaw, station, unit, count, type, time, recover);
            else if (from[unit] != null) at = HiveFormation.flight(from[unit], station, unit, type, (time - shot.startedAt()) / Armageddon.ASSEMBLED);
            else at = HiveFormation.deployed(owner, yaw, station, unit, count, type, time, shot.startedAt(), Armageddon.ASSEMBLED);
            seen[unit] = at;
            drawDrone(minecraft, event, player, type, at, time >= recover ? null : shot.target(), appear, count, camera, poses, glow, budget);
        }
        Vec3 chest = new Vec3(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()) + player.getBbHeight() * .6,
                Mth.lerp(partial, player.zo, player.getZ()));
        ArmageddonVisual.cannon(shot, chest, time, camera, glow, poses.last().pose());
        swallow(minecraft, shot, time, camera, poses);
    }

    /**
     * The swarm in Mana Armageddon: every drone spirals in from where it was to its place in a flower, the
     * places nearest the hearts filling first; the escort rides the streams out, swirls round the collision
     * and holds the sphere of runes until it shatters and flings them off; and all fly home once the white
     * has settled.
     */
    private static void manaArmageddon(Minecraft minecraft, RenderLevelStageEvent event, Player player, HiveType type, ArmageddonState shot,
            List<HiveStackState.Unit> units, HiveFlightPlan plan, int count, Vec3 owner, float yaw, double appear, Vec3[] seen, long now,
            double time, float partial, Vec3 camera, PoseStack poses, com.mojang.blaze3d.vertex.VertexConsumer glow, int[] budget) {
        Vec3[] from = launches(player, type, shot, seen, count);
        // The whole swarm flies in the Armageddon, whatever its modes: every fighter, as many as fit in the air.
        HiveFlightPlan.Wing swarm = plan.whole();
        int slots = swarm.slots();
        int[] occupants = swarm.occupants(units, now);
        double age = shot.age(time), recover = shot.startedAt() + ManaArmageddon.RECOVER;
        // The escort is flung off as the sphere shatters and lost until the drones are called home.
        boolean lost = age > ManaArmageddon.IMPACT + 30 && time < recover;
        int flowered = ManaArmageddon.flowered(slots);
        for (int slot = 0; slot < slots; slot++) {
            int unit = occupants[slot];
            if (unit < 0 || lost && slot >= flowered) continue;
            Vec3 station = ManaArmageddon.station(shot, slot, slots, Math.min(time, recover));
            Vec3 at;
            if (time >= recover) at = HiveFormation.returning(owner, yaw, station, unit, count, type, time, recover);
            else {
                double gathered = slot < flowered ? ManaArmageddon.gathered(slot, slots, age) : Math.clamp(age / ManaArmageddon.ASSEMBLED, 0, 1);
                Vec3 start = from[unit] != null ? from[unit] : HiveFormation.idle(owner, yaw, unit, count, type, shot.startedAt());
                at = ManaArmageddon.spiral(shot, ManaArmageddon.side(slot < flowered ? slot : slot - flowered), start, station, gathered);
            }
            seen[unit] = at;
            // Petal drones a little smaller than the swarm's, so the petals read as petals.
            double scale = slot < flowered && age < ManaArmageddon.IGNITE + ManaArmageddon.COLLAPSE ? .7 : 1;
            drawDrone(minecraft, event, player, type, at, time >= recover ? null : shot.target(), appear * scale, count, camera, poses, glow, budget);
            if (slot < flowered && age < ManaArmageddon.IGNITE) ManaArmageddonVisual.twinkle(glow, poses.last().pose(), at.subtract(camera), slot, time, ManaArmageddon.side(slot));
        }
        Vec3 chest = new Vec3(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()) + player.getBbHeight() * .6,
                Mth.lerp(partial, player.zo, player.getZ()));
        ManaArmageddonVisual.construct(shot, chest, time, camera, glow, ManaRunes.consumer(), poses.last().pose());
    }

    /**
     * The swarm in RF Armageddon: every drone flies in to the axis and out to its place in the hologram of the relay
     * drone, the body built from the stern to the nose and the panels laid along it; as the ball leaves, the hologram's
     * drones scatter home while its escort rides the rings round the ball, until the flash flings them off and the
     * drones are called home.
     */
    private static void rfArmageddon(Minecraft minecraft, RenderLevelStageEvent event, Player player, HiveType type, ArmageddonState shot,
            List<HiveStackState.Unit> units, HiveFlightPlan plan, int count, Vec3 owner, float yaw, double appear, Vec3[] seen, long now,
            double time, float partial, Vec3 camera, PoseStack poses, com.mojang.blaze3d.vertex.VertexConsumer glow, int[] budget) {
        Vec3[] from = launches(player, type, shot, seen, count);
        // The whole swarm flies in the Armageddon, whatever its modes: every fighter, as many as fit in the air.
        HiveFlightPlan.Wing swarm = plan.whole();
        int slots = swarm.slots();
        int[] occupants = swarm.occupants(units, now);
        double age = shot.age(time), scatter = shot.startedAt() + RfArmageddon.SCATTER, recover = shot.startedAt() + RfArmageddon.RECOVER;
        int hologram = RfArmageddon.hologram(slots);
        // The escort is flung off by the flash and lost until the drones are called home.
        boolean lost = age > RfArmageddon.IMPACT + RfArmageddon.FLASH + 30 && time < recover;
        for (int slot = 0; slot < slots; slot++) {
            int unit = occupants[slot];
            boolean escort = slot >= hologram;
            if (unit < 0 || lost && escort) continue;
            boolean home = !escort && time >= scatter || time >= recover;
            Vec3 at;
            if (!escort && time >= scatter) at = HiveFormation.returning(owner, yaw, RfArmageddon.station(shot, slot, slots, scatter), unit, count, type, time, scatter);
            else if (time >= recover) at = HiveFormation.returning(owner, yaw, RfArmageddon.station(shot, slot, slots, recover), unit, count, type, time, recover);
            else {
                Vec3 start = from[unit] != null ? from[unit] : HiveFormation.idle(owner, yaw, unit, count, type, shot.startedAt());
                at = RfArmageddon.assemble(shot, start, RfArmageddon.station(shot, slot, slots, time), RfArmageddon.gathered(slot, slots, age));
            }
            seen[unit] = at;
            // The hologram's drones a little smaller than the swarm's, so its lines read; the escort faces the ball it rides with.
            Vec3 facing = home ? null : escort && age >= RfArmageddon.FIRE ? RfArmageddon.ball(shot, Math.min(age, RfArmageddon.IMPACT)) : shot.target();
            drawDrone(minecraft, event, player, type, at, facing, appear * (home ? 1 : .8), count, camera, poses, glow, budget);
        }
        Vec3 chest = new Vec3(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()) + player.getBbHeight() * .6,
                Mth.lerp(partial, player.zo, player.getZ()));
        RfArmageddonVisual.construct(shot, chest, time, camera, glow, poses.last().pose());
    }

    /**
     * The blocks the black hole tears up: noted (a sample of a few hundred) just before it starts to eat,
     * then each flies in on a tightening spiral, tumbling and shrinking, from the moment it is taken.
     */
    private static void swallow(Minecraft minecraft, ArmageddonState shot, double time, Vec3 camera, PoseStack poses) {
        double age = shot.age(time);
        if (age < Armageddon.ARRIVE - 3 || age > Armageddon.IMPACT + 2 || dev.hurtify.relicsaddon.server.ArmageddonController.safeClient()) {
            if (age > Armageddon.IMPACT + 2) PREY.remove(shot.startedAt());
            return;
        }
        List<Prey> prey = PREY.computeIfAbsent(shot.startedAt(), ignored -> notePrey(minecraft, shot.target()));
        Vec3 hole = shot.target();
        var buffers = minecraft.renderBuffers().bufferSource();
        var blocks = minecraft.getBlockRenderer();
        for (Prey block : prey) {
            double flight = (age - block.takenAt()) / 14;
            if (flight < 0 || flight >= 1) continue;
            Vec3 from = net.minecraft.world.phys.Vec3.atCenterOf(block.pos());
            double ease = flight * flight;
            Vec3 in = from.subtract(hole);
            double swirl = 2.4 * ease;
            Vec3 spun = new Vec3(in.x * Math.cos(swirl) - in.z * Math.sin(swirl), in.y, in.x * Math.sin(swirl) + in.z * Math.cos(swirl));
            Vec3 at = hole.add(spun.scale(1 - ease));
            float size = (float) (1 - .85 * ease);
            poses.pushPose();
            poses.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            poses.mulPose(Axis.YP.rotation((float) (flight * 9 + block.pos().hashCode())));
            poses.mulPose(Axis.XP.rotation((float) (flight * 7)));
            poses.scale(size, size, size);
            poses.translate(-.5, -.5, -.5);
            blocks.renderSingleBlock(block.state(), poses, buffers, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            poses.popPose();
        }
    }

    /**
     * A few hundred of the blocks round the black hole, spread evenly from the middle out, with when each is
     * taken: the top block of every column in its reach (the ones that show), each taken as the black hole's reach
     * passes its column.
     */
    private static List<Prey> notePrey(Minecraft minecraft, Vec3 hole) {
        List<Prey> all = new ArrayList<>();
        int reach = (int) Math.ceil(Armageddon.DEVOUR_RADIUS), cx = (int) Math.floor(hole.x), cz = (int) Math.floor(hole.z);
        double most = Armageddon.DEVOUR_RADIUS * Armageddon.DEVOUR_RADIUS;
        for (int x = cx - reach; x <= cx + reach; x++) for (int z = cz - reach; z <= cz + reach; z++) {
            double dx = x + .5 - hole.x, dz = z + .5 - hole.z, flat = dx * dx + dz * dz;
            if (flat > most) continue;
            int y = minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (Math.abs(y + .5 - hole.y) > Math.sqrt(most - flat)) continue;
            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(x, y, z);
            var state = minecraft.level.getBlockState(at);
            if (state.isAir() || state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            all.add(new Prey(at, state, Armageddon.devouredAt(Math.sqrt(flat))));
        }
        int step = Math.max(1, all.size() / 450);
        List<Prey> sample = new ArrayList<>();
        for (int index = 0; index < all.size(); index += step) sample.add(all.get(index));
        return sample;
    }

    /** A thin trail behind a drone on the move, fading back over its last two ticks; none far off or at low detail. */
    private static void trail(Player player, HiveType type, int unit, int count, Vec3 at, double time, Vec3 camera,
            com.mojang.blaze3d.vertex.VertexConsumer glow, PoseStack poses) {
        if (HiveJuice.detail() == HiveJuice.Detail.LOW || at.distanceToSqr(camera) > 40 * 40) return;
        Trail[] trails = TRAILS.computeIfAbsent(player, ignored -> new EnumMap<>(HiveType.class)).compute(type,
                (ignored, old) -> old == null || old.length != count ? new Trail[count] : old);
        if (trails[unit] == null) trails[unit] = new Trail();
        Vec3 oldest = trails[unit].note(at, time);
        if (oldest == null) return;
        double moved = oldest.distanceTo(at);
        if (moved < .15 || moved > 6) return;
        int color = HiveModeVisual.color(type);
        GlowBrush.line(glow, poses.last().pose(), at.subtract(camera), oldest.subtract(camera), .025, .004, color, color, 130, 0);
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
