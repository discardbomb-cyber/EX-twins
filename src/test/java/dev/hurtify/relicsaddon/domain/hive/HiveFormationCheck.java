package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** Pure geometry and bookkeeping guard for everything the server and the renderer share about a swarm. */
public final class HiveFormationCheck {
    public static void main(String[] args) {
        Vec3d owner = new Vec3d(3, 64, -2), target = new Vec3d(-4, 63, 7);
        Vec3d core = HiveFormation.core(target, 1.9);

        // Docked drones sit inside the belt; healers ring the owner's chest and recall to the same slots.
        for (HiveType type : HiveType.values()) for (int count : new int[]{1, 12, 250, HiveType.MAX_DRONES}) {
            for (int index = 0; index < count; index += Math.max(1, count / 40)) {
                Vec3d idle = HiveFormation.idle(owner, 35, index, count, type, 187.25);
                require(idle.distanceToSqr(HiveFormation.belt(owner, 35, type)) < .003, "docked drones must sit inside the belt");
                Vec3d healed = HiveFormation.healing(owner, 35, index, count, type, 187.25, 1);
                double reach = Math.hypot(healed.x - owner.x, healed.z - owner.z);
                require(reach >= 1.1 && reach <= 2.8, "healers ring the owner outside arm's reach: " + reach);
                require(healed.y - owner.y > .3 && healed.y - owner.y < 1.75, "healers circle the owner's chest");
                require(HiveFormation.healing(owner, 35, index, count, type, 187.25, 0).equals(idle), "healers recall to their belt slots");
            }
        }

        // Every mode keeps every place finite, near where it belongs and apart from the others.
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slots : new int[]{1, 2, 12, 16, 17, 100, 250}) {
            for (double time : new double[]{0, 187.25, 5_000.5, 3_000_000.75}) {
                Vec3d[] at = new Vec3d[slots];
                for (int slot = 0; slot < slots; slot++) {
                    at[slot] = HiveFormation.station(mode, type, slot, slots, owner, target, 1.1, 1.9, time, 100, 60);
                    requireFinite(at[slot], mode + " station");
                    double limit = switch (mode) {
                        case CONTAINMENT -> 4;
                        case BARRAGE -> 9;
                        case DROPLET -> owner.distanceTo(core) + 14;
                    };
                    require(at[slot].distanceTo(core) < limit, mode + " " + type + " place " + slot + "/" + slots + " strayed " + at[slot].distanceTo(core));
                    Vec3d later = HiveFormation.station(mode, type, slot, slots, owner, target, 1.1, 1.9, time + .001, 100, 60);
                    require(at[slot].distanceTo(later) < .05, mode + " " + type + " stations must move smoothly");
                }
                if (mode != AttackMode.DROPLET || time < 100) for (int a = 0; a < slots; a++) for (int b = a + 1; b < slots; b++) {
                    require(at[a].distanceToSqr(at[b]) > 1e-8, mode + " " + type + " places " + a + " and " + b + " coincide");
                }
            }
        }

        // Drones fly out from their hive slot and land exactly on their station, smoothly in between.
        for (HiveType type : HiveType.values()) for (int unit = 0; unit < 60; unit++) {
            Vec3d station = HiveFormation.station(AttackMode.BARRAGE, type, unit, 60, owner, target, 1.1, 1.9, 150, 100, 60);
            require(HiveFormation.deployed(owner, 35, station, unit, 60, type, 150, 150, 30).equals(HiveFormation.idle(owner, 35, unit, 60, type, 150)),
                    "a drone sets off from its hive slot");
            require(HiveFormation.deployed(owner, 35, station, unit, 60, type, 180, 150, 30).equals(station), "a drone lands on its station");
            Vec3d previous = null;
            for (double t = 150; t <= 180; t += .25) {
                Vec3d at = HiveFormation.deployed(owner, 35, station, unit, 60, type, t, 150, 30);
                if (previous != null) require(previous.distanceTo(at) < 2.5, "a launch must not jump");
                previous = at;
            }
            Vec3d home = HiveFormation.returning(owner, 35, station, unit, 60, type, 150 + HiveFormation.RETURN_TICKS, 150);
            require(home.equals(HiveFormation.idle(owner, 35, unit, 60, type, 150 + HiveFormation.RETURN_TICKS)), "a hit drone ends back in the hive");
        }

        // Droplet: every group forms up in its owner's fan, flies out and lands its blow exactly on the target, then comes back.
        for (int groups = 2; groups <= 16; groups++) {
            for (int a = 0; a < groups; a++) for (int b = a + 1; b < groups; b++) {
                double apart = HiveFormation.muster(owner, target, a, groups, 50).distanceTo(HiveFormation.muster(owner, target, b, groups, 50));
                require(apart > 2.3, "figures " + a + " and " + b + " of " + groups + " form up too close: " + apart);
            }
            for (int group = 0; group < groups; group++) {
                int hits = 0;
                long started = 100 + Math.round(60.0 * group / groups);
                require(HiveFormation.groupPhase(started - 1, 100, 60, group, groups) < 0, "a group waits until its own start");
                Vec3d home = HiveFormation.muster(owner, target, group, groups, 0);
                require(home.distanceTo(owner) < 11 && home.y > owner.y + 1.8, "figures form up above and around their owner: " + home.subtract(owner));
                require(home.distanceTo(core) > owner.distanceTo(core), "figures form up behind their owner, not in the way");
                require(HiveFormation.dropletCentre(owner, target, 1.9, group, groups, started - 1, 100, 60).equals(HiveFormation.muster(owner, target, group, groups, started - 1)),
                        "a figure waits in the fan before its first sortie");
                for (long now = started; now < started + 60 * 5; now++) {
                    if (!HiveFormation.passes(now, 100, 60, group, groups, HiveFormation.IMPACT)) continue;
                    hits++;
                    Vec3d centre = HiveFormation.dropletCentre(owner, target, 1.9, group, groups, now, 100, 60);
                    require(centre.distanceTo(core) < .01, "a group's blow lands when its figure reaches the target (" + centre.distanceTo(core) + ")");
                    long flight = Math.round(HiveFormation.flightTicks(HiveFormation.muster(owner, target, group, groups, now).distanceTo(core), 60));
                    // The blow lands on the first whole tick past the impact and the flight is rounded, so the sound may trail the launch by up to two ticks.
                    require(HiveFormation.sortie(owner, target, 1.9, group, groups, now - flight - 2, 100, 60) < 0,
                            "the launch sound plays as the figure leaves the fan: " + groups + "/" + group + " at " + now);
                }
                require(hits == 5, "one blow per group per cycle, got " + hits);
                Vec3d previous = null;
                for (double t = started; t < started + 120; t += .25) {
                    Vec3d centre = HiveFormation.dropletCentre(owner, target, 1.9, group, groups, t, 100, 60);
                    if (previous != null) require(previous.distanceTo(centre) < 1.5, "a figure flies, it does not jump (" + previous.distanceTo(centre) + " at " + t + ")");
                    previous = centre;
                }
            }
        }

        // Barrage: dense clumps that stay apart, each drone inside its own clump, joined into the family's pattern.
        for (HiveType type : HiveType.values()) for (int groups = 1; groups <= 16; groups++) {
            int members = Math.max(1, 250 / groups);
            double radius = HiveFormation.clumpRadius(members);
            for (double time : new double[]{0, 400.5, 77_777.25}) {
                for (int a = 0; a < groups; a++) for (int b = a + 1; b < groups; b++) {
                    double apart = HiveFormation.clusterCentre(type, target, 1.1, 1.9, a, groups, time)
                            .distanceTo(HiveFormation.clusterCentre(type, target, 1.1, 1.9, b, groups, time));
                    require(apart > radius * 2.9, type + " clumps " + a + " and " + b + " of " + groups + " touch: " + apart);
                }
                for (int member = 0; member < members; member += Math.max(1, members / 30)) {
                    Vec3d offset = dev.hurtify.relicsaddon.domain.hive.HiveShapes.clump(type, member, members, time, new Vec3d(1, -.3, .2), radius);
                    double limit = type == HiveType.TWINS ? radius * 1.25 : radius * 1.08;
                    require(offset.length() < limit, type + " clump member strays: " + offset.length() + " of " + radius);
                    if (type != HiveType.TWINS) require(offset.length() > radius * .5, "RF and Mana clumps leave their middle to the charge");
                }
            }
            int[][] links = HiveFormation.clusterLinks(type, groups);
            require(groups < 2 || links.length >= groups - 1, type + " pattern links every clump");
            for (int[] link : links) require(link[0] != link[1] && link[0] >= 0 && link[1] < groups, "links join two different clumps");
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

    private static void requireFinite(Vec3d point, String what) { require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z), what + " must be finite"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
