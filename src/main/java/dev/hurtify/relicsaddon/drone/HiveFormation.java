package dev.hurtify.relicsaddon.drone;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Shared, deterministic hive positions: the server lands blows and launches charges from these points
 * and the client draws the drones there, so the motion is analytic rather than a client-only random walk.
 *
 * <p>Fighters hold places ({@link HiveSlots}) in two to sixteen strike groups. Where a place is depends
 * on the attack mode:
 * <ul>
 *   <li>Droplet: each group forms one big figure ({@link HiveShapes}) at its own place in a fan behind
 *   and above the owner, and in turn flies at the target like a projectile, strikes it whole at
 *   {@link #IMPACT} of its cycle and flies back to re-form.</li>
 *   <li>Barrage: each group is a dense clump around the target, and the clumps together make a pattern
 *   (an RF crown, a Mana star, Twins octagons). A clump charges and fires at {@link #FIRE}; a few drones
 *   hop to a neighbouring clump now and then.</li>
 *   <li>Containment: the whole swarm builds one construct around the target.</li>
 * </ul>
 * Drones leave the hive one after another along their own curved paths and come home the same way.
 */
public final class HiveFormation {
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    /** Share of a deployment over which departures are staggered. */
    private static final double STAGGER = .45;
    /** Droplet cycle: a group waits formed up, launches so it reaches the target at {@link #IMPACT}, holds there briefly and flies back. */
    public static final double IMPACT = .7;
    /** Barrage cycle: charge until {@link #FIRE}, then cool down. */
    public static final double FIRE = .8;
    /** Ticks a hit drone takes to fly home. */
    public static final int RETURN_TICKS = 24;
    /** Containment constructs (the RF torus, the Mana ward, the Twins rift spheres) are this many times their base size. */
    public static final double CONTAINMENT_SCALE = 3;
    /** Droplet fan: half its opening angle (a half circle from shoulder to shoulder), how far apart its figures sit, and how far it leans back from the target. */
    private static final double FAN = Math.PI / 2, FAN_SPACING = 2.7, FAN_LEAN = .44;

    public static Vec3 idle(Vec3 owner, float yaw, int index, int count, HiveType type, double time) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        return belt(owner, yaw, type).add(fibonacciSphere(index, count, .045, .6));
    }

    public static Vec3 belt(Vec3 owner, float yaw, HiveType type) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        return owner.add(right.scale((type.ordinal() - 1) * .16)).add(forward.scale(.22)).add(0, .86, 0);
    }

    /** Healers: a slowly turning ring around the owner's chest, wider and deeper as their number grows. */
    public static Vec3 healing(Vec3 owner, float yaw, int index, int count, HiveType type, double time, double progress) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        time = safeTime(time);
        Vec3 rest = idle(owner, yaw, index, count, type, time);
        double depth = Math.min(1.6, .3 + .07 * Math.sqrt(count));
        double radius = 1.15 + depth * hash(index, type, 11);
        double angle = index * GOLDEN_ANGLE + time * (.028 + .012 * hash(index, type, 12)) * (1.2 - .4 * radius / 2.75);
        double height = 1.0 + (hash(index, type, 13) - .5) * (.35 + depth * .4) + .05 * Math.sin(time * .09 + index);
        Vec3 deployed = owner.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
        return path(rest, deployed, stagger(index, type, progress), index, type);
    }

    /** Ticks the flight out to a target takes: longer for far targets, so a swarm never teleports across 128 blocks. */
    public static int travelTicks(double distance) {
        return (int) Math.clamp(Math.round(distance / 2.2), 20, 70);
    }

    /**
     * Where a group's cycle stands at {@code time}, 0 to 1, or below 0 before it starts. Each group
     * starts its first cycle a share of the interval after the last, so from the start they are spread
     * evenly and none begins in the middle of a flight.
     */
    public static double groupPhase(double time, double cycleStart, int interval, int group, int groups) {
        double phase = (time - cycleStart) / Math.max(1, interval) - group / (double) Math.max(1, groups);
        return phase < 0 ? phase : phase - Math.floor(phase);
    }

    /** Whether tick {@code now} is the first tick at or after a group's cycle passes {@code mark} (0..1); drives blows and shots. */
    public static boolean passes(long now, long cycleStart, int interval, int group, int groups, double mark) {
        interval = Math.max(1, interval);
        long first = (long) Math.ceil((group / (double) Math.max(1, groups) + mark) * interval - 1e-9);
        long elapsed = now - cycleStart - first;
        return elapsed >= 0 && elapsed % interval == 0;
    }

    /** Centre of the construct around a target: a little above its middle. */
    public static Vec3 core(Vec3 target, double targetHeight) {
        return target.add(0, saneSize(targetHeight, 1.8) * .55, 0);
    }

    // --- droplet ---------------------------------------------------------------------------------

    /**
     * Droplet: where a group forms up. The groups fill arcs of a fan behind and above their owner,
     * inner arc first, leaning back from the target, so a launched figure flies past overhead.
     */
    public static Vec3 muster(Vec3 owner, Vec3 target, int group, int groups, double time) {
        groups = Math.max(1, groups);
        group = Math.clamp(group, 0, groups - 1);
        Vec3 toward = new Vec3(target.x - owner.x, 0, target.z - owner.z);
        Vec3 forward = toward.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : toward.normalize();
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        Vec3 up = new Vec3(0, Math.cos(FAN_LEAN), 0).subtract(forward.scale(Math.sin(FAN_LEAN)));
        int row = 0, first = 0, inRow;
        while (true) {
            inRow = Math.min(groups - first, fanCapacity(row));
            if (group < first + inRow) break;
            first += inRow;
            row++;
        }
        double radius = fanRadius(row);
        double angle = inRow == 1 ? 0 : -FAN + 2 * FAN * (group - first + .5) / inRow;
        Vec3 pivot = owner.add(0, 1.5, 0).subtract(forward.scale(.9));
        double bob = .12 * Math.sin(safeTime(time) * .08 + group * 1.3);
        return pivot.add(up.scale(Math.cos(angle) * radius + bob)).add(side.scale(Math.sin(angle) * radius));
    }

    private static double fanRadius(int row) {
        return 2.6 + 2.6 * row;
    }

    private static int fanCapacity(int row) {
        return Math.max(1, (int) (fanRadius(row) * 2 * FAN / FAN_SPACING));
    }

    /** Ticks a figure takes from its place in the fan to the target: quick, at least 5, at most 40% of a cycle. */
    public static double flightTicks(double distance, int interval) {
        return Math.clamp(distance / 1.6, 5, Math.max(5, interval * .4));
    }

    /**
     * Droplet: how far through its sortie a group is. Below 0 it waits in the fan; 0 to 1 it flies out,
     * reaching the target at 1; it holds there at 1; from 1 to 2 it flies home.
     */
    public static double sortie(Vec3 owner, Vec3 target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        interval = Math.max(1, interval);
        double phase = groupPhase(time, cycleStart, interval, group, groups);
        if (phase < 0) return -1;
        Vec3 home = muster(owner, target, group, groups, time), core = core(target, targetHeight);
        double flight = flightTicks(home.distanceTo(core), interval) / interval;
        double launch = IMPACT - flight;
        if (phase < launch) return -1;
        if (phase < IMPACT) return (phase - launch) / flight;
        double release = release(interval);
        if (phase < release) return 1;
        return 1 + (phase - release) / (1 - release);
    }

    /** End of a figure's short hold on the target, so the blow (the first tick past {@link #IMPACT}) lands on it. */
    private static double release(int interval) {
        return IMPACT + Math.min(3, .1 * interval) / Math.max(1, interval);
    }

    /** Droplet: centre of a group's figure at {@code time}: in the fan, flying at the target, or flying home. */
    public static Vec3 dropletCentre(Vec3 owner, Vec3 target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        Vec3 home = muster(owner, target, group, groups, time), core = core(target, targetHeight);
        double sortie = sortie(owner, target, targetHeight, group, groups, time, cycleStart, interval);
        if (sortie <= 0) return home;
        double distance = home.distanceTo(core);
        if (sortie <= 1) {
            // Launched from rest, it gathers speed all the way in over a shallow arc.
            double arc = Math.min(2.5, distance * .12);
            return home.lerp(core, sortie * sortie).add(0, Math.sin(Math.PI * sortie) * arc, 0);
        }
        // Home on a wider arc swung out to one side, clear of the figures still flying in.
        double s = sortie - 1, eased = s * s * (3 - 2 * s), swing = Math.sin(Math.PI * s) * Math.min(4, distance * .2);
        Vec3 across = new Vec3(-(core.z - home.z), 0, core.x - home.x);
        across = across.lengthSqr() < 1e-6 ? Vec3.ZERO : across.normalize().scale(group % 2 == 0 ? 1 : -1);
        return core.lerp(home, eased).add(0, swing * .6, 0).add(across.scale(swing));
    }

    /** Size of a droplet figure for a group of {@code members}. */
    public static double shapeSize(int members) {
        return .55 + .1 * Math.cbrt(Math.max(1, members));
    }

    // --- barrage ---------------------------------------------------------------------------------

    /**
     * Barrage: centre of a group's clump, where its charge builds. The clumps themselves make the
     * pattern: RF a crown with alternate points raised, Mana a breathing star between a wide low ring
     * and a narrow high one, Twins octagons of up to eight clumps stacked and turning against each other.
     */
    public static Vec3 clusterCentre(HiveType type, Vec3 target, double targetWidth, double targetHeight, int group, int groups, double time) {
        groups = Math.max(1, groups);
        group = Math.clamp(group, 0, groups - 1);
        time = safeTime(time);
        Vec3 core = core(target, targetHeight);
        double reach = 2.7 + .13 * groups + saneSize(targetWidth, .6) * .5;
        return core.add(switch (type) {
            case RF -> {
                double angle = group * Math.PI * 2 / groups + time * .004;
                double lift = groups > 3 && group % 2 == 1 ? .75 : 0;
                yield new Vec3(Math.cos(angle) * reach, 1.3 + lift, Math.sin(angle) * reach);
            }
            case MANA -> {
                boolean inner = groups > 1 && group % 2 == 1;
                double angle = group * Math.PI * 2 / groups - time * .003;
                double r = reach * (1 + .05 * Math.sin(time * .04)) * (inner ? .58 : 1);
                yield new Vec3(Math.cos(angle) * r, inner ? 2.3 : .8, Math.sin(angle) * r);
            }
            case TWINS -> {
                int ring = group / 8, index = group % 8, inRing = Math.min(8, groups - ring * 8);
                double angle = index * Math.PI * 2 / inRing + (ring % 2 == 0 ? 1 : -1) * time * .005 + ring * Math.PI / 8;
                double r = reach * (1 - .28 * ring);
                yield new Vec3(Math.cos(angle) * r, 1.0 + 1.5 * ring, Math.sin(angle) * r);
            }
        });
    }

    /** The pattern's lines, as pairs of groups: the crown's ring, the star's zigzag and outer ring, the octagons and the struts between them. */
    public static int[][] clusterLinks(HiveType type, int groups) {
        if (groups < 2) return new int[0][];
        List<int[]> links = new ArrayList<>();
        switch (type) {
            case RF, MANA -> {
                for (int group = 0; group < (groups == 2 ? 1 : groups); group++) links.add(new int[]{group, (group + 1) % groups});
                if (type == HiveType.MANA && groups >= 6) {
                    int outer = (groups + 1) / 2;
                    for (int k = 0; k < outer; k++) {
                        int a = 2 * k, b = 2 * ((k + 1) % outer);
                        if (a != b) links.add(new int[]{a, b});
                    }
                }
            }
            case TWINS -> {
                for (int ring = 0; ring * 8 < groups; ring++) {
                    int size = Math.min(8, groups - ring * 8);
                    for (int index = 0; index < (size == 2 ? 1 : size); index++) {
                        if (size > 1) links.add(new int[]{ring * 8 + index, ring * 8 + (index + 1) % size});
                    }
                    if (ring > 0) for (int index = 0; index < size; index++) links.add(new int[]{(ring - 1) * 8 + index, ring * 8 + index});
                }
            }
        }
        return links.toArray(int[][]::new);
    }

    /** Radius of a barrage clump of {@code members} drones. */
    public static double clumpRadius(int members) {
        return .36 + .1 * Math.cbrt(Math.max(1, members));
    }

    /**
     * Where fighter place {@code slot} of {@code slots} flies once deployed. {@code target} is the
     * target's feet; for containment the construct surrounds it. Droplet figures form up near
     * {@code owner}'s feet.
     */
    public static Vec3 station(AttackMode mode, HiveType type, int slot, int slots, Vec3 owner, Vec3 target, double targetWidth,
            double targetHeight, double time, double cycleStart, int interval) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        time = safeTime(time);
        targetWidth = saneSize(targetWidth, .6);
        targetHeight = saneSize(targetHeight, 1.8);
        int groups = HiveSlots.groups(slots, mode), group = HiveSlots.group(slot, groups), member = HiveSlots.member(slot, groups);
        int members = HiveSlots.groupSize(group, slots, groups);
        Vec3 core = core(target, targetHeight);
        return switch (mode) {
            case DROPLET -> {
                Vec3 centre = dropletCentre(owner, target, targetHeight, group, groups, time, cycleStart, interval);
                Vec3 facing = core.subtract(muster(owner, target, group, groups, time));
                double size = shapeSize(members);
                yield centre.add(switch (type) {
                    case RF -> HiveShapes.tesseract(member, members, time + group * 17, size);
                    case MANA -> HiveShapes.droplet(member, members, time, facing, size);
                    case TWINS -> HiveShapes.hexagons(member, members, time + group * 11, facing, size);
                });
            }
            case BARRAGE -> {
                // A few drones hop to the next clump every eight seconds, gliding over for a second.
                int home = group;
                Vec3 at = barragePoint(type, home, member, members, groups, target, targetWidth, targetHeight, time, -1);
                if (member % 7 == 3 && groups > 1) {
                    double clock = time + member * 37 + group * 53;
                    long hops = (long) Math.floor(clock / 160);
                    double into = clock - hops * 160;
                    int from = (int) Math.floorMod(home + hops - 1, groups), to = (int) Math.floorMod(home + hops, groups);
                    // A visiting drone circles just outside the clump it drops in on.
                    Vec3 there = barragePoint(type, to, member, members, groups, target, targetWidth, targetHeight, time, to == home ? -1 : home);
                    if (into < 24) {
                        Vec3 before = barragePoint(type, from, member, members, groups, target, targetWidth, targetHeight, time, from == home ? -1 : home);
                        double t = into / 24;
                        at = before.lerp(there, t * t * (3 - 2 * t)).add(0, Math.sin(Math.PI * t) * .8, 0);
                    } else at = there;
                }
                yield at;
            }
            case CONTAINMENT -> containmentCentre(type, target, targetWidth, targetHeight, slots).add(switch (type) {
                case RF -> HiveShapes.torus(slot, slots, time, torusMajor(targetWidth), torusMinor(slots));
                case MANA -> HiveShapes.ward(slot, slots, time, wardScale(targetHeight));
                case TWINS -> HiveShapes.riftSpheres(slot, slots, time, riftDistance(targetWidth), riftRadius());
            });
        };
    }

    /**
     * A drone's place in clump {@code group}. A visitor (from clump {@code visitorFrom}, or -1 for a clump's
     * own drones) circles just outside it, turned by its home clump so that visitors from different
     * clumps never share a place.
     */
    // --- containment -----------------------------------------------------------------------------

    /**
     * Centre of a containment construct: the target's middle, raised just enough that the construct's
     * lowest point clears the ground the target stands on, so the ground never cuts it off.
     */
    public static Vec3 containmentCentre(HiveType type, Vec3 target, double targetWidth, double targetHeight, int slots) {
        targetWidth = saneSize(targetWidth, .6);
        targetHeight = saneSize(targetHeight, 1.8);
        Vec3 core = core(target, targetHeight);
        double below = switch (type) {
            case RF -> torusMinor(slots);
            case MANA -> 1.55 * wardScale(targetHeight);
            case TWINS -> riftRadius() - .02 * riftDistance(targetWidth);
        };
        double clear = below + .15 - (core.y - target.y);
        return clear > 0 ? core.add(0, clear, 0) : core;
    }

    public static double torusMajor(double targetWidth) { return Math.max(1.25, saneSize(targetWidth, .6) * .7 + .8) * CONTAINMENT_SCALE; }
    public static double torusMinor(int slots) { return (.42 + .02 * Math.cbrt(Math.max(1, slots))) * CONTAINMENT_SCALE; }
    public static double wardScale(double targetHeight) { return Math.max(1, saneSize(targetHeight, 1.8) / 1.8) * CONTAINMENT_SCALE; }
    public static double riftDistance(double targetWidth) { return Math.max(1.5, saneSize(targetWidth, .6) * .7 + 1.1) * CONTAINMENT_SCALE; }
    public static double riftRadius() { return HiveShapes.RIFT_RADIUS * CONTAINMENT_SCALE; }

    private static Vec3 barragePoint(HiveType type, int group, int member, int members, int groups, Vec3 target,
            double targetWidth, double targetHeight, double time, int visitorFrom) {
        Vec3 centre = clusterCentre(type, target, targetWidth, targetHeight, group, groups, time);
        Vec3 facing = core(target, targetHeight).subtract(centre);
        double turn = visitorFrom < 0 ? 0 : visitorFrom * 41 + 17;
        return centre.add(HiveShapes.clump(type, member, members, time + group * 29 + turn, facing, clumpRadius(members))
                .scale(visitorFrom < 0 ? 1 : 1.45));
    }

    /**
     * A deployed drone's position: flying out from the hive along its own curve until it reaches
     * {@code station}. {@code launched} is when this drone set off (the combat start, or when it
     * replaced a hit drone) and {@code travel} how long the flight out takes.
     */
    public static Vec3 deployed(Vec3 owner, float yaw, Vec3 station, int unit, int count, HiveType type,
            double time, double launched, int travel) {
        Vec3 rest = idle(owner, yaw, unit, count, type, time);
        double progress = (time - launched) / Math.max(1, travel);
        return path(rest, station, stagger(unit, type, progress), unit, type);
    }

    /** A hit drone on its way home, from where it was struck back into the hive. */
    public static Vec3 returning(Vec3 owner, float yaw, Vec3 struckAt, int unit, int count, HiveType type, double time, double hitAt) {
        Vec3 rest = idle(owner, yaw, unit, count, type, time);
        double progress = (time - hitAt) / RETURN_TICKS;
        return path(struckAt, rest, progress, unit, type);
    }

    /**
     * This drone's own share of a deployment: it sets off after a delay of its own and arrives by the
     * end, so a swarm pours out of the hive; a recall runs the same timeline backwards.
     */
    public static double stagger(int index, HiveType type, double progress) {
        double delay = hash(index, type, 21) * STAGGER;
        return Math.clamp((Math.clamp(progress, 0, 1) - delay) / (1 - STAGGER), 0, 1);
    }

    /** Curved flight from {@code from} to {@code to}: each drone bows out to its own side and rises a little. */
    private static Vec3 path(Vec3 from, Vec3 to, double progress, int index, HiveType type) {
        double p = Math.clamp(progress, 0, 1);
        if (p <= 0) return from;
        if (p >= 1) return to;
        double t = p * p * (3 - 2 * p);
        Vec3 span = to.subtract(from);
        double length = span.length();
        if (length < 1e-6) return to;
        Vec3 forward = span.scale(1 / length);
        Vec3 side = forward.cross(Math.abs(forward.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 lift = side.cross(forward);
        double angle = hash(index, type, 31) * Math.PI * 2;
        double bow = Math.min(2.2, length * (.18 + .22 * hash(index, type, 32)));
        Vec3 control = from.lerp(to, .5).add(side.scale(Math.cos(angle) * bow)).add(lift.scale(Math.abs(Math.sin(angle)) * bow * .8));
        double u = 1 - t;
        return from.scale(u * u).add(control.scale(2 * u * t)).add(to.scale(t * t));
    }

    private static Vec3 fibonacciSphere(int index, int count, double radius, double vertical) {
        double y = 1.0 - 2.0 * (index + .5) / count;
        double around = index * GOLDEN_ANGLE;
        double horizontal = Math.sqrt(Math.max(0, 1 - y * y));
        return new Vec3(Math.cos(around) * horizontal * radius, y * radius * vertical, Math.sin(around) * horizontal * radius);
    }

    /** Stable per-drone random number in [0, 1). */
    private static double hash(int index, HiveType type, int salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ (type.ordinal() + 1) * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static int safeCount(int count) { return Math.clamp(count, 1, HiveType.MAX_DRONES); }
    private static double safeTime(double time) { return Double.isFinite(time) ? time : 0; }
    private static double saneSize(double value, double fallback) { return Double.isFinite(value) ? Math.max(.1, value) : fallback; }
    private HiveFormation() { }
}
