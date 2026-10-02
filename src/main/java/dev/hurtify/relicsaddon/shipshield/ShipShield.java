package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The shield a generator raises round its structure, on the server. It traces the shell layers
 * off-thread whenever the structure changes (the old shell stands until the new one is ready),
 * seats the docks' emitter drones on the outermost shell, shares the shell out between them as
 * patches with integrity on every layer, takes blows and spreads them, sends worn drones home
 * and brings them back, and mends its patches between fights. All positions are in the
 * structure's own coordinates; {@link ShipShieldFields} carries hits in from the world.
 */
public final class ShipShield {
    /** Ticks the shield must go unhit before its patches mend. */
    public static final int QUIET_TICKS = 40;
    /** Battery points pushing one hostile mob out costs each tick. */
    public static final int PUSH_COST = 1;
    public static final String NOTICE_NO_DRONES = "notice.relics_addon.ship.no_drones";
    public static final String NOTICE_SHORT_DRONES = "notice.relics_addon.ship.short_drones";
    public static final String NOTICE_OVERLOADED = "notice.relics_addon.ship.overloaded";
    private static final long NEVER = Long.MIN_VALUE / 4;

    private final ShipDeviceBlockEntity generator;
    private double offset = 2;
    private int layerCount = 1, cellLimit = 4096;
    /** The shell layers, innermost first; empty until the first trace is done. */
    private List<ShellMesh> layers = List.of();
    private LongSet tracedBlocks = new LongOpenHashSet();
    private long tracedKey;
    private long changeSeenAt = -1;
    private @Nullable CompletableFuture<List<ShellMesh>> pending;
    private long pendingKey;
    private long rejectedKey = Long.MIN_VALUE;
    private Vec3[] seats = new Vec3[0];
    private final List<EmitterDrone> drones = new ArrayList<>();
    private @Nullable ShellPatches patches;
    private ShieldLayers integrity = new ShieldLayers(1, 0, 1);
    private long lastHitAt = NEVER, overloadedUntil = NEVER, passedAt = NEVER, repairedAt = NEVER;
    private boolean heldChanged = true, dirty;
    private @Nullable ListTag loadedDrones;
    private int[] loadedIntegrity = new int[0];
    private int loadedSeats;
    private final List<ShipShieldImpact> impacts = new ArrayList<>();
    private long raisedAt = -1;
    private boolean wasUp;

    public ShipShield(ShipDeviceBlockEntity generator) {
        this.generator = generator;
    }

    // --- readings -----------------------------------------------------------------------------

    public double offset() { return offset; }
    public int layerCount() { return layerCount; }
    public List<ShellMesh> layers() { return layers; }
    public @Nullable ShellMesh outer() { return layers.isEmpty() ? null : layers.get(layers.size() - 1); }
    public List<EmitterDrone> drones() { return drones; }
    public ShieldLayers integrity() { return integrity; }
    public @Nullable ShellPatches patches() { return patches; }
    public int seatCount() { return seats.length; }
    public Vec3 seat(int index) { return seats[index]; }
    public long lastHitAt() { return lastHitAt; }
    public long overloadedUntil() { return overloadedUntil; }
    public boolean overloaded(long now) { return now < overloadedUntil; }
    /** Whether something got through the shield this tick (a blow it could not hold). */
    public boolean passedThrough(long now) { return passedAt == now; }

    public int heldSeats() {
        int held = 0;
        for (EmitterDrone drone : drones) if (drone.holding()) held++;
        return held;
    }

    /** Whether the shield stands: a shell traced, the generator running, not overloaded, at least one drone at its seat. */
    public boolean up(long now) {
        return !layers.isEmpty() && generator.operating() && !overloaded(now) && heldSeats() > 0;
    }

    /** Whether a point (structure coordinates) is under the outermost layer. */
    public boolean inside(Vec3 local) {
        ShellMesh outer = outer();
        return outer != null && outer.field().inside(local);
    }

    /** Where the way from {@code a} to {@code b} crosses the outermost layer (0..1), or -1. */
    public double entry(Vec3 a, Vec3 b) {
        ShellMesh outer = outer();
        return outer == null ? -1 : outer.field().entry(a, b);
    }

    public Vec3 outward(Vec3 local) {
        ShellMesh outer = outer();
        return outer == null ? new Vec3(0, 1, 0) : outer.field().outward(local);
    }

    /** How deep under the outermost layer a point is, 0 outside. */
    public double depth(Vec3 local) {
        ShellMesh outer = outer();
        return outer == null ? 0 : Math.max(0, outer.field().offset() - outer.field().distance(local));
    }

    /** The box round the outermost layer, in structure coordinates. */
    public AABB bounds() {
        ShellMesh outer = outer();
        return outer == null ? new AABB(generator.getBlockPos()) : outer.bounds();
    }

    // --- ticking ---------------------------------------------------------------------------------

    public void tick(ServerLevel level, long now) {
        readSettings();
        trace(now);
        if (layers.isEmpty()) return;
        boolean running = generator.operating();
        if (overloadedUntil != NEVER && now >= overloadedUntil && running) {
            // Back up after the overload: every patch whole again.
            integrity.fill();
            overloadedUntil = NEVER;
            dirty = true;
        }
        if (integrity.seats() > 0 && integrity.max() != patchMax()) {
            // The level moved within its layer band: every patch keeps its share of a larger (or smaller) whole.
            ShieldLayers scaled = new ShieldLayers(integrity.layers(), integrity.seats(), patchMax());
            for (int layer = 0; layer < integrity.layers(); layer++) for (int seat = 0; seat < integrity.seats(); seat++) {
                scaled.set(layer, seat, (int) Math.round(integrity.integrity(layer, seat) * (double) scaled.max() / integrity.max()));
            }
            integrity = scaled;
            dirty = true;
        }
        boolean deploy = running && !overloaded(now);
        tickDrones(level, now, deploy);
        boolean isUp = up(now);
        if (isUp && !wasUp) { raisedAt = now; dirty = true; }
        wasUp = isUp;
        impacts.removeIf(hit -> now - hit.time() >= ShipShieldImpact.LIFETIME);
        if (heldChanged) {
            heldChanged = false;
            boolean[] held = new boolean[seats.length];
            for (EmitterDrone drone : drones) held[drone.seat] = drone.holding();
            patches = ShellPatches.of(outer(), seats, held);
            dirty = true;
        }
        if (deploy && heldSeats() > 0) {
            mend(now);
            ShipShieldFields.put(level, generator, this, now);
            pushMobs(level, now);
        }
        if (dirty || now % 10 == 0 && flying()) {
            dirty = false;
            generator.deviceChanged();
        }
    }

    private void readSettings() {
        boolean loaded = AddonConfig.SPEC.isLoaded();
        offset = loaded ? AddonConfig.SHIP_SHELL_OFFSET.get() : 2;
        cellLimit = loaded ? AddonConfig.SHIP_SHELL_CELLS.get() : 4096;
        int perLayer = loaded ? AddonConfig.SHIP_LEVELS_PER_LAYER.get() : 4;
        layerCount = Math.min(3, 1 + generator.level() / perLayer);
    }

    private int patchMax() {
        boolean loaded = AddonConfig.SPEC.isLoaded();
        int base = switch (generator.family()) {
            case RF -> loaded ? AddonConfig.SHIP_PATCH_RF.get() : 40;
            case MANA -> loaded ? AddonConfig.SHIP_PATCH_MANA.get() : 60;
            case TWINS -> loaded ? AddonConfig.SHIP_PATCH_TWINS.get() : 50;
        };
        return Math.max(1, base + base * generator.level() / 10);
    }

    // --- the shell -------------------------------------------------------------------------------

    /** Starts a trace when the structure differs from the one traced, after the configured delay, and takes a finished one in. */
    private void trace(long now) {
        ShipStructure structure = generator.structure();
        if (pending != null) {
            if (!pending.isDone()) return;
            List<ShellMesh> traced;
            try {
                traced = pending.join();
            } catch (RuntimeException failure) {
                dev.hurtify.relicsaddon.RelicsAddon.LOGGER.warn("Ship shield at {} failed to trace its shell", generator.getBlockPos(), failure);
                rejectedKey = pendingKey;
                traced = null;
            }
            pending = null;
            if (traced != null && !traced.isEmpty() && !traced.get(0).isEmpty()) {
                tracedKey = pendingKey;
                adopt(traced);
            }
        }
        if (structure == null || structure.size() == 0) return;
        long key = key(structure);
        if (key == rejectedKey) return;
        if (key == tracedKey) {
            changeSeenAt = -1;
            return;
        }
        if (changeSeenAt < 0) changeSeenAt = now;
        int delay = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_SHELL_REBUILD_DELAY.get() : 40;
        if (!layers.isEmpty() && now - changeSeenAt < delay) return;
        LongSet blocks = new LongOpenHashSet(structure.size());
        structure.forEach(pos -> blocks.add(pos.asLong()));
        tracedBlocks = blocks;
        pendingKey = key;
        changeSeenAt = -1;
        List<CompletableFuture<ShellMesh>> traces = new ArrayList<>();
        for (int layer = 0; layer < layerCount; layer++) traces.add(ShellCache.trace(blocks, offset + layer, cellLimit, Util.backgroundExecutor()));
        pending = CompletableFuture.allOf(traces.toArray(CompletableFuture[]::new)).thenApply(ignored -> traces.stream().map(CompletableFuture::join).toList());
    }

    private long key(ShipStructure structure) {
        long key = structure.fingerprint() ^ Double.doubleToLongBits(offset) * 31;
        return key * 31 + layerCount * 1024L + cellLimit;
    }

    /** Takes a traced shell in: seats the drones afresh on its outermost layer, keeping every drone that still has a seat. */
    private void adopt(List<ShellMesh> traced) {
        layers = traced;
        ShellMesh outer = outer();
        int wanted = generator.dronesWanted();
        Vec3 seed = Vec3.atCenterOf(generator.getBlockPos()).add(0, offset + layerCount, 0);
        int[] chosen = outer.spread(wanted, seed);
        Vec3[] next = new Vec3[chosen.length];
        for (int index = 0; index < chosen.length; index++) next[index] = outer.vertex(chosen[index]);
        seats = next;
        // Drones whose seats are gone go home; the rest move to where their seat is now.
        for (EmitterDrone drone : new ArrayList<>(drones)) {
            if (drone.seat >= seats.length) {
                dismiss(drone);
            } else if (drone.holding()) {
                drone.place(seats[drone.seat], EmitterDrone.State.HOLDING);
            }
        }
        ShieldLayers next_integrity = new ShieldLayers(layerCount, seats.length, patchMax());
        if (loadedSeats > 0 && loadedIntegrity.length > 0) {
            // A saved shield: its patches as they were, where the seats still exist.
            for (int layer = 0; layer < layerCount; layer++) for (int seat = 0; seat < Math.min(seats.length, loadedSeats); seat++) {
                int index = layer * loadedSeats + seat;
                if (index < loadedIntegrity.length) next_integrity.set(layer, seat, loadedIntegrity[index]);
            }
            loadedIntegrity = new int[0];
            loadedSeats = 0;
        } else {
            for (int layer = 0; layer < Math.min(layerCount, integrity.layers()); layer++) {
                for (int seat = 0; seat < Math.min(seats.length, integrity.seats()); seat++) next_integrity.set(layer, seat, integrity.integrity(layer, seat));
            }
        }
        integrity = next_integrity;
        if (loadedDrones != null) {
            for (Tag tag : loadedDrones) {
                EmitterDrone saved = EmitterDrone.load((CompoundTag) tag, Vec3.ZERO, Vec3.ZERO);
                if (seatTaken(saved.seat)) continue;
                if (saved.seat < 0 || saved.seat >= seats.length) {
                    if (!saved.away() && generator.getLevel().getBlockEntity(saved.dock) instanceof ShipDeviceBlockEntity dock) dock.droneHome();
                    continue;
                }
                EmitterDrone drone = EmitterDrone.load((CompoundTag) tag, seats[saved.seat], dockPosition(saved.dock));
                drones.add(drone);
            }
            loadedDrones = null;
        }
        heldChanged = true;
        dirty = true;
    }

    private boolean seatTaken(int seat) {
        for (EmitterDrone drone : drones) if (drone.seat == seat) return true;
        return false;
    }

    private static Vec3 dockPosition(BlockPos dock) {
        return Vec3.atCenterOf(dock).add(0, .75, 0);
    }

    // --- drones ------------------------------------------------------------------------------------

    private boolean flying() {
        for (EmitterDrone drone : drones) if (drone.state() == EmitterDrone.State.LAUNCHING || drone.state() == EmitterDrone.State.RETURNING) return true;
        return false;
    }

    private void tickDrones(ServerLevel level, long now, boolean deploy) {
        boolean loaded = AddonConfig.SPEC.isLoaded();
        int chargeTicks = loaded ? AddonConfig.SHIP_DRONE_CHARGE_TICKS.get() : 200;
        int flightCost = loaded ? AddonConfig.SHIP_DRONE_FLIGHT_COST.get() : 20;
        // Seats without a drone get one from a dock that has a spare, while the shield is wanted.
        if (deploy) {
            for (int seat = 0; seat < seats.length; seat++) {
                if (seatTaken(seat)) continue;
                // The first dock with a spare drone that can pay for the flight; a dock out of charge keeps its drones.
                ShipDeviceBlockEntity dock = null;
                for (BlockPos pos : generator.docks()) {
                    if (level.getBlockEntity(pos) instanceof ShipDeviceBlockEntity candidate && candidate.role().isDroneDock()
                            && candidate.spareDrones() > 0 && candidate.droneCount() > ofDock(pos)
                            && DevicePower.drain(null, candidate.device(), flightCost)) {
                        dock = candidate;
                        break;
                    }
                }
                if (dock == null) break;
                dock.sendDrone();
                EmitterDrone drone = new EmitterDrone(seat, dock.getBlockPos(), dockPosition(dock.getBlockPos()));
                drone.fly(seats[seat], false);
                drones.add(drone);
                dirty = true;
            }
        }
        for (EmitterDrone drone : new ArrayList<>(drones)) {
            ShipDeviceBlockEntity dock = level.getBlockEntity(drone.dock) instanceof ShipDeviceBlockEntity entity && entity.role().isDroneDock() ? entity : null;
            // A dock that is gone takes its drones with it; one whose drones were taken out loses those at home first.
            if (dock == null || dock.droneCount() < ofDock(drone.dock) && (drone.away() || dock.droneCount() < awayFrom(drone.dock))) {
                drones.remove(drone);
                if (dock != null && !drone.away()) dock.droneHome();
                heldChanged = true;
                continue;
            }
            switch (drone.state()) {
                case LAUNCHING, RETURNING -> {
                    boolean landed = drone.tickFlight();
                    if (landed) heldChanged = true;
                    if (landed && drone.away()) dock.droneHome();
                    if (drone.holding() && !deploy) {
                        drone.fly(dockPosition(drone.dock), true);
                        heldChanged = true;
                    }
                }
                case HOLDING -> {
                    drone.drain(EmitterDrone.HOLD_DRAIN);
                    if (!deploy || drone.critical()) {
                        DevicePower.drain(null, dock.device(), flightCost);
                        drone.fly(dockPosition(drone.dock), true);
                        heldChanged = true;
                    }
                }
                case DOCKED, CHARGING -> {
                    if (!drone.ready()) {
                        // Charging costs the dock a point a tick; an empty dock leaves the drone waiting.
                        if (DevicePower.drain(null, dock.device(), 1)) drone.recharge(1F / chargeTicks);
                    } else if (deploy && drone.seat < seats.length && DevicePower.drain(null, dock.device(), flightCost)) {
                        dock.sendDrone();
                        drone.fly(seats[drone.seat], false);
                    }
                }
            }
        }
    }

    /** Drones of this shield that belong to a dock, and how many of them are away from it. */
    private int ofDock(BlockPos dock) {
        int count = 0;
        for (EmitterDrone drone : drones) if (drone.dock.equals(dock)) count++;
        return count;
    }

    private int awayFrom(BlockPos dock) {
        int count = 0;
        for (EmitterDrone drone : drones) if (drone.dock.equals(dock) && !drone.away()) count++;
        return count;
    }

    /** A drone leaves the shield for good (its seat is gone): back to its dock's count. */
    private void dismiss(EmitterDrone drone) {
        drones.remove(drone);
        if (!drone.away() && generator.getLevel() != null && generator.getLevel().getBlockEntity(drone.dock) instanceof ShipDeviceBlockEntity dock) dock.droneHome();
        heldChanged = true;
    }

    /** Every drone home: the generator switched off, the shield overloaded, or the block broken. */
    public void recall() {
        for (EmitterDrone drone : drones) {
            if (drone.holding() || drone.state() == EmitterDrone.State.LAUNCHING) {
                drone.fly(dockPosition(drone.dock), true);
            }
        }
        heldChanged = true;
        dirty = true;
    }

    /** The drones' claims on their docks given back (the generator block is gone). */
    public void release(ServerLevel level) {
        if (loadedDrones != null) {
            for (Tag entry : loadedDrones) {
                CompoundTag saved = (CompoundTag) entry;
                int state = saved.getByte("state");
                if (state != EmitterDrone.State.DOCKED.ordinal() && state != EmitterDrone.State.CHARGING.ordinal()
                        && level.getBlockEntity(BlockPos.of(saved.getLong("dock"))) instanceof ShipDeviceBlockEntity dock) dock.droneHome();
            }
            loadedDrones = null;
        }
        for (EmitterDrone drone : drones) {
            if (!drone.away() && level.getBlockEntity(drone.dock) instanceof ShipDeviceBlockEntity dock) dock.droneHome();
        }
        drones.clear();
        ShipShieldFields.remove(level, generator);
    }

    public @Nullable EmitterDrone drone(int seat) {
        for (EmitterDrone drone : drones) if (drone.seat == seat) return drone;
        return null;
    }

    // --- blows -------------------------------------------------------------------------------------

    /**
     * A blow of {@code cost} integrity at a point of the outermost shell (structure coordinates).
     * What the shield could not hold is in the strike's {@code passed}.
     */
    public ShieldLayers.Strike hit(Vec3 local, int cost, long now) {
        int seat = patches == null ? -1 : patches.seatAt(local);
        if (seat < 0 || cost <= 0) return new ShieldLayers.Strike(0, Math.max(0, cost), false, 0, List.of());
        int rings = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_SPREAD_RINGS.get() : 2;
        ShieldLayers.Strike strike = integrity.strike(seat, cost, patches.neighbours(), rings);
        if (strike.absorbed() > 0) {
            int broken = 0;
            for (ShieldLayers.Drain drain : strike.drains()) {
                if (integrity.integrity(drain.layer(), drain.seat()) == 0) broken |= 1 << drain.layer();
            }
            if (impacts.size() >= ShipShieldImpact.MAX) impacts.removeFirst();
            impacts.add(new ShipShieldImpact(local.subtract(Vec3.atLowerCornerOf(generator.getBlockPos())), now, strike.absorbed(), broken, strike.overloaded()));
            if (generator.getLevel() instanceof ServerLevel world) {
                var role = switch (generator.family()) {
                    case RF -> dev.hurtify.relicsaddon.relic.RelicRole.RF_SHIELD;
                    case MANA -> dev.hurtify.relicsaddon.relic.RelicRole.MANA_SHIELD;
                    case TWINS -> dev.hurtify.relicsaddon.relic.RelicRole.TWINS_SHIELD;
                };
                Vec3 contact = ShipStructures.worldPosition(world, local);
                dev.hurtify.relicsaddon.sound.RelicSounds.shield(world, contact, role, broken != 0, strike.overloaded());
                dev.hurtify.relicsaddon.sound.RelicSounds.ripple(world, contact, role);
            }
        }
        lastHitAt = now;
        dirty = true;
        if (strike.absorbed() > 0) {
            DevicePower.drain(null, generator.device(), strike.absorbed() * DevicePower.ABSORB_PER_HP);
            ServerPlayer owner = generator.ownerOnline();
            if (owner != null) RelicRuntime.awardAbsorption(owner, generator.device(), strike.absorbed());
        }
        for (ShieldLayers.Drain drain : strike.drains()) {
            EmitterDrone drone = drone(drain.seat());
            if (drone == null) continue;
            drone.drain(EmitterDrone.HIT_DRAIN * drain.amount());
            if (integrity.integrity(drain.layer(), drain.seat()) == 0) drone.wear(EmitterDrone.STRIP_WEAR);
        }
        if (strike.overloaded()) {
            int restart = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_OVERLOAD_RESTART.get() : 600;
            overloadedUntil = now + restart;
            recall();
        }
        if (strike.passed() > 0) passedAt = now;
        return strike;
    }

    /** Patches win a point back each interval once the shield has been quiet, paid from the generator's batteries. */
    private void mend(long now) {
        int interval = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_PATCH_REPAIR_INTERVAL.get() : 20;
        if (now - lastHitAt < QUIET_TICKS || now - repairedAt < interval || integrity.total() >= integrity.capacity()) return;
        repairedAt = now;
        int want = Math.max(1, seats.length);
        int affordable = Math.min(want, DevicePower.usablePoints(generator.device()) / DevicePower.REPAIR_PER_HP);
        if (affordable <= 0) return;
        int given = integrity.repair(affordable);
        if (given > 0) {
            DevicePower.drain(null, generator.device(), given * DevicePower.REPAIR_PER_HP);
            dirty = true;
        }
    }

    /** Hostile mobs that got under the shell are shoved back out, as a worn shield does. */
    private void pushMobs(ServerLevel level, long now) {
        ShipShieldFields.Field field = ShipShieldFields.of(level, generator);
        if (field == null) return;
        AABB area = field.worldBounds().inflate(1);
        for (net.minecraft.world.entity.Mob mob : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, area, mob -> mob.isAlive() && !field.friendly(mob)
                && (mob instanceof net.minecraft.world.entity.monster.Enemy || mob.getTarget() instanceof net.minecraft.world.entity.player.Player))) {
            Vec3 local = field.local(mob.getBoundingBox().getCenter(), now);
            double depth = depth(local);
            if (depth <= 0) continue;
            if (!DevicePower.drain(null, generator.device(), PUSH_COST)) return;
            Vec3 out = field.frame().turn(outward(local)).normalize();
            Vec3 horizontal = new Vec3(out.x, 0, out.z);
            if (horizontal.lengthSqr() < 1e-6) horizontal = new Vec3(1, 0, 0);
            horizontal = horizontal.normalize();
            if (!mob.isMultipartEntity()) mob.move(net.minecraft.world.entity.MoverType.SELF, out.scale(Math.min(depth + mob.getBbWidth() * .5, 1.5)));
            Vec3 motion = mob.getDeltaMovement();
            if (motion.dot(out) < .35) mob.setDeltaMovement(horizontal.scale(.35).add(0, Math.max(motion.y, out.y > .5 ? .3 : .05), 0));
            mob.hurtMarked = true;
        }
    }

    // --- sync and saving ---------------------------------------------------------------------------

    public ShipShieldView view(long now) {
        BlockPos origin = generator.getBlockPos();
        List<Float> seatList = new ArrayList<>(seats.length * 3);
        for (Vec3 seat : seats) { seatList.add((float) (seat.x - origin.getX())); seatList.add((float) (seat.y - origin.getY())); seatList.add((float) (seat.z - origin.getZ())); }
        List<Float> droneList = new ArrayList<>(seats.length * 3);
        List<Integer> states = new ArrayList<>(seats.length);
        for (int seat = 0; seat < seats.length; seat++) {
            EmitterDrone drone = drone(seat);
            Vec3 at = drone == null ? seats[seat] : drone.at();
            droneList.add((float) (at.x - origin.getX())); droneList.add((float) (at.y - origin.getY())); droneList.add((float) (at.z - origin.getZ()));
            states.add(drone == null ? EmitterDrone.State.DOCKED.ordinal() : drone.state().ordinal());
        }
        List<Integer> integrityList = new ArrayList<>(integrity.layers() * integrity.seats());
        for (int layer = 0; layer < integrity.layers(); layer++) for (int seat = 0; seat < integrity.seats(); seat++) integrityList.add(integrity.integrity(layer, seat));
        return new ShipShieldView(up(now), offset, layerCount, cellLimit, overloaded(now) ? overloadedUntil : 0, integrity.max(), origin, seatList, droneList, states, integrityList, raisedAt, impacts);
    }

    public void save(CompoundTag tag, long now) {
        ListTag list = new ListTag();
        for (EmitterDrone drone : drones) {
            CompoundTag entry = new CompoundTag();
            drone.save(entry);
            list.add(entry);
        }
        tag.put("drones", list);
        tag.putInt("seats", integrity.seats());
        int[] values = new int[integrity.layers() * integrity.seats()];
        for (int layer = 0; layer < integrity.layers(); layer++) for (int seat = 0; seat < integrity.seats(); seat++) values[layer * integrity.seats() + seat] = integrity.integrity(layer, seat);
        tag.putIntArray("integrity", values);
        if (overloaded(now)) tag.putLong("overload_left", overloadedUntil - now);
    }

    public void load(CompoundTag tag, long now) {
        loadedDrones = tag.getList("drones", Tag.TAG_COMPOUND);
        loadedSeats = tag.getInt("seats");
        loadedIntegrity = tag.getIntArray("integrity");
        overloadedUntil = tag.contains("overload_left") ? now + tag.getLong("overload_left") : NEVER;
    }

    /** The notice the generator shows for its shield, or empty. */
    public String notice(long now) {
        if (overloaded(now)) return NOTICE_OVERLOADED;
        if (!layers.isEmpty() && drones.isEmpty() && seats.length > 0) return NOTICE_NO_DRONES;
        if (!layers.isEmpty() && drones.size() < seats.length) return NOTICE_SHORT_DRONES;
        return "";
    }

    public String noticeDetail(long now) {
        if (overloaded(now)) return String.valueOf((overloadedUntil - now + 19) / 20);
        return drones.size() + "/" + seats.length;
    }
}
