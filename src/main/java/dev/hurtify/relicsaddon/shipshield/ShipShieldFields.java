package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.server.ShieldCoverage;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The ship shields standing this tick, and what they stop: shots flying in through the shell,
 * blows from outside on whoever is inside, explosions outside tearing at what is inside, and
 * (through {@link ShipShield#tick}) hostile mobs under the shell. A generator puts its shield up
 * every tick with the frame its structure is in; a hit is carried into the structure's own
 * coordinates through that frame. Block changes within a shield ask its generator to look at its
 * structure again. Server thread only.
 */
public final class ShipShieldFields {
    /** A shield as its generator put it up at {@code at}. */
    public record Field(ShipDeviceBlockEntity generator, ShipShield shield, ShipFrame frame, long at) {
        /** A world point in the structure's coordinates, allowing for how far the ship has gone since. */
        public Vec3 local(Vec3 world, long now) {
            return frame.toPlot(world.subtract(frame.velocity().scale(Math.max(0, now - at))));
        }

        public Vec3 world(Vec3 local, long now) {
            return frame.toWorld(local).add(frame.velocity().scale(Math.max(0, now - at)));
        }

        /** The box round the shield in the world. */
        public AABB worldBounds() {
            if (!frame.aboard()) return shield.bounds();
            return frame.bounds().inflate(shield.offset() + shield.layerCount() + 1);
        }

        /** Whether a block position (world, or the ship's plot) lies under the shield. */
        public boolean covers(BlockPos pos, long now) {
            Vec3 middle = Vec3.atCenterOf(pos);
            if (frame.aboard() && shield.inside(middle)) return true;
            return shield.inside(local(middle, now));
        }

        /**
         * The owner and their allies and pets. With the owner away nobody is, which costs nothing:
         * what is already under the shell is never hit from inside (shots from inside fly out, blows
         * from inside are not the shield's).
         */
        public boolean friendly(Entity entity) {
            ServerPlayer owner = generator.ownerOnline();
            return owner != null && ShieldCoverage.friendly(owner, entity);
        }
    }

    private static final class Standing {
        final Map<ShipDeviceBlockEntity, Field> fields = new IdentityHashMap<>();
        List<Field> list = List.of();
        long prunedAt = Long.MIN_VALUE / 4;
        boolean changed;
    }

    private static final Map<ResourceKey<Level>, Standing> FIELDS = new HashMap<>();
    /** Create Big Cannons' shells by class: 2 a big cannon's, 1 an autocannon's, 0 not a cannon's. */
    private static final Map<Class<?>, Integer> CANNON = new ConcurrentHashMap<>();
    private static final String BIG_SHELL = "rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile",
            AUTOCANNON_ROUND = "rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile";
    /** What a big cannon's shell and an autocannon's round cost a shield, and an explosion per block of radius. */
    public static final int BIG_SHELL_COST = 40, ROUND_COST = 6, BLAST_COST_PER_BLOCK = 12;

    static void put(ServerLevel level, ShipDeviceBlockEntity generator, ShipShield shield, long now) {
        Standing standing = FIELDS.computeIfAbsent(level.dimension(), ignored -> new Standing());
        standing.fields.put(generator, new Field(generator, shield, ShipFrame.of(generator), now));
        standing.changed = true;
    }

    static void remove(ServerLevel level, ShipDeviceBlockEntity generator) {
        Standing standing = FIELDS.get(level.dimension());
        if (standing != null && standing.fields.remove(generator) != null) standing.changed = true;
    }

    @Nullable
    public static Field of(ServerLevel level, ShipDeviceBlockEntity generator) {
        Standing standing = FIELDS.get(level.dimension());
        return standing == null ? null : standing.fields.get(generator);
    }

    /** The shields standing in {@code level} now; those whose generators stopped putting them up are dropped, once a tick. */
    public static List<Field> live(ServerLevel level, long now) {
        Standing standing = FIELDS.get(level.dimension());
        if (standing == null || standing.fields.isEmpty()) return List.of();
        if (standing.prunedAt != now) {
            standing.prunedAt = now;
            standing.changed |= standing.fields.values().removeIf(field -> field.generator().isRemoved() || now - field.at() > 2 || field.at() > now);
        }
        if (standing.changed) {
            standing.changed = false;
            standing.list = List.copyOf(standing.fields.values());
        }
        return standing.list;
    }

    // --- shots -------------------------------------------------------------------------------------

    private record Crossing(Field field, double t, Vec3 local) {
    }

    /** Shots a ship shield stops: whatever a worn shield stops, and Create Big Cannons' shells and rounds. */
    public static boolean interceptable(Projectile projectile) {
        return !projectile.isRemoved() && (cannon(projectile.getClass()) > 0 || ShieldProjectileInterceptor.supported(projectile));
    }

    public static int cost(Projectile projectile) {
        return switch (cannon(projectile.getClass())) {
            case 2 -> BIG_SHELL_COST;
            case 1 -> ROUND_COST;
            default -> ShieldProjectileInterceptor.impactCost(projectile);
        };
    }

    public static int cannon(Class<?> type) {
        return CANNON.computeIfAbsent(type, start -> {
            for (Class<?> at = start; at != null; at = at.getSuperclass()) {
                if (at.getName().equals(BIG_SHELL)) return 2;
                if (at.getName().equals(AUTOCANNON_ROUND)) return 1;
            }
            return 0;
        });
    }

    /**
     * A shot about to fly in through a shield this tick is stopped on the shell if the shield holds
     * it; what the shield cannot hold flies on (an arrow weakened by what was held). The ship's own
     * shots start inside and fly out unhindered.
     */
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Projectile projectile) || !(projectile.level() instanceof ServerLevel level)) return;
        if (!FIELDS.containsKey(level.dimension()) || !interceptable(projectile)) return;
        long now = level.getGameTime();
        List<Field> fields = live(level, now);
        if (fields.isEmpty()) return;
        Entity owner = projectile.getOwner();
        Vec3 from = projectile.position(), motion = projectile.getDeltaMovement();
        List<Crossing> crossings = null;
        for (Field field : fields) {
            Vec3 relative = motion.subtract(field.frame().velocity());
            AABB near = field.worldBounds().inflate(1);
            Vec3 end = from.add(relative);
            if (!near.contains(from) && !near.contains(end) && near.clip(from, end).isEmpty()) continue;
            if (owner != null && field.friendly(owner)) continue;
            if (owner instanceof Player shooter && !level.getServer().isPvpAllowed() && !(projectile instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile)
                    && cannon(projectile.getClass()) == 0) continue;
            Vec3 a = field.local(from, now), b = field.local(from.add(relative), now);
            double t = field.shield().entry(a, b);
            if (t < 0) continue;
            if (crossings == null) crossings = new ArrayList<>(2);
            crossings.add(new Crossing(field, t, a.lerp(b, t)));
        }
        if (crossings == null) return;
        crossings.sort(java.util.Comparator.comparingDouble(Crossing::t));
        for (Crossing crossing : crossings) {
            int cost = cost(projectile);
            ShieldLayers.Strike strike = crossing.field().shield().hit(crossing.local(), cost, now);
            if (strike.absorbed() <= 0 && !strike.overloaded()) continue;
            ServerPlayer owner_ = crossing.field().generator().ownerOnline();
            if (owner_ != null && owner != null) ShieldCoverage.provoked(owner_, owner);
            if (strike.held()) {
                projectile.setPos(from.add(motion.scale(crossing.t())));
                if (projectile instanceof AbstractArrow arrow && arrow.pickup == AbstractArrow.Pickup.ALLOWED) arrow.spawnAtLocation(arrow.getPickupItemStackOrigin().copy());
                projectile.discard();
                event.setCanceled(true);
                return;
            }
            if (projectile instanceof AbstractArrow arrow) arrow.setBaseDamage(arrow.getBaseDamage() * (strike.passed() / (double) cost));
        }
    }

    // --- blows -------------------------------------------------------------------------------------

    /** A blow from outside the shield on someone inside it is taken by the shield, as far as it holds. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || event.getAmount() <= 0) return;
        DamageSource source = event.getSource();
        Vec3 origin = source.getSourcePosition();
        if (origin == null || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || dev.hurtify.relicsaddon.server.ShieldController.passesField(source)) return;
        long now = level.getGameTime();
        List<Field> fields = live(level, now);
        if (fields.isEmpty()) return;
        Entity attacker = source.getEntity();
        for (Field field : fields) {
            if (attacker != null && field.friendly(attacker)) continue;
            Vec3 inside = field.local(victim.getBoundingBox().getCenter(), now);
            if (!field.shield().inside(inside)) continue;
            Vec3 from = field.local(origin, now);
            if (field.shield().inside(from)) continue;
            double t = field.shield().entry(from, inside);
            Vec3 at = t < 0 ? inside : from.lerp(inside, t);
            int cost = net.minecraft.util.Mth.ceil(event.getAmount());
            ShieldLayers.Strike strike = field.shield().hit(at, cost, now);
            if (strike.absorbed() <= 0 && !strike.overloaded()) continue;
            ServerPlayer owner = field.generator().ownerOnline();
            if (owner != null && attacker != null) ShieldCoverage.provoked(owner, attacker);
            if (strike.held()) {
                event.setCanceled(true);
            } else {
                event.setAmount(event.getAmount() * strike.passed() / (float) cost);
            }
            return;
        }
    }

    // --- explosions --------------------------------------------------------------------------------

    /** An explosion outside a shield spares what is inside it, if the shield holds the whole blast. */
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (dev.hurtify.relicsaddon.server.ShieldController.passesField(event.getExplosion().damageSource)) return;
        long now = level.getGameTime();
        List<Field> fields = live(level, now);
        if (fields.isEmpty()) return;
        Vec3 centre = event.getExplosion().center();
        for (Field field : fields) {
            Vec3 from = field.local(centre, now);
            if (field.shield().inside(from)) continue;
            List<BlockPos> blocks = new ArrayList<>();
            for (BlockPos pos : event.getAffectedBlocks()) if (field.covers(pos, now)) blocks.add(pos);
            List<Entity> inside = new ArrayList<>();
            for (Entity entity : event.getAffectedEntities()) {
                if (field.shield().inside(field.local(entity.getBoundingBox().getCenter(), now))) inside.add(entity);
            }
            if (blocks.isEmpty() && inside.isEmpty()) continue;
            Vec3 towards = blocks.isEmpty() ? field.local(inside.get(0).getBoundingBox().getCenter(), now) : Vec3.atCenterOf(blocks.get(0));
            if (!field.frame().aboard() || !field.shield().inside(towards)) towards = field.local(Vec3.atCenterOf(blocks.isEmpty() ? BlockPos.containing(centre) : blocks.get(0)), now);
            double t = field.shield().entry(from, towards);
            Vec3 at = t < 0 ? towards : from.lerp(towards, t);
            int cost = Math.max(1, Math.round(event.getExplosion().radius() * BLAST_COST_PER_BLOCK));
            ShieldLayers.Strike strike = field.shield().hit(at, cost, now);
            if (!strike.held()) continue;
            event.getAffectedBlocks().removeAll(blocks);
            event.getAffectedEntities().removeAll(inside);
            ServerPlayer owner = field.generator().ownerOnline();
            if (owner != null && event.getExplosion().getIndirectSourceEntity() instanceof LivingEntity culprit) ShieldCoverage.provoked(owner, culprit);
        }
    }

    // --- the structure changing ----------------------------------------------------------------------

    /** A block placed, broken or changed under a shield: its generator looks at its structure again soon. */
    public static void onBlockChange(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !FIELDS.containsKey(level.dimension())) return;
        long now = level.getGameTime();
        for (Field field : live(level, now)) {
            if (field.covers(event.getPos(), now)) field.generator().requestScanSoon();
        }
    }

    /**
     * A Create Big Cannons shell about to break a block under a shield that holds and let nothing
     * through this tick: the block is spared (the shell itself was stopped on the shell, or missed
     * it between two of its sweeps). Bound by {@code compat.aeronautics.CbcShellGuard}.
     */
    public static boolean guardsBlock(Level world, BlockPos pos) {
        if (!(world instanceof ServerLevel level) || !FIELDS.containsKey(level.dimension())) return false;
        long now = level.getGameTime();
        for (Field field : live(level, now)) {
            if (!field.shield().passedThrough(now) && field.covers(pos, now)) return true;
        }
        return false;
    }

    /** Once a second, the shields whose generators stopped putting them up are let go of. */
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        FIELDS.entrySet().removeIf(entry -> {
            ServerLevel level = event.getServer().getLevel(entry.getKey());
            if (level == null) return true;
            long now = level.getGameTime();
            Standing standing = entry.getValue();
            standing.changed |= standing.fields.values().removeIf(field -> field.generator().isRemoved() || now - field.at() > 40 || field.at() > now);
            return standing.fields.isEmpty();
        });
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        FIELDS.clear();
        ShellCache.clear();
    }

    private ShipShieldFields() {
    }
}
