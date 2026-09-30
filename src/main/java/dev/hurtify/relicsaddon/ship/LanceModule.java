package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.AddonConfig;
import java.util.List;
import javax.annotation.Nullable;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * LANCE: three drones joined into a turret over the hive, holding one continuous beam on the worst threat they can
 * see. The turret swings onto its mark before it burns; the beam strikes the first creature in its path four times a
 * second, heats the turret as it burns and stops when the turret overheats, until it has cooled right down. It only
 * ever burns a threat: with anyone else first in its line of fire it holds, and looks for another mark when that
 * lasts. Every second it weighs its mark against the ship's other threats. It runs on the hive's FE.
 */
public final class LanceModule implements ShipModule {
    /** Heat at which the turret overheats; heat lost a tick while it does not burn. */
    public static final int HOT = 160, COOLING = 2;
    /** Ticks between the beam's strikes, idle ticks before the drones dock, ticks a mark may hide, and between second thoughts. */
    private static final int PULSE = 5, DOCK_AFTER = 60, BLIND_AFTER = 20, RETHINK = 20;
    /**
     * Most the turret swings a tick, how close to the mark it must point before it burns, and how far below the face's
     * plane it can aim (the sine of the angle: standing well out from the face, it can look down past its edge).
     */
    private static final double TURN = .2, ALIGNED = .06, LOWEST = -.6;
    /** How far the turret turns (radians) or its beam grows or shrinks (blocks) before clients are told again. */
    private static final double AIM_SYNC = .07, REACH_SYNC = 1;
    private static final long NEVER = Long.MIN_VALUE / 4;

    // Kept with the hive, and sent to clients.
    private int heat;
    private long heatAt;
    private boolean overheated;

    // Sent to clients only: none of it means anything once the world is loaded again.
    private boolean firing, deployed, aimEye;
    private long deployedAt = NEVER;
    private int targetId = -1;
    /** Where the turret points, in the ship's own terms, and how far its beam runs: for a client that cannot see the mark. */
    private Vec3 aimLocal = new Vec3(0, 1, 0);
    private float reach;

    // Server only.
    @Nullable
    private LivingEntity target;
    @Nullable
    private Vec3 aim;
    private int idle, blind;
    private boolean unpowered, blocked;

    @Override
    public void tick(ShipHiveBlockEntity hive, ServerLevel level, ShipFrame frame, ShipBrain brain, long now) {
        Vec3 normal = frame.turn(hive.normal()).normalize();
        Vec3 mount = LanceShape.mount(frame.centre(), normal);
        if (aim == null) aim = normal;
        boolean wasFiring = firing, wasOverheated = overheated, wasDeployed = deployed, wasEye = aimEye;
        int wasTarget = targetId;
        Vec3 wasAim = aimLocal;
        float wasReach = reach;

        // An overheated or unpowered turret keeps its mark in its sights; it only stops burning.
        LivingEntity mark = hive.switchedOn(level) ? mark(level, brain, mount, normal, now) : null;
        target = mark;
        targetId = mark == null ? -1 : mark.getId();
        firing = unpowered = blocked = false;
        if (mark != null) {
            brain.claim(mark, now);
            idle = 0;
            if (!deployed) {
                deployed = true;
                deployedAt = now;
            }
            Vec3 point = aimPoint(level, mount, mark);
            aimEye = point != null && point.equals(mark.getEyePosition()) && !point.equals(mark.getBoundingBox().getCenter());
            if (point == null) point = mark.getBoundingBox().getCenter();
            Vec3 want = point.subtract(mount).normalize();
            aim = LanceShape.clampToFace(LanceShape.turnTowards(aim, want, TURN), normal, LOWEST);
            Vec3 from = LanceShape.focus(frame.centre(), normal, aim);
            Vec3 end = ShipRays.reach(level, from, aim, ShipBrain.range());
            LivingEntity first = firstInPath(level, from, end);
            Vec3 cut = first == null ? null : entry(first, from, end);
            reach = (float) (cut == null ? end.distanceTo(from) : cut.distanceTo(from));
            boolean ready = now - deployedAt >= LanceShape.DEPLOY_TICKS && Math.acos(Math.clamp(aim.dot(want), -1, 1)) < ALIGNED;
            if (ready && !overheated) {
                blocked = first != null && !(ShipAllies.threat(brain, first, now) > 0);
                unpowered = !blocked && !hive.draw(energyPerTick());
                if (!blocked && !unpowered) {
                    firing = true;
                    if (now % PULSE == 0 && first != null) burn(hive, level, first, from);
                }
            }
        } else {
            aimEye = false;
            aim = LanceShape.turnTowards(aim, normal, TURN);
            reach = 0;
            if (deployed && ++idle > DOCK_AFTER) {
                deployed = false;
                deployedAt = now;
            }
        }
        aimLocal = frame.unturn(aim);

        // Heat climbs a point a tick of burning and falls two a tick otherwise; the turret overheats at the top and must cool right down.
        int nowHeat = firing ? Math.min(HOT, heat + 1) : Math.max(0, heat - COOLING);
        if (firing && nowHeat >= HOT) {
            overheated = true;
            firing = false;
            RelicSounds.ship(level, mount, RelicSounds.Ship.LANCE_OVERHEAT, 1.2F, 1);
        } else if (overheated && nowHeat == 0) overheated = false;
        if (firing && !wasFiring) RelicSounds.ship(level, LanceShape.focus(frame.centre(), normal, aim), RelicSounds.Ship.LANCE_IGNITE, 1.2F, 1);
        heat = nowHeat;
        heatAt = now;
        boolean turned = Math.acos(Math.clamp(aimLocal.dot(wasAim), -1, 1)) > AIM_SYNC || Math.abs(reach - wasReach) > REACH_SYNC;
        if (firing != wasFiring || overheated != wasOverheated || deployed != wasDeployed || targetId != wasTarget || aimEye != wasEye) hive.changed();
        else if (turned && deployed) hive.changed();
        else {
            // Small moves are not worth a packet: keep measuring from what the clients last got.
            aimLocal = wasAim;
            reach = wasReach;
        }
    }

    /**
     * The creature the turret fights this tick: the one it has while it stays a threat it can get at (a moment out of
     * sight is forgiven), weighed against the ship's other threats once a second; else the brain's pick.
     */
    @Nullable
    private LivingEntity mark(ServerLevel level, ShipBrain brain, Vec3 mount, Vec3 normal, long now) {
        double range = ShipBrain.range();
        LivingEntity kept = target;
        boolean keep = kept != null && kept.isAlive() && !kept.isRemoved() && kept.level() == level
                && kept.getBoundingBox().getCenter().distanceTo(mount) <= range && ShipAllies.threat(brain, kept, now) > 0;
        if (keep) {
            if (inFront(mount, normal, kept) && clearShot(level, brain, mount, kept, now)) blind = 0;
            else if (++blind > BLIND_AFTER) keep = false;
        }
        if (keep && now % RETHINK != 0) return kept;
        LivingEntity picked = brain.pick(mount, range, keep ? kept : null,
                candidate -> inFront(mount, normal, candidate) && clearShot(level, brain, mount, candidate, now), now);
        if (picked == null) return keep ? kept : null;
        if (picked != kept) blind = 0;
        return picked;
    }

    private static boolean inFront(Vec3 mount, Vec3 normal, LivingEntity candidate) {
        Vec3 to = candidate.getBoundingBox().getCenter().subtract(mount);
        return to.lengthSqr() > 1e-6 && to.normalize().dot(normal) >= LOWEST;
    }

    /** The point of {@code candidate} the turret can see from {@code from}: its middle, else its eyes; null if neither. */
    @Nullable
    static Vec3 aimPoint(ServerLevel level, Vec3 from, LivingEntity candidate) {
        Vec3 middle = candidate.getBoundingBox().getCenter();
        if (ShipRays.clear(level, from, middle)) return middle;
        Vec3 eyes = candidate.getEyePosition();
        return ShipRays.clear(level, from, eyes) ? eyes : null;
    }

    /** Whether the turret could burn {@code candidate} from {@code mount}: a point of it in sight, and nobody but threats in between. */
    private static boolean clearShot(ServerLevel level, ShipBrain brain, Vec3 mount, LivingEntity candidate, long now) {
        Vec3 point = aimPoint(level, mount, candidate);
        if (point == null) return false;
        LivingEntity first = firstInPath(level, mount, point);
        return first == null || first == candidate || ShipAllies.threat(brain, first, now) > 0;
    }

    /** One strike of the beam on the creature it meets first (always a threat: the turret holds its fire otherwise). */
    private static void burn(ShipHiveBlockEntity hive, ServerLevel level, LivingEntity victim, Vec3 from) {
        victim.hurt(ShipDamage.source(level, ShipDamage.LANCE, hive, from), damage());
    }

    /** The first creature a line from {@code from} to {@code to} passes through (with a little room round each), or null. */
    @Nullable
    static LivingEntity firstInPath(ServerLevel level, Vec3 from, Vec3 to) {
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(1),
                entity -> entity.isAlive() && !entity.isSpectator() && entity.isPickable());
        LivingEntity first = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity entity : near) {
            Vec3 cut = entry(entity, from, to);
            if (cut == null) continue;
            double distance = cut.distanceToSqr(from);
            if (distance < best) {
                best = distance;
                first = entity;
            }
        }
        return first;
    }

    /** Where the line from {@code from} to {@code to} enters {@code entity} (its box with a little room), or null if it misses it. */
    @Nullable
    private static Vec3 entry(LivingEntity entity, Vec3 from, Vec3 to) {
        AABB box = entity.getBoundingBox().inflate(.3);
        if (box.contains(from)) return from;
        return box.clip(from, to).orElse(null);
    }

    private static int energyPerTick() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.LANCE_ENERGY.get() : 100;
    }

    private static float damage() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.LANCE_DAMAGE.get().floatValue() : 3;
    }

    @Override
    public void stop(ShipHiveBlockEntity hive) {
        target = null;
    }

    @Override
    public ShipStatus.Line status() {
        if (overheated) return ShipStatus.LANCE_OVERHEATED.line();
        if (blocked) return ShipStatus.LANCE_BLOCKED.line();
        if (unpowered) return ShipStatus.UNPOWERED.line();
        if (firing) return ShipStatus.LANCE_FIRING.line();
        if (targetId >= 0) return ShipStatus.LANCE_AIMING.line();
        return ShipStatus.WATCHING.line();
    }

    /** The turret's heat as the server has it (the window's meter). */
    public int heat() {
        return heat;
    }

    /** The turret's heat at {@code time} (a client's frame time is fine): what was sent, run on at the rate it was going. */
    public double heat(double time) {
        double since = Math.max(0, time - heatAt);
        return firing ? Math.min(HOT, heat + since) : Math.max(0, heat - COOLING * since);
    }

    public boolean firing() {
        return firing;
    }

    public boolean overheated() {
        return overheated;
    }

    public boolean deployed() {
        return deployed;
    }

    public long deployedAt() {
        return deployedAt;
    }

    /** The mark's entity id (for a client to find it), or -1. */
    public int targetId() {
        return targetId;
    }

    /** Whether the turret burns its mark's eyes (its middle is behind cover) rather than its middle. */
    public boolean aimEye() {
        return aimEye;
    }

    /** Where the turret points, in the ship's own terms, as the server last said. */
    public Vec3 aimLocal() {
        return aimLocal;
    }

    /** How far the beam runs, as the server last said. */
    public double reach() {
        return reach;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Heat", heat);
        tag.putLong("HeatAt", heatAt);
        tag.putBoolean("Overheated", overheated);
    }

    @Override
    public void load(CompoundTag tag) {
        heat = Math.clamp(tag.getInt("Heat"), 0, HOT);
        heatAt = tag.getLong("HeatAt");
        overheated = tag.getBoolean("Overheated");
    }

    @Override
    public void saveSync(CompoundTag tag) {
        tag.putBoolean("Firing", firing);
        tag.putBoolean("Deployed", deployed);
        tag.putLong("DeployedAt", deployedAt);
        tag.putInt("Target", targetId);
        tag.putBoolean("AimEye", aimEye);
        tag.putFloat("AimX", (float) aimLocal.x);
        tag.putFloat("AimY", (float) aimLocal.y);
        tag.putFloat("AimZ", (float) aimLocal.z);
        tag.putFloat("Reach", reach);
    }

    @Override
    public void loadSync(CompoundTag tag) {
        firing = tag.getBoolean("Firing");
        deployed = tag.getBoolean("Deployed");
        deployedAt = tag.contains("DeployedAt") ? tag.getLong("DeployedAt") : NEVER;
        targetId = tag.contains("Target") ? tag.getInt("Target") : -1;
        aimEye = tag.getBoolean("AimEye");
        Vec3 read = new Vec3(tag.getFloat("AimX"), tag.getFloat("AimY"), tag.getFloat("AimZ"));
        aimLocal = read.lengthSqr() > .5 && read.lengthSqr() < 1.5 ? read.normalize() : new Vec3(0, 1, 0);
        float length = tag.getFloat("Reach");
        reach = Float.isFinite(length) ? Math.clamp(length, 0, 256) : 0;
    }
}
