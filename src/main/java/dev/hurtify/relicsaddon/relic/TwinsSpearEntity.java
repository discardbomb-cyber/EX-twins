package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.network.NoctisPayloads;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.NoctisCombat;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/**
 * The thrown reflection of the Twins spear. It flies, and where it strikes it opens its event horizon: the creature
 * it struck stays pinned where it was struck ({@link #ANCHOR} ticks; a boss is only slowed hard), every other foe
 * within {@code spear.horizonRadius} is dragged in to it and held, and while the wielder wears a working Twins
 * hive, {@link #BLADES} of its drones circle the pinned creature as the Halo Execution, each striking in turn.
 * Struck into a block it holds the horizon there a while. Thrown steeply up it is the Black Halo instead: it rises,
 * opens an eclipse and rains light spears on every foe below for {@link #RAIN} ticks. Then it flies back to its
 * wielder and is gone.
 */
public class TwinsSpearEntity extends Projectile {
    public static final int FLYING = 0, ANCHORED = 1, STUCK = 2, RETURNING = 3, HALO = 4;
    /** How long a creature stays pinned, a block holds the spear, and it may fly before it turns home, in ticks. */
    public static final int ANCHOR = 50, HOLD = 40, FLIGHT = 40;
    /** The halo: how long it rises, how long it rains, how high it hangs, how often a light spear falls and what it does. */
    public static final int RISE = 12, RAIN = 60, HALO_EVERY = 8, HALO_COST = 40;
    public static final double HALO_HEIGHT = 10;
    public static final float HALO_DAMAGE = 4;
    /** The blades that leave the hive to circle a pinned creature, and the blow each lands every {@link #BLADE_EVERY} ticks. */
    public static final int BLADES = 7, BLADE_EVERY = 8;
    public static final float THROW_DAMAGE = 8, BLADE_DAMAGE = 2.5F;
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TARGET = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SWARM = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.INT);
    /** Where the spear sits on the pinned creature, from its feet, so both sides place it alike. */
    private static final EntityDataAccessor<org.joml.Vector3f> OFFSET = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.VECTOR3);

    /** Ticks in the present state, counted on both sides. */
    private int stateTicks;
    /** Where the creature was pinned; and where the halo was thrown from. */
    private Vec3 pinnedAt, thrownFrom;

    public TwinsSpearEntity(EntityType<? extends TwinsSpearEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, FLYING);
        builder.define(TARGET, -1);
        builder.define(SWARM, 0);
        builder.define(OFFSET, new org.joml.Vector3f());
    }

    public int state() { return entityData.get(STATE); }
    public int stateTicks() { return stateTicks; }
    /** The blades circling the pinned creature (none unless pinned with a working Twins hive). */
    public int swarm() { return entityData.get(SWARM); }
    public Entity target() { return level().getEntity(entityData.get(TARGET)); }
    /** How far the horizon reaches from the pinned spear. */
    public static double horizon() { return AddonConfig.SPEC.isLoaded() ? AddonConfig.SPEAR_HORIZON_RADIUS.get() : 12; }
    public static double haloRadius() { return AddonConfig.SPEC.isLoaded() ? AddonConfig.SPEAR_HALO_RADIUS.get() : 10; }

    private void state(int state) {
        entityData.set(STATE, state);
        stateTicks = 0;
    }

    /** Thrown as the Black Halo: it rises from where it was thrown instead of flying on. */
    void halo() {
        thrownFrom = position();
        state(HALO);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key)) stateTicks = 0;
    }

    /** The reflection {@code player} has out, if any. */
    public static TwinsSpearEntity of(Player player) {
        for (TwinsSpearEntity spear : player.level().getEntitiesOfClass(TwinsSpearEntity.class, player.getBoundingBox().inflate(128))) {
            if (spear.getOwner() == player && spear.isAlive()) return spear;
        }
        return null;
    }

    @Override
    public void tick() {
        super.tick();
        stateTicks++;
        switch (state()) {
            case FLYING -> fly();
            case ANCHORED -> hold();
            case STUCK -> {
                setDeltaMovement(Vec3.ZERO);
                drag();
                if (stateTicks > HOLD && !level().isClientSide) state(RETURNING);
            }
            case HALO -> rain();
            default -> home();
        }
    }

    private void fly() {
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() != HitResult.Type.MISS && !level().isClientSide) {
            onHit(hit);
            if (state() != FLYING) return;
        }
        Vec3 motion = getDeltaMovement();
        setPos(position().add(motion));
        setDeltaMovement(motion.scale(.99).add(0, -.03, 0));
        updateRotation();
        if (stateTicks > FLIGHT && !level().isClientSide) state(RETURNING);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        Entity owner = getOwner();
        if (target == owner || !(level() instanceof ServerLevel level)) return;
        target.hurt(damageSources().trident(this, owner == null ? this : owner), THROW_DAMAGE);
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            state(RETURNING);
            return;
        }
        // Where its flight meets the creature's box (the hit's own location is only the creature's feet), or its middle.
        Vec3 heading = getDeltaMovement().lengthSqr() < 1e-6 ? Vec3.ZERO : getDeltaMovement().normalize();
        Vec3 struck = target.getBoundingBox().inflate(.1).clip(position().subtract(heading.scale(2)), position().add(getDeltaMovement()).add(heading))
                .orElse(target.getBoundingBox().getCenter());
        Vec3 offset = struck.add(heading.scale(.25)).subtract(target.position());
        entityData.set(OFFSET, offset.toVector3f());
        pinnedAt = target.position();
        setDeltaMovement(Vec3.ZERO);
        entityData.set(TARGET, target.getId());
        entityData.set(SWARM, owner instanceof Player player && NoctisCore.hive(player) != null ? BLADES : 0);
        state(ANCHORED);
        setPos(target.position().add(offset));
        RelicSounds.spear(level, position(), RelicSounds.Spear.ANCHOR);
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 heading = getDeltaMovement().lengthSqr() < 1e-6 ? Vec3.ZERO : getDeltaMovement().normalize();
        setPos(result.getLocation().subtract(heading.scale(.1)));
        setDeltaMovement(Vec3.ZERO);
        state(STUCK);
        RelicSounds.spear(level, position(), RelicSounds.Spear.STICK);
    }

    private void hold() {
        Entity target = target();
        if (target == null || !target.isAlive() || stateTicks > ANCHOR) {
            if (!level().isClientSide) state(RETURNING);
            return;
        }
        setPos(target.position().add(new Vec3(entityData.get(OFFSET))));
        if (!(level() instanceof ServerLevel level) || !(target instanceof LivingEntity living)) return;
        if (pinnedAt == null) pinnedAt = target.position();
        boolean boss = target.getType().is(Tags.EntityTypes.BOSSES);
        if (boss) {
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 3, false, false));
        } else {
            // Held where it was struck: nothing it does moves it, though it may still fall.
            if (target.position().distanceToSqr(pinnedAt) > 1e-4) target.teleportTo(pinnedAt.x, Math.min(pinnedAt.y, target.getY()), pinnedAt.z);
            target.setDeltaMovement(0, Math.min(0, target.getDeltaMovement().y), 0);
            target.hurtMarked = true;
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 9, false, false));
        }
        drag();
        if (swarm() > 0 && stateTicks > 8 && stateTicks % BLADE_EVERY == 2 && getOwner() instanceof Player player) {
            var hive = NoctisCore.hive(player);
            if (hive == null || !DevicePower.drain(player, hive, DevicePower.SHOT)) {
                entityData.set(SWARM, 0);
                return;
            }
            living.invulnerableTime = 0;
            living.hurt(damageSources().thrown(this, player), BLADE_DAMAGE);
            RelicSounds.spear(level, target.position().add(0, target.getBbHeight() / 2, 0), RelicSounds.Spear.STRIKE);
        }
    }

    /** The event horizon: every other foe within reach is dragged in to the spear and held there. */
    private void drag() {
        if (!(level() instanceof ServerLevel level) || !(getOwner() instanceof ServerPlayer owner)) return;
        double reach = horizon();
        Entity pinned = target();
        Vec3 centre = position();
        for (LivingEntity foe : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(reach),
                foe -> foe != pinned && HiveCombatController.validTarget(owner, foe, true))) {
            Vec3 to = centre.subtract(foe.getBoundingBox().getCenter());
            double distance = to.length();
            if (distance > reach) continue;
            // Pulled in hard, and once near, held: the nearer, the less of its own motion is left to it.
            double pull = distance < 1.5 ? 0 : Math.min(.9, .25 + distance * .06);
            Vec3 motion = foe.getDeltaMovement().scale(distance < 1.5 ? .1 : .4).add(to.normalize().scale(pull));
            foe.setDeltaMovement(motion.x, distance < 1.5 ? Math.min(0, motion.y) : motion.y, motion.z);
            foe.hurtMarked = true;
            foe.fallDistance = 0;
            foe.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 6, false, false));
        }
    }

    /** The Black Halo: rises, then rains light spears on every foe below. */
    private void rain() {
        if (thrownFrom == null) thrownFrom = position();
        if (stateTicks <= RISE) {
            double k = stateTicks / (double) RISE, lift = HALO_HEIGHT * (1 - (1 - k) * (1 - k));
            setPos(thrownFrom.x, thrownFrom.y + lift, thrownFrom.z);
            setDeltaMovement(Vec3.ZERO);
            setXRot(-90);
            return;
        }
        if (!(level() instanceof ServerLevel level) || !(getOwner() instanceof ServerPlayer owner)) return;
        if (stateTicks == RISE + 1) RelicSounds.spear(level, position(), RelicSounds.Spear.HALO);
        if (stateTicks > RISE + RAIN) {
            state(RETURNING);
            return;
        }
        if ((stateTicks - RISE) % HALO_EVERY != 0) return;
        double reach = haloRadius();
        Vec3 ground = thrownFrom;
        List<LivingEntity> foes = level.getEntitiesOfClass(LivingEntity.class, new AABB(ground, ground).inflate(reach, HALO_HEIGHT + 4, reach),
                foe -> HiveCombatController.validTarget(owner, foe, true) && foe.position().subtract(ground).horizontalDistance() <= reach);
        foes.sort(java.util.Comparator.comparingDouble(foe -> foe.distanceToSqr(ground)));
        int fallen = 0;
        for (LivingEntity foe : foes) {
            if (fallen++ >= 6) break;
            foe.invulnerableTime = 0;
            foe.hurt(level.damageSources().source(NoctisCombat.LIGHT, owner), HALO_DAMAGE);
            NoctisPayloads.tell(level, NoctisPayloads.Kind.HALO_SPEAR, position(), foe.getBoundingBox().getCenter(), 1);
        }
    }

    private void home() {
        Entity owner = getOwner();
        if (owner == null || !owner.isAlive() || owner.level() != level() || stateTicks > 80) {
            if (!level().isClientSide) discard();
            return;
        }
        Vec3 to = owner.getEyePosition().subtract(0, .4, 0).subtract(position());
        if (to.length() < 1.2) {
            if (level() instanceof ServerLevel level) {
                RelicSounds.spear(level, owner.position(), RelicSounds.Spear.RETURN);
                if (owner instanceof Player player) player.getCooldowns().addCooldown(ModItems.TWINS_SPEAR.get(), TwinsSpearItem.COOLDOWN);
                discard();
            }
            return;
        }
        setDeltaMovement(to.normalize().scale(Math.min(1.8, .3 + stateTicks * .08)));
        setPos(position().add(getDeltaMovement()));
        updateRotation();
    }

    /**
     * Pulls the wielder to where the spear is pinned (in a creature or a block) and sends it home; false if it is
     * not pinned.
     */
    public boolean pull() {
        if ((state() != ANCHORED && state() != STUCK) || !(getOwner() instanceof Player player)) return false;
        if (level() instanceof ServerLevel level) {
            Vec3 to = position().subtract(player.position());
            player.setDeltaMovement(to.normalize().scale(Math.min(2.2, .5 + to.length() * .12)).add(0, .35, 0));
            player.hurtMarked = true;
            player.fallDistance = 0;
            RelicSounds.spear(level, player.position(), RelicSounds.Spear.PULL);
            state(RETURNING);
        }
        return true;
    }

    /** An enderman pinned by a spear cannot teleport away, nor one its horizon holds. */
    public static void onEnderTeleport(EntityTeleportEvent.EnderEntity event) {
        LivingEntity entity = event.getEntityLiving();
        for (TwinsSpearEntity spear : entity.level().getEntitiesOfClass(TwinsSpearEntity.class, entity.getBoundingBox().inflate(horizon()))) {
            if ((spear.state() == ANCHORED || spear.state() == STUCK) && spear.distanceTo(entity) <= horizon()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @Override
    protected double getDefaultGravity() {
        return 0;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // A reflection loaded from a save has nothing left to hold: it goes home.
        entityData.set(STATE, RETURNING);
    }
}
