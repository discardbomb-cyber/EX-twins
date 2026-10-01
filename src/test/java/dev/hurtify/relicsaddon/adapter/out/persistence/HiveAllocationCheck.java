package dev.hurtify.relicsaddon.adapter.out.persistence;

import dev.hurtify.relicsaddon.domain.hive.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import dev.hurtify.relicsaddon.domain.math.Vec3d;

/**
 * How drones are shared between the attack modes: each mode's minimum is the corner count of its figure
 * (the drones placed for exactly that many stand on exactly those corners, and one corner fewer breaks the
 * figure), the 250 places in the air are shared out without a mode ever flying fewer than its minimum,
 * and the console's buttons never make an allocation the server would refuse.
 */
public final class HiveAllocationCheck {
    private static final Vec3d FORWARD = new Vec3d(.3, -.2, 1);

    public static void main(String[] args) {
        minimumsAreCorners();
        seatsAddUp();
        buttons();
        resolving();
        StringBuilder table = new StringBuilder();
        for (HiveType type : HiveType.values()) {
            table.append(type).append(':');
            for (AttackMode mode : AttackMode.values()) table.append(' ').append(mode.id()).append(' ').append(HiveFigures.minimum(type, mode));
            table.append("; ");
        }
        System.out.println("Hive allocation: minimums are figure corners (" + table.toString().trim() + "), seats add up, buttons and saves stay allowed");
    }

    /** For every family and mode, the drones placed for exactly the minimum stand on the figure's corners, one each. */
    private static void minimumsAreCorners() {
        for (double time : new double[]{0, 123.25, 40_000.5}) {
            double size = .9;
            // Droplet: the tesseract's corners, the drop's corners, the corners of the Twins hexagons.
            List<Vec3d> tesseract = new ArrayList<>(), corners = new ArrayList<>();
            for (int m = 0; m < HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET); m++) tesseract.add(HiveShapes.tesseract(m, 16, time, size));
            for (int corner = 0; corner < 16; corner++) corners.add(HiveShapes.tesseractCorner(corner, time, size));
            sameCorners(tesseract, corners, "RF droplet");
            List<Vec3d> drop = new ArrayList<>();
            int dropMinimum = HiveFigures.minimum(HiveType.MANA, AttackMode.DROPLET);
            for (int m = 0; m < dropMinimum; m++) drop.add(HiveShapes.droplet(m, dropMinimum, time, FORWARD, size));
            require(distinct(drop) == dropMinimum, "the Mana drop's corners are all apart");
            Vec3d axis = FORWARD.normalize();
            for (Vec3d point : drop) {
                require(point.dot(axis) <= drop.getFirst().dot(axis) + 1e-9, "the drop's tip is its frontmost corner");
                require(point.dot(axis) >= drop.getLast().dot(axis) - 1e-9, "the drop's back is its rearmost corner");
            }
            int hexMinimum = HiveFigures.minimum(HiveType.TWINS, AttackMode.DROPLET);
            List<Vec3d> hexagons = new ArrayList<>(), hexCorners = new ArrayList<>();
            for (int m = 0; m < hexMinimum; m++) hexagons.add(HiveShapes.hexagons(m, hexMinimum, time, FORWARD, size));
            int rings = HiveShapes.hexagonCount(hexMinimum);
            require(rings == HiveShapes.MIN_HEXAGONS, "the fewest Twins drones make the fewest hexagons");
            for (int ring = 0; ring < rings; ring++) {
                Vec3d centre = HiveShapes.hexagonCentre(ring, rings, time, FORWARD, size);
                double spin = time * (ring % 2 == 0 ? .05 : -.05);
                for (int corner = 0; corner < HiveShapes.HEXAGON_CORNERS; corner++) {
                    hexCorners.add(centre.add(HiveShapes.hexagonPoint(corner, spin, FORWARD, size * .45)));
                }
            }
            sameCorners(hexagons, hexCorners, "Twins droplet");

            // Containment: the RF cage's corners are an icosahedron's, the lotus's one tier of petals and their base, the rift's four hexagons.
            int cageMinimum = HiveFigures.minimum(HiveType.RF, AttackMode.CONTAINMENT);
            List<Vec3d> cage = new ArrayList<>();
            for (int s = 0; s < cageMinimum; s++) cage.add(HiveConstructs.cage(s, cageMinimum, 1000, time, 1));
            require(distinct(cage) == cageMinimum && cageMinimum == HiveConstructs.geodesic(1).corners().size(), "the cage is an icosahedron");
            for (Vec3d point : cage) require(Math.abs(point.length() - HiveConstructs.CAGE_SCALE) < 1e-9, "a closed cage's drones stand on its sphere");
            require(HiveConstructs.cageFaces(cageMinimum).size() == 20 && HiveConstructs.cageFaces(cageMinimum - 1).size() < 20, "a drone fewer breaks the cage");
            int lotusMinimum = HiveFigures.minimum(HiveType.MANA, AttackMode.CONTAINMENT);
            List<Vec3d> lotus = new ArrayList<>();
            for (int s = 0; s < lotusMinimum; s++) lotus.add(HiveConstructs.lotus(s, lotusMinimum, 1000, time, 1));
            require(distinct(lotus) == lotusMinimum && HiveConstructs.lotusEdges(lotusMinimum).size() == 4 * HiveConstructs.PETALS
                    && HiveConstructs.lotusEdges(lotusMinimum - 1).size() < 4 * HiveConstructs.PETALS, "a lotus is a base and six whole petals");
            int riftMinimum = HiveFigures.minimum(HiveType.TWINS, AttackMode.CONTAINMENT);
            List<Vec3d> rift = new ArrayList<>();
            for (int s = 0; s < riftMinimum; s++) rift.add(HiveConstructs.rift(s, riftMinimum, 1000, time, 1));
            require(distinct(rift) == riftMinimum && HiveConstructs.shards(riftMinimum) * HiveConstructs.SHARD_CORNERS == riftMinimum
                    && HiveConstructs.shards(riftMinimum - 1) * HiveConstructs.SHARD_CORNERS > riftMinimum - 1, "a rift is four whole hexagons");
        }

        // Barrage: the pattern needs its corners; a clump fewer is not that pattern any more.
        for (HiveType type : HiveType.values()) {
            int corners = HiveFigures.minimum(type, AttackMode.BARRAGE);
            require(corners >= 3, type + " barrage makes at least a triangle");
            List<Vec3d> clumps = new ArrayList<>();
            for (int group = 0; group < corners; group++) clumps.add(HiveFormation.clusterCentre(type, Vec3d.ZERO, .6, 1.8, group, corners, 55));
            require(distinct(clumps) == corners, type + " pattern corners are apart");
            require(pattern(type, corners) && !pattern(type, corners - 1), type + " pattern needs exactly " + corners + " clumps");
        }
        require(HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET) == 16, "a tesseract has 16 corners");
        require(HiveFigures.minimum(HiveType.RF, AttackMode.CONTAINMENT) == 12, "the RF cage is an icosahedron at least");
    }

    /** Whether {@code groups} clumps make the family's pattern: a crown with raised points, a star, an octagon. */
    private static boolean pattern(HiveType type, int groups) {
        return switch (type) {
            case RF -> {
                double low = HiveFormation.clusterCentre(type, Vec3d.ZERO, .6, 1.8, 0, groups, 0).y;
                boolean raised = false;
                for (int group = 1; group < groups; group++) raised |= HiveFormation.clusterCentre(type, Vec3d.ZERO, .6, 1.8, group, groups, 0).y > low + .5;
                yield raised;
            }
            case MANA -> HiveFormation.clusterLinks(type, groups).length > groups;
            case TWINS -> groups >= 8 && HiveFormation.clusterLinks(type, groups).length >= 8;
        };
    }

    /** Places in the air: they add up, never exceed the drones or the room, and never leave a mode short of its figure. */
    private static void seatsAddUp() {
        Random random = new Random(7);
        for (int trial = 0; trial < 20_000; trial++) {
            int[] pools = new int[3], minimums = new int[3];
            for (int mode = 0; mode < 3; mode++) {
                minimums[mode] = 3 + random.nextInt(22);
                pools[mode] = random.nextInt(4) == 0 ? 0 : minimums[mode] + random.nextInt(random.nextBoolean() ? 40 : 700);
            }
            int available = random.nextInt(HiveType.MAX_DEPLOYED + 1);
            int[] seats = HiveFlightPlan.seats(pools, minimums, available);
            int sum = 0, surviving = 0;
            boolean dropped = false;
            for (int mode = 0; mode < 3; mode++) {
                require(seats[mode] == 0 || seats[mode] >= minimums[mode], "a mode flies a whole figure or not at all: " + seats[mode] + " of min " + minimums[mode]);
                require(seats[mode] <= pools[mode], "a mode never flies more drones than it has");
                sum += seats[mode];
                if (seats[mode] > 0) surviving += pools[mode];
                dropped |= pools[mode] > 0 && seats[mode] == 0;
            }
            require(sum <= available, "no more places than there is room for");
            require(sum == Math.min(available, surviving), "the places add up: " + sum + " of " + available + " for " + surviving + " drones");
            if (!dropped && pools[0] + pools[1] + pools[2] > available) require(sum == available, "every place is taken");
        }
        // The task's example: 60 in Droplet, 120 in Barrage, 70 in Containment fly exactly so.
        HiveFlightPlan plan = HiveFlightPlan.of(HiveType.RF, 400, new HiveSettings(0, 60, 120, 70));
        require(plan.wing(AttackMode.DROPLET).slots() == 60 && plan.wing(AttackMode.BARRAGE).slots() == 120 && plan.wing(AttackMode.CONTAINMENT).slots() == 70,
                "60 / 120 / 70 all fly");
        // A whole hive: shared in proportion, 250 in the air, and each wing's drones next to each other.
        plan = HiveFlightPlan.of(HiveType.TWINS, HiveType.MAX_DRONES, new HiveSettings(100, 900, 600, 400));
        require(plan.healerSlots() == 100 && plan.slots() == 150, "healers fly first, the modes share the rest");
        int base = 0;
        for (HiveFlightPlan.Wing wing : plan.wings()) {
            require(wing.base() == base, "each wing's drones follow the last's");
            base += wing.pool();
        }
        require(plan.wing(AttackMode.DROPLET).slots() > plan.wing(AttackMode.BARRAGE).slots()
                && plan.wing(AttackMode.BARRAGE).slots() > plan.wing(AttackMode.CONTAINMENT).slots(), "the places follow the drones given");
        // Shares are rounded first: 16 and 238 of 250 places come to 15.7 and 234.3, and the Droplet's rounds up to its tesseract.
        int[] rounded = HiveFlightPlan.seats(new int[]{16, 238, 0}, new int[]{16, 4, 18}, 250);
        require(rounded[0] == 16 && rounded[1] == 234, "a share rounded up to the minimum flies: " + java.util.Arrays.toString(rounded));
        // A small mode squeezed by a large one does not fly, and its places go to the others.
        plan = HiveFlightPlan.of(HiveType.RF, HiveType.MAX_DRONES, new HiveSettings(0, 16, 1_900, 0));
        require(plan.wing(AttackMode.DROPLET).grounded() && plan.wing(AttackMode.BARRAGE).slots() == HiveType.MAX_DEPLOYED, "a squeezed mode stays home");
    }

    /** The console's step buttons: off to the minimum in one step, back to off from it, nothing between. */
    private static void buttons() {
        for (HiveType type : HiveType.values()) for (AttackMode mode : AttackMode.values()) {
            int minimum = HiveFigures.minimum(type, mode), capacity = 100;
            HiveSettings off = new HiveSettings(0, 0, 0, 0);
            require(off.adjust(type, capacity, mode, 1).value() == minimum && off.adjust(type, capacity, mode, 1).allowed(), "+1 from off gives the minimum");
            require(off.adjust(type, capacity, mode, 10).value() == Math.max(10, minimum), "+10 from off gives at least the minimum");
            require(off.adjust(type, capacity, mode, -1).refusal() == HiveSettings.Refusal.NOTHING_LEFT, "nothing to take from an empty mode");
            HiveSettings least = off.with(mode, minimum);
            require(least.adjust(type, capacity, mode, -1).value() == 0 && least.adjust(type, capacity, mode, -1).allowed(), "-1 from the minimum switches off");
            require(least.adjust(type, capacity, mode, -10).value() == 0, "-10 from the minimum switches off");
            HiveSettings above = off.with(mode, minimum + 2);
            require(above.adjust(type, capacity, mode, -1).allowed() && above.adjust(type, capacity, mode, -1).value() == minimum + 1, "-1 above the minimum");
            if (minimum + 2 - 10 >= 1) require(above.adjust(type, capacity, mode, -10).refusal() == HiveSettings.Refusal.BELOW_MINIMUM,
                    "a step that would leave 1 to minimum - 1 drones is refused");
            HiveSettings crowded = new HiveSettings(capacity - minimum + 1, 0, 0, 0);
            require(crowded.adjust(type, capacity, mode, 1).refusal() == HiveSettings.Refusal.BELOW_MINIMUM, "too few free drones for a figure");
            HiveSettings packed = new HiveSettings(capacity, 0, 0, 0);
            require(packed.adjust(type, capacity, mode, 1).refusal() == HiveSettings.Refusal.NONE_FREE, "no free drones");
            require(packed.adjustHealers(type, capacity, 1).refusal() == HiveSettings.Refusal.NONE_FREE, "healers only come from free drones");
            for (int count = 0; count <= capacity; count++) {
                HiveSettings settings = off.with(mode, count);
                for (int delta : new int[]{-10, -1, 1, 10}) {
                    HiveSettings.Change change = settings.adjust(type, capacity, mode, delta);
                    if (change.allowed()) require(HiveFigures.allowed(type, mode, change.value()), "an allowed step lands on an allowed count");
                }
            }
            require(off.allInto(type, minimum - 1 + 0, mode).refusal() != null || minimum == 1, "all into a mode too big for the hive is refused");
            require(off.allInto(type, capacity, mode).value() == capacity, "all into a mode takes every fighter");
        }
    }

    /** Old saves and shrinking hives: modes that no longer fit are switched off, with a notice. */
    private static void resolving() {
        HiveSettings old = HiveSettings.legacy(10, AttackMode.CONTAINMENT);
        HiveSettings twins = old.resolve(HiveType.TWINS, 100);
        require(twins.containment() == 90 && twins.notice() == null && twins.healers() == 10, "an old save puts every fighter in its mode");
        HiveSettings small = HiveSettings.legacy(80, AttackMode.CONTAINMENT).resolve(HiveType.TWINS, 100);
        require(small.containment() == 0 && small.barrage() == 20 && small.notice().kind() == HiveSettings.Notice.Kind.MOVED, "too few for the mode: Barrage");
        HiveSettings none = HiveSettings.legacy(95, AttackMode.DROPLET).resolve(HiveType.TWINS, 100);
        require(none.assigned() == 0 && none.notice().kind() == HiveSettings.Notice.Kind.EMPTY && none.notice().had() == 5, "too few for any: nothing");
        HiveSettings shrunk = new HiveSettings(0, 40, 30, 30).resolve(HiveType.RF, 80);
        require(shrunk.droplet() == 40 && shrunk.barrage() == 30 && shrunk.containment() == 0, "the last mode that no longer fits is switched off");
        require(shrunk.notice().kind() == HiveSettings.Notice.Kind.CUT && shrunk.notice().mode() == AttackMode.CONTAINMENT && shrunk.notice().had() == 10
                && shrunk.notice().need() == HiveFigures.minimum(HiveType.RF, AttackMode.CONTAINMENT), "and the notice says why");
        require(shrunk.resolve(HiveType.RF, 80) == shrunk, "resolved orders stay as they are");
        HiveSettings emptied = new HiveSettings(0, 100, 0, 18).resolve(HiveType.RF, 100);
        require(emptied.containment() == 0 && emptied.notice() != null && emptied.notice().mode() == AttackMode.CONTAINMENT && emptied.notice().had() == 0,
                "a mode squeezed out to nothing is switched off with a notice too");
        HiveSettings crafted = new HiveSettings(0, 5, 0, 0).resolve(HiveType.RF, 100);
        require(crafted.droplet() == 0 && crafted.notice().kind() == HiveSettings.Notice.Kind.CUT, "a count below the minimum never stands");
        require(HiveSettings.DEFAULT.resolve(HiveType.MANA, 100).barrage() == 100, "a new hive fights in Barrage");
    }

    private static void sameCorners(List<Vec3d> placed, List<Vec3d> corners, String what) {
        require(placed.size() == corners.size() && distinct(placed) == placed.size(), what + ": " + distinct(placed) + " distinct drones for " + corners.size() + " corners");
        for (Vec3d corner : corners) require(placed.stream().anyMatch(point -> point.distanceTo(corner) < 1e-9), what + ": a corner has no drone");
    }

    private static int distinct(List<Vec3d> points) {
        List<Vec3d> seen = new ArrayList<>();
        for (Vec3d point : points) if (seen.stream().noneMatch(other -> other.distanceTo(point) < 1e-6)) seen.add(point);
        return seen.size();
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
