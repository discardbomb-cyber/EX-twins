package dev.hurtify.relicsaddon.shipshield;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * One emitter drone of a ship shield: it belongs to a dock, holds a seat on the shell, and
 * carries charge and integrity. On a critical reading it leaves the shield, flies home in an arc,
 * is charged and mended in its dock, and comes back on its own. Drones never break for good.
 * Positions are in the structure's own coordinates, so they ride the ship.
 */
public final class EmitterDrone {
    public enum State { DOCKED, LAUNCHING, HOLDING, RETURNING, CHARGING }

    /** Below these a drone leaves its seat; above this charge it leaves its dock again. */
    public static final float CRITICAL_CHARGE = .15F, CRITICAL_INTEGRITY = .3F, READY_CHARGE = .95F;
    /** Charge a drone spends holding its seat each tick (ten minutes a charge), and for every point its patch loses. */
    public static final float HOLD_DRAIN = 1 / 12000F, HIT_DRAIN = .01F;
    /** Integrity a drone loses whenever its patch is drained dry; a dock mends it as it charges. */
    public static final float STRIP_WEAR = .1F;
    /** Blocks a flying drone covers each tick, and the highest its arc rises. */
    public static final double SPEED = .6, ARC = 3;

    public final int seat;
    public final BlockPos dock;
    private State state = State.DOCKED;
    private Vec3 at, from, to;
    private int flightTick, flightTicks;
    private float charge = 1, integrity = 1;

    public EmitterDrone(int seat, BlockPos dock, Vec3 at) {
        this.seat = seat;
        this.dock = dock.immutable();
        this.at = at;
        this.from = at;
        this.to = at;
    }

    public State state() { return state; }
    public Vec3 at() { return at; }
    public float charge() { return charge; }
    public float integrity() { return integrity; }
    public boolean holding() { return state == State.HOLDING; }
    public boolean away() { return state == State.DOCKED || state == State.CHARGING; }
    public boolean critical() { return charge <= CRITICAL_CHARGE || integrity <= CRITICAL_INTEGRITY; }
    public boolean ready() { return charge >= READY_CHARGE && integrity >= READY_CHARGE; }

    public void setCharge(float value) { charge = Math.clamp(value, 0, 1); }
    public void setIntegrity(float value) { integrity = Math.clamp(value, 0, 1); }

    /** Charge spent at the seat. */
    public void drain(float amount) { charge = Math.max(0, charge - amount); }
    public void wear(float amount) { integrity = Math.max(0, integrity - amount); }

    /** Charged and mended in the dock, by a share of a full charge. */
    public void recharge(float share) {
        charge = Math.min(1, charge + share);
        integrity = Math.min(1, integrity + share);
    }

    /** Sets off from where it is to {@code target}, in an arc. */
    public void fly(Vec3 target, boolean home) {
        from = at;
        to = target;
        flightTick = 0;
        flightTicks = Math.max(4, (int) Math.ceil(from.distanceTo(to) / SPEED) + 4);
        state = home ? State.RETURNING : State.LAUNCHING;
    }

    /** Placed straight at its seat or dock, no flight (a loaded shield, a fresh generator). */
    public void place(Vec3 position, State at) {
        this.at = position;
        from = position;
        to = position;
        state = at;
    }

    /** One tick of flight; true when the flight ended this tick. */
    public boolean tickFlight() {
        if (state != State.LAUNCHING && state != State.RETURNING) return false;
        flightTick++;
        double t = Math.min(1, flightTick / (double) flightTicks);
        double rise = Math.min(ARC, from.distanceTo(to) / 3) * Math.sin(t * Math.PI);
        at = from.lerp(to, t).add(0, rise, 0);
        if (t >= 1) {
            at = to;
            state = state == State.LAUNCHING ? State.HOLDING : State.CHARGING;
            return true;
        }
        return false;
    }

    public void save(CompoundTag tag) {
        tag.putInt("seat", seat);
        tag.putLong("dock", dock.asLong());
        tag.putByte("state", (byte) state.ordinal());
        tag.putFloat("charge", charge);
        tag.putFloat("integrity", integrity);
    }

    /** A drone as saved: at its dock when it was away, at its seat otherwise (flights are not saved). */
    public static EmitterDrone load(CompoundTag tag, Vec3 seatPosition, Vec3 dockPosition) {
        State state = State.values()[Math.clamp(tag.getByte("state"), 0, State.values().length - 1)];
        boolean away = state == State.DOCKED || state == State.CHARGING || state == State.RETURNING;
        EmitterDrone drone = new EmitterDrone(tag.getInt("seat"), BlockPos.of(tag.getLong("dock")), away ? dockPosition : seatPosition);
        drone.state = away ? State.CHARGING : State.HOLDING;
        drone.charge = Math.clamp(tag.getFloat("charge"), 0, 1);
        drone.integrity = Math.clamp(tag.getFloat("integrity"), 0, 1);
        // Keep the dock's outstanding claim until tickFlight reports the arrival after reload.
        if (state == State.RETURNING) drone.fly(dockPosition, true);
        return drone;
    }
}
