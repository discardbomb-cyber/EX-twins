package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The aegis shields standing this tick, and what they stop: shots flying in through the shell, blows from outside on
 * the crew inside, and explosions outside tearing at what is inside. A shield is put up again by its hive every tick
 * (block entities tick after entities, so a shot meets the shield its hive put up a tick before, carried on by the
 * ship's motion since). Server thread only.
 */
public final class AegisFields {
    /** A shield put up by its hive at {@code at}. */
    record Field(ShipHiveBlockEntity hive, AegisModule module, ShipFrame frame, AegisShape shape, ShipBrain brain, long at) {
        /** A world point in the shield's unit terms, allowing for how far the ship has gone since the shield was put up. */
        Vec3 unit(Vec3 world, long now) {
            return shape.unitOfWorld(frame, world.subtract(frame.velocity().scale(Math.max(0, now - at))));
        }

        /** The shield's middle in the world, now. */
        Vec3 middle(long now) {
            return frame.toWorld(shape.centre()).add(frame.velocity().scale(Math.max(0, now - at)));
        }
    }

    /** The shields of one level: kept by hive, and handed out as a list worked out once a tick. */
    private static final class Standing {
        final Map<ShipHiveBlockEntity, Field> fields = new IdentityHashMap<>();
        List<Field> list = List.of();
        long prunedAt = Long.MIN_VALUE / 4;
        boolean changed;
    }

    private static final Map<ResourceKey<Level>, Standing> FIELDS = new HashMap<>();
    /** Create Big Cannons' shells by class: 2 a big cannon's, 1 an autocannon's, 0 not a cannon's at all. */
    private static final Map<Class<?>, Integer> CANNON = new ConcurrentHashMap<>();
    private static final String BIG_SHELL = "rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile",
            AUTOCANNON_ROUND = "rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile";
    /** What stopping a big cannon's shell and an autocannon's round costs a shield. */
    static final float BIG_SHELL_COST = 40, ROUND_COST = 6;

    static void put(ServerLevel level, ShipHiveBlockEntity hive, AegisModule module, ShipFrame frame, AegisShape shape, ShipBrain brain, long now) {
        Standing standing = FIELDS.computeIfAbsent(level.dimension(), ignored -> new Standing());
        standing.fields.put(hive, new Field(hive, module, frame, shape, brain, now));
        standing.changed = true;
    }

    /** The shields standing in {@code level} now; those whose hives stopped putting them up are dropped, once a tick. */
    static List<Field> live(ServerLevel level, long now) {
        Standing standing = FIELDS.get(level.dimension());
        if (standing == null || standing.fields.isEmpty()) return List.of();
        if (standing.prunedAt != now) {
            standing.prunedAt = now;
            standing.changed |= standing.fields.values().removeIf(field -> field.hive().isRemoved() || now - field.at() > 2 || field.at() > now
                    || !field.module().raised());
        }
        if (standing.changed) {
            standing.changed = false;
            standing.list = List.copyOf(standing.fields.values());
        }
        return standing.list;
    }

    // --- shots -------------------------------------------------------------------------------------

    /** A crossing of a shot through a shield's shell: which shield, how far along the shot's tick, and where on the shell. */
    private record Crossing(Field field, double t, Vec3 unit) {
    }

    /**
     * A shot about to fly in through a shield this tick is stopped at the shell, if the shield can pay for it; one that
     * breaks a shield flies on to the next shield it meets. The crew's own shots fly out, and so do the shots of players
     * who may not fight (with PvP off, they could not hurt anyone inside anyway).
     */
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Projectile projectile) || !(projectile.level() instanceof ServerLevel level)) return;
        if (!FIELDS.containsKey(level.dimension()) || !interceptable(projectile)) return;
        long now = level.getGameTime();
        List<Field> fields = live(level, now);
        if (fields.isEmpty()) return;
        Entity owner = projectile.getOwner();
        if (owner instanceof net.minecraft.world.entity.player.Player && !level.getServer().isPvpAllowed()) return;
        Vec3 from = projectile.position(), motion = projectile.getDeltaMovement();
        List<Crossing> crossings = null;
        for (Field field : fields) {
            // The shot's way through the shield, seen from the ship: its own motion less the ship's.
            Vec3 relative = motion.subtract(field.frame().velocity());
            double reach = field.shape().reach() + Math.max(motion.length(), relative.length()) + 1;
            if (field.middle(now).distanceToSqr(from) > reach * reach) continue;
            if (owner != null && ShipAllies.friendly(field.brain(), owner, now)) continue;
            Vec3 a = field.unit(from, now), b = field.unit(from.add(relative), now);
            double t = AegisShape.entry(a, b);
            if (t < 0) continue;
            if (crossings == null) crossings = new ArrayList<>(2);
            crossings.add(new Crossing(field, t, a.lerp(b, t)));
        }
        if (crossings == null) return;
        crossings.sort(java.util.Comparator.comparingDouble(Crossing::t));
        boolean big = cannon(projectile.getClass()) == 2;
        for (Crossing crossing : crossings) {
            if (!crossing.field().module().absorb(crossing.field().hive(), crossing.unit(), cost(projectile), now)) continue;
            Vec3 at = from.add(motion.scale(crossing.t()));
            flash(level, at, big ? 2 : projectile instanceof net.minecraft.world.entity.projectile.AbstractArrow ? 0 : 1);
            if (owner instanceof LivingEntity shooter) crossing.field().brain().grudge(shooter, now);
            projectile.discard();
            event.setCanceled(true);
            return;
        }
    }

    /** Shots a shield stops: whatever a personal shield stops, and Create Big Cannons' shells and rounds. */
    static boolean interceptable(Projectile projectile) {
        return !projectile.isRemoved() && (cannon(projectile.getClass()) > 0 || ShieldProjectileInterceptor.supported(projectile));
    }

    static float cost(Projectile projectile) {
        return switch (cannon(projectile.getClass())) {
            case 2 -> BIG_SHELL_COST;
            case 1 -> ROUND_COST;
            default -> ShieldProjectileInterceptor.impactCost(projectile);
        };
    }

    static int cannon(Class<?> type) {
        return CANNON.computeIfAbsent(type, start -> {
            for (Class<?> at = start; at != null; at = at.getSuperclass()) {
                if (at.getName().equals(BIG_SHELL)) return 2;
                if (at.getName().equals(AUTOCANNON_ROUND)) return 1;
            }
            return 0;
        });
    }

    // --- blows -------------------------------------------------------------------------------------

    /** A blow on one of the crew inside a shield that comes from outside it is taken by the shield instead. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || event.getAmount() <= 0) return;
        DamageSource source = event.getSource();
        Vec3 origin = source.getSourcePosition();
        if (origin == null || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        long now = level.getGameTime();
        List<Field> fields = live(level, now);
        if (fields.isEmpty()) return;
        Entity attacker = source.getEntity();
        for (Field field : fields) {
            if (attacker != null && ShipAllies.friendly(field.brain(), attacker, now) || !ShipAllies.friendly(field.brain(), victim, now)) continue;
            if (field.unit(victim.getBoundingBox().getCenter(), now).lengthSqr() >= 1) continue;
            Vec3 from = field.unit(origin, now);
            if (from.lengthSqr() <= 1) continue;
            if (!field.module().absorb(field.hive(), from, event.getAmount() * 2, now)) continue;
            flash(level, field.shape().worldOnShell(field.frame(), from.normalize()), 0);
            if (attacker instanceof LivingEntity culprit) field.brain().grudge(culprit, now);
            event.setCanceled(true);
            return;
        }
    }

    // --- explosions --------------------------------------------------------------------------------

    /** An explosion outside a shield spares the blocks and the crew inside it, if the shield can pay for it. */
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        List<Field> fields = live(level, now);
        if (fields.isEmpty()) return;
        Vec3 centre = event.getExplosion().center();
        for (Field field : fields) {
            Vec3 from = field.unit(centre, now);
            if (from.lengthSqr() <= 1) continue;
            List<BlockPos> blocks = new ArrayList<>();
            for (BlockPos pos : event.getAffectedBlocks()) if (inside(field, pos, now)) blocks.add(pos);
            List<Entity> crew = new ArrayList<>();
            for (Entity entity : event.getAffectedEntities()) {
                if (ShipAllies.friendly(field.brain(), entity, now) && field.unit(entity.getBoundingBox().getCenter(), now).lengthSqr() < 1) crew.add(entity);
            }
            if (blocks.isEmpty() && crew.isEmpty()) continue;
            if (!field.module().absorb(field.hive(), from, event.getExplosion().radius() * 12, now)) continue;
            event.getAffectedBlocks().removeAll(blocks);
            event.getAffectedEntities().removeAll(crew);
            if (event.getExplosion().getIndirectSourceEntity() instanceof LivingEntity culprit) field.brain().grudge(culprit, now);
        }
    }

    /** Whether a block the explosion reaches is inside the shield: a world block, or one of the ship's own in its plot. */
    private static boolean inside(Field field, BlockPos pos, long now) {
        Vec3 middle = Vec3.atCenterOf(pos);
        if (field.unit(middle, now).lengthSqr() < 1) return true;
        return field.frame().aboard() && field.shape().unitOfPlot(middle).lengthSqr() < 1;
    }

    /** A burst of light where the shield stopped something: 0 a small one, 1 a fireball's, 2 a cannon shell's. */
    private static void flash(ServerLevel level, Vec3 at, int size) {
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 6 + size * 10, .2 + size * .3, .2 + size * .3, .2 + size * .3, .05 + size * .05);
        if (size >= 2) level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        dev.hurtify.relicsaddon.sound.RelicSounds.ship(level, at, dev.hurtify.relicsaddon.sound.RelicSounds.Ship.AEGIS_BLOCK, 1 + size * .5F, size >= 2 ? .75F : 1);
        if (size >= 2) level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.5F, 1.3F);
    }

    @Nullable
    static Field of(ServerLevel level, ShipHiveBlockEntity hive) {
        Standing standing = FIELDS.get(level.dimension());
        return standing == null ? null : standing.fields.get(hive);
    }

    /** Once a second, the shields whose hives stopped putting them up are let go of. */
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        FIELDS.entrySet().removeIf(entry -> {
            ServerLevel level = event.getServer().getLevel(entry.getKey());
            if (level == null) return true;
            long now = level.getGameTime();
            Standing standing = entry.getValue();
            standing.changed |= standing.fields.values().removeIf(field -> field.hive().isRemoved() || now - field.at() > 40 || field.at() > now);
            return standing.fields.isEmpty();
        });
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        FIELDS.clear();
    }

    private AegisFields() {
    }
}
