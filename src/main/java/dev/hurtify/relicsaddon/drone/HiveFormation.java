package dev.hurtify.relicsaddon.drone;

import net.minecraft.world.phys.Vec3;

/**
 * Shared, deterministic hive positions: the server lands blows and launches charges from these points
 * and the client draws the drones there, so the motion is analytic rather than a client-only random walk.
 *
 * <p>Fighters hold places ({@link HiveSlots}) in two to sixteen strike groups. Where a place is depends
 * on the attack mode:
 * <ul>
 *   <li>Droplet: each group builds its shape ({@link HiveShapes}) at its own post around and above the
 *   target, and in turn dives through the target and swings back out. The blow lands at
 *   {@link #IMPACT} of the group's cycle.</li>
 *   <li>Barrage: each group hangs at its post as a patterned cluster, charges and fires at {@link #FIRE};
 *   a few drones hop to a neighbouring cluster now and then.</li>
 *   <li>Containment: the whole swarm builds one construct around the target.</li>
 * </ul>
 * Drones leave the hive one after another along their own curved paths and come home the same way.
 */
public final class HiveFormation {
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    /** Share of a deployment over which departures are staggered. */
    private static final double STAGGER = .45;
    /** Droplet cycle: hover until {@link #DIVE}, dive until {@link #IMPACT}, then swing back to the post. */
    public static final double DIVE = .55, IMPACT = .75;
    /** Barrage cycle: charge until {@link #FIRE}, then cool down. */
    public static final double FIRE = .8;
    /** Ticks a hit drone takes to fly home. */
    public static final int RETURN_TICKS = 24;

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
     * evenly and none begins in the middle of a dive.
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

    /** A group's post around and above the target; groups spread over the upper half-sphere and drift round. */
    public static Vec3 post(Vec3 target, double targetWidth, double targetHeight, int group, int groups, double time, double reach) {
        double up = .22 + .5 * ((group * .6180339887 + .31) % 1);
        double angle = group * GOLDEN_ANGLE + time * .006;
        double ring = Math.sqrt(1 - up * up);
        double radius = reach + .2 * groups + saneSize(targetWidth, .6) * .5;
        return core(target, targetHeight).add(Math.cos(angle) * ring * radius, up * radius, Math.sin(angle) * ring * radius);
    }

    /** Droplet: centre of a group's shape at {@code time}: hovering at its post, diving through the target, swinging back. */
    public static Vec3 dropletCentre(Vec3 target, double targetWidth, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        // The group bobs gently at its post; dives leave from and swings return to that bobbing point.
        Vec3 hover = post(target, targetWidth, targetHeight, group, groups, time, 4.2).add(0, .15 * Math.sin(time * .1 + group), 0);
        Vec3 core = core(target, targetHeight);
        double phase = groupPhase(time, cycleStart, interval, group, groups);
        if (phase < DIVE) return hover;
        if (phase < IMPACT) {
            double s = (phase - DIVE) / (IMPACT - DIVE);
            return hover.lerp(core, s * s);
        }
        // It holds on the target for three ticks, so the blow (the first tick past IMPACT) lands on it.
        double release = IMPACT + 3.0 / Math.max(1, interval);
        if (phase < release) return core;
        double s = (phase - release) / (1 - release), eased = 1 - (1 - s) * (1 - s);
        return core.lerp(hover, eased).add(0, Math.sin(Math.PI * s) * 1.2, 0);
    }

    /** Barrage: centre of a group's cluster, where its charge builds. */
    public static Vec3 clusterCentre(Vec3 target, double targetWidth, double targetHeight, int group, int groups, double time) {
        return post(target, targetWidth, targetHeight, group, groups, time, 3.8);
    }

    /** Size of a droplet shape for a group of {@code members}. */
    public static double shapeSize(int members) {
        return .55 + .07 * Math.cbrt(Math.max(1, members));
    }

    /**
     * Where fighter place {@code slot} of {@code slots} flies once deployed. {@code target} is the
     * target's feet; for containment the construct surrounds it.
     */
    public static Vec3 station(AttackMode mode, HiveType type, int slot, int slots, Vec3 target, double targetWidth, double targetHeight,
            double time, double cycleStart, int interval) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        time = safeTime(time);
        targetWidth = saneSize(targetWidth, .6);
        targetHeight = saneSize(targetHeight, 1.8);
        int groups = HiveSlots.groups(slots), group = HiveSlots.group(slot, groups), member = HiveSlots.member(slot, groups);
        int members = HiveSlots.groupSize(group, slots, groups);
        Vec3 core = core(target, targetHeight);
        return switch (mode) {
            case DROPLET -> {
                Vec3 centre = dropletCentre(target, targetWidth, targetHeight, group, groups, time, cycleStart, interval);
                Vec3 facing = core.subtract(post(target, targetWidth, targetHeight, group, groups, time, 4.2));
                double size = shapeSize(members);
                yield centre.add(switch (type) {
                    case RF -> HiveShapes.tesseract(member, members, time + group * 17, size);
                    case MANA -> HiveShapes.droplet(member, members, time, facing, size);
                    case TWINS -> HiveShapes.hexagons(member, members, time + group * 11, facing, size);
                });
            }
            case BARRAGE -> {
                // A few drones hop to the next cluster every eight seconds, gliding over for a second.
                int home = group;
                Vec3 at = barragePoint(type, home, member, members, groups, target, targetWidth, targetHeight, time, 1);
                if (member % 7 == 3 && groups > 1) {
                    double clock = time + member * 37 + group * 53;
                    long hops = (long) Math.floor(clock / 160);
                    double into = clock - hops * 160;
                    int from = (int) Math.floorMod(home + hops - 1, groups), to = (int) Math.floorMod(home + hops, groups);
                    // A visiting drone keeps to the outer ring of the cluster it drops in on.
                    Vec3 there = barragePoint(type, to, member, members, groups, target, targetWidth, targetHeight, time, to == home ? 1 : 1.45);
                    if (into < 24) {
                        Vec3 before = barragePoint(type, from, member, members, groups, target, targetWidth, targetHeight, time, from == home ? 1 : 1.45);
                        double t = into / 24;
                        at = before.lerp(there, t * t * (3 - 2 * t)).add(0, Math.sin(Math.PI * t) * .8, 0);
                    } else at = there;
                }
                yield at;
            }
            case CONTAINMENT -> core.add(switch (type) {
                case RF -> HiveShapes.torus(slot, slots, time, Math.max(1.25, targetWidth * .7 + .8), .42 + .02 * Math.cbrt(slots));
                case MANA -> HiveShapes.ward(slot, slots, time, Math.max(1, targetHeight / 1.8));
                case TWINS -> HiveShapes.riftSpheres(slot, slots, time, Math.max(1.5, targetWidth * .7 + 1.1));
            });
        };
    }

    private static Vec3 barragePoint(HiveType type, int group, int member, int members, int groups, Vec3 target,
            double targetWidth, double targetHeight, double time, double spread) {
        Vec3 centre = clusterCentre(target, targetWidth, targetHeight, group, groups, time);
        Vec3 facing = core(target, targetHeight).subtract(centre);
        return centre.add(HiveShapes.cluster(type, member, members, time, facing).scale(spread));
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
