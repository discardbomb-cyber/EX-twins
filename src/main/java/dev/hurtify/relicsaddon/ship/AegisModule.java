package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.AddonConfig;
import java.util.ArrayList;
import java.util.List;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * AEGIS: six drones, each alone at its own place on a shell round the whole ship, hold up a shield between them. The
 * shield stops shots flying in at the ship (cannon shells too), takes the blows meant for the crew from outside it and
 * keeps explosions outside from tearing at the hull, paying for each from its charge. The charge comes back from the
 * hive's FE once nothing has struck it for a few seconds; a blow bigger than what is left breaks the shield, which then
 * takes a few seconds to come back up. The drones lean towards the side the ship's threats are on. A hive newly set
 * down raises an empty shield, which fills from its FE.
 */
public final class AegisModule implements ShipModule {
    /** A full shield's charge; how long a broken one takes to come back up, and with how much. */
    public static final int FULL = 400, REBOOT = 100, REBOOT_CHARGE = FULL / 4;
    /** Quiet ticks before the charge comes back, charge regained a tick, and hits remembered for the drawing. */
    private static final int REGEN_DELAY = 60, REGEN = 2, HITS = 8, HIT_TICKS = 40;
    private static final long NEVER = Long.MIN_VALUE / 4;

    /** A hit on the shell: where (a unit direction in the shield's unit terms), when, and how hard (0 to 1; 1 broke it). */
    public record Hit(Vec3 unit, long at, float strength) {
    }

    // Kept with the hive, and sent to clients.
    private int charge;
    private boolean down;
    private long downAt = NEVER;

    // Sent to clients only.
    private boolean raised;
    private long changedAt = NEVER;
    /** Which way the ship's threats lie, in the shield's unit terms, as long as how pressing they are (up to 1). */
    private Vec3 lean = Vec3.ZERO;
    private final List<Hit> hits = new ArrayList<>();

    // Server only.
    private long hitAt = NEVER;
    private boolean unpowered;

    @Override
    public void tick(ShipHiveBlockEntity hive, ServerLevel level, ShipFrame frame, ShipBrain brain, long now) {
        int bucket = charge * 10 / FULL;
        boolean changed = hits.removeIf(hit -> now - hit.at() > HIT_TICKS || hit.at() > now);
        if (down && now - downAt >= REBOOT) {
            down = false;
            charge = REBOOT_CHARGE;
            changed = true;
        }
        boolean on = hive.switchedOn(level);
        boolean up = on && !down && hive.draw(upkeep());
        unpowered = on && !down && !up;
        if (up != raised) {
            raised = up;
            changedAt = now;
            changed = true;
            if (up) RelicSounds.ship(level, frame.middle(), RelicSounds.Ship.AEGIS_RAISE, 1.5F, 1);
        }
        if (raised) {
            if (now - hitAt >= REGEN_DELAY && charge < FULL && hive.draw(regenCost())) charge = Math.min(FULL, charge + REGEN);
            AegisShape shape = AegisShape.of(frame, hive.getBlockPos());
            AegisFields.put(level, hive, this, frame, shape, brain, now);
            if (now % 10 == 0) changed |= steer(level, frame, shape, brain, now);
        } else if (lean.lengthSqr() > 0) {
            lean = Vec3.ZERO;
            changed = true;
        }
        if (changed || charge * 10 / FULL != bucket) hive.changed();
    }

    /** Leans the drones towards the ship's threats, the worse and nearer the harder; true if the lean moved much. */
    private boolean steer(ServerLevel level, ShipFrame frame, AegisShape shape, ShipBrain brain, long now) {
        Vec3 sum = Vec3.ZERO;
        double weight = 0;
        for (ShipBrain.Threat threat : brain.threats(now)) {
            Vec3 unit = shape.unitOfWorld(frame, threat.entity().getBoundingBox().getCenter());
            double length = unit.length();
            if (length < 1e-6) continue;
            double pull = threat.score() / (1 + .15 * Math.max(0, length - 1) * shape.reach());
            sum = sum.add(unit.scale(pull / length));
            weight += pull;
        }
        Vec3 next = weight <= 0 ? Vec3.ZERO : sum.scale(1 / weight).scale(Math.min(1, weight / 40));
        if (next.distanceTo(lean) < .15) return false;
        lean = next;
        return true;
    }

    /**
     * A blow of {@code cost} arriving through the shell at {@code unit} (unit terms): paid from the charge and stopped,
     * or, when it is more than is left, it breaks the shield and gets through. False when the shield is not up.
     */
    public boolean absorb(ShipHiveBlockEntity hive, Vec3 unit, float cost, long now) {
        if (!raised || down) return false;
        Vec3 where = unit.lengthSqr() < 1e-9 ? new Vec3(0, 1, 0) : unit.normalize();
        hitAt = now;
        boolean holds = cost <= charge;
        charge = holds ? charge - (int) Math.ceil(cost) : 0;
        if (hits.size() >= HITS) hits.removeFirst();
        hits.add(new Hit(where, now, holds ? Math.min(1, cost / 20F) : 1));
        if (charge <= 0) {
            charge = 0;
            if (hive.getLevel() instanceof ServerLevel level) {
                RelicSounds.ship(level, ShipFrame.of(hive).middle(), RelicSounds.Ship.AEGIS_BREAK, 2, 1);
            }
            down = true;
            downAt = now;
            raised = false;
            changedAt = now;
        }
        hive.changed();
        return holds;
    }

    private static int upkeep() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.AEGIS_UPKEEP.get() : 30;
    }

    private static int regenCost() {
        return REGEN * (AddonConfig.SPEC.isLoaded() ? AddonConfig.AEGIS_ENERGY_PER_POINT.get() : 150);
    }

    @Override
    public ShipStatus.Line status() {
        if (down) return ShipStatus.AEGIS_DOWN.line();
        if (unpowered) return ShipStatus.UNPOWERED.line();
        if (!raised) return ShipStatus.WATCHING.line();
        return ShipStatus.AEGIS_UP.with(charge * 100 / FULL);
    }

    public int charge() {
        return charge;
    }

    public boolean raised() {
        return raised;
    }

    public boolean down() {
        return down;
    }

    /** The tick the shield last went up or down. */
    public long changedAt() {
        return changedAt;
    }

    public Vec3 lean() {
        return lean;
    }

    public List<Hit> hits() {
        return hits;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Charge", charge);
        tag.putBoolean("Down", down);
        tag.putLong("DownAt", downAt);
    }

    @Override
    public void load(CompoundTag tag) {
        charge = Math.clamp(tag.getInt("Charge"), 0, FULL);
        down = tag.getBoolean("Down");
        downAt = tag.contains("DownAt") ? tag.getLong("DownAt") : NEVER;
    }

    @Override
    public void saveSync(CompoundTag tag) {
        tag.putBoolean("Raised", raised);
        tag.putLong("ChangedAt", changedAt);
        tag.putDouble("LeanX", lean.x);
        tag.putDouble("LeanY", lean.y);
        tag.putDouble("LeanZ", lean.z);
        ListTag list = new ListTag();
        for (Hit hit : hits) {
            CompoundTag entry = new CompoundTag();
            entry.putFloat("X", (float) hit.unit().x);
            entry.putFloat("Y", (float) hit.unit().y);
            entry.putFloat("Z", (float) hit.unit().z);
            entry.putLong("At", hit.at());
            entry.putFloat("S", hit.strength());
            list.add(entry);
        }
        tag.put("Hits", list);
    }

    @Override
    public void loadSync(CompoundTag tag) {
        raised = tag.getBoolean("Raised");
        changedAt = tag.contains("ChangedAt") ? tag.getLong("ChangedAt") : NEVER;
        Vec3 read = new Vec3(tag.getDouble("LeanX"), tag.getDouble("LeanY"), tag.getDouble("LeanZ"));
        lean = Double.isFinite(read.lengthSqr()) && read.lengthSqr() <= 1.0001 ? read : Vec3.ZERO;
        hits.clear();
        ListTag list = tag.getList("Hits", Tag.TAG_COMPOUND);
        for (int k = 0; k < list.size() && k < HITS; k++) {
            CompoundTag entry = list.getCompound(k);
            Vec3 unit = new Vec3(entry.getFloat("X"), entry.getFloat("Y"), entry.getFloat("Z"));
            if (unit.lengthSqr() < .5 || unit.lengthSqr() > 1.5) continue;
            hits.add(new Hit(unit, entry.getLong("At"), Math.clamp(entry.getFloat("S"), 0, 1)));
        }
    }
}
