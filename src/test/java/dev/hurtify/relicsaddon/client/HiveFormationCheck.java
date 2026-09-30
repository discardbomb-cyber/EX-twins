package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFlightPlan;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveTarget;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.ManaArmageddon;
import dev.hurtify.relicsaddon.drone.RfArmageddon;
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

        // Every mode keeps every place finite, near where it belongs and apart from the others.
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slots : new int[]{1, 2, 12, 16, 17, 100, 250}) {
            for (double time : new double[]{0, 187.25, 5_000.5, 3_000_000.75}) {
                Vec3[] at = new Vec3[slots];
                for (int slot = 0; slot < slots; slot++) {
                    at[slot] = HiveFormation.station(mode, type, slot, slots, owner, target, 1.1, 1.9, time, 100, 60);
                    requireFinite(at[slot], mode + " station");
                    double limit = switch (mode) {
                        case CONTAINMENT -> 12;
                        case BARRAGE -> 9;
                        case DROPLET -> owner.distanceTo(core) + 14;
                    };
                    require(at[slot].distanceTo(core) < limit, mode + " " + type + " place " + slot + "/" + slots + " strayed " + at[slot].distanceTo(core));
                    Vec3 later = HiveFormation.station(mode, type, slot, slots, owner, target, 1.1, 1.9, time + .001, 100, 60);
                    require(at[slot].distanceTo(later) < .05, mode + " " + type + " stations must move smoothly");
                }
                if (mode != AttackMode.DROPLET || time < 100) for (int a = 0; a < slots; a++) for (int b = a + 1; b < slots; b++) {
                    require(at[a].distanceToSqr(at[b]) > 1e-8, mode + " " + type + " places " + a + " and " + b + " coincide");
                }
            }
        }

        // Containment constructs rest on the ground: no drone of an unlifted target's construct is below its feet.
        for (HiveType type : HiveType.values()) for (int slots : new int[]{1, 12, 100, 250}) for (double height : new double[]{.5, 1.8, 2.9}) {
            for (int slot = 0; slot < slots; slot++) {
                // RF rings stay round the target, whose hold lifts it clear of the ground.
                // Every construct stays round its creature, whose hold lifts it clear of the ground.
                Vec3 held = target.add(0, HiveFormation.constructLift(type, .6, height), 0);
                Vec3 at = HiveFormation.station(AttackMode.CONTAINMENT, type, slot, slots, owner, held, .6, height, 777.5, 100, 60);
                require(at.y >= target.y + .1, type + " containment dips into the ground: " + (at.y - target.y) + " (height " + height + ")");
            }
        }

        // Several targets: groups are shared out evenly, every place keeps to its own target, and each
        // target's places are laid out exactly once.
        List<HiveTarget> foes = List.of(new HiveTarget(1, target, 1.1, 1.9), new HiveTarget(2, target.add(9, 0, 3), .6, 1.8),
                new HiveTarget(3, target.add(-6, 1, -8), .9, 2.6));
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slots : new int[]{2, 12, 100, 250}) {
            int groups = HiveSlots.groups(slots, mode, type), engaged = HiveFormation.engaged(foes.size(), HiveSlots.figures(groups, mode, type));
            for (int slot = 0; slot < slots; slot++) {
                Vec3 at = HiveFormation.station(mode, type, slot, slots, owner, foes, 555.5, 100, 60);
                requireFinite(at, "multi-target station");
                HiveTarget mine = foes.get(HiveSlots.group(slot, groups) % engaged);
                Vec3 middle = HiveFormation.core(mine.feet(), mine.height());
                double limit = mode == AttackMode.DROPLET ? owner.distanceTo(middle) + 14 : mode == AttackMode.CONTAINMENT ? 12 : 9;
                require(at.distanceTo(middle) < limit, mode + " " + type + " place " + slot + "/" + slots + " strays from its own target: " + at.distanceTo(middle));
            }
            int total = 0;
            for (int index = 0; index < engaged; index++) {
                int localGroups = HiveSlots.localGroups(index, engaged, groups), localSlots = HiveSlots.localSlots(index, engaged, slots, groups);
                java.util.Set<Integer> places = new java.util.HashSet<>();
                for (int local = 0; local < localSlots; local++) {
                    int slot = HiveSlots.globalSlot(index, engaged, local, localGroups, groups);
                    require(slot < slots && HiveSlots.group(slot, groups) % engaged == index && places.add(slot),
                            "local place " + local + " of target " + index + " maps onto one of its own places");
                }
                total += localSlots;
            }
            require(total == slots, "every place belongs to exactly one target");
            require(engaged == Math.min(foes.size(), HiveSlots.figures(groups, mode, type)), "one target per figure at most");
            // A single target in a list is laid out exactly as before.
            for (int slot = 0; slot < slots; slot += Math.max(1, slots / 20)) {
                Vec3 alone = HiveFormation.station(mode, type, slot, slots, owner, List.of(foes.getFirst()), 321.25, 100, 60);
                require(alone.distanceTo(HiveFormation.station(mode, type, slot, slots, owner, target, 1.1, 1.9, 321.25, 100, 60)) < 1e-9,
                        mode + " one target in a list must match the single-target layout");
            }
        }

        // A change of targets: every drone flies from where it was straight to its new place, smoothly.
        List<HiveTarget> before = foes.subList(0, 2), after = List.of(foes.get(1), foes.get(2));
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slot = 0; slot < 100; slot += 7) {
            Vec3 start = HiveFormation.engagedStation(mode, type, slot, 100, owner, after, before, 1000, 1000, 100, 60);
            Vec3 old = HiveFormation.station(mode, type, slot, 100, owner, before, 1000, 100, 60);
            Vec3 fresh = HiveFormation.station(mode, type, slot, 100, owner, after, 1000, 100, 60);
            require(start.distanceTo(old) < 1e-9 || start.distanceTo(fresh) < 1e-9, mode + " a retargeted drone starts where it was");
            Vec3 end = HiveFormation.engagedStation(mode, type, slot, 100, owner, after, before, 1000, 1000 + HiveFormation.RETARGET_TICKS, 100, 60);
            require(end.distanceTo(HiveFormation.station(mode, type, slot, 100, owner, after, 1000 + HiveFormation.RETARGET_TICKS, 100, 60)) < 1e-9,
                    mode + " a retargeted drone ends on its new place");
            Vec3 previous = null;
            for (double t = 1000; t <= 1000 + HiveFormation.RETARGET_TICKS + 2; t += .25) {
                Vec3 at = HiveFormation.engagedStation(mode, type, slot, 100, owner, after, before, 1000, t, 100, 60);
                if (previous != null) require(previous.distanceTo(at) < 2.5, mode + " " + type + " a retargeted drone must fly, not jump (" + previous.distanceTo(at) + ")");
                previous = at;
            }
        }

        // Drones fly out from their hive slot and land exactly on their station, smoothly in between.
        for (HiveType type : HiveType.values()) for (int unit = 0; unit < 60; unit++) {
            Vec3 station = HiveFormation.station(AttackMode.BARRAGE, type, unit, 60, owner, target, 1.1, 1.9, 150, 100, 60);
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
                Vec3 home = HiveFormation.muster(owner, target, group, groups, 0);
                require(home.distanceTo(owner) < 11 && home.y > owner.y + 1.8, "figures form up above and around their owner: " + home.subtract(owner));
                require(home.distanceTo(core) > owner.distanceTo(core), "figures form up behind their owner, not in the way");
                require(HiveFormation.dropletCentre(owner, target, 1.9, group, groups, started - 1, 100, 60).equals(HiveFormation.muster(owner, target, group, groups, started - 1)),
                        "a figure waits in the fan before its first sortie");
                for (long now = started; now < started + 60 * 5; now++) {
                    if (!HiveFormation.passes(now, 100, 60, group, groups, HiveFormation.IMPACT)) continue;
                    hits++;
                    Vec3 centre = HiveFormation.dropletCentre(owner, target, 1.9, group, groups, now, 100, 60);
                    require(centre.distanceTo(core) < .01, "a group's blow lands when its figure reaches the target (" + centre.distanceTo(core) + ")");
                    long flight = Math.round(HiveFormation.flightTicks(HiveFormation.muster(owner, target, group, groups, now).distanceTo(core), 60));
                    // The blow lands on the first whole tick past the impact and the flight is rounded, so the sound may trail the launch by up to two ticks.
                    require(HiveFormation.sortie(owner, target, 1.9, group, groups, now - flight - 2, 100, 60) < 0,
                            "the launch sound plays as the figure leaves the fan: " + groups + "/" + group + " at " + now);
                }
                require(hits == 5, "one blow per group per cycle, got " + hits);
                Vec3 previous = null;
                for (double t = started; t < started + 120; t += .25) {
                    Vec3 centre = HiveFormation.dropletCentre(owner, target, 1.9, group, groups, t, 100, 60);
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
                    Vec3 offset = dev.hurtify.relicsaddon.drone.HiveShapes.clump(type, member, members, time, new Vec3(1, -.3, .2), radius);
                    require(offset.length() < radius * 1.08, type + " clump member strays: " + offset.length() + " of " + radius);
                    require(offset.length() > radius * .5, type + " clumps swirl round their charge and leave its middle clear");
                }
            }
            int[][] links = HiveFormation.clusterLinks(type, groups);
            require(groups < 2 || links.length >= groups - 1, type + " pattern links every clump");
            for (int[] link : links) require(link[0] != link[1] && link[0] >= 0 && link[1] < groups, "links join two different clumps");
        }

        // Lanes: a hit drone hands its place to the next one at once, and a repaired one waits in reserve.
        HiveFlightPlan plan = HiveFlightPlan.of(HiveType.RF, HiveType.MAX_DRONES, HiveSettings.DEFAULT);
        HiveFlightPlan.Wing wing = plan.wing(AttackMode.BARRAGE);
        List<HiveStackState.Unit> units = new ArrayList<>();
        for (int index = 0; index < HiveType.MAX_DRONES; index++) units.add(HiveStackState.Unit.fresh());
        int slots = wing.slots();
        require(plan.slots() == HiveType.MAX_DEPLOYED && slots == HiveType.MAX_DEPLOYED, "at most 250 drones fly");
        require(wing.occupants(units, 1000)[7] == 7, "a place starts with the first drone of its lane");
        units.set(7, units.get(7).hit(1, 1000, 1200));
        require(wing.occupants(units, 1000)[7] == 7 + slots, "the lane's next drone takes over at once");
        require(wing.since(units, 7, 7 + slots, 1000) == 1000, "the replacement set off when the first was hit");
        require(wing.occupants(units, 1300)[7] == 7 + slots, "a repaired drone does not bump its replacement");
        units.set(7 + slots, units.get(7 + slots).hit(3, 1400, 1600));
        require(wing.occupants(units, 1400)[7] == 7 + 2 * slots, "the lane keeps rotating");
        // A lane that has run dry takes a spare drone of its wing rather than leave its place empty: 234 healers
        // leave 16 places, so a Droplet of 66 has lanes of four or five drones.
        HiveFlightPlan.Wing droplet = HiveFlightPlan.of(HiveType.RF, 300, new HiveSettings(234, 66, 0, 0)).wing(AttackMode.DROPLET);
        require(droplet.slots() == 16 && droplet.pool() == 66, "234 healers leave the Droplet 16 places (" + droplet.slots() + ")");
        List<HiveStackState.Unit> dry = new ArrayList<>(java.util.Collections.nCopies(300, HiveStackState.Unit.fresh()));
        for (int unit = 0; unit < 66; unit += 16) dry.set(unit, dry.get(unit).hit(1, 10, 500));
        require(droplet.occupant(dry, 0, 20) < 0 && droplet.occupants(dry, 20)[0] >= 16 && droplet.ready(dry, 20),
                "a dry lane borrows a spare drone, so the wing keeps its figure");
        for (HiveType type : HiveType.values()) for (AttackMode mode : AttackMode.values()) {
            int minimum = dev.hurtify.relicsaddon.drone.HiveFigures.minimum(type, mode);
            for (int flying = minimum; flying <= HiveType.MAX_DEPLOYED; flying++) {
                int groups = HiveSlots.groups(flying, mode, type), sum = 0;
                require(groups >= 1 && groups <= 16, "one to sixteen strike groups");
                for (int group = 0; group < groups; group++) {
                    int size = HiveSlots.groupSize(group, flying, groups);
                    require(size >= (mode == AttackMode.BARRAGE ? 1 : minimum), mode + " " + type + " group " + group + " of " + flying
                            + " is too small for its figure: " + size);
                    sum += size;
                }
                require(sum == flying, "every drone belongs to one group");
                if (mode == AttackMode.BARRAGE) require(groups >= minimum && groups % minimum == 0, type + " barrage clumps make whole patterns: "
                        + groups + " of " + flying);
            }
        }
        int total = 0;
        for (int group = 0; group < 16; group++) total += HiveSlots.groupSize(group, 250, 16);
        require(total == 250, "every place belongs to exactly one group");
        armageddon();
        manaArmageddon();
        rfArmageddon();
        System.out.println("Hive formation: every mode bounded, smooth and separated; launches land; blows due on time; lanes rotate; "
                + "the Armageddon cannon forms, fires, devours and bursts on time; the Mana flowers form apart, their streams meet head-on, "
                + "and the column grows with the blast; the RF relay unfolds with its charge, its ball flies, hangs and sinks on time, and its dome and "
                + "shock front strike as they say");
    }

    /**
     * RF Armageddon: its stages in order and its blast as long as its sound; the panels folded while the hologram is
     * built, opening click by click into a full cross exactly as the charge fills and snapped shut as the ball leaves;
     * the hologram twelve to sixteen blocks long, whole, smooth and over its owner, every place its own; the ball a
     * point at the first click, as big as the panels are open, flying out to hang over the target and sinking to meet
     * it on time; the escort riding with it; the dome's and the shock front's reach true to their inverses; the crater
     * and its rim.
     */
    private static void rfArmageddon() {
        require(RfArmageddon.ASSEMBLED < RfArmageddon.FIRE && RfArmageddon.FIRE < RfArmageddon.SCATTER && RfArmageddon.SCATTER < RfArmageddon.ARRIVE
                && RfArmageddon.ARRIVE < RfArmageddon.DESCEND && RfArmageddon.DESCEND < RfArmageddon.IMPACT && RfArmageddon.IMPACT < RfArmageddon.RECOVER
                && RfArmageddon.RECOVER < RfArmageddon.END, "RF Armageddon's stages come in order");
        require(RfArmageddon.FLASH < RfArmageddon.FLOODED && RfArmageddon.FLOODED < RfArmageddon.SILHOUETTES && RfArmageddon.SILHOUETTES < RfArmageddon.COLOUR
                && RfArmageddon.FLASH + RfArmageddon.SHOCK < RfArmageddon.FALLEN && RfArmageddon.FALLEN < RfArmageddon.BLAST
                && RfArmageddon.RECOVER == RfArmageddon.IMPACT + RfArmageddon.FLASH + RfArmageddon.BLAST, "the blast's stages come in order, and the drones go home as it falls silent");
        require(RfArmageddon.BLAST == Math.round(RfArmageddon.BLAST_SECONDS * 20), "the blast lasts as many ticks as its sound's seconds");
        require(RfArmageddon.charge(RfArmageddon.ASSEMBLED) == 0 && RfArmageddon.charge(RfArmageddon.FIRE) == 1, "the charge runs from empty to full as the ball leaves");
        // The panels: folded while the hologram is built, one click after another, a full cross exactly as the charge fills.
        require(RfArmageddon.unfold(0) == 0 && RfArmageddon.unfold(RfArmageddon.ASSEMBLED) == 0, "the panels lie folded along the body while it is built");
        require(RfArmageddon.opened(RfArmageddon.FIRE) == 1 && Math.abs(RfArmageddon.opened(RfArmageddon.FIRE - 1) - 1) < 1e-3, "the cross is full as the charge is");
        require(RfArmageddon.unfold(RfArmageddon.SCATTER) == 0 && RfArmageddon.unfold(RfArmageddon.FIRE + RfArmageddon.SNAP / 2.0) < 1, "the panels snap shut as the ball leaves");
        for (int click = 0; click < RfArmageddon.CLICKS; click++) {
            double at = RfArmageddon.clickAt(click), swing = RfArmageddon.CLICK_SWING * (RfArmageddon.FIRE - RfArmageddon.ASSEMBLED) / RfArmageddon.CLICKS;
            require(Math.abs(RfArmageddon.opened(at) - click / (double) RfArmageddon.CLICKS) < 1e-9
                    && Math.abs(RfArmageddon.opened(at + swing) - (click + 1) / (double) RfArmageddon.CLICKS) < 1e-9, "click " + click + " opens one step");
        }
        for (double age = 0; age < RfArmageddon.FIRE; age += 1) require(RfArmageddon.opened(age + 1) >= RfArmageddon.opened(age), "the panels only open while it charges");
        for (int row = 0; row < RfArmageddon.ROWS; row++) {
            double charged = RfArmageddon.ASSEMBLED + (RfArmageddon.FIRE - RfArmageddon.ASSEMBLED) * (row + 1.0) / RfArmageddon.ROWS;
            require(RfArmageddon.lit(row, charged) > 1 - 1e-9 && RfArmageddon.lit(row, charged - (RfArmageddon.FIRE - RfArmageddon.ASSEMBLED) / (double) RfArmageddon.ROWS) < 1e-9,
                    "row " + row + " lights in its own share of the charge, from the body out");
        }
        // The ball: a point at the first click, as big as the panels are open, whole as it leaves, swelling on its way.
        require(RfArmageddon.ballRadius(RfArmageddon.ASSEMBLED) == 0 && RfArmageddon.ballRadius(RfArmageddon.clickAt(0) + 30) > 0
                && RfArmageddon.ballRadius(RfArmageddon.clickAt(0) + 30) < .4, "the ball starts as a point with the first click");
        require(Math.abs(RfArmageddon.ballRadius(RfArmageddon.FIRE - 1e-6) - RfArmageddon.BALL_CHARGED) < 1e-3
                && Math.abs(RfArmageddon.ballRadius(RfArmageddon.ARRIVE) - RfArmageddon.BALL_HOVER) < 1e-9, "the ball is whole as it leaves and swells to its hover");
        for (double age = 0; age < RfArmageddon.ARRIVE; age += 1) require(RfArmageddon.ballRadius(age + 1) >= RfArmageddon.ballRadius(age) - 1e-12, "the ball only grows");
        require(RfArmageddon.NOSE_TIP + RfArmageddon.NOSE_NEEDLES - (RfArmageddon.STERN_CAP - RfArmageddon.STERN_NEEDLES) >= 12
                && RfArmageddon.NOSE_TIP + RfArmageddon.NOSE_NEEDLES - (RfArmageddon.STERN_CAP - RfArmageddon.STERN_NEEDLES) <= 16, "the hologram is twelve to sixteen blocks long");
        require(RfArmageddon.BALL_AT - RfArmageddon.BALL_CHARGED > RfArmageddon.NOSE_TIP + RfArmageddon.NOSE_NEEDLES, "the whole ball stays clear of the nose's needles");

        Vec3 eye = new Vec3(10, 70, 10);
        for (Vec3 aim : new Vec3[]{new Vec3(0, -10, -60), new Vec3(180, 5, 170), new Vec3(3, -60, 4), new Vec3(0, 0, -12), new Vec3(-200, 40, 20)}) {
            Vec3 target = eye.add(aim);
            ArmageddonState shot = new ArmageddonState(HiveType.RF, 1_000, RfArmageddon.origin(eye, target), target, true);
            require(shot.origin().y - eye.y > 6 && shot.origin().distanceTo(eye) < 10, "the hologram hangs over its owner's head");
            Vec3[] frame = RfArmageddon.frame(shot);
            require(Math.abs(frame[0].length() - 1) < 1e-9 && Math.abs(frame[0].dot(frame[1])) < 1e-9 && Math.abs(frame[0].dot(frame[2])) < 1e-9
                    && Math.asin(Math.abs(frame[0].y)) <= RfArmageddon.MAX_PITCH + 1e-9, "the hologram's frame is square and leans no more than it may");
            // The ball leaves the nose, hangs over the target, and its middle meets the ground as it bursts.
            require(RfArmageddon.ball(shot, RfArmageddon.FIRE).distanceTo(RfArmageddon.nose(shot)) < 1e-9
                    && RfArmageddon.ball(shot, RfArmageddon.ARRIVE).distanceTo(RfArmageddon.hover(shot)) < 1e-9
                    && RfArmageddon.ball(shot, RfArmageddon.DESCEND).distanceTo(RfArmageddon.hover(shot)) < 1e-9
                    && RfArmageddon.ball(shot, RfArmageddon.IMPACT).distanceTo(target) < 1e-9, "the ball flies to its hover over the target and sinks onto it");
            for (double age = RfArmageddon.FIRE; age < RfArmageddon.IMPACT; age += .5) {
                require(RfArmageddon.ball(shot, age + .5).distanceTo(RfArmageddon.ball(shot, age)) < 2.5, "the ball flies slow and heavy, never jumping");
            }
            double reach = target.distanceTo(shot.origin());
            for (int slots : new int[]{1, 7, 100, 250, 2000}) {
                require(RfArmageddon.hologram(slots) + RfArmageddon.escorts(slots) == slots, "every place is in the hologram or escorts the ball");
                Vec3[] previous = new Vec3[slots];
                for (double age = 0; age <= RfArmageddon.RECOVER; age += .5) {
                    double time = shot.startedAt() + age;
                    Vec3[] at = new Vec3[slots];
                    for (int slot = 0; slot < slots; slot++) {
                        Vec3 station = RfArmageddon.station(shot, slot, slots, time);
                        requireFinite(station, "RF Armageddon station");
                        // As the renderer flies them: in to the axis from where they were (a little below the owner's eyes), then out to their place.
                        Vec3 start = eye.add(Math.sin(slot) * .8, -1, Math.cos(slot) * .8);
                        at[slot] = RfArmageddon.assemble(shot, start, station, RfArmageddon.gathered(slot, slots, age));
                        requireFinite(at[slot], "RF Armageddon drone");
                        if (age < RfArmageddon.FIRE) require(at[slot].distanceTo(shot.origin()) < 11, "the hologram keeps together over its owner: "
                                + at[slot].distanceTo(shot.origin()) + " at " + age);
                        else require(at[slot].distanceTo(shot.origin()) < reach + 150, "the escort stays with the ball and the blast");
                        if (previous[slot] != null) require(previous[slot].distanceTo(at[slot]) < 2 + reach * .05,
                                "an RF Armageddon drone must not jump: " + previous[slot].distanceTo(at[slot]) + " at " + age + " (" + slot + "/" + slots + ") aiming " + aim + " from " + previous[slot] + " to " + at[slot] + ", ball " + RfArmageddon.ball(shot, age) + " r " + RfArmageddon.ballRadius(age));
                    }
                    if (slots <= 250 && (age == 400 || age == RfArmageddon.FIRE - 1)) for (int a = 0; a < slots; a++) for (int b = a + 1; b < slots; b++) {
                        require(at[a].distanceToSqr(at[b]) > 1e-8, "RF Armageddon places " + a + " and " + b + " of " + slots + " coincide");
                    }
                    previous = at;
                }
            }
        }
        // The hologram is built by the time the charge starts, the body from the stern and the panels after it.
        for (int slots : new int[]{7, 250}) for (int slot = 0; slot < slots; slot++) {
            require(RfArmageddon.gathered(slot, slots, RfArmageddon.ASSEMBLED) == 1 && RfArmageddon.gathered(slot, slots, 0) == 0, "every drone lands by the time the charge starts");
        }
        require(RfArmageddon.builtAt(RfArmageddon.STERN_CAP) < RfArmageddon.builtAt(RfArmageddon.NOSE_TIP)
                && RfArmageddon.builtAt(RfArmageddon.NOSE_TIP) <= RfArmageddon.panelBuiltAt(RfArmageddon.PANEL_LENGTH), "the body is built from the stern to the nose, the panels after");
        // The dome and the shock front: nothing before the ball meets the ground, the dome's edge to the crater by the flash, the front to the edge.
        require(RfArmageddon.reach(-1) == 0 && Math.abs(RfArmageddon.reach(0) - RfArmageddon.BALL_HOVER) < 1e-9
                && Math.abs(RfArmageddon.reach(RfArmageddon.FLASH) - RfArmageddon.DOME_RADIUS) < 1e-9
                && Math.abs(RfArmageddon.reach(RfArmageddon.FLASH + RfArmageddon.SHOCK) - RfArmageddon.RADIUS) < 1e-9, "the dome swells to the crater, the front runs to the edge");
        for (double t = 0; t < RfArmageddon.FLASH + RfArmageddon.SHOCK; t += 1.5) require(RfArmageddon.reach(t + 1.5) >= RfArmageddon.reach(t), "the blast's reach only grows");
        for (double distance = 1; distance <= RfArmageddon.RADIUS; distance += 2.5) {
            double when = RfArmageddon.reaches(distance);
            require(RfArmageddon.reach(when) >= distance - 1e-6 && RfArmageddon.reach(when - .01) < distance,
                    "the RF blast reaches " + distance + " blocks when it says it does (" + when + ")");
        }
        require(RfArmageddon.carved(RfArmageddon.IMPACT - 1) == 0 && Math.abs(RfArmageddon.carved(RfArmageddon.IMPACT + RfArmageddon.DOME) - RfArmageddon.DOME_RADIUS) < 1e-9,
                "the dome cuts the land from the moment the ball meets it out to the crater's edge");
        for (double distance = .5; distance < RfArmageddon.DOME_RADIUS; distance += 1.5) {
            double when = RfArmageddon.carvedAt(distance);
            require(RfArmageddon.carved(when) >= distance - 1e-6, "a block is cut when the dome's edge reaches it");
        }
        // The rim: none inside the bowl or past its reach, highest at the crater's edge, sloping gently away outside.
        require(RfArmageddon.rimHeight(RfArmageddon.DOME_RADIUS * .9) == 0 && RfArmageddon.rimHeight(RfArmageddon.rimReach() + .5) == 0
                && Math.abs(RfArmageddon.rimHeight(RfArmageddon.DOME_RADIUS) - RfArmageddon.RIM_HEIGHT) < 1e-9, "the rim stands at the crater's edge");
        for (double d = RfArmageddon.DOME_RADIUS; d < RfArmageddon.rimReach(); d += .5) require(RfArmageddon.rimHeight(d + .5) <= RfArmageddon.rimHeight(d), "the rim slopes away outside");
        require(RfArmageddon.TIMELINE.carveDepth() == RfArmageddon.BOWL && RfArmageddon.BOWL < 1 && dev.hurtify.relicsaddon.drone.Armageddon.TIMELINE.carveDepth() == 1
                && ManaArmageddon.TIMELINE.carveDepth() == 1, "only the RF crater is a bowl");
    }

    /**
     * Mana Armageddon: its stages in order; the rings written one after another, the last as the charge fills; the
     * flowers whole, smooth and clear of the gap between them, their drones spiralling in without a jump; the
     * streams leaving the hearts and meeting head-on at the target; the vortex, the dome and the column true to
     * their inverses and ends, the column widest exactly as the blast falls silent.
     */
    private static void manaArmageddon() {
        require(ManaArmageddon.ASSEMBLED < ManaArmageddon.FIRE && ManaArmageddon.FIRE < ManaArmageddon.ARRIVE && ManaArmageddon.ARRIVE < ManaArmageddon.TEAR
                && ManaArmageddon.TEAR < ManaArmageddon.IGNITE && ManaArmageddon.IGNITE < ManaArmageddon.IMPACT && ManaArmageddon.IMPACT < ManaArmageddon.RECOVER
                && ManaArmageddon.RECOVER < ManaArmageddon.END, "Mana Armageddon's stages come in order");
        require(ManaArmageddon.DOME + ManaArmageddon.EXPAND < ManaArmageddon.DOME_HOLD && ManaArmageddon.DOME_HOLD < ManaArmageddon.DOME_GONE
                && ManaArmageddon.COLUMN < ManaArmageddon.BLAST && ManaArmageddon.BLAST < ManaArmageddon.WHITE && ManaArmageddon.WHITE < ManaArmageddon.QUIET
                && ManaArmageddon.RECOVER == ManaArmageddon.IMPACT + ManaArmageddon.QUIET, "the blast's stages come in order, and the drones go home after the white");
        require(ManaArmageddon.BLAST == Math.round(ManaArmageddon.BLAST_SECONDS * 20), "the blast lasts as many ticks as its sound's seconds");
        require(ManaArmageddon.charge(0) == 0 && ManaArmageddon.charge(ManaArmageddon.FIRE) == 1, "the charge runs from empty to full as the streams leave");
        for (int ring = 0; ring < ManaArmageddon.RINGS; ring++) {
            double filled = ManaArmageddon.filledAt(ring), span = (ManaArmageddon.FIRE - ManaArmageddon.ASSEMBLED) / (double) ManaArmageddon.RINGS;
            require(Math.abs(ManaArmageddon.written(ring, filled) - 1) < 1e-9 && ManaArmageddon.written(ring, filled - span) < 1e-9,
                    "ring " + ring + " is written in its own share of the charge");
            require(ManaArmageddon.ringTurn(ring, filled) == 0 && ManaArmageddon.ringTurn(ring, filled + 20) != 0, "a ring starts to turn once it is full");
        }
        require(Math.abs(ManaArmageddon.filledAt(ManaArmageddon.RINGS - 1) - ManaArmageddon.FIRE) < 1e-9, "the last ring fills as the charge does");

        Vec3 eye = new Vec3(10, 70, 10);
        for (Vec3 aim : new Vec3[]{new Vec3(0, -10, -60), new Vec3(180, 5, 170), new Vec3(3, -60, 4), new Vec3(0, 0, -12)}) {
            Vec3 target = eye.add(aim);
            ArmageddonState shot = new ArmageddonState(HiveType.MANA, 1_000, ManaArmageddon.origin(eye, target), target, true);
            Vec3 left = ManaArmageddon.heart(shot, -1), right = ManaArmageddon.heart(shot, 1);
            require(Math.abs(left.distanceTo(right) - 2 * ManaArmageddon.SPREAD) < 1e-9 && left.y > eye.y && right.y > eye.y,
                    "the flowers hang over the owner's shoulders, one either side");
            for (int side : new int[]{-1, 1}) {
                require(ManaArmageddon.stream(shot, side, 0).distanceTo(ManaArmageddon.heart(shot, side)) < 1e-9
                        && ManaArmageddon.stream(shot, side, 1).distanceTo(target) < 1e-9, "each stream runs from its flower's heart to the target");
            }
            require(ManaArmageddon.streamAxes(shot, -1, 1)[0].dot(ManaArmageddon.streamAxes(shot, 1, 1)[0]) < -.999, "the streams meet head-on");
            double reach = target.distanceTo(shot.origin());
            Vec3[] frame = ManaArmageddon.frame(shot);
            for (int slots : new int[]{1, 7, 100, 250, 2000}) {
                require(ManaArmageddon.flowered(slots) + ManaArmageddon.escorts(slots) == slots, "every place is in a flower or rides a stream");
                int flowered = ManaArmageddon.flowered(slots);
                Vec3[] previous = new Vec3[slots];
                for (double age = 0; age <= ManaArmageddon.RECOVER; age += .5) {
                    double time = shot.startedAt() + age;
                    Vec3[] at = new Vec3[slots];
                    for (int slot = 0; slot < slots; slot++) {
                        Vec3 station = ManaArmageddon.station(shot, slot, slots, time);
                        requireFinite(station, "Mana Armageddon station");
                        // As the renderer flies them: from where they were (a little below the owner's eyes), spiralling in.
                        double gathered = slot < flowered ? ManaArmageddon.gathered(slot, slots, age) : Math.clamp(age / ManaArmageddon.ASSEMBLED, 0, 1);
                        Vec3 start = eye.add(Math.sin(slot) * .8, -1, Math.cos(slot) * .8);
                        at[slot] = ManaArmageddon.spiral(shot, ManaArmageddon.side(slot < flowered ? slot : slot - flowered), start, station, gathered);
                        requireFinite(at[slot], "Mana Armageddon drone");
                        if (age < ManaArmageddon.FIRE) require(at[slot].distanceTo(shot.origin()) < 10, "the flowers keep together over their owner: "
                                + at[slot].distanceTo(shot.origin()) + " at " + age);
                        else require(at[slot].distanceTo(shot.origin()) < reach * 1.6 + 130, "the escort stays with the streams and the blast");
                        if (previous[slot] != null) require(previous[slot].distanceTo(at[slot]) < 2 + reach * .05,
                                "a Mana Armageddon drone must not jump: " + previous[slot].distanceTo(at[slot]) + " at " + age + " (" + slot + "/" + slots + ")");
                        // Resting in its flower, a petal drone keeps clear of the gap between the flowers.
                        if (slot < flowered && age >= ManaArmageddon.ASSEMBLED && age < ManaArmageddon.FIRE) {
                            double off = Math.abs(at[slot].subtract(shot.origin()).dot(frame[1]));
                            require(off > ManaArmageddon.SPREAD - ManaArmageddon.PETAL - .3, "the gap between the flowers stays empty: " + off);
                        }
                    }
                    if (slots <= 250 && (age == 400 || age == ManaArmageddon.FIRE - 1)) for (int a = 0; a < slots; a++) for (int b = a + 1; b < slots; b++) {
                        require(at[a].distanceToSqr(at[b]) > 1e-8, "Mana Armageddon places " + a + " and " + b + " of " + slots + " coincide");
                    }
                    previous = at;
                }
            }
        }
        // The petals: every place inside its petal.
        for (int members : new int[]{1, 6, 25, 250}) for (int petal = 0; petal < ManaArmageddon.PETALS; petal++) for (int member = 0; member < members; member++) {
            double[] place = ManaArmageddon.petalPlace(petal, member, members);
            double angle = ManaArmageddon.petalAngle(petal);
            double along = place[0] * Math.cos(angle) + place[1] * Math.sin(angle), across = -place[0] * Math.sin(angle) + place[1] * Math.cos(angle);
            require(along > ManaArmageddon.PETAL_BASE && along < ManaArmageddon.PETAL && Math.abs(across) <= ManaArmageddon.petalHalfWidth(along) + 1e-9,
                    "a petal drone rests inside its petal");
        }

        // The vortex tears the land from the middle out to its whole reach before the sun ignites, and its inverse agrees.
        require(ManaArmageddon.torn(ManaArmageddon.TEAR) == 0 && Math.abs(ManaArmageddon.torn(ManaArmageddon.IGNITE - 4) - ManaArmageddon.CARVE_RADIUS) < 1e-9,
                "the vortex tears the land out to its whole reach");
        for (double distance = .5; distance < ManaArmageddon.CARVE_RADIUS; distance += 1.5) {
            double when = ManaArmageddon.tornAt(distance);
            require(Math.abs(ManaArmageddon.torn(when) - distance) < 1e-3, "a block is torn up when the vortex reaches it");
        }
        // The dome of light strikes nothing before it forms, sweeps out to the radius and agrees with its inverse.
        require(ManaArmageddon.dome(ManaArmageddon.DOME - 1) == 0 && Math.abs(ManaArmageddon.dome(ManaArmageddon.DOME + ManaArmageddon.EXPAND) - ManaArmageddon.RADIUS) < 1e-9,
                "the dome of light sweeps out to the radius");
        for (double t = 0; t < ManaArmageddon.DOME + ManaArmageddon.EXPAND; t += 2.5) require(ManaArmageddon.dome(t + 2.5) >= ManaArmageddon.dome(t), "the dome only grows");
        for (double distance = 1; distance <= ManaArmageddon.RADIUS; distance += 2.5) {
            double when = ManaArmageddon.domeReaches(distance);
            require(ManaArmageddon.dome(when) >= distance - 1e-6 && ManaArmageddon.dome(when - .01) < distance, "the dome reaches " + distance + " when it says it does");
        }
        // The column: a thread as it rises, never narrowing, widest exactly as the blast falls silent, easing into it with no jump.
        require(Math.abs(ManaArmageddon.column(ManaArmageddon.COLUMN) - ManaArmageddon.COLUMN_START) < 1e-9
                && Math.abs(ManaArmageddon.column(ManaArmageddon.BLAST) - ManaArmageddon.COLUMN_RADIUS) < 1e-9
                && ManaArmageddon.column(ManaArmageddon.WHITE) == ManaArmageddon.COLUMN_RADIUS && ManaArmageddon.COLUMN_RADIUS <= ManaArmageddon.RADIUS,
                "the column grows from a thread to its widest as the blast falls silent, no wider than the dome");
        for (double t = ManaArmageddon.COLUMN; t < ManaArmageddon.BLAST; t += 1) require(ManaArmageddon.column(t + 1) >= ManaArmageddon.column(t), "the column never narrows");
        require(ManaArmageddon.column(ManaArmageddon.BLAST) - ManaArmageddon.column(ManaArmageddon.BLAST - 1) < .01, "the column eases into its widest without a jump");
        require(Math.abs(ManaArmageddon.shock(ManaArmageddon.SHOCK) - ManaArmageddon.SHOCK_RADIUS) < 1e-9, "the ring of stones runs out to its reach");
    }

    /** Armageddon: its stages in order, the cannon whole and smooth, the shot landing on its target, the front and the devouring true to their inverses. */
    private static void armageddon() {
        require(Armageddon.ASSEMBLED < Armageddon.ringLit(0) && Armageddon.ringLit(Armageddon.RINGS - 1) < Armageddon.CHARGED
                && Armageddon.CHARGED < Armageddon.FIRE && Armageddon.FIRE < Armageddon.ARRIVE && Armageddon.ARRIVE < Armageddon.IMPACT
                && Armageddon.IMPACT < Armageddon.RECOVER && Armageddon.RECOVER < Armageddon.END, "Armageddon's stages come in order");
        require(Armageddon.charge(0) == 0 && Armageddon.charge(Armageddon.FIRE) == 1, "the charge runs from empty to full as the shot fires");
        for (double age = 0; age < Armageddon.FIRE; age += 7) require(Armageddon.charge(age) <= Armageddon.charge(age + 7), "the charge only grows");
        require(Armageddon.shotSize(Armageddon.CHARGED) == 0 && Math.abs(Armageddon.shotSize(Armageddon.FIRE) - Armageddon.SHOT_HORIZON) < 1e-9,
                "the black hole takes shape as the funnel focuses and is whole as it leaves");

        Vec3 eye = new Vec3(10, 70, 10);
        for (Vec3 aim : new Vec3[]{new Vec3(0, -10, -60), new Vec3(180, 5, 170), new Vec3(3, -60, 4), new Vec3(0, 0, -12)}) {
            Vec3 target = eye.add(aim);
            ArmageddonState shot = new ArmageddonState(HiveType.TWINS, 1_000, Armageddon.origin(eye, target), target, true);
            require(shot.origin().y - eye.y > 6 && shot.origin().distanceTo(eye) < 8, "the cannon hangs over its owner's head");
            // The black hole leaves the muzzle, lands on the target and flies ever faster on the way.
            require(Armageddon.shot(shot, Armageddon.ARRIVE).distanceTo(target) < 1e-9, "the black hole lands on its target");
            double previousStep = 0;
            for (double age = Armageddon.FIRE; age < Armageddon.ARRIVE; age += 1) {
                double step = Armageddon.shot(shot, age + 1).distanceTo(Armageddon.shot(shot, age));
                require(step >= previousStep - 1e-9, "the black hole speeds up as it flies");
                previousStep = step;
            }
            double reach = target.distanceTo(shot.origin());
            for (int slots : new int[]{1, 7, 100, 250, 2000}) {
                require(Armageddon.cores(slots) + Armageddon.ringed(slots) + Armageddon.shells(slots) == slots, "every place has a part in the cannon");
                Vec3[] previous = new Vec3[slots];
                for (double age = 0; age <= Armageddon.RECOVER; age += .5) {
                    double time = shot.startedAt() + age;
                    Vec3[] at = new Vec3[slots];
                    for (int slot = 0; slot < slots; slot++) {
                        at[slot] = Armageddon.station(shot, slot, slots, time);
                        requireFinite(at[slot], "Armageddon station");
                        if (age < Armageddon.FIRE) require(at[slot].distanceTo(shot.origin()) < 22, "the cannon keeps together over its owner: "
                                + at[slot].distanceTo(shot.origin()) + " at " + age);
                        else require(at[slot].distanceTo(shot.origin()) < reach + 120, "the escort stays with the shot and the blast");
                        // Flying out with the black hole at its fastest, or flung from the blast, a drone still moves smoothly.
                        if (previous[slot] != null) require(previous[slot].distanceTo(at[slot]) < 2 + reach * .05,
                                "an Armageddon drone must not jump: " + previous[slot].distanceTo(at[slot]) + " at " + age + " (" + slot + "/" + slots + ")");
                    }
                    if (slots <= 250 && (age == 400 || age == Armageddon.FIRE - 1)) for (int a = 0; a < slots; a++) for (int b = a + 1; b < slots; b++) {
                        require(at[a].distanceToSqr(at[b]) > 1e-8, "Armageddon places " + a + " and " + b + " of " + slots + " coincide");
                    }
                    previous = at;
                }
            }
            // Every ring drone rides its ring.
            Vec3[] frame = Armageddon.frame(shot);
            int slots = 250, cores = Armageddon.cores(slots);
            for (int slot = cores; slot < cores + Armageddon.ringed(slots); slot++) {
                int ring = (slot - cores) % Armageddon.RINGS;
                Vec3 out = Armageddon.station(shot, slot, slots, shot.startedAt() + 500).subtract(shot.origin());
                double along = out.dot(frame[0]), across = out.subtract(frame[0].scale(along)).length();
                require(Math.abs(along - Armageddon.ringAt(ring)) < 1e-6 && Math.abs(across - Armageddon.ringRadius(ring)) < 1e-6, "a ring drone rides its ring");
            }
        }

        // The devouring spreads from the middle to its whole reach just before the burst, and its inverse agrees.
        require(Armageddon.devoured(Armageddon.HUNGER) == 0 && Math.abs(Armageddon.devoured(Armageddon.IMPACT - 4) - Armageddon.DEVOUR_RADIUS) < 1e-9,
                "the black hole devours out to its whole reach before it bursts");
        for (double distance = .5; distance < Armageddon.DEVOUR_RADIUS; distance += 1.5) {
            double when = Armageddon.devouredAt(distance);
            require(when >= Armageddon.HUNGER && when <= Armageddon.IMPACT - 4 && Math.abs(Armageddon.devoured(when) - distance) < 1e-3,
                    "a block is taken when the devouring reaches it");
        }
        // At the target the containment breaks, then the black hole feeds; the supernova's stages follow in
        // order. Nothing is struck until the ball of light forms; it strikes everything it sweeps over, holds,
        // and is crushed back in; when the blast first reaches a place agrees with how far it has got.
        require(Armageddon.ARRIVE < Armageddon.BROKEN && Armageddon.BROKEN < Armageddon.HUNGER && Armageddon.HUNGER < Armageddon.IMPACT,
                "the containment breaks before the black hole starts to feed");
        require(Armageddon.FLASH < Armageddon.BALL && Armageddon.BALL + Armageddon.EXPAND < Armageddon.HOLD && Armageddon.HOLD < Armageddon.CRUSHED
                && Armageddon.ERUPT <= Armageddon.CRUSHED && Armageddon.CRUSHED < Armageddon.NARROW && Armageddon.NARROW < Armageddon.GONE
                && Armageddon.RECOVER > Armageddon.IMPACT + Armageddon.GONE, "the supernova's stages come in order, and the drones come home after");
        require(Armageddon.reach(Armageddon.BALL - 1) == 0 && Math.abs(Armageddon.front(Armageddon.BALL + Armageddon.EXPAND) - Armageddon.RADIUS) < 1e-9,
                "nothing is struck before the ball of light forms, and it sweeps out to the radius");
        require(Math.abs(Armageddon.ball(Armageddon.HOLD) - Armageddon.RADIUS) < 1e-9 && Armageddon.ball(Armageddon.CRUSHED) < 1e-9,
                "the ball holds at the radius and is crushed back to nothing");
        for (double t = 0; t < Armageddon.BALL + Armageddon.EXPAND; t += 2.5) require(Armageddon.reach(t + 2.5) >= Armageddon.reach(t), "the blast's reach only grows");
        for (double distance = 1; distance <= Armageddon.RADIUS; distance += 2.5) {
            double when = Armageddon.reaches(distance);
            require(Armageddon.reach(when) >= distance - 1e-6 && Armageddon.reach(when - .01) < distance,
                    "the blast reaches " + distance + " blocks when it says it does (" + when + ")");
        }
    }

    private static void requireFinite(Vec3 point, String what) { require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z), what + " must be finite"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
