package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.HiveSupportState;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.HiveController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Client projection of synchronized hive state. It never creates entities, targets, damage or sounds. */
public final class HiveVisualRenderer {
    private record EquippedCache(long tick, Object level, List<HiveController.Equipped> hives, java.util.Set<HiveType> present) { }
    private record CombatPose(Vec3 target, double width, double height, long changedAt, long disabledAt) { }
    private record Visibility(boolean enabled, long changedAt) { }
    private static final Map<net.minecraft.world.entity.player.Player, EquippedCache> ACTIVE = new WeakHashMap<>();
    private static final Map<net.minecraft.world.entity.player.Player, EnumMap<HiveType, CombatPose>> POSES = new WeakHashMap<>();
    private static final Map<net.minecraft.world.entity.player.Player, EnumMap<HiveType, Visibility>> VISIBILITY = new WeakHashMap<>();
    private static final int MODEL_BUDGET = 750;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            ACTIVE.clear(); POSES.clear(); VISIBILITY.clear(); return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = minecraft.level.getGameTime() + partial;
        Vec3 camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poses = event.getPoseStack();
        boolean rendered = false;
        int budget = MODEL_BUDGET;
        var players = new ArrayList<>(minecraft.level.players());
        players.sort(Comparator.comparingDouble(player -> player.distanceToSqr(camera)));
        for (var player : players) {
            if (player.isInvisible() || player.distanceToSqr(camera) > 32 * 32) continue;
            EquippedCache cached = active(player, minecraft.level.getGameTime(), minecraft.level);
            for (var hive : cached.hives()) {
                HiveStackState units = hive.stack().getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
                HiveCombatState combat = hive.stack().getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
                boolean enabled = cached.present().contains(hive.type()) && units.enabled();
                boolean liveCombat = enabled && combat.active();
                CombatPose combatPose = resolvePose(player, hive.type(), combat, liveCombat, time, minecraft);
                Visibility visibility = visibility(player, hive.type(), enabled, time);
                boolean fadingOut = !visibility.enabled() && time - visibility.changedAt() < 10;
                if (!enabled && !fadingOut) continue;
                int count = units.units().size();
                if (count == 0) continue;
                var tasks = HiveTaskController.settings(hive.stack());
                int fighters = tasks.fighters(count);
                var support = hive.stack().getOrDefault(ModDataComponents.HIVE_SUPPORT_STATE.get(), HiveSupportState.DEFAULT);
                double progress = liveCombat ? Mth.clamp((time - combat.changedAt()) / 20.0, 0, 1)
                        : combatPose != null ? Mth.clamp(1 - (time - combatPose.disabledAt()) / 10.0, 0, 1) : 0;
                double appear = visibility.enabled() ? Mth.clamp((time - visibility.changedAt()) / 10.0, 0, 1)
                        : Mth.clamp(1 - (time - visibility.changedAt()) / 10.0, 0, 1);
                Vec3 owner = new Vec3(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()),
                        Mth.lerp(partial, player.zo, player.getZ()));
                float yaw = Mth.rotLerp(partial, player.yRotO, player.getYRot());
                List<Vec3> formation = liveCombat ? new ArrayList<>(count) : List.of();
                for (int index = 0; index < count; index++) {
                    HiveStackState.Unit unit = units.units().get(index);
                    double hitAge = unit.lastHit() < 0 ? 100 : time - unit.lastHit();
                    if (unit.hp() == 0 && (hitAge < 0 || hitAge >= 6)) continue;
                    boolean healer = tasks.healer(index, count);
                    double supportAge = time - (enabled ? support.changedAt() : visibility.changedAt());
                    double supportProgress = support.active() && enabled ? Mth.clamp(supportAge / 12, 0, 1)
                            : support.changedAt() > 0 ? Mth.clamp(1 - supportAge / 12, 0, 1) : 0;
                    double motion = healer ? supportProgress : progress;
                    Vec3 position;
                    if (healer) {
                        if (motion <= 0) continue;
                        position = HiveFormation.healing(owner, yaw, index - fighters, count - fighters, hive.type(), time, motion);
                    } else if (combatPose != null && motion > 0) {
                        position = HiveFormation.position(owner, yaw, combatPose.target(), combatPose.width(), combatPose.height(),
                                index, fighters, hive.type(), time, motion);
                    } else if (hitAge >= 0 && hitAge < 12) {
                        motion = 1 - hitAge / 12;
                        Vec3 point = owner.add(unit.x() * HiveController.RADIUS, .92 + unit.y() * HiveController.RADIUS, unit.z() * HiveController.RADIUS);
                        position = HiveFormation.belt(owner, yaw, hive.type()).lerp(point, motion * motion * (3 - 2 * motion));
                    } else continue;
                    if (liveCombat && !healer) formation.add(position);
                    if (budget <= 0) continue;
                    if (!event.getFrustum().isVisible(new AABB(position, position).inflate(.45))) continue;
                    if (player == minecraft.player && minecraft.options.getCameraType().isFirstPerson() && position.distanceToSqr(camera) < 1) continue;
                    poses.pushPose();
                    poses.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
                    float baseSize = count > 50 ? .20F : .30F;
                    float size = unit.hp() == 0 ? (float) (baseSize * Math.max(.1, 1 - hitAge / 6)) : baseSize;
                    float visibleSize = size * (float) Math.min(appear, Math.min(1, motion * 4));
                    poses.scale(visibleSize, visibleSize, visibleSize);
                    if (liveCombat && !healer && combatPose != null) {
                        Vec3 direction = combatPose.target().add(0, combatPose.height() * .55, 0).subtract(position);
                        poses.mulPose(Axis.YP.rotation((float) Math.atan2(direction.x, direction.z)));
                        poses.mulPose(Axis.XP.rotation((float) -Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z))));
                    } else poses.mulPose(Axis.YP.rotationDegrees(-yaw));
                    poses.translate(-.5, -.5, -.5);
                    renderModel(hive.type(), poses, buffers, index == 0 && player.distanceToSqr(camera) < 16,
                            count > 50 || player.distanceToSqr(camera) > 144);
                    poses.popPose();
                    rendered = true;
                    budget--;
                }
                if (liveCombat && combatPose != null) {
                    if (progress > .9) HiveCombatVisual.renderFormation(hive.type(), formation, combatPose.target().add(0, combatPose.height() * .55, 0), camera, buffers, poses.last().pose(), time);
                    HiveCombatVisual.renderTravel(hive.type(), owner, yaw, combatPose.target(), combatPose.height(), progress,
                            camera, buffers, poses.last().pose(), time);
                }
                HiveCombatVisual.renderShots(combat.shots(), camera, buffers, poses.last().pose(), time);
            }
        }
        if (rendered) buffers.endBatch();
        buffers.endBatch(HiveCombatVisual.renderType());
    }

    private static EquippedCache active(net.minecraft.world.entity.player.Player player, long tick, Object level) {
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
            cached = new EquippedCache(tick, level, List.copyOf(retained), present);
            ACTIVE.put(player, cached);
        }
        return cached;
    }

    private static CombatPose resolvePose(net.minecraft.world.entity.player.Player player, HiveType type, HiveCombatState state,
            boolean active, double time, Minecraft minecraft) {
        EnumMap<HiveType, CombatPose> poses = POSES.computeIfAbsent(player, ignored -> new EnumMap<>(HiveType.class));
        CombatPose old = poses.get(type);
        if (active) {
            Entity targetEntity = minecraft.level.getEntity(state.targetId());
            Vec3 target = targetEntity == null ? new Vec3(state.targetX(), state.targetY(), state.targetZ()) : targetEntity.position();
            double width = targetEntity == null ? .6 : targetEntity.getBbWidth();
            double height = targetEntity == null ? 1.8 : targetEntity.getBbHeight();
            CombatPose next = new CombatPose(target, width, height, state.changedAt(), -1);
            poses.put(type, next);
            return next;
        }
        if (old != null && old.disabledAt() < 0) {
            CombatPose fade = new CombatPose(old.target(), old.width(), old.height(), old.changedAt(), (long) time);
            poses.put(type, fade);
            return fade;
        }
        if (old != null && time - old.disabledAt() >= 10) poses.remove(type);
        return old;
    }

    private static Visibility visibility(net.minecraft.world.entity.player.Player player, HiveType type, boolean enabled, double time) {
        EnumMap<HiveType, Visibility> values = VISIBILITY.computeIfAbsent(player, ignored -> new EnumMap<>(HiveType.class));
        Visibility old = values.get(type);
        if (old == null) {
            Visibility initial = new Visibility(enabled, (long) time);
            values.put(type, initial);
            return initial;
        }
        if (old.enabled() != enabled) {
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
