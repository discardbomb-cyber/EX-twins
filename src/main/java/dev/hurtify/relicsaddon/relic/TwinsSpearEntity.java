package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/**
 * The thrown reflection of the Twins spear. It flies, and what it strikes it pins: the creature stays where it
 * was struck ({@link #ANCHOR} ticks; a boss is only slowed hard), and while the wielder wears a working Twins hive,
 * {@link #DRONES} of its drones circle the pinned creature and strike it. Struck into a block it stays there a
 * while. Then it flies back to its wielder and is gone.
 */
public class TwinsSpearEntity extends Projectile {
    public static final int FLYING = 0, ANCHORED = 1, STUCK = 2, RETURNING = 3;
    /** How long a creature stays pinned, a block holds the spear, and it may fly before it turns home, in ticks. */
    public static final int ANCHOR = 50, HOLD = 40, FLIGHT = 40;
    /** The drones that leave the hive to circle a pinned creature, and the blow each lands every {@link #DRONE_EVERY} ticks. */
    public static final int DRONES = 6, DRONE_EVERY = 8;
    public static final float THROW_DAMAGE = 8, DRONE_DAMAGE = 2.5F;
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TARGET = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SWARM = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.INT);
    /** Where the spear sits on the pinned creature, from its feet, so both sides place it alike. */
    private static final EntityDataAccessor<org.joml.Vector3f> OFFSET = SynchedEntityData.defineId(TwinsSpearEntity.class, EntityDataSerializers.VECTOR3);

    /** Ticks in the present state, counted on both sides. */
    private int stateTicks;
    /** Where the creature was pinned. */
    private Vec3 pinnedAt;

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
    /** The drones circling the pinned creature (none unless pinned with a working Twins hive). */
    public int swarm() { return entityData.get(SWARM); }
    public Entity target() { return level().getEntity(entityData.get(TARGET)); }

    private void state(int state) {
        entityData.set(STATE, state);
        stateTicks = 0;
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
                if (stateTicks > HOLD && !level().isClientSide) state(RETURNING);
            }
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
        // Sunk into the creature a little past where it struck, along its flight.
        Vec3 heading = getDeltaMovement().lengthSqr() < 1e-6 ? Vec3.ZERO : getDeltaMovement().normalize();
        // Where its flight meets the creature's box (the hit's own location is only the creature's feet), or its middle.
        Vec3 struck = target.getBoundingBox().inflate(.1).clip(position().subtract(heading.scale(2)), position().add(getDeltaMovement()).add(heading))
                .orElse(target.getBoundingBox().getCenter());
        Vec3 offset = struck.add(heading.scale(.25)).subtract(target.position());
        entityData.set(OFFSET, offset.toVector3f());
        pinnedAt = target.position();
        setDeltaMovement(Vec3.ZERO);
        entityData.set(TARGET, target.getId());
        entityData.set(SWARM, owner instanceof Player player && hive(player) != null ? DRONES : 0);
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
        if (swarm() > 0 && stateTicks > 8 && stateTicks % DRONE_EVERY == 2 && getOwner() instanceof Player player) {
            var hive = hive(player);
            if (hive == null || !DevicePower.drain(player, hive, DevicePower.SHOT)) {
                entityData.set(SWARM, 0);
                return;
            }
            living.invulnerableTime = 0;
            living.hurt(damageSources().thrown(this, player), DRONE_DAMAGE);
            RelicSounds.spear(level, target.position().add(0, target.getBbHeight() / 2, 0), RelicSounds.Spear.STRIKE);
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

    /** The worn, working Twins hive of {@code player}, or null. */
    private static net.minecraft.world.item.ItemStack hive(Player player) {
        for (var equipped : HiveController.active(player)) if (equipped.type() == HiveType.TWINS) return equipped.stack();
        return null;
    }

    /** An enderman pinned by a spear cannot teleport away. */
    public static void onEnderTeleport(EntityTeleportEvent.EnderEntity event) {
        LivingEntity entity = event.getEntityLiving();
        for (TwinsSpearEntity spear : entity.level().getEntitiesOfClass(TwinsSpearEntity.class, entity.getBoundingBox().inflate(4))) {
            if (spear.state() == ANCHORED && spear.target() == entity) {
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
