package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.AddonConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The mind a ship's hives share: which creatures round the ship are threats, how bad each is, and who has hurt the
 * crew or the ship lately (a grudge, kept half a minute). Every hive aboard reads the same board, so they agree on
 * what to fight and spread over it instead of all piling onto one creature. Hives off a ship share one with the
 * owner's other hives close by (a base). Server thread only.
 */
public final class ShipBrain {
    private static final Map<Key, ShipBrain> BRAINS = new HashMap<>();
    /** Ticks between scans of the ship's surroundings, a grudge's length, and how long an idle brain is kept. */
    private static final int SCAN_EVERY = 10, GRUDGE_TICKS = 600, FORGET_AFTER = 100;
    /** Hives off a ship share a brain within regions this many blocks across (2^6). */
    private static final int BASE_REGION_BITS = 6;
    /** A tick long past, far enough from any game time that differences with it cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 4;

    private record Key(ResourceKey<Level> dimension, @Nullable UUID ship, @Nullable UUID owner, long region) {
    }

    /** A creature on the board and how much it matters. */
    public record Threat(LivingEntity entity, double score) {
    }

    private final Key key;
    private final ServerLevel level;
    /** Where each of its hives' ship (or the hive itself, in a base) was as it last ticked, and when. */
    private final Map<ShipHiveBlockEntity, Member> members = new java.util.IdentityHashMap<>();
    /** All of them together, worked out once a tick. */
    private AABB area;
    private long areaAt = NEVER, touchedAt, scannedAt = NEVER, claimsAt = NEVER;
    /** The hives' owners and the tick each was last seen, and their names (for scoreboard teams while they are away). */
    private final Map<UUID, Long> owners = new HashMap<>();
    private final Map<String, Long> ownerNames = new HashMap<>();
    private final List<Threat> threats = new ArrayList<>();
    private final Map<Integer, Long> grudges = new LinkedHashMap<>();
    private final Map<Integer, Integer> claims = new HashMap<>();

    private record Member(AABB bounds, long at) {
    }

    private ShipBrain(Key key, ServerLevel level, AABB area) {
        this.key = key;
        this.level = level;
        this.area = area;
    }

    /** The brain of the hive's ship (or base), told that the hive is there this tick. */
    static ShipBrain of(ServerLevel level, ShipFrame frame, ShipHiveBlockEntity hive, long now) {
        UUID owner = hive.owner();
        Key key = frame.aboard() ? new Key(level.dimension(), frame.ship(), null, 0)
                : new Key(level.dimension(), null, owner, region(hive));
        ShipBrain brain = BRAINS.get(key);
        if (brain == null || brain.level != level) {
            brain = new ShipBrain(key, level, frame.bounds());
            BRAINS.put(key, brain);
        }
        brain.note(frame, hive, now);
        return brain;
    }

    private static long region(ShipHiveBlockEntity hive) {
        var pos = hive.getBlockPos();
        long x = pos.getX() >> BASE_REGION_BITS, y = pos.getY() >> BASE_REGION_BITS, z = pos.getZ() >> BASE_REGION_BITS;
        return (x & 0x1FFFFF) << 42 | (y & 0x1FFFFF) << 21 | z & 0x1FFFFF;
    }

    private void note(ShipFrame frame, ShipHiveBlockEntity hive, long now) {
        touchedAt = now;
        members.put(hive, new Member(frame.bounds(), now));
        if (areaAt != now) areaAt = NEVER;
        UUID owner = hive.owner();
        if (owner != null) owners.put(owner, now);
        if (!hive.ownerName().isEmpty()) ownerNames.put(hive.ownerName(), now);
        if (now % 20 == 0) {
            owners.values().removeIf(at -> now - at > 40 || at > now);
            ownerNames.values().removeIf(at -> now - at > 40 || at > now);
        }
    }

    /** Once a second: brains whose hives have all gone quiet are forgotten, and hives gone from a brain are dropped. */
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        BRAINS.values().removeIf(brain -> {
            long now = brain.level.getGameTime();
            brain.members.entrySet().removeIf(entry -> entry.getKey().isRemoved() || now - entry.getValue().at() > 40 || entry.getValue().at() > now);
            return brain.members.isEmpty() || now - brain.touchedAt > FORGET_AFTER || brain.touchedAt > now
                    || brain.level.getServer().getLevel(brain.level.dimension()) != brain.level;
        });
    }

    /** The box round all the brain's hives' ships (or the hives themselves): every one that ticked lately, whatever their order. */
    private AABB area(long now) {
        if (areaAt == now && area != null) return area;
        AABB union = null;
        for (Member member : members.values()) {
            if (now - member.at() > 2 || member.at() > now) continue;
            union = union == null ? member.bounds() : union.minmax(member.bounds());
        }
        if (union != null) {
            area = union;
            areaAt = now;
        }
        return area;
    }

    @Nullable
    UUID ship() {
        return key.ship();
    }

    boolean owns(UUID player) {
        return owners.containsKey(player);
    }

    Set<UUID> owners() {
        return owners.keySet();
    }

    Set<String> ownerNames() {
        return ownerNames.keySet();
    }

    /** Whether {@code point} is within {@code reach} blocks of the ship's box. */
    boolean near(Vec3 point, double reach) {
        return area(level.getGameTime()).inflate(reach).contains(point);
    }

    static double range() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_RANGE.get() : 48;
    }

    /** The threats round the ship, the worst first; rescanned every half second. */
    List<Threat> threats(long now) {
        if (now - scannedAt >= SCAN_EVERY || scannedAt > now) scan(now);
        threats.removeIf(threat -> !threat.entity().isAlive() || threat.entity().isRemoved() || threat.entity().level() != level);
        return threats;
    }

    private void scan(long now) {
        scannedAt = now;
        threats.clear();
        grudges.values().removeIf(until -> until < now || until - now > GRUDGE_TICKS);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area(now).inflate(range()), LivingEntity::isAlive)) {
            double score = ShipAllies.threat(this, entity, now);
            if (score > 0) threats.add(new Threat(entity, score));
        }
        threats.sort(Comparator.comparingDouble(Threat::score).reversed());
    }

    /**
     * The threat a hive at {@code from} should take on: the worst within {@code reach} that it can get at, less so the
     * further it is and the more of the ship's hives are already on it; the one it already fights is kept unless a
     * clearly worse one turns up.
     */
    @Nullable
    LivingEntity pick(Vec3 from, double reach, @Nullable LivingEntity current, Predicate<LivingEntity> reachable, long now) {
        List<Threat> board = threats(now);
        if (board.isEmpty()) return null;
        if (claimsAt != now) {
            claims.clear();
            claimsAt = now;
        }
        List<Threat> ranked = new ArrayList<>(board.size());
        Map<LivingEntity, Double> worth = new HashMap<>();
        for (Threat threat : board) {
            LivingEntity entity = threat.entity();
            double distance = entity.getBoundingBox().getCenter().distanceTo(from);
            if (distance > reach) continue;
            worth.put(entity, threat.score() - distance * .4 + (entity == current ? 12 : 0) - claims.getOrDefault(entity.getId(), 0) * 6);
            ranked.add(threat);
        }
        ranked.sort(Comparator.comparingDouble((Threat threat) -> worth.get(threat.entity())).reversed());
        for (Threat threat : ranked) if (reachable.test(threat.entity())) return threat.entity();
        return null;
    }

    /** Notes that one of the ship's hives is on {@code target} this tick, so the others look elsewhere first. */
    void claim(LivingEntity target, long now) {
        if (claimsAt != now) {
            claims.clear();
            claimsAt = now;
        }
        claims.merge(target.getId(), 1, Integer::sum);
    }

    boolean grudges(LivingEntity entity, long now) {
        Long until = grudges.get(entity.getId());
        return until != null && until >= now;
    }

    /** {@code attacker} hurt the crew or the ship: the hives go after it for a while, whatever it is. */
    void grudge(LivingEntity attacker, long now) {
        if (attacker.level() != level || ShipAllies.sworn(this, attacker)) return;
        grudges.remove(attacker.getId());
        grudges.put(attacker.getId(), now + GRUDGE_TICKS);
        if (grudges.size() > 64) {
            Iterator<Integer> oldest = grudges.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        scannedAt = NEVER;
    }

    // --- grudges from the world ----------------------------------------------------------------

    /** A creature that hurts one of a ship's crew earns that ship's grudge. */
    public static void onDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == victim) return;
        long now = level.getGameTime();
        for (ShipBrain brain : BRAINS.values()) {
            if (brain.level == level && brain.near(victim.position(), 8) && ShipAllies.friendly(brain, victim, now)) brain.grudge(attacker, now);
        }
    }

    /** An explosion at a ship earns whoever set it off that ship's grudge. */
    public static void onExplosion(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getExplosion().getIndirectSourceEntity() instanceof LivingEntity culprit)) return;
        Vec3 centre = event.getExplosion().center();
        long now = level.getGameTime();
        for (ShipBrain brain : BRAINS.values()) if (brain.level == level && brain.near(centre, 3)) brain.grudge(culprit, now);
    }

    /** A shot that strikes a ship's hull earns its shooter that ship's grudge. */
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level) || event.getRayTraceResult().getType() != HitResult.Type.BLOCK) return;
        Entity shooter = projectile.getOwner();
        if (!(shooter instanceof LivingEntity culprit)) return;
        // A hull that is struck is struck in its ship's plot: carried back out, the point is where the ship is.
        Vec3 at = dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.projectOutOfSubLevel(level, event.getRayTraceResult().getLocation());
        long now = level.getGameTime();
        for (ShipBrain brain : BRAINS.values()) {
            if (brain.level == level && brain.ship() != null && brain.near(at, .5)) brain.grudge(culprit, now);
        }
    }

    /** One line per hive the brains know, with what it is doing: for logs and tests. */
    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        for (ShipBrain brain : BRAINS.values()) {
            for (ShipHiveBlockEntity hive : brain.members.keySet()) {
                lines.add((brain.ship() == null ? "base" : "ship " + brain.ship()) + " " + hive.kind() + " at " + hive.getBlockPos().toShortString()
                        + ": " + hive.module().status().getString() + ", " + hive.energy().getEnergyStored() + " FE, "
                        + brain.threats(brain.level.getGameTime()).size() + " threats");
            }
        }
        return lines;
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        BRAINS.clear();
    }
}
