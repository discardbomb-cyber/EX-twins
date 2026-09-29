package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.relic.HiveUpgrades;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server authority for virtual combat drones. The map contains only short-lived mana bolts;
 * drones themselves remain item state and therefore never create pathfinding entities.
 */
public final class HiveCombatController {
    private static final int FORMATION_TICKS = 20;
    private static final int SHOT_VISUAL_TICKS = 20;
    private static final double BOLT_SPEED = 1.15;
    private static final int MAX_SHOTS_PER_TICK = 24;
    /** Drone hits: credited to the owner, but never knock the target around (hundreds of hits would juggle it). */
    public static final ResourceKey<DamageType> DRONE_SHOT = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(dev.hurtify.relicsaddon.RelicsAddon.MOD_ID, "drone_shot"));
    private static final Map<UUID, EnumMap<HiveType, List<Flight>>> FLIGHTS = new HashMap<>();

    /** Called once per server player tick by {@link HiveController}. */
    public static void tick(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.isAlive() || serverPlayer.isSpectator()) {
            clear(player);
            return;
        }
        ServerLevel level = serverPlayer.serverLevel();
        long now = level.getGameTime();
        var equipped = HiveController.active(serverPlayer);
        for (var hive : equipped) HiveController.prepare(serverPlayer, hive.stack(), false);
        HiveTaskController.tickHealing(serverPlayer, equipped, now);
        var liveTypes = new boolean[HiveType.values().length];
        for (HiveController.Equipped hive : equipped) {
            liveTypes[hive.type().ordinal()] = true;
            tickHive(serverPlayer, level, hive, now);
        }
        clearMissing(serverPlayer.getUUID(), liveTypes);
    }

    private static void tickHive(ServerPlayer owner, ServerLevel level, HiveController.Equipped hive, long now) {
        ItemStack stack = hive.stack();
        HiveStackState swarm = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        HiveCombatState before = stack.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
        LivingEntity target = selectTarget(owner, level, before.targetId());
        var settings = HiveTaskController.settings(stack);
        int fighters = settings.fighters(swarm.units().size());
        if (fighters == 0) target = null;
        if (target == null) {
            finish(owner, level, hive.type(), stack, before, now);
            FLIGHTS.getOrDefault(owner.getUUID(), new EnumMap<>(HiveType.class)).remove(hive.type());
            return;
        }

        Vec3 targetFeet = target.position();
        HiveCombatState state = before;
        if (!before.active() || before.targetId() != target.getId()) {
            state = HiveCombatState.target(target.getId(), targetFeet.x, targetFeet.y, targetFeet.z, now);
            RelicSounds.summon(level, owner.position(), hive.type(), true);
        } else if (now % 10 == 0 && (before.targetX() != targetFeet.x || before.targetY() != targetFeet.y || before.targetZ() != targetFeet.z)) {
            state = new HiveCombatState(true, target.getId(), before.changedAt(), targetFeet.x, targetFeet.y, targetFeet.z, before.shots());
        }

        List<HiveCombatState.Shot> shots = new ArrayList<>(recentShots(state.shots(), now));
        tickFlights(owner, level, hive.type(), stack, shots, now);
        double progress = Mth.clamp((now - state.changedAt()) / (double) FORMATION_TICKS, 0, 1);
        if (progress >= 1 && !swarm.units().isEmpty()) {
            var nextUnits = new ArrayList<>(swarm.units());
            boolean changed = false;
            int intervalMax = attackIntervalMax(owner, stack);
            float damage = attackDamage(owner, stack, hive.type());
            // A 500-drone swarm fires in bounded volleys; ready drones beyond the budget wait a tick.
            int volley = MAX_SHOTS_PER_TICK;
            int size = nextUnits.size(), first = (int) Math.floorMod(now * 7, (long) size);
            for (int step = 0; step < size && volley > 0; step++) {
                int index = (first + step) % size;
                if (settings.healer(index, nextUnits.size())) continue;
                HiveStackState.Unit unit = nextUnits.get(index);
                if (!unit.attackReady(now)) continue;
                if (unit.attackReadyAt() == 0) {
                    nextUnits.set(index, new HiveStackState.Unit(unit.hp(), unit.readyAt(), unit.lastHit(), unit.x(), unit.y(), unit.z(),
                            now + attackIntervalFor(owner.getUUID(), hive.type(), index, now, intervalMax)));
                    changed = true;
                    continue;
                }
                if (!dev.hurtify.relicsaddon.power.DevicePower.drain(owner, stack, dev.hurtify.relicsaddon.power.DevicePower.SHOT)) break;
                Vec3 origin = HiveFormation.position(owner.position(), owner.getYRot(), targetFeet, target.getBbWidth(),
                        target.getBbHeight(), index, fighters, hive.type(), now, progress);
                int kind = shotKind(hive.type(), owner.getUUID(), index, now);
                long readyAt = now + attackIntervalFor(owner.getUUID(), hive.type(), index, now, intervalMax);
                volley--;
                nextUnits.set(index, new HiveStackState.Unit(unit.hp(), unit.readyAt(), unit.lastHit(), unit.x(), unit.y(), unit.z(), readyAt));
                changed = true;
                if (kind == 0 || kind == 2) {
                    if (lineOfSight(level, origin, target.getBoundingBox().getCenter()) && validTarget(owner, target, true)) {
                        if (damage(owner, target, damage, hive.type(), kind, target.getBoundingBox().getCenter()))
                            RelicRuntime.awardAbsorption(owner, stack, damage);
                        Vec3 end = target.getBoundingBox().getCenter();
                        shots.add(new HiveCombatState.Shot(index, now, kind, origin.x, origin.y, origin.z, end.x, end.y, end.z, now));
                    }
                } else {
                    Flight flight = Flight.create(owner, hive.type(), index, kind, damage, origin, target, now);
                    flights(owner.getUUID(), hive.type()).add(flight);
                    shots.add(flight.shot());
                }
                RelicSounds.attack(level, origin, hive.type(), kind);
            }
            if (changed) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(swarm.enabled(), nextUnits));
        }
        HiveCombatState next = state.withShots(recentShots(shots, now));
        if (!next.equals(before)) stack.set(ModDataComponents.HIVE_COMBAT_STATE.get(), next);
    }

    private static void finish(ServerPlayer owner, ServerLevel level, HiveType type, ItemStack stack,
                               HiveCombatState current, long now) {
        List<HiveCombatState.Shot> shots = recentShots(current.shots(), now);
        if (current.active()) RelicSounds.summon(level, owner.position(), type, false);
        if (current.active() || !shots.equals(current.shots())) {
            stack.set(ModDataComponents.HIVE_COMBAT_STATE.get(), new HiveCombatState(false, -1, now, 0, 0, 0, shots));
        }
    }

    /** Keep a live hostile target until defeated; visibility is only needed to acquire a new one. */
    public static LivingEntity selectTarget(ServerPlayer owner, ServerLevel level, int previousTargetId) {
        Entity previous = previousTargetId < 0 ? null : level.getEntity(previousTargetId);
        if (previous instanceof LivingEntity living && validTarget(owner, living, true)) return living;
        LivingEntity attacked = owner.getLastHurtMob();
        if (owner.tickCount - owner.getLastHurtMobTimestamp() < 100 && validTarget(owner, attacked, false)) return attacked;
        LivingEntity aggressor = owner.getLastHurtByMob();
        if (owner.tickCount - owner.getLastHurtByMobTimestamp() < 100 && validTarget(owner, aggressor, false)) return aggressor;
        return null;
    }

    /** Public for tests and for later team/claim integrations. */
    public static boolean validTarget(ServerPlayer owner, LivingEntity candidate, boolean pursuing) {
        if (candidate == null || !candidate.isAlive() || candidate.isSpectator() || candidate.level() != owner.level()
                || candidate == owner || candidate.isAlliedTo(owner) || owner.isAlliedTo(candidate)) return false;
        if (candidate instanceof Player other && (!owner.server.isPvpAllowed() || !owner.canHarmPlayer(other) || other.isCreative())) return false;
        if (candidate instanceof TamableAnimal pet && pet.getOwner() != null
                && (pet.getOwner() == owner || pet.getOwner().isAlliedTo(owner))) return false;
        double range = pursuing ? AddonConfig.HIVE_PURSUIT_RANGE.get() : AddonConfig.HIVE_TARGET_RANGE.get();
        return owner.distanceToSqr(candidate) <= range * range && (pursuing || owner.hasLineOfSight(candidate));
    }

    private static void tickFlights(ServerPlayer owner, ServerLevel level, HiveType type, ItemStack stack,
            List<HiveCombatState.Shot> shots, long now) {
        List<Flight> flights = flights(owner.getUUID(), type);
        if (flights.isEmpty()) return;
        var iterator = flights.iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            if (flight.level != level || !owner.isAlive()) { iterator.remove(); continue; }
            if (HiveTaskController.settings(stack).healer(flight.unit, HiveController.capacity(owner, stack))) {
                iterator.remove();
                shots.removeIf(shot -> shot.unit() == flight.unit && shot.firedAt() == flight.firedAt);
                continue;
            }
            Vec3 from = flight.position;
            Vec3 to = from.add(flight.velocity);
            Hit hit = firstHit(owner, level, from, to);
            if (hit.blocked()) {
                RelicSounds.impact(level, hit.position(), type, flight.kind);
                finishShot(shots, flight, hit.position(), now);
                iterator.remove();
                continue;
            }
            if (hit.entity() != null) {
                if (damage(owner, hit.entity(), flight.damage, type, flight.kind, hit.position()))
                    RelicRuntime.awardAbsorption(owner, stack, flight.damage);
                finishShot(shots, flight, hit.position(), now);
                iterator.remove();
                continue;
            }
            flight.position = to;
            if (now >= flight.expiresAt) {
                RelicSounds.impact(level, to, type, flight.kind);
                iterator.remove();
                continue;
            }
        }
    }

    private static void finishShot(List<HiveCombatState.Shot> shots, Flight flight, Vec3 point, long now) {
        shots.removeIf(shot -> shot.unit() == flight.unit && shot.firedAt() == flight.firedAt);
        shots.add(new HiveCombatState.Shot(flight.unit, flight.firedAt, flight.kind, flight.start.x, flight.start.y, flight.start.z,
                point.x, point.y, point.z, now));
    }

    private static Hit firstHit(ServerPlayer owner, ServerLevel level, Vec3 from, Vec3 to) {
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        double blockDistance = block.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? Double.POSITIVE_INFINITY
                : from.distanceToSqr(block.getLocation());
        LivingEntity closest = null;
        Vec3 closestPoint = null;
        double closestDistance = blockDistance;
        AABB bounds = new AABB(from, to).inflate(.35);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, bounds,
                living -> validTarget(owner, living, true) && living.canBeHitByProjectile())) {
            var point = candidate.getBoundingBox().inflate(.15).clip(from, to);
            if (point.isEmpty()) continue;
            double distance = from.distanceToSqr(point.get());
            if (distance < closestDistance) {
                closest = candidate;
                closestPoint = point.get();
                closestDistance = distance;
            }
        }
        if (closest != null) return new Hit(closest, closestPoint, false);
        return blockDistance < Double.POSITIVE_INFINITY ? new Hit(null, block.getLocation(), true) : new Hit(null, to, false);
    }

    public static boolean lineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty())).getType()
                == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    public static boolean damage(ServerPlayer owner, LivingEntity target, float damage, HiveType type, int kind, Vec3 point) {
        if (!validTarget(owner, target, true) || !target.hurt(owner.level().damageSources().source(DRONE_SHOT, owner), damage)) return false;
        RelicSounds.impact(owner.serverLevel(), point, type, kind);
        return true;
    }

    private static int attackIntervalMax(ServerPlayer player, ItemStack stack) {
        return (int) Math.round(RelicRuntime.stat(player, stack, "attack_interval_max", 100, 20, 100));
    }

    private static float attackDamage(ServerPlayer player, ItemStack stack, HiveType type) {
        return (float) (RelicRuntime.stat(player, stack, "attack_damage", type.initialAttackDamage, 1, 100)
                * HiveUpgrades.damageMultiplier(player, stack));
    }

    public static int attackIntervalFor(UUID owner, HiveType type, int unit, long now, int max) {
        long seed = owner.getLeastSignificantBits() ^ owner.getMostSignificantBits() ^ ((long) type.ordinal() << 56)
                ^ ((long) unit << 32) ^ now * 0x9E3779B97F4A7C15L;
        int span = Math.max(1, max - 20 + 1);
        return 20 + (int) Math.floorMod(seed ^ (seed >>> 33), span);
    }

    private static int shotKind(HiveType type, UUID owner, int unit, long now) {
        return switch (type) {
            case RF -> 0;
            case MANA -> 1;
            case TWINS -> ((owner.getLeastSignificantBits() + unit + now / 20) & 1) == 0 ? 2 : 3;
        };
    }

    private static List<HiveCombatState.Shot> recentShots(List<HiveCombatState.Shot> shots, long now) {
        List<HiveCombatState.Shot> recent = shots.stream().filter(shot -> now - shot.firedAt() <= SHOT_VISUAL_TICKS).toList();
        int start = Math.max(0, recent.size() - HiveCombatState.MAX_SHOTS);
        return List.copyOf(recent.subList(start, recent.size()));
    }

    private static List<Flight> flights(UUID owner, HiveType type) {
        return FLIGHTS.computeIfAbsent(owner, ignored -> new EnumMap<>(HiveType.class))
                .computeIfAbsent(type, ignored -> new ArrayList<>());
    }

    private static void clearMissing(UUID owner, boolean[] liveTypes) {
        EnumMap<HiveType, List<Flight>> byType = FLIGHTS.get(owner);
        if (byType == null) return;
        for (HiveType type : HiveType.values()) if (!liveTypes[type.ordinal()]) byType.remove(type);
        if (byType.isEmpty()) FLIGHTS.remove(owner);
    }

    public static void clear(Player player) {
        if (player != null) FLIGHTS.remove(player.getUUID());
    }

    /** These are intentionally separate from the ordinary tick cleanup: a disconnected player no longer ticks. */
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity());
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        clear(event.getEntity());
    }

    private record Hit(LivingEntity entity, Vec3 position, boolean blocked) { }

    private static final class Flight {
        private final ServerLevel level;
        private final HiveType type;
        private final int unit, kind;
        private final float damage;
        private final long firedAt, expiresAt;
        private final Vec3 start, end, velocity;
        private Vec3 position;

        private Flight(ServerLevel level, HiveType type, int unit, int kind, float damage, long firedAt, long expiresAt,
                       Vec3 start, Vec3 end, Vec3 velocity) {
            this.level = level; this.type = type; this.unit = unit; this.kind = kind; this.damage = damage;
            this.firedAt = firedAt; this.expiresAt = expiresAt; this.start = start; this.end = end;
            this.velocity = velocity; this.position = start;
        }

        static Flight create(ServerPlayer owner, HiveType type, int unit, int kind, float damage,
                             Vec3 start, LivingEntity target, long now) {
            Vec3 end = target.getBoundingBox().getCenter();
            Vec3 velocity = end.subtract(start).normalize().scale(BOLT_SPEED);
            long impactAt = now + Math.max(1, Mth.ceil(start.distanceTo(end) / BOLT_SPEED));
            return new Flight(owner.serverLevel(), type, unit, kind, damage, now, impactAt, start, end, velocity);
        }

        HiveCombatState.Shot shot() {
            return new HiveCombatState.Shot(unit, firedAt, kind, start.x, start.y, start.z,
                    end.x, end.y, end.z, expiresAt);
        }
    }

    private HiveCombatController() { }
}
