package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.HiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Containment: the swarm holds its target fast. Every family roots it where it stands; RF and Twins
 * also smother its attacks (and RF eats every shot it fires), Mana lets it strike but turns each blow
 * back on it from a ward the drones keep topping up, and Twins swallow it in a black hole. Every family
 * lifts it just far enough for its construct to clear the ground, as far as the space above it allows
 * (Twins, whose tori ring the wide black hole, highest). Bosses and players are never pinned, only
 * hurt. A creature held by one player's swarm stays in that hold while it lasts; another player's
 * swarm joins in rather than taking it over.
 */
public final class HiveContainment {
    private static final int LIFT_TICKS = 30;
    /** Marks a held creature whose gravity we switched off, so a crash or reload can never leave it floating. */
    private static final String GRAVITY = RelicsAddon.MOD_ID + ":held_gravity";
    private static final Map<LivingEntity, Hold> HELD = new WeakHashMap<>();

    /**
     * A creature held by one owner's hive. {@code ward} is Mana's store of damage it can still turn back.
     * Twins lift from {@code anchor} (the ground under the target) by {@code lift}, starting from
     * {@code startLift} if it was already off the ground.
     */
    public static final class Hold {
        final UUID owner;
        final HiveType type;
        final Vec3 anchor;
        final double lift, startLift;
        final long since;
        long seen;
        double ward, wardMax;
        final List<Vec3> intercepted = new ArrayList<>();
        final List<Vec3> reflected = new ArrayList<>();

        Hold(UUID owner, HiveType type, LivingEntity target, long since) {
            this.owner = owner;
            this.type = type;
            this.since = since;
            this.seen = since;
            // Every construct stays centred on its creature, which is lifted just clear of the ground for it: the RF
            // cage and the Mana lotus clear it, the Twins rift lifts its creature four blocks at least.
            double wanted = dev.hurtify.relicsaddon.drone.HiveFormation.constructLift(type, target.getBbWidth(), target.getBbHeight());
            anchor = groundBelow(target, wanted);
            lift = headroom(target, anchor, wanted);
            startLift = Math.clamp(target.getY() - anchor.y, 0, lift);
        }

        public HiveType type() { return type; }
        public double ward() { return ward; }
        public double wardMax() { return wardMax; }
        public double lift() { return lift; }
        public Vec3 anchor() { return anchor; }
    }

    /** Keeps {@code target} held this tick by {@code owner}'s {@code type} hive of {@code drones} flying drones. */
    public static Hold hold(Player owner, LivingEntity target, HiveType type, int drones, long now) {
        Hold hold = HELD.get(target);
        boolean mine = hold != null && hold.owner.equals(owner.getUUID()) && hold.type == type;
        // Another swarm holds it and is still at it: join in rather than tear its hold down every tick.
        if (hold != null && !mine && now - hold.seen <= 1 && now >= hold.seen) return hold;
        if (!mine) {
            release(target);
            hold = new Hold(owner.getUUID(), type, target, now);
            HELD.put(target, hold);
        }
        hold.seen = now;
        hold.wardMax = Math.max(1, drones);
        if (!immune(target)) pin(target, hold, now);
        return hold;
    }

    public static Hold held(Entity entity) {
        return entity instanceof LivingEntity living ? HELD.get(living) : null;
    }

    /** Whether a hold should stop this creature: it is held and not a boss or player. */
    public static boolean pinned(Entity entity) {
        return held(entity) != null && !immune((LivingEntity) entity);
    }

    /** Charges Mana's ward by {@code amount}, up to its capacity; returns how much was added. */
    static double refill(Hold hold, double amount) {
        double before = hold.ward;
        hold.ward = Math.min(hold.wardMax, hold.ward + Math.max(0, amount));
        return hold.ward - before;
    }

    /** Positions of shots the hold ate and blows it turned back since the last call, for the swarm's visual events. */
    static List<Vec3> drainIntercepted(Hold hold) {
        List<Vec3> result = List.copyOf(hold.intercepted);
        hold.intercepted.clear();
        return result;
    }

    static List<Vec3> drainReflected(Hold hold) {
        List<Vec3> result = List.copyOf(hold.reflected);
        hold.reflected.clear();
        return result;
    }

    public static void release(LivingEntity target) {
        Hold hold = HELD.remove(target);
        if (hold == null) return;
        restoreGravity(target);
        // The construct comes apart into its drones.
        if (target.level() instanceof net.minecraft.server.level.ServerLevel level) {
            dev.hurtify.relicsaddon.sound.RelicSounds.constructRelease(level, target.getBoundingBox().getCenter(), hold.type);
        }
    }

    /** Releases everything held by one owner's hive of {@code type} (null: any type). */
    public static void releaseAll(UUID owner, HiveType type) {
        for (LivingEntity target : List.copyOf(HELD.keySet())) {
            Hold hold = HELD.get(target);
            if (hold != null && hold.owner.equals(owner) && (type == null || hold.type == type)) release(target);
        }
    }

    /** Bosses and players are never pinned, only hurt. */
    public static boolean immune(LivingEntity target) {
        return target instanceof Player || target.getType().is(Tags.EntityTypes.BOSSES);
    }

    /**
     * Where a target stands, or the ground under it if it is in the air (looking down a little further
     * than the {@code lift} it is to get), so a new lift never stacks on an old one.
     */
    private static Vec3 groundBelow(LivingEntity target, double lift) {
        Vec3 at = target.position();
        if (target.onGround()) return at;
        BlockHitResult ground = target.level().clip(new ClipContext(at, at.subtract(0, Math.max(4, lift) + 2, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, target));
        return ground.getType() == HitResult.Type.MISS ? at : ground.getLocation();
    }

    /**
     * How far, up to {@code most}, the target can rise from {@code anchor} without its box meeting a
     * block, in quarter-block steps from the bottom, so a lift never pushes it into a ceiling or
     * through one.
     */
    private static double headroom(LivingEntity target, Vec3 anchor, double most) {
        AABB box = target.getBoundingBox().move(anchor.subtract(target.position()));
        double free = 0;
        for (double up = .25; up <= most + 1e-6; up += .25) {
            if (!target.level().noCollision(target, box.move(0, up, 0))) return free;
            free = up;
        }
        // Clear all the way: rise the whole distance, not just to the last quarter step.
        return target.level().noCollision(target, box.move(0, most, 0)) ? most : free;
    }

    private static void pin(LivingEntity target, Hold hold, long now) {
        Vec3 at = hold.anchor;
        if (hold.lift > 0 || hold.startLift > 0) {
            double t = Math.min(1, (now - hold.since) / (double) LIFT_TICKS);
            at = at.add(0, hold.startLift + (hold.lift - hold.startLift) * t * t * (3 - 2 * t), 0);
            CompoundTag data = target.getPersistentData();
            if (!data.contains(GRAVITY)) data.putBoolean(GRAVITY, target.isNoGravity());
            target.setNoGravity(true);
        }
        target.setDeltaMovement(Vec3.ZERO);
        if (target.position().distanceToSqr(at) > 1e-4) target.teleportTo(at.x, at.y, at.z);
        target.fallDistance = 0;
        target.hurtMarked = true;
        if (target instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.setJumping(false);
            if (hold.type != HiveType.MANA) {
                mob.setTarget(null);
                mob.setAggressive(false);
                if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                if (mob instanceof Creeper creeper) creeper.setSwellDir(-1);
            }
        }
    }

    private static void restoreGravity(LivingEntity target) {
        CompoundTag data = target.getPersistentData();
        if (data.contains(GRAVITY)) {
            target.setNoGravity(data.getBoolean(GRAVITY));
            data.remove(GRAVITY);
        }
    }

    /** Releases holds their hive stopped refreshing (target lost, hive off, owner gone). */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || HELD.isEmpty()) return;
        long now = level.getGameTime();
        for (LivingEntity target : List.copyOf(HELD.keySet())) {
            Hold hold = HELD.get(target);
            if (hold == null || target.level() != level) continue;
            if (!target.isAlive() || now - hold.seen > 2 || now < hold.seen) release(target);
        }
    }

    /** A held creature's own blows: smothered (RF, Twins) or turned back on it while Mana's ward lasts. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity attacker = event.getSource().getEntity();
        if (attacker == null || attacker.level().isClientSide() || attacker == event.getEntity()) return;
        Hold hold = held(attacker);
        if (hold == null || immune((LivingEntity) attacker)) return;
        float amount = event.getAmount();
        if (hold.type != HiveType.MANA) {
            event.setCanceled(true);
            return;
        }
        if (hold.ward < amount) return;
        hold.ward -= amount;
        event.setCanceled(true);
        Player owner = attacker.level().getPlayerByUUID(hold.owner);
        LivingEntity self = (LivingEntity) attacker;
        self.hurt(attacker.level().damageSources().source(HiveCombatController.SWARM_REFLECT, owner), amount);
        hold.reflected.add(self.getBoundingBox().getCenter());
    }

    /** RF and Twins holds swallow every projectile the held creature fires. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof Projectile projectile) {
            Hold hold = held(projectile.getOwner());
            if (hold != null && hold.type != HiveType.MANA && !immune((LivingEntity) projectile.getOwner())) {
                hold.intercepted.add(projectile.position());
                event.setCanceled(true);
            }
            return;
        }
        // A creature saved while lifted gets its own gravity back on load.
        if (event.getEntity() instanceof LivingEntity living && !HELD.containsKey(living)) restoreGravity(living);
    }

    /**
     * A held creature cannot be scooped into a bucket or otherwise used until it is let go: a bucket
     * would keep a lifted fish's switched-off gravity but not the mark that restores it.
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide() && pinned(event.getTarget())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    /** Endermen and shulkers cannot blink out of a hold. */
    public static void onTeleport(EntityTeleportEvent.EnderEntity event) {
        if (pinned(event.getEntity())) event.setCanceled(true);
    }

    private HiveContainment() {
    }
}
