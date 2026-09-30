package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.AddonConfig;
import javax.annotation.Nullable;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * ESCORT: four drones flying out two by two. Each linked pair is a wing of its own with a small swarm round it: it
 * leaves the hive, keeps watch round the ship, goes after a threat on its own (the two wings take different ones when
 * they can), circles it, strikes it with the arc between its two drones and sends its little drones diving at it, and
 * never strays further from the ship than its leash. A wing flies on its own charge and comes home to fill up from the
 * hive's FE when it runs low, or when the hive is switched off; a hive newly set down fills its wings before they fly.
 */
public final class EscortModule implements ShipModule {
    public static final int WINGS = 2;
    /** A wing's charge when full, and what it spends a tick in the air, on an arc and on a dive. */
    public static final int FULL = 600;
    private static final int AIRBORNE = 1, ARC_COST = 30, DIVE_COST = 5;
    /** Charge won back a tick at the hive, and the FE it costs a point. */
    private static final int REFILL = 10, FE_PER_POINT = 20;
    /** Ticks between a wing's arcs and between its dives; ticks a launch lasts. */
    public static final int ARC_EVERY = 20, DIVE_EVERY = 10, LAUNCH_TICKS = 15;
    /** A wing's top speed and its pull (blocks a tick, and a tick squared), how close it circles its mark, and above it. */
    private static final double SPEED = .95, PULL = .16, CIRCLE = 5, ABOVE = 2.5;
    /** How near its mark a wing must be to strike, and how often clients are told where the wings are. */
    private static final double STRIKE_REACH = 7.5;
    private static final int SYNC_EVERY = 5;
    private static final long NEVER = Long.MIN_VALUE / 4;

    public enum Phase { DOCKED, LAUNCH, PATROL, ENGAGE, RETURN }

    /** One wing: a linked pair of drones and its swarm. */
    public static final class Wing {
        Phase phase = Phase.DOCKED;
        long phaseAt = NEVER, arcAt = NEVER, diveAt = NEVER, positionAt = NEVER;
        Vec3 position, velocity = Vec3.ZERO;
        int charge, targetId = -1;
        @Nullable
        LivingEntity target;

        public Phase phase() {
            return phase;
        }

        public long phaseAt() {
            return phaseAt;
        }

        /** The tick of the wing's last arc and of its last dive, each at its mark. */
        public long arcAt() {
            return arcAt;
        }

        public long diveAt() {
            return diveAt;
        }

        /** The tick {@link #position()} and {@link #velocity()} were true at. */
        public long positionAt() {
            return positionAt;
        }

        /** Where the pair's middle is, in the world, as the server last said, and how it was moving then. */
        public Vec3 position() {
            return position;
        }

        public Vec3 velocity() {
            return velocity;
        }

        public int charge() {
            return charge;
        }

        public int targetId() {
            return targetId;
        }
    }

    private final Wing[] wings = {new Wing(), new Wing()};
    /** The tick the wings' places were last sent to clients. */
    private long sentAt = NEVER;
    private boolean unpowered;

    public Wing[] wings() {
        return wings;
    }

    @Override
    public void tick(ShipHiveBlockEntity hive, ServerLevel level, ShipFrame frame, ShipBrain brain, long now) {
        Vec3 normal = frame.turn(hive.normal()).normalize();
        Vec3 across = frame.turn(LanceShape.across(hive.facing())).normalize();
        boolean on = hive.switchedOn(level);
        boolean changed = false;
        unpowered = false;
        Vec3 middle = frame.middle();
        double leash = leash(), orbit = Math.min(orbit(frame), leash - 2);
        for (int index = 0; index < WINGS; index++) {
            Wing wing = wings[index];
            Vec3 dock = dock(frame.centre(), normal, across, index);
            if (wing.position == null || wing.position.distanceToSqr(dock) > sq(leash * 2 + orbit + 32)) {
                // First tick, or the ship went far away all at once (a teleport): the wing is home.
                wing.position = dock;
                wing.velocity = Vec3.ZERO;
                changed |= to(wing, Phase.DOCKED, now);
            }
            Phase before = wing.phase;
            int targetBefore = wing.targetId;
            switch (wing.phase) {
                case DOCKED -> {
                    wing.position = dock;
                    wing.velocity = frame.velocity();
                    if (wing.charge < FULL) {
                        if (hive.draw(REFILL * energyPerPoint())) wing.charge = Math.min(FULL, wing.charge + REFILL);
                        else unpowered = true;
                    }
                    if (on && wing.charge >= FULL * 6 / 10 && to(wing, Phase.LAUNCH, now)) {
                        RelicSounds.ship(level, dock, RelicSounds.Ship.ESCORT_LAUNCH, 1, 1 + index * .12F);
                    }
                }
                case LAUNCH -> {
                    fly(wing, dock.add(normal.scale(5)), frame.velocity());
                    if (now - wing.phaseAt >= LAUNCH_TICKS) to(wing, Phase.PATROL, now);
                }
                case PATROL, ENGAGE -> {
                    wing.charge -= AIRBORNE;
                    LivingEntity mark = on ? mark(wing, level, brain, middle, leash, now) : null;
                    wing.target = mark;
                    wing.targetId = mark == null ? -1 : mark.getId();
                    if (!on || wing.charge < FULL / 4) to(wing, Phase.RETURN, now);
                    else if (mark != null) {
                        if (wing.phase != Phase.ENGAGE) to(wing, Phase.ENGAGE, now);
                        brain.claim(mark, now);
                        engage(hive, wing, index, mark, level, brain, middle, leash, now);
                    } else {
                        if (wing.phase != Phase.PATROL) to(wing, Phase.PATROL, now);
                        double angle = now * .02 + index * Math.PI;
                        Vec3 round = frame.turn(new Vec3(Math.cos(angle) * orbit, 3, Math.sin(angle) * orbit));
                        fly(wing, leashed(middle, middle.add(round), leash), frame.velocity());
                    }
                }
                case RETURN -> {
                    wing.target = null;
                    wing.targetId = -1;
                    fly(wing, dock, frame.velocity());
                    // The wing has moved on to next tick's place, and so will the dock with its ship.
                    if (wing.position.distanceToSqr(dock.add(frame.velocity())) < .8 * .8) to(wing, Phase.DOCKED, now);
                }
            }
            wing.positionAt = now;
            changed |= wing.phase != before || wing.targetId != targetBefore;
        }
        boolean flying = false;
        for (Wing wing : wings) flying |= wing.phase != Phase.DOCKED;
        if (changed || flying && now - sentAt >= SYNC_EVERY) {
            sentAt = now;
            hive.changed();
        }
    }

    /** Where a wing waits on the hive: just off the launch face, the two side by side. */
    public static Vec3 dock(Vec3 centre, Vec3 normal, Vec3 across, int index) {
        return centre.add(normal.scale(1.1)).add(across.scale(index == 0 ? -.55 : .55));
    }

    /** How far out round the ship's middle the wings keep watch. */
    static double orbit(ShipFrame frame) {
        var hull = frame.hull();
        double reach = Math.max(hull.getXsize(), hull.getZsize()) * .5;
        return Math.clamp(reach + 7, 7, 64);
    }

    private static boolean to(Wing wing, Phase phase, long now) {
        if (wing.phase == phase) return false;
        wing.phase = phase;
        wing.phaseAt = now;
        return true;
    }

    /** The wing's mark: the one it has while it stays a threat within the leash, weighed again every second; else the brain's pick. */
    @Nullable
    private static LivingEntity mark(Wing wing, ServerLevel level, ShipBrain brain, Vec3 middle, double leash, long now) {
        LivingEntity kept = wing.target;
        boolean keep = kept != null && kept.isAlive() && !kept.isRemoved() && kept.level() == level
                && kept.position().distanceTo(middle) <= leash && ShipAllies.threat(brain, kept, now) > 0;
        if (keep && now % 20 != 0) return kept;
        LivingEntity picked = brain.pick(wing.position, leash + ShipBrain.range() * .25, keep ? kept : null,
                candidate -> candidate.position().distanceTo(middle) <= leash, now);
        return picked != null ? picked : keep ? kept : null;
    }

    /**
     * Circles the mark (never past the leash) and strikes it: the pair's arc now and then, a dive of its swarm more
     * often; only while it is still a threat, and from where the wing is.
     */
    private void engage(ShipHiveBlockEntity hive, Wing wing, int index, LivingEntity mark, ServerLevel level, ShipBrain brain, Vec3 middle,
            double leash, long now) {
        Vec3 at = mark.getBoundingBox().getCenter();
        double angle = now * .07 + index * Math.PI;
        Vec3 round = new Vec3(Math.cos(angle) * CIRCLE, ABOVE, Math.sin(angle) * CIRCLE);
        fly(wing, leashed(middle, at.add(round), leash), mark.getDeltaMovement());
        if (wing.position.distanceTo(at) > STRIKE_REACH || !ShipRays.clear(level, wing.position, at) || !(ShipAllies.threat(brain, mark, now) > 0)) return;
        if (now - wing.arcAt >= ARC_EVERY && wing.charge > ARC_COST) {
            wing.arcAt = now;
            wing.charge -= ARC_COST;
            mark.hurt(ShipDamage.source(level, ShipDamage.ESCORT, hive, wing.position), arcDamage());
            RelicSounds.ship(level, at, RelicSounds.Ship.ESCORT_ARC, 1, .95F + index * .1F);
            hive.changed();
        } else if (now - wing.diveAt >= DIVE_EVERY && wing.charge > DIVE_COST) {
            wing.diveAt = now;
            wing.charge -= DIVE_COST;
            mark.hurt(ShipDamage.source(level, ShipDamage.ESCORT, hive, wing.position), arcDamage() * .35F);
            hive.changed();
        }
    }

    /** {@code goal}, drawn in to within {@code leash} of the ship's middle. */
    private static Vec3 leashed(Vec3 middle, Vec3 goal, double leash) {
        Vec3 out = goal.subtract(middle);
        double length = out.length();
        return length <= leash || length < 1e-6 ? goal : middle.add(out.scale(leash / length));
    }

    /** Steers the wing towards {@code goal}, easing in as it arrives, moving along with {@code carried} (its ship, or its mark). */
    private static void fly(Wing wing, Vec3 goal, Vec3 carried) {
        Vec3 to = goal.subtract(wing.position);
        double distance = to.length();
        Vec3 wanted = distance < 1e-6 ? Vec3.ZERO : to.scale(Math.min(SPEED, distance * .25) / distance);
        Vec3 change = wanted.add(carried).subtract(wing.velocity);
        double pull = change.length();
        if (pull > PULL) change = change.scale(PULL / pull);
        wing.velocity = wing.velocity.add(change);
        wing.position = wing.position.add(wing.velocity);
    }

    private static double sq(double value) {
        return value * value;
    }

    private static double leash() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.ESCORT_LEASH.get() : 40;
    }

    private static float arcDamage() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.ESCORT_DAMAGE.get().floatValue() : 4;
    }

    private static int energyPerPoint() {
        return FE_PER_POINT;
    }

    @Override
    public void stop(ShipHiveBlockEntity hive) {
        for (Wing wing : wings) wing.target = null;
    }

    @Override
    public ShipStatus.Line status() {
        int out = 0, fighting = 0;
        for (Wing wing : wings) {
            if (wing.phase != Phase.DOCKED) out++;
            if (wing.phase == Phase.ENGAGE) fighting++;
        }
        if (fighting > 0) return ShipStatus.ESCORT_FIGHTING.with(fighting);
        if (out > 0) return ShipStatus.ESCORT_PATROL.with(out);
        if (unpowered) return ShipStatus.UNPOWERED.line();
        return ShipStatus.ESCORT_DOCKED.line();
    }

    @Override
    public void save(CompoundTag tag) {
        int[] charges = new int[WINGS];
        for (int k = 0; k < WINGS; k++) charges[k] = wings[k].charge;
        tag.putIntArray("Charges", charges);
    }

    @Override
    public void load(CompoundTag tag) {
        int[] charges = tag.getIntArray("Charges");
        for (int k = 0; k < WINGS; k++) wings[k].charge = k < charges.length ? Math.clamp(charges[k], 0, FULL) : 0;
    }

    @Override
    public void saveSync(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Wing wing : wings) {
            CompoundTag entry = new CompoundTag();
            entry.putByte("Phase", (byte) wing.phase.ordinal());
            entry.putLong("PhaseAt", wing.phaseAt);
            entry.putLong("ArcAt", wing.arcAt);
            entry.putLong("DiveAt", wing.diveAt);
            entry.putInt("Target", wing.targetId);
            entry.putInt("Charge", wing.charge);
            if (wing.position != null) {
                entry.putLong("At", wing.positionAt);
                entry.putDouble("X", wing.position.x);
                entry.putDouble("Y", wing.position.y);
                entry.putDouble("Z", wing.position.z);
                entry.putFloat("VX", (float) wing.velocity.x);
                entry.putFloat("VY", (float) wing.velocity.y);
                entry.putFloat("VZ", (float) wing.velocity.z);
            }
            list.add(entry);
        }
        tag.put("Wings", list);
    }

    @Override
    public void loadSync(CompoundTag tag) {
        ListTag list = tag.getList("Wings", Tag.TAG_COMPOUND);
        for (int k = 0; k < WINGS && k < list.size(); k++) {
            CompoundTag entry = list.getCompound(k);
            Wing wing = wings[k];
            int phase = entry.getByte("Phase");
            wing.phase = phase >= 0 && phase < Phase.values().length ? Phase.values()[phase] : Phase.DOCKED;
            wing.phaseAt = entry.getLong("PhaseAt");
            wing.arcAt = entry.getLong("ArcAt");
            wing.diveAt = entry.getLong("DiveAt");
            wing.targetId = entry.getInt("Target");
            wing.charge = Math.clamp(entry.getInt("Charge"), 0, FULL);
            if (entry.contains("X")) {
                Vec3 at = new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
                Vec3 moving = new Vec3(entry.getFloat("VX"), entry.getFloat("VY"), entry.getFloat("VZ"));
                wing.position = Double.isFinite(at.lengthSqr()) ? at : null;
                wing.positionAt = entry.getLong("At");
                wing.velocity = Double.isFinite(moving.lengthSqr()) && moving.lengthSqr() < 400 ? moving : Vec3.ZERO;
            } else wing.position = null;
        }
    }
}
