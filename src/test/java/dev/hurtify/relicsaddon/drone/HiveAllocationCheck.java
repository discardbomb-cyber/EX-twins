package dev.hurtify.relicsaddon.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

/**
 * How drones are shared between the attack modes: each mode's minimum is the corner count of its figure
 * (the drones placed for exactly that many stand on exactly those corners, and one corner fewer breaks the
 * figure), the 250 places in the air are shared out without a mode ever flying fewer than its minimum,
 * and the console's buttons never make an allocation the server would refuse.
 */
public final class HiveAllocationCheck {
    private static final Vec3 FORWARD = new Vec3(.3, -.2, 1);

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
            List<Vec3> tesseract = new ArrayList<>(), corners = new ArrayList<>();
            for (int m = 0; m < HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET); m++) tesseract.add(HiveShapes.tesseract(m, 16, time, size));
            for (int corner = 0; corner < 16; corner++) corners.add(HiveShapes.tesseractCorner(corner, time, size));
            sameCorners(tesseract, corners, "RF droplet");
            List<Vec3> drop = new ArrayList<>();
            int dropMinimum = HiveFigures.minimum(HiveType.MANA, AttackMode.DROPLET);
            for (int m = 0; m < dropMinimum; m++) drop.add(HiveShapes.droplet(m, dropMinimum, time, FORWARD, size));
            require(distinct(drop) == dropMinimum, "the Mana drop's corners are all apart");
            Vec3 axis = FORWARD.normalize();
            for (Vec3 point : drop) {
                require(point.dot(axis) <= drop.getFirst().dot(axis) + 1e-9, "the drop's tip is its frontmost corner");
                require(point.dot(axis) >= drop.getLast().dot(axis) - 1e-9, "the drop's back is its rearmost corner");
            }
            int hexMinimum = HiveFigures.minimum(HiveType.TWINS, AttackMode.DROPLET);
            List<Vec3> hexagons = new ArrayList<>(), hexCorners = new ArrayList<>();
            for (int m = 0; m < hexMinimum; m++) hexagons.add(HiveShapes.hexagons(m, hexMinimum, time, FORWARD, size));
            int rings = HiveShapes.hexagonCount(hexMinimum);
            require(rings == HiveShapes.MIN_HEXAGONS, "the fewest Twins drones make the fewest hexagons");
            for (int ring = 0; ring < rings; ring++) {
                Vec3 centre = HiveShapes.hexagonCentre(ring, rings, time, FORWARD, size);
                double spin = time * (ring % 2 == 0 ? .05 : -.05);
                for (int corner = 0; corner < HiveShapes.HEXAGON_CORNERS; corner++) {
                    hexCorners.add(centre.add(HiveShapes.hexagonPoint(corner, spin, FORWARD, size * .45)));
                }
            }
            sameCorners(hexagons, hexCorners, "Twins droplet");

            // Containment: the Mana ward's corners are the rhombi's (their tips shared), the tori's their core polygons.
            int wardMinimum = HiveFigures.minimum(HiveType.MANA, AttackMode.CONTAINMENT);
            List<Vec3> ward = new ArrayList<>(), rhombi = new ArrayList<>();
            for (int s = 0; s < wardMinimum; s++) ward.add(HiveShapes.wardPlace(s, wardMinimum, time, 1));
            for (int rhombus = 0; rhombus < 3; rhombus++) for (int corner = 0; corner < 4; corner++) {
                Vec3 point = HiveShapes.rhombusPoint(corner / 4.0, time * .008 + rhombus * Math.PI * 2 / 3);
                if (rhombi.stream().noneMatch(seen -> seen.distanceTo(point) < 1e-6)) rhombi.add(point);
            }
            sameCorners(ward, rhombi, "Mana ward");
            for (boolean dense : new boolean[]{false, true}) {
                HiveType type = dense ? HiveType.TWINS : HiveType.RF;
                int minimum = HiveFigures.minimum(type, AttackMode.CONTAINMENT), perRing = HiveShapes.ringCorners(dense);
                List<Vec3> tori = new ArrayList<>();
                for (int s = 0; s < minimum; s++) tori.add(HiveShapes.ringPlace(s, minimum, time, 2, dense));
                require(distinct(tori) == minimum && minimum == HiveShapes.RING_RADII.length * perRing, type + " tori have " + perRing + " corners each");
                for (int ring = 0; ring < HiveShapes.RING_RADII.length; ring++) {
                    // A torus's corners: evenly round its core line, each the same way out from it.
                    for (int corner = 0; corner < perRing; corner++) {
                        Vec3 expected = HiveShapes.ringPoint(ring, corner * Math.PI * 2 / perRing, Math.PI / HiveShapes.ringRows(dense), 1.12, time, 2, dense);
                        require(tori.stream().anyMatch(point -> point.distanceTo(expected) < 1e-9), type + " torus " + ring + " corner " + corner + " is flown");
                    }
                }
            }
        }

        // Barrage: the pattern needs its corners; a clump fewer is not that pattern any more.
        for (HiveType type : HiveType.values()) {
            int corners = HiveFigures.minimum(type, AttackMode.BARRAGE);
            require(corners >= 3, type + " barrage makes at least a triangle");
            List<Vec3> clumps = new ArrayList<>();
            for (int group = 0; group < corners; group++) clumps.add(HiveFormation.clusterCentre(type, Vec3.ZERO, .6, 1.8, group, corners, 55));
            require(distinct(clumps) == corners, type + " pattern corners are apart");
            require(pattern(type, corners) && !pattern(type, corners - 1), type + " pattern needs exactly " + corners + " clumps");
        }
        require(HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET) == 16, "a tesseract has 16 corners");
        require(HiveFigures.minimum(HiveType.MANA, AttackMode.CONTAINMENT) == 8, "three rhombi sharing their tips have 8 corners");
    }

    /** Whether {@code groups} clumps make the family's pattern: a crown with raised points, a star, an octagon. */
    private static boolean pattern(HiveType type, int groups) {
        return switch (type) {
            case RF -> {
                double low = HiveFormation.clusterCentre(type, Vec3.ZERO, .6, 1.8, 0, groups, 0).y;
                boolean raised = false;
                for (int group = 1; group < groups; group++) raised |= HiveFormation.clusterCentre(type, Vec3.ZERO, .6, 1.8, group, groups, 0).y > low + .5;
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
            // One more place never grounds a mode.
            if (available < HiveType.MAX_DEPLOYED) {
                int[] more = HiveFlightPlan.seats(pools, minimums, available + 1);
                for (int mode = 0; mode < 3; mode++) require(seats[mode] == 0 || more[mode] > 0, "a place more grounded a mode");
            }
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
                && shrunk.notice().need() == 18, "and the notice says why");
        require(shrunk.resolve(HiveType.RF, 80) == shrunk, "resolved orders stay as they are");
        HiveSettings crafted = new HiveSettings(0, 5, 0, 0).resolve(HiveType.RF, 100);
        require(crafted.droplet() == 0 && crafted.notice().kind() == HiveSettings.Notice.Kind.CUT, "a count below the minimum never stands");
        require(HiveSettings.DEFAULT.resolve(HiveType.MANA, 100).barrage() == 100, "a new hive fights in Barrage");
    }

    private static void sameCorners(List<Vec3> placed, List<Vec3> corners, String what) {
        require(placed.size() == corners.size() && distinct(placed) == placed.size(), what + ": " + distinct(placed) + " distinct drones for " + corners.size() + " corners");
        for (Vec3 corner : corners) require(placed.stream().anyMatch(point -> point.distanceTo(corner) < 1e-9), what + ": a corner has no drone");
    }

    private static int distinct(List<Vec3> points) {
        List<Vec3> seen = new ArrayList<>();
        for (Vec3 point : points) if (seen.stream().noneMatch(other -> other.distanceTo(point) < 1e-6)) seen.add(point);
        return seen.size();
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
