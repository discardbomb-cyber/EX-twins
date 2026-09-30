package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveTarget;
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
                Vec3 held = target.add(0, switch (type) {
                    case RF -> HiveFormation.ringLift(.6, height, false);
                    case TWINS -> HiveFormation.twinsLift(.6, height);
                    case MANA -> HiveFormation.wardLift(.6, height);
                }, 0);
                Vec3 at = HiveFormation.station(AttackMode.CONTAINMENT, type, slot, slots, owner, held, .6, height, 777.5, 100, 60);
                require(at.y >= target.y + .1, type + " containment dips into the ground: " + (at.y - target.y) + " (height " + height + ")");
            }
        }

        // Several targets: groups are shared out evenly, every place keeps to its own target, and each
        // target's places are laid out exactly once.
        List<HiveTarget> foes = List.of(new HiveTarget(1, target, 1.1, 1.9), new HiveTarget(2, target.add(9, 0, 3), .6, 1.8),
                new HiveTarget(3, target.add(-6, 1, -8), .9, 2.6));
        for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slots : new int[]{2, 12, 100, 250}) {
            int groups = HiveSlots.groups(slots, mode), engaged = HiveFormation.engaged(foes.size(), groups);
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
            require(engaged == Math.min(foes.size(), groups), "one target per strike group at most");
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
        require(HiveSlots.groups(12, AttackMode.BARRAGE) == 3 && HiveSlots.groups(3, AttackMode.BARRAGE) == 3
                && HiveSlots.groups(2, AttackMode.BARRAGE) == 2 && HiveSlots.groups(250, AttackMode.BARRAGE) == 16
                && HiveSlots.groups(12, AttackMode.DROPLET) == 2, "barrage clumps make at least a triangle");
        for (int few = 3; few < 40; few++) {
            int barrage = HiveSlots.groups(few, AttackMode.BARRAGE), total = 0;
            for (int group = 0; group < barrage; group++) {
                require(HiveSlots.groupSize(group, few, barrage) > 0, "every barrage clump has a drone (" + few + " drones)");
                total += HiveSlots.groupSize(group, few, barrage);
            }
            require(total == few, "every drone belongs to one clump");
        }
        int total = 0;
        for (int group = 0; group < 16; group++) total += HiveSlots.groupSize(group, 250, 16);
        require(total == 250, "every place belongs to exactly one group");
        armageddon();
        System.out.println("Hive formation: every mode bounded, smooth and separated; launches land; blows due on time; lanes rotate; "
                + "the Armageddon cannon forms, fires, devours and bursts on time");
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
            ArmageddonState shot = new ArmageddonState(1_000, Armageddon.origin(eye, target), target, true);
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
