package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveTarget;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.HiveUpgrades;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Server authority for the virtual swarm. Drones are item state, never entities; this class decides
 * when each strike group's figure launches from its owner and lands its blow (droplet), when each
 * clump fires its charge (barrage) or how the construct holds its target (containment), and when
 * drones are hit and swapped for replacements. Positions come from {@link HiveFormation}, the same
 * maths the renderer uses.
 */
public final class HiveCombatController {
    /** Containment zaps: credited to the owner, never knocking the target about. */
    public static final ResourceKey<DamageType> DRONE_SHOT = key("drone_shot");
    /** A group's single blow and a charge's blast; this mod throws the target itself along the blow. */
    public static final ResourceKey<DamageType> SWARM_STRIKE = key("swarm_strike");
    /** Twins black hole: crushes through armour. */
    public static final ResourceKey<DamageType> SWARM_VOID = key("swarm_void");
    /** Mana ward: a held creature's own blow turned back on it. */
    public static final ResourceKey<DamageType> SWARM_REFLECT = key("swarm_reflect");
    /** Every swarm type. The tag is part of {@code shield_passes}, so no field absorbs a swarm's blow. */
    public static final TagKey<DamageType> SWARM_DAMAGE = TagKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "swarm_damage"));
    private static final int SHOT_VISUAL_TICKS = 40;
    private static final double BALL_SPEED = .9;
    /** Battery points per drone taking part in a blow or a charge. */
    private static final int STRIKE_COST_PER_DRONE = 1;
    /** Ticks a hit drone needs per point of damage before it is whole again (a destroyed one is rebuilt instead). */
    private static final int REPAIR_TICKS_PER_HP = 40;
    private static final Map<UUID, EnumMap<HiveType, List<Flight>>> FLIGHTS = new HashMap<>();
    /** Creatures that attacked each owner (or the owner's field) lately: entity id to the tick of the attack. */
    private static final Map<UUID, Map<Integer, Long>> ATTACKERS = new HashMap<>();
    /** How long an attack keeps its attacker a target, and how far round the owner creatures targeting them are sought. */
    private static final int ATTACK_MEMORY = 100, ATTACKER_SCAN = 32;

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
        for (HiveType type : HiveType.values()) if (!liveTypes[type.ordinal()]) HiveContainment.releaseAll(serverPlayer.getUUID(), type);
        // A shot whose hive was switched off or taken off is called off.
        if (!liveTypes[HiveType.TWINS.ordinal()] && !liveTypes[HiveType.MANA.ordinal()]) ArmageddonController.abort(serverPlayer);
        clearMissing(serverPlayer.getUUID(), liveTypes);
    }

    private static void tickHive(ServerPlayer owner, ServerLevel level, HiveController.Equipped hive, long now) {
        ItemStack stack = hive.stack();
        HiveType type = hive.type();
        if (type != HiveType.RF && ArmageddonController.tick(owner, stack, now)) {
            // The whole swarm is in the Armageddon: it lets go of what it held and fights nothing else.
            HiveContainment.releaseAll(owner.getUUID(), type);
            EnumMap<HiveType, List<Flight>> flights = FLIGHTS.get(owner.getUUID());
            if (flights != null) flights.remove(type);
            return;
        }
        HiveStackState swarm = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        HiveCombatState before = stack.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
        HiveSettings settings = HiveTaskController.settings(stack);
        int units = swarm.units().size(), slots = HiveSlots.fighterSlots(units, settings), fighters = settings.fighters(units);
        int groups = HiveSlots.groups(Math.max(1, slots), settings.mode());
        List<LivingEntity> foes = slots == 0 ? List.of()
                : selectTargets(owner, level, before, Math.min(groups, HiveCombatState.MAX_TARGETS), now);
        if (foes.isEmpty()) {
            finish(owner, level, type, stack, before, now);
            FLIGHTS.getOrDefault(owner.getUUID(), new EnumMap<>(HiveType.class)).remove(type);
            HiveContainment.releaseAll(owner.getUUID(), type);
            return;
        }

        // Where every engaged creature is this tick; the first one leads (its direction turns the droplet fan).
        List<HiveTarget> frames = new ArrayList<>(foes.size());
        for (LivingEntity foe : foes) frames.add(new HiveTarget(foe.getId(), foe.position(), foe.getBbWidth(), foe.getBbHeight()));
        HiveCombatState state = before;
        if (!before.active() || before.mode() != settings.mode()) {
            HiveContainment.releaseAll(owner.getUUID(), type);
            state = HiveCombatState.engage(frames, now, settings.mode(), HiveFormation.travelTicks(owner.distanceTo(foes.getFirst())));
            RelicSounds.summon(level, owner.position(), type, true);
        } else if (!before.sameTargets(frames)) {
            // Creatures that dropped out are let go; the drones fly straight on to the new set, not home first.
            for (HiveTarget old : before.targets()) {
                if (frames.stream().noneMatch(frame -> frame.id() == old.id()) && level.getEntity(old.id()) instanceof LivingEntity gone) {
                    HiveContainment.release(gone);
                }
            }
            state = before.retarget(frames, now);
        } else if (now % 5 == 0 && moved(before.targets(), frames)) {
            state = before.withTargets(frames);
        }

        List<HiveCombatState.Shot> shots = new ArrayList<>(recentShots(state.shots(), now));
        tickFlights(owner, level, type, stack, shots, now);
        Vec3 home = owner.position();
        long cycleStart = state.changedAt() + state.travel();
        int interval = strikeInterval(owner, stack);
        int engaged = HiveFormation.engaged(frames.size(), groups);
        float perDrone = attackDamage(owner, stack, type);

        int[] occupant = new int[slots];
        int[] members = new int[groups];
        for (int slot = 0; slot < slots; slot++) {
            occupant[slot] = HiveSlots.occupant(swarm.units(), slot, slots, fighters, now);
            if (occupant[slot] >= 0) members[HiveSlots.group(slot, groups)]++;
        }

        switch (state.mode()) {
            case DROPLET -> {
                Vec3 fan = frames.getFirst().feet();
                for (int group = 0; group < groups; group++) {
                    if (members[group] == 0) continue;
                    LivingEntity target = foes.get(group % engaged);
                    HiveTarget frame = frames.get(group % engaged);
                    Vec3 core = HiveFormation.core(frame.feet(), frame.height());
                    Vec3 muster = HiveFormation.muster(home, fan, group, groups, now);
                    // The figure leaves the owner's fan so that it arrives exactly when its blow is due.
                    long flight = Math.round(HiveFormation.flightTicks(muster.distanceTo(core), interval));
                    if (HiveFormation.passes(now + flight, cycleStart, interval, group, groups, HiveFormation.IMPACT)) {
                        RelicSounds.chargeFire(level, muster, type);
                    }
                    if (!HiveFormation.passes(now, cycleStart, interval, group, groups, HiveFormation.IMPACT)) continue;
                    if (!reeling(target)) {
                        if (!DevicePower.drain(owner, stack, members[group] * STRIKE_COST_PER_DRONE)) break;
                        float damage = members[group] * perDrone * efficiency();
                        if (swarmHit(owner, target, damage, core.subtract(muster), knockback(members[group]), SWARM_STRIKE)) {
                            RelicRuntime.awardCombatExperience(owner, stack, damage);
                        }
                    }
                    shots.add(new HiveCombatState.Shot(group, now, HiveCombatState.DROPLET, muster.x, muster.y, muster.z, core.x, core.y, core.z, now));
                    RelicSounds.swarmStrike(level, core, type);
                }
            }
            case BARRAGE -> {
                for (int group = 0; group < groups; group++) {
                    if (members[group] == 0 || !HiveFormation.passes(now, cycleStart, interval, group, groups, HiveFormation.FIRE)) continue;
                    int cost = members[group] * STRIKE_COST_PER_DRONE;
                    if (!DevicePower.canAfford(owner, stack, cost)) break;
                    int index = group % engaged;
                    HiveTarget frame = frames.get(index);
                    Vec3 from = HiveFormation.clusterCentre(type, frame.feet(), frame.width(), frame.height(), group / engaged,
                            HiveSlots.localGroups(index, engaged, groups), now);
                    Flight flight = Flight.ball(owner, type, group, members[group], cost, members[group] * perDrone * efficiency(), from, foes.get(index), now);
                    flights(owner.getUUID(), type).add(flight);
                    shots.add(flight.shot());
                    RelicSounds.chargeFire(level, from, type);
                }
            }
            case CONTAINMENT -> {
                if (now >= state.changedAt() + state.travel() / 2) {
                    for (int index = 0; index < engaged; index++) {
                        contain(owner, level, stack, type, state, foes.get(index), frames, index, engaged, members, slots, groups,
                                perDrone, cycleStart, interval, shots, now);
                    }
                }
            }
        }

        // Each engaged creature swings at drones that come within its reach; a hit drone heads home and its lane sends the next.
        List<HiveStackState.Unit> next = swarm.units();
        if (now % 20 == 7) {
            Vec3[] at = new Vec3[slots];
            for (int index = 0; index < engaged; index++) {
                if (!(foes.get(index) instanceof Mob mob) || HiveContainment.pinned(mob) || !mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) continue;
                double attack = mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
                AABB reach = mob.getBoundingBox().inflate(1.5);
                int closest = -1;
                double best = Double.MAX_VALUE;
                Vec3 struck = null;
                for (int slot = 0; slot < slots; slot++) {
                    if (occupant[slot] < 0) continue;
                    if (at[slot] == null) at[slot] = dronePosition(owner, state, type, swarm.units(), slot, slots, fighters, occupant[slot],
                            frames, now, cycleStart, interval);
                    double distance = at[slot].distanceToSqr(mob.getBoundingBox().getCenter());
                    if (reach.contains(at[slot]) && distance < best) {
                        best = distance;
                        closest = occupant[slot];
                        struck = at[slot];
                    }
                }
                if (attack > 0 && closest >= 0) {
                    next = hitDrone(owner, stack, type, next, closest, attack >= 6 ? 2 : 1, now);
                    shots.add(new HiveCombatState.Shot(closest, now, HiveCombatState.DRONE_HIT, struck.x, struck.y, struck.z, struck.x, struck.y, struck.z, now));
                }
            }
        }
        if (next != swarm.units()) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(swarm.enabled(), next));
        HiveCombatState updated = state.withShots(recentShots(shots, now));
        if (!updated.equals(before)) stack.set(ModDataComponents.HIVE_COMBAT_STATE.get(), updated);
    }

    /** Containment of one engaged creature by the strike groups assigned to it. */
    private static void contain(ServerPlayer owner, ServerLevel level, ItemStack stack, HiveType type, HiveCombatState state, LivingEntity target,
            List<HiveTarget> frames, int index, int engaged, int[] members, int slots, int groups, float perDrone, long cycleStart, int interval,
            List<HiveCombatState.Shot> shots, long now) {
        int flying = 0;
        for (int group = index; group < groups; group += engaged) flying += members[group];
        if (flying == 0) return;
        HiveTarget frame = frames.get(index);
        HiveContainment.Hold hold = HiveContainment.hold(owner, target, type, flying, now);
        if (now % 20 == 0) {
            if (!DevicePower.drain(owner, stack, Math.max(1, flying / 5))) {
                HiveContainment.release(target);
            } else {
                Vec3 core = HiveFormation.core(target.position(), frame.height());
                float damage = flying * perDrone * efficiency() * .12F;
                switch (type) {
                    case RF -> {
                        // The bolt comes from a place of this creature's own torus.
                        int localGroups = HiveSlots.localGroups(index, engaged, groups);
                        int localSlots = HiveSlots.localSlots(index, engaged, slots, groups);
                        int slot = HiveSlots.globalSlot(index, engaged, (int) (now / 20 % Math.max(1, localSlots)), localGroups, groups);
                        Vec3 from = HiveFormation.station(AttackMode.CONTAINMENT, type, Math.min(slot, slots - 1), slots, owner.position(), frames,
                                now, cycleStart, interval);
                        if (swarmHit(owner, target, damage, Vec3.ZERO, 0, DRONE_SHOT)) RelicRuntime.awardCombatExperience(owner, stack, damage);
                        shots.add(new HiveCombatState.Shot(0, now, HiveCombatState.ZAP, from.x, from.y, from.z, core.x, core.y, core.z, now));
                        RelicSounds.containment(level, core, type);
                    }
                    case TWINS -> {
                        if (swarmHit(owner, target, damage, Vec3.ZERO, 0, SWARM_VOID)) RelicRuntime.awardCombatExperience(owner, stack, damage);
                        shots.add(new HiveCombatState.Shot(0, now, HiveCombatState.VOID, core.x, core.y, core.z, core.x, core.y, core.z, now));
                        RelicSounds.containment(level, core, type);
                    }
                    case MANA -> {
                        if (HiveContainment.immune(target)) {
                            // A boss or player cannot be held, so it never strikes into the ward: the drones strike through it instead.
                            Vec3 centre = target.getBoundingBox().getCenter();
                            if (swarmHit(owner, target, damage, Vec3.ZERO, 0, DRONE_SHOT)) RelicRuntime.awardCombatExperience(owner, stack, damage);
                            shots.add(new HiveCombatState.Shot(0, now, HiveCombatState.WARD, centre.x, centre.y, centre.z, centre.x, centre.y, centre.z, now));
                            RelicSounds.reflect(level, centre);
                        } else {
                            // The drones keep the ward topped up; turned-back blows are its only damage.
                            HiveContainment.refill(hold, flying * .5);
                            if (now % 60 == 0) RelicSounds.containment(level, core, type);
                        }
                    }
                }
            }
        }
        for (Vec3 point : HiveContainment.drainIntercepted(hold)) {
            shots.add(new HiveCombatState.Shot(0, now, HiveCombatState.INTERCEPT, point.x, point.y, point.z, point.x, point.y, point.z, now));
        }
        for (Vec3 point : HiveContainment.drainReflected(hold)) {
            shots.add(new HiveCombatState.Shot(0, now, HiveCombatState.WARD, point.x, point.y, point.z, point.x, point.y, point.z, now));
            RelicSounds.reflect(level, point);
        }
    }

    /** Whether any engaged creature has moved since its position was last recorded. */
    private static boolean moved(List<HiveTarget> recorded, List<HiveTarget> now) {
        for (int index = 0; index < now.size(); index++) {
            HiveTarget a = recorded.get(index), b = now.get(index);
            if (a.x() != b.x() || a.y() != b.y() || a.z() != b.z() || a.width() != b.width() || a.height() != b.height()) return true;
        }
        return false;
    }

    /**
     * Where the drone flying place {@code slot} is right now, exactly as the renderer draws it: still on
     * its way out from the hive (after the combat began or it replaced a hit drone), crossing over to a
     * new target, or on its station.
     */
    private static Vec3 dronePosition(Player owner, HiveCombatState state, HiveType type, List<HiveStackState.Unit> units, int slot, int slots,
            int fighters, int unit, List<HiveTarget> targets, long now, long cycleStart, int interval) {
        Vec3 home = owner.position();
        Vec3 station = HiveFormation.engagedStation(state.mode(), type, slot, slots, home, targets, state.previous(), state.retargetedAt(),
                now, cycleStart, interval);
        double launched = Math.max(HiveSlots.since(units, slot, slots, fighters, unit, now), state.changedAt());
        return HiveFormation.deployed(home, owner.getYRot(), station, unit, units.size(), type, now, launched, state.travel());
    }

    /**
     * Whether a blow now would do nothing: the target still reels from the last one (vanilla hurt
     * immunity). Such a blow glances off without spending charge.
     */
    private static boolean reeling(LivingEntity target) {
        return target.invulnerableTime > 10;
    }

    /** One drone takes {@code damage}: it flies home, is repaired (or rebuilt if destroyed), and its lane covers for it. */
    private static List<HiveStackState.Unit> hitDrone(Player owner, ItemStack stack, HiveType type, List<HiveStackState.Unit> units,
            int unit, int damage, long now) {
        var copy = new ArrayList<>(units);
        HiveStackState.Unit before = copy.get(unit);
        int left = before.hp() - Math.max(1, damage);
        double pace = HiveUpgrades.rebuildMultiplier(owner, stack);
        long away = left <= 0 ? Math.round(RelicRuntime.stat(owner, stack, "cooldown", type.initialCooldown, 10, 400) * pace)
                : Math.round(REPAIR_TICKS_PER_HP * (HiveType.DRONE_HP - left) * pace);
        copy.set(unit, before.hit(damage, now, now + HiveFormation.RETURN_TICKS + away));
        return copy;
    }

    /** Drones inside a blast are damaged by it, three HP at the centre down to one at the edge. */
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Vec3 centre = event.getExplosion().center();
        double reach = event.getExplosion().radius() * 2;
        Entity cause = event.getExplosion().getIndirectSourceEntity();
        long now = level.getGameTime();
        for (ServerPlayer owner : level.players()) {
            if (owner == cause || owner.distanceToSqr(centre) > 200 * 200) continue;
            for (HiveController.Equipped hive : HiveController.active(owner)) {
                ItemStack stack = hive.stack();
                HiveCombatState combat = stack.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
                if (!combat.active()) continue;
                HiveStackState swarm = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
                HiveSettings settings = HiveTaskController.settings(stack);
                int units = swarm.units().size(), slots = HiveSlots.fighterSlots(units, settings), fighters = settings.fighters(units);
                List<HiveTarget> targets = new ArrayList<>(combat.targets().size());
                for (HiveTarget recorded : combat.targets()) {
                    Entity entity = level.getEntity(recorded.id());
                    targets.add(entity != null ? new HiveTarget(recorded.id(), entity.position(), entity.getBbWidth(), entity.getBbHeight()) : recorded);
                }
                if (targets.isEmpty()) continue;
                long cycleStart = combat.changedAt() + combat.travel();
                int interval = strikeInterval(owner, stack);
                List<HiveStackState.Unit> next = swarm.units();
                for (int slot = 0; slot < slots; slot++) {
                    int unit = HiveSlots.occupant(next, slot, slots, fighters, now);
                    if (unit < 0) continue;
                    Vec3 at = dronePosition(owner, combat, hive.type(), next, slot, slots, fighters, unit, targets, now, cycleStart, interval);
                    double distance = at.distanceTo(centre);
                    if (distance > reach) continue;
                    next = hitDrone(owner, stack, hive.type(), next, unit, (int) Math.ceil(3 * (1 - distance / reach)), now);
                }
                if (next != swarm.units()) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(swarm.enabled(), next));
            }
        }
    }

    /**
     * Hurts {@code target} for {@code damage} of {@code type} credited to the owner and, if {@code knockback}
     * is positive, throws it along {@code push} (horizontal).
     */
    private static boolean swarmHit(ServerPlayer owner, LivingEntity target, float damage, Vec3 push, double knockback, ResourceKey<DamageType> type) {
        if (!validTarget(owner, target, true) || !(damage > 0)) return false;
        boolean hurt = target.hurt(owner.level().damageSources().source(type, owner), damage);
        Vec3 flat = new Vec3(push.x, 0, push.z);
        if (hurt && knockback > 0 && target.isAlive() && flat.lengthSqr() > 1e-6) {
            flat = flat.normalize();
            target.knockback(knockback, -flat.x, -flat.z);
            target.hurtMarked = true;
        }
        return hurt;
    }

    private static double knockback(int members) {
        return Math.min(1.4, .35 + .03 * members);
    }

    private static float efficiency() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.HIVE_STRIKE_EFFICIENCY.get().floatValue() : .5F;
    }

    private static void finish(ServerPlayer owner, ServerLevel level, HiveType type, ItemStack stack, HiveCombatState current, long now) {
        List<HiveCombatState.Shot> shots = recentShots(current.shots(), now);
        if (current.active()) RelicSounds.summon(level, owner.position(), type, false);
        if (current.active() || !shots.equals(current.shots())) {
            // The recall starts when combat ends; tidying expired events later keeps that moment.
            stack.set(ModDataComponents.HIVE_COMBAT_STATE.get(), new HiveCombatState(false, -1, current.active() ? now : current.changedAt(),
                    current.targetX(), current.targetY(), current.targetZ(), current.mode(), current.travel(), shots));
        }
    }

    /**
     * The creatures the swarm engages, at most {@code limit}: those it already fights, while they stay
     * valid, then whoever the owner is fighting, then whoever attacks the owner (a blow on them or on
     * their field, or a creature that has them as its target), nearest first. Visibility is only needed
     * to take on a new one.
     */
    public static List<LivingEntity> selectTargets(ServerPlayer owner, ServerLevel level, HiveCombatState before, int limit, long now) {
        List<LivingEntity> chosen = new ArrayList<>();
        if (before.active()) for (HiveTarget kept : before.targets()) {
            if (chosen.size() >= limit) return chosen;
            if (level.getEntity(kept.id()) instanceof LivingEntity living && !chosen.contains(living) && validTarget(owner, living, true)) {
                chosen.add(living);
            }
        }
        LivingEntity attacked = owner.getLastHurtMob();
        boolean fighting = owner.tickCount - owner.getLastHurtMobTimestamp() < ATTACK_MEMORY && validTarget(owner, attacked, false);
        List<LivingEntity> attackers = new ArrayList<>();
        LivingEntity aggressor = owner.getLastHurtByMob();
        if (owner.tickCount - owner.getLastHurtByMobTimestamp() < ATTACK_MEMORY && validTarget(owner, aggressor, false)) attackers.add(aggressor);
        for (LivingEntity attacker : recentAttackers(owner, level, now)) if (validTarget(owner, attacker, false)) attackers.add(attacker);
        if (now % 5 == 0 || chosen.isEmpty()) {
            double reach = Math.min(AddonConfig.HIVE_TARGET_RANGE.get(), ATTACKER_SCAN);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(reach), mob -> mob.getTarget() == owner)) {
                if (validTarget(owner, mob, false)) attackers.add(mob);
            }
        }
        attackers.sort(Comparator.comparingDouble(owner::distanceToSqr));
        if (fighting && !chosen.contains(attacked) && chosen.size() < limit) chosen.add(attacked);
        for (LivingEntity attacker : attackers) {
            if (chosen.size() >= limit) break;
            if (!chosen.contains(attacker)) chosen.add(attacker);
        }
        return chosen;
    }

    /** Remembers that {@code attacker} went for {@code owner} or their field, so the swarm takes it on. */
    public static void noteAttacker(Player owner, LivingEntity attacker) {
        if (owner.level().isClientSide() || attacker == null || attacker == owner) return;
        Map<Integer, Long> seen = ATTACKERS.computeIfAbsent(owner.getUUID(), ignored -> new LinkedHashMap<>());
        seen.put(attacker.getId(), owner.level().getGameTime());
        if (seen.size() > 64) seen.remove(seen.keySet().iterator().next());
    }

    /** A creature that hurts a player is remembered as that player's attacker. */
    public static void onOwnerDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer owner && event.getSource().getEntity() instanceof LivingEntity attacker) {
            noteAttacker(owner, attacker);
        }
    }

    private static List<LivingEntity> recentAttackers(ServerPlayer owner, ServerLevel level, long now) {
        Map<Integer, Long> seen = ATTACKERS.get(owner.getUUID());
        if (seen == null) return List.of();
        seen.values().removeIf(at -> now - at > ATTACK_MEMORY || at > now);
        if (seen.isEmpty()) {
            ATTACKERS.remove(owner.getUUID());
            return List.of();
        }
        List<LivingEntity> result = new ArrayList<>(seen.size());
        for (int id : seen.keySet()) if (level.getEntity(id) instanceof LivingEntity living) result.add(living);
        return result;
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
            // Charges home in on the target so a fleeing mob cannot sidestep a slow ball of lightning.
            if (flight.target != null && flight.target.isAlive()) {
                Vec3 aim = flight.target.getBoundingBox().getCenter().subtract(flight.position);
                if (aim.lengthSqr() > 1e-6) flight.velocity = aim.normalize().scale(BALL_SPEED);
            }
            Vec3 from = flight.position, to = from.add(flight.velocity);
            Hit hit = firstHit(owner, level, from, to, flight.target);
            if (hit.entity() != null || now >= flight.expiresAt) {
                Vec3 point = hit.entity() != null ? hit.position() : to;
                // The charge is paid for when it lands a blow; one that glances off a reeling target costs nothing.
                boolean paid = hit.entity() != null && !reeling(hit.entity()) && DevicePower.drain(owner, stack, flight.cost);
                if (paid && swarmHit(owner, hit.entity(), flight.damage, flight.velocity, knockback(flight.members), SWARM_STRIKE)) {
                    RelicRuntime.awardCombatExperience(owner, stack, flight.damage);
                }
                // The blast also catches other hostiles close by, at a share of the damage.
                if (paid) for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, new AABB(point, point).inflate(2),
                        living -> living != hit.entity() && struckBy(owner, living, flight.target) && living.distanceToSqr(point) < 4)) {
                    swarmHit(owner, near, flight.damage * .4F, near.position().subtract(point), knockback(flight.members) * .5, SWARM_STRIKE);
                }
                RelicSounds.swarmExplosion(level, point, type);
                finishShot(shots, flight, point, now);
                iterator.remove();
                continue;
            }
            flight.position = to;
        }
    }

    private static void finishShot(List<HiveCombatState.Shot> shots, Flight flight, Vec3 point, long now) {
        shots.removeIf(shot -> shot.unit() == flight.group && shot.firedAt() == flight.firedAt && shot.kind() == HiveCombatState.BALL);
        shots.add(new HiveCombatState.Shot(flight.group, flight.firedAt, HiveCombatState.BALL, flight.start.x, flight.start.y, flight.start.z,
                point.x, point.y, point.z, Math.max(flight.firedAt, now)));
    }

    /**
     * The first creature a charge meets between {@code from} and {@code to}. Ball lightning passes
     * through blocks: clumps hang all round the target, often inside a cave's walls or ceiling, and
     * their charges must still reach it.
     */
    private static Hit firstHit(ServerPlayer owner, ServerLevel level, Vec3 from, Vec3 to, LivingEntity target) {
        LivingEntity closest = null;
        Vec3 closestPoint = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        AABB bounds = new AABB(from, to).inflate(.5);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, bounds,
                living -> struckBy(owner, living, target) && living.canBeHitByProjectile())) {
            var point = candidate.getBoundingBox().inflate(.3).clip(from, to);
            if (point.isEmpty()) continue;
            double distance = from.distanceToSqr(point.get());
            if (distance < closestDistance) {
                closest = candidate;
                closestPoint = point.get();
                closestDistance = distance;
            }
        }
        return closest != null ? new Hit(closest, closestPoint) : new Hit(null, to);
    }

    /** A charge hurts its own target and creatures hostile to the owner, never bystanders such as villagers, neutral mobs or pets. */
    private static boolean struckBy(ServerPlayer owner, LivingEntity living, LivingEntity target) {
        if (!validTarget(owner, living, true)) return false;
        return living == target || living instanceof Mob mob && ShieldStrike.aggressive(owner, mob);
    }

    /** Ticks between one group's blows or charges: 5 seconds at level 0 down to 2 at level 10. */
    public static int strikeInterval(Player player, ItemStack stack) {
        return (int) Math.round(RelicRuntime.stat(player, stack, "attack_interval_max", 100, 20, 100));
    }

    /** What one drone adds to its group's blow. */
    public static float attackDamage(Player player, ItemStack stack, HiveType type) {
        return (float) (RelicRuntime.stat(player, stack, "attack_damage", type.initialAttackDamage, 1, 100)
                * HiveUpgrades.damageMultiplier(player, stack));
    }

    private static List<HiveCombatState.Shot> recentShots(List<HiveCombatState.Shot> shots, long now) {
        List<HiveCombatState.Shot> recent = shots.stream().filter(shot -> now - shot.impactAt() <= SHOT_VISUAL_TICKS).toList();
        int start = Math.max(0, recent.size() - HiveCombatState.MAX_SHOTS);
        return List.copyOf(recent.subList(start, recent.size()));
    }

    private static List<Flight> flights(UUID owner, HiveType type) {
        return FLIGHTS.computeIfAbsent(owner, ignored -> new EnumMap<>(HiveType.class)).computeIfAbsent(type, ignored -> new ArrayList<>());
    }

    private static void clearMissing(UUID owner, boolean[] liveTypes) {
        EnumMap<HiveType, List<Flight>> byType = FLIGHTS.get(owner);
        if (byType == null) return;
        for (HiveType type : HiveType.values()) if (!liveTypes[type.ordinal()]) byType.remove(type);
        if (byType.isEmpty()) FLIGHTS.remove(owner);
    }

    public static void clear(Player player) {
        if (player == null) return;
        ArmageddonController.abort(player);
        FLIGHTS.remove(player.getUUID());
        ATTACKERS.remove(player.getUUID());
        HiveContainment.releaseAll(player.getUUID(), null);
    }

    /** These are intentionally separate from the ordinary tick cleanup: a disconnected player no longer ticks. */
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity());
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        clear(event.getEntity());
    }

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, path));
    }

    private record Hit(LivingEntity entity, Vec3 position) { }

    /** A charge (ball lightning) flying from its clump to the target. */
    private static final class Flight {
        private final ServerLevel level;
        private final int group, members, cost;
        private final float damage;
        private final long firedAt, expiresAt;
        private final Vec3 start;
        private final LivingEntity target;
        private Vec3 position, velocity;

        private Flight(ServerLevel level, int group, int members, int cost, float damage, long firedAt, long expiresAt, Vec3 start, LivingEntity target,
                Vec3 velocity) {
            this.level = level;
            this.group = group;
            this.members = members;
            this.cost = cost;
            this.damage = damage;
            this.firedAt = firedAt;
            this.expiresAt = expiresAt;
            this.start = start;
            this.target = target;
            this.velocity = velocity;
            this.position = start;
        }

        static Flight ball(ServerPlayer owner, HiveType type, int group, int members, int cost, float damage, Vec3 start, LivingEntity target, long now) {
            Vec3 end = target.getBoundingBox().getCenter();
            Vec3 velocity = end.subtract(start).normalize().scale(BALL_SPEED);
            long reach = now + Math.max(1, Mth.ceil(start.distanceTo(end) / BALL_SPEED)) + 40;
            return new Flight(owner.serverLevel(), group, members, cost, damage, now, reach, start, target, velocity);
        }

        /** The in-flight event; its end is the target's centre at launch, its impact time an estimate the landing replaces. */
        HiveCombatState.Shot shot() {
            Vec3 end = target.getBoundingBox().getCenter();
            long arrival = firedAt + Math.max(1, Mth.ceil(start.distanceTo(end) / BALL_SPEED));
            return new HiveCombatState.Shot(group, firedAt, HiveCombatState.BALL, start.x, start.y, start.z, end.x, end.y, end.z, arrival);
        }
    }

    private HiveCombatController() { }
}
