package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Pure geometry and bookkeeping guard for everything the server and the renderer share about a swarm. */
public final class HiveFormationCheck {
    public static void main(String[] args) {
        Vec3 owner = new Vec3(3, 64, -2), target = new Vec3(-4, 63, 7);
        Vec3 core = HiveFormation.core(target, 1.9);

        // Docked drones sit inside the belt; healers ring the owner's chest and recall to the same slots.
        for (HiveType type : HiveType.values()) for (int count : new int[]{1, 12, 250, HiveType.MAX_DRONES}) {
            for (int index = 0; index < count; index += Math.max(1, count / 40)) {
                Vec3 idle = HiveFormation.idle(owner, 35, index, count, type, 187.25);
                require(idle.distanceToSqr(HiveFormation.belt(owner, 35, type)) < .003, "docked drones must sit inside the belt");
                Vec3 healed = HiveFormation.healing(owner, 35, index, count, type, 187.25, 1);
                double reach = Math.hypot(healed.x - owner.x, healed.z - owner.z);
                require(reach >= 1.1 && reach <= 2.8, "healers ring the owner outside arm's reach: " + reach);
                require(healed.y - owner.y > .3 && healed.y - owner.y < 1.75, "healers circle the owner's chest");
                require(HiveFormation.healing(owner, 35, index, count, type, 187.25, 0).equals(idle), "healers recall to their belt slots");
            }
        }

        // Every mode keeps every place finite, near the target and apart from the others.
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slots : new int[]{1, 2, 12, 16, 17, 100, 250}) {
            for (double time : new double[]{0, 187.25, 5_000.5, 3_000_000.75}) {
                Vec3[] at = new Vec3[slots];
                for (int slot = 0; slot < slots; slot++) {
                    at[slot] = HiveFormation.station(mode, type, slot, slots, target, 1.1, 1.9, time, 100, 60);
                    requireFinite(at[slot], mode + " station");
                    double limit = mode == AttackMode.CONTAINMENT ? 4 : 15;
                    require(at[slot].distanceTo(core) < limit, mode + " " + type + " place " + slot + "/" + slots + " strayed " + at[slot].distanceTo(core));
                    Vec3 later = HiveFormation.station(mode, type, slot, slots, target, 1.1, 1.9, time + .001, 100, 60);
                    require(at[slot].distanceTo(later) < .05, mode + " " + type + " stations must move smoothly");
                }
                if (mode != AttackMode.DROPLET || time < 100) for (int a = 0; a < slots; a++) for (int b = a + 1; b < slots; b++) {
                    require(at[a].distanceToSqr(at[b]) > 1e-8, mode + " " + type + " places " + a + " and " + b + " coincide");
                }
            }
        }

        // Drones fly out from their hive slot and land exactly on their station, smoothly in between.
        for (HiveType type : HiveType.values()) for (int unit = 0; unit < 60; unit++) {
            Vec3 station = HiveFormation.station(AttackMode.BARRAGE, type, unit, 60, target, 1.1, 1.9, 150, 100, 60);
            require(HiveFormation.deployed(owner, 35, station, unit, 60, type, 150, 150, 30).equals(HiveFormation.idle(owner, 35, unit, 60, type, 150)),
                    "a drone sets off from its hive slot");
            require(HiveFormation.deployed(owner, 35, station, unit, 60, type, 180, 150, 30).equals(station), "a drone lands on its station");
            Vec3 previous = null;
            for (double t = 150; t <= 180; t += .25) {
                Vec3 at = HiveFormation.deployed(owner, 35, station, unit, 60, type, t, 150, 30);
                if (previous != null) require(previous.distanceTo(at) < 2.5, "a launch must not jump");
                previous = at;
            }
            Vec3 home = HiveFormation.returning(owner, 35, station, unit, 60, type, 150 + HiveFormation.RETURN_TICKS, 150);
            require(home.equals(HiveFormation.idle(owner, 35, unit, 60, type, 150 + HiveFormation.RETURN_TICKS)), "a hit drone ends back in the hive");
        }

        // Droplet: every group dives through the target exactly when its blow is due.
        for (int groups = 2; groups <= 16; groups++) for (int group = 0; group < groups; group++) {
            int hits = 0;
            long started = 100 + Math.round(60.0 * group / groups);
            require(HiveFormation.groupPhase(started - 1, 100, 60, group, groups) < 0, "a group hovers until its own start");
            for (long now = started; now < started + 60 * 5; now++) {
                if (!HiveFormation.passes(now, 100, 60, group, groups, HiveFormation.IMPACT)) continue;
                hits++;
                Vec3 centre = HiveFormation.dropletCentre(target, 1.1, 1.9, group, groups, now, 100, 60);
                require(centre.distanceTo(core) < .01, "a group's blow lands when its shape reaches the target (" + centre.distanceTo(core) + ")");
            }
            require(hits == 5, "one blow per group per cycle, got " + hits);
            require(HiveFormation.post(target, 1.1, 1.9, group, groups, 0, 4.2).distanceTo(core) > 4, "groups wait well clear of the target");
        }

        // Lanes: a hit drone hands its place to the next one at once, and a repaired one waits in reserve.
        HiveSettings settings = HiveSettings.DEFAULT;
        List<HiveStackState.Unit> units = new ArrayList<>();
        for (int index = 0; index < HiveType.MAX_DRONES; index++) units.add(HiveStackState.Unit.fresh());
        int slots = HiveSlots.fighterSlots(units.size(), settings), fighters = settings.fighters(units.size());
        require(slots == HiveType.MAX_DEPLOYED, "at most 250 drones fly");
        require(HiveSlots.occupant(units, 7, slots, fighters, 1000) == 7, "a place starts with the first drone of its lane");
        units.set(7, units.get(7).hit(1, 1000, 1200));
        require(HiveSlots.occupant(units, 7, slots, fighters, 1000) == 7 + slots, "the lane's next drone takes over at once");
        require(HiveSlots.since(units, 7, slots, fighters, 7 + slots, 1000) == 1000, "the replacement set off when the first was hit");
        require(HiveSlots.occupant(units, 7, slots, fighters, 1300) == 7 + slots, "a repaired drone does not bump its replacement");
        units.set(7 + slots, units.get(7 + slots).hit(3, 1400, 1600));
        require(HiveSlots.occupant(units, 7, slots, fighters, 1400) == 7 + 2 * slots, "the lane keeps rotating");
        require(HiveSlots.groups(250) == 16 && HiveSlots.groups(12) == 2 && HiveSlots.groups(1) == 1, "two to sixteen strike groups");
        int total = 0;
        for (int group = 0; group < 16; group++) total += HiveSlots.groupSize(group, 250, 16);
        require(total == 250, "every place belongs to exactly one group");
        System.out.println("Hive formation: every mode bounded, smooth and separated; launches land; blows due on time; lanes rotate");
    }

    private static void requireFinite(Vec3 point, String what) { require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z), what + " must be finite"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
