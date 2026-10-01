package dev.hurtify.relicsaddon.domain.hive;

import java.util.ArrayList;
import java.util.List;
import dev.hurtify.relicsaddon.domain.math.Vec3d;

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
    /**
     * Containment constructs are sized from the creature they hold: their inside clears its hitbox by a
     * fifth of its size on every side (the hitbox plus two fifths of it across). This is that clearance
     * as a radius from the creature's middle, per block of its larger side.
     */
    private static final double ENCLOSURE = 1.4 / 2;
    /** The Twins black hole's horizon per block of enclosure: wider than the creature it swallows. */
    private static final double HORIZON = 1.86;
    /** The Twins tori keep this much room round the black hole's horizon. */
    private static final double HORIZON_CLEARANCE = 1.25;
    /** Droplet fan: half its opening angle (a half circle from shoulder to shoulder), how far apart its figures sit, and how far it leans back from the target. */
    private static final double FAN = Math.PI / 2, FAN_SPACING = 2.7, FAN_LEAN = .44;

    public static Vec3d idle(Vec3d owner, float yaw, int index, int count, HiveType type, double time) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        return belt(owner, yaw, type).add(fibonacciSphere(index, count, .045, .6));
    }

    public static Vec3d belt(Vec3d owner, float yaw, HiveType type) {
        Vec3d forward = Vec3d.directionFromRotation(0.0F, yaw);
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        return owner.add(right.scale((type.ordinal() - 1) * .16)).add(forward.scale(.22)).add(0, .86, 0);
    }

    /** Healers: a slowly turning ring around the owner's chest, wider and deeper as their number grows. */
    public static Vec3d healing(Vec3d owner, float yaw, int index, int count, HiveType type, double time, double progress) {
        count = safeCount(count);
        index = Math.clamp(index, 0, count - 1);
        time = safeTime(time);
        Vec3d rest = idle(owner, yaw, index, count, type, time);
        double depth = Math.min(1.6, .3 + .07 * Math.sqrt(count));
        double radius = 1.15 + depth * hash(index, type, 11);
        double angle = index * GOLDEN_ANGLE + time * (.028 + .012 * hash(index, type, 12)) * (1.2 - .4 * radius / 2.75);
        double height = 1.0 + (hash(index, type, 13) - .5) * (.35 + depth * .4) + .05 * Math.sin(time * .09 + index);
        Vec3d deployed = owner.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
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
    public static Vec3d core(Vec3d target, double targetHeight) {
        return target.add(0, saneSize(targetHeight, 1.8) * .55, 0);
    }

    // --- droplet ---------------------------------------------------------------------------------

    /**
     * Droplet: where a group forms up. The groups fill arcs of a fan behind and above their owner,
     * inner arc first, leaning back from the target, so a launched figure flies past overhead.
     */
    public static Vec3d muster(Vec3d owner, Vec3d target, int group, int groups, double time) {
        groups = Math.max(1, groups);
        group = Math.clamp(group, 0, groups - 1);
        Vec3d toward = new Vec3d(target.x - owner.x, 0, target.z - owner.z);
        Vec3d forward = toward.lengthSqr() < 1e-6 ? new Vec3d(0, 0, 1) : toward.normalize();
        Vec3d side = new Vec3d(-forward.z, 0, forward.x);
        Vec3d up = new Vec3d(0, Math.cos(FAN_LEAN), 0).subtract(forward.scale(Math.sin(FAN_LEAN)));
        int row = 0, first = 0, inRow;
        while (true) {
            inRow = Math.min(groups - first, fanCapacity(row));
            if (group < first + inRow) break;
            first += inRow;
            row++;
        }
        double radius = fanRadius(row);
        double angle = inRow == 1 ? 0 : -FAN + 2 * FAN * (group - first + .5) / inRow;
        Vec3d pivot = owner.add(0, 1.5, 0).subtract(forward.scale(.9));
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
    public static double sortie(Vec3d owner, Vec3d target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return sortie(owner, target, target, targetHeight, group, groups, time, cycleStart, interval);
    }

    /** As above for a figure whose fan faces {@code fanTarget} while it strikes {@code strike}. */
    public static double sortie(Vec3d owner, Vec3d fanTarget, Vec3d strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        interval = Math.max(1, interval);
        double phase = groupPhase(time, cycleStart, interval, group, groups);
        if (phase < 0) return -1;
        Vec3d home = muster(owner, fanTarget, group, groups, time), core = core(strike, strikeHeight);
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

    /** Ticks a figure winds up before it launches: the RF tesseract closes into a cube. */
    public static final double WINDUP_TICKS = 8;

    /**
     * Droplet: how wound up a group's figure is, 0 to 1: rising over the {@link #WINDUP_TICKS} before it
     * launches, whole in flight, and easing off again on its way home.
     */
    public static double windup(Vec3d owner, Vec3d fanTarget, Vec3d strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        interval = Math.max(1, interval);
        double phase = groupPhase(time, cycleStart, interval, group, groups);
        if (phase < 0) return 0;
        Vec3d home = muster(owner, fanTarget, group, groups, time), core = core(strike, strikeHeight);
        double flight = flightTicks(home.distanceTo(core), interval) / interval, launch = IMPACT - flight;
        if (phase < launch) return Math.clamp(1 - (launch - phase) * interval / WINDUP_TICKS, 0, 1);
        double release = release(interval);
        if (phase < release) return 1;
        return Math.clamp(1 - (phase - release) / (1 - release) * 3, 0, 1);
    }

    /**
     * Droplet: whether a group's figure is between the rifts of its jump (Twins only), out of sight: it dives
     * into a rift behind its owner and comes out of another by the target, and back the same way.
     */
    public static boolean dropletHidden(HiveType type, double sortie) {
        return type == HiveType.TWINS && (sortie > RIFT_IN && sortie < RIFT_OUT || sortie > 2 - RIFT_OUT && sortie < 2 - RIFT_IN);
    }

    /** Where in its flight out a Twins figure enters the first rift and comes out of the second. */
    public static final double RIFT_IN = .3, RIFT_OUT = .55;

    /**
     * Twins: the rifts on a figure's path, where it vanishes and where it comes out: going in, one by its place in
     * the fan behind its owner and one by the target; coming home, the same the other way round.
     */
    public static final double[] RIFT_MARKS = {RIFT_IN, RIFT_OUT, 2 - RIFT_OUT, 2 - RIFT_IN};

    /** Droplet: centre of a group's figure at {@code time}: in the fan, flying at the target, or flying home. */
    public static Vec3d dropletCentre(Vec3d owner, Vec3d target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return dropletCentre(owner, target, target, targetHeight, group, groups, time, cycleStart, interval);
    }

    /** As above for a figure whose fan faces {@code fanTarget} while it strikes {@code strike}. */
    public static Vec3d dropletCentre(Vec3d owner, Vec3d fanTarget, Vec3d strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return dropletCentre(HiveType.RF, owner, fanTarget, strike, strikeHeight, group, groups, time, cycleStart, interval);
    }

    /**
     * As above for a figure of {@code type}. RF rams straight in over a shallow arc. A Mana drop climbs high over
     * its target and falls on it from above, like rain, and bursts back up after it lands. A Twins figure dives
     * into a rift behind its owner, comes out of another by the target and strikes, and goes home the same way.
     */
    public static Vec3d dropletCentre(HiveType type, Vec3d owner, Vec3d fanTarget, Vec3d strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        Vec3d home = muster(owner, fanTarget, group, groups, time), core = core(strike, strikeHeight);
        double sortie = sortie(owner, fanTarget, strike, strikeHeight, group, groups, time, cycleStart, interval);
        if (sortie <= 0) return home;
        // A Twins figure keeps the same path as RF's; the client only hides it between the rifts on that path.
        if (type == HiveType.MANA) return rainPath(home, core, sortie, group);
        return arcPath(home, core, sortie, group);
    }

    /** RF's flight (and the Twins figure's, rifts or not): in over a shallow arc, home on a wider one swung out to the side. */
    public static Vec3d arcPath(Vec3d home, Vec3d core, double sortie, int group) {
        if (sortie <= 0) return home;
        double distance = home.distanceTo(core);
        if (sortie <= 1) {
            // Launched from rest, it gathers speed all the way in over a shallow arc.
            double arc = Math.min(2.5, distance * .12);
            return home.lerp(core, sortie * sortie).add(0, Math.sin(Math.PI * sortie) * arc, 0);
        }
        // Home on a wider arc swung out to one side, clear of the figures still flying in.
        double s = sortie - 1, eased = s * s * (3 - 2 * s), swing = Math.sin(Math.PI * s) * Math.min(4, distance * .2);
        Vec3d across = new Vec3d(-(core.z - home.z), 0, core.x - home.x);
        across = across.lengthSqr() < 1e-6 ? Vec3d.ZERO : across.normalize().scale(group % 2 == 0 ? 1 : -1);
        return core.lerp(home, eased).add(0, swing * .6, 0).add(across.scale(swing));
    }

    /** Mana: up over the target, then straight down on it gathering speed; after it lands, a burst back up and home. */
    private static Vec3d rainPath(Vec3d home, Vec3d core, double sortie, int group) {
        double height = 5.5 + 1.8 * ((group * 0.6180339887) % 1);
        Vec3d apex = core.add(Math.cos(group * 2.4) * .6, height, Math.sin(group * 2.4) * .6);
        if (sortie <= 1) {
            double climb = .62;
            if (sortie < climb) {
                double t = sortie / climb, eased = t * t * (3 - 2 * t);
                return home.lerp(apex, eased).add(0, Math.sin(Math.PI * t) * 1.2, 0);
            }
            double t = (sortie - climb) / (1 - climb);
            return apex.lerp(core, t * t);
        }
        double s = sortie - 1;
        if (s < .4) {
            double t = s / .4;
            return core.lerp(apex.add(0, -1.5, 0), 1 - (1 - t) * (1 - t));
        }
        double t = (s - .4) / .6, eased = t * t * (3 - 2 * t);
        return apex.add(0, -1.5, 0).lerp(home, eased);
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
    public static Vec3d clusterCentre(HiveType type, Vec3d target, double targetWidth, double targetHeight, int group, int groups, double time) {
        groups = Math.max(1, groups);
        group = Math.clamp(group, 0, groups - 1);
        time = safeTime(time);
        Vec3d core = core(target, targetHeight);
        double reach = 2.7 + .13 * groups + saneSize(targetWidth, .6) * .5;
        return core.add(switch (type) {
            case RF -> {
                double angle = group * Math.PI * 2 / groups + time * .004;
                double lift = groups >= CROWN_POINTS && group % 2 == 1 ? .75 : 0;
                yield new Vec3d(Math.cos(angle) * reach, 1.3 + lift, Math.sin(angle) * reach);
            }
            case MANA -> {
                boolean inner = groups > 1 && group % 2 == 1;
                double angle = group * Math.PI * 2 / groups - time * .003;
                double r = reach * (1 + .05 * Math.sin(time * .04)) * (inner ? .58 : 1);
                yield new Vec3d(Math.cos(angle) * r, inner ? 2.3 : .8, Math.sin(angle) * r);
            }
            case TWINS -> {
                int ring = group / OCTAGON, index = group % OCTAGON, inRing = Math.min(OCTAGON, groups - ring * OCTAGON);
                double angle = index * Math.PI * 2 / inRing + (ring % 2 == 0 ? 1 : -1) * time * .005 + ring * Math.PI / OCTAGON;
                double r = reach * (1 - .28 * ring);
                yield new Vec3d(Math.cos(angle) * r, 1.0 + 1.5 * ring, Math.sin(angle) * r);
            }
        });
    }

    /** Clumps a pattern needs to be itself: an RF crown raises every other point of four or more, a Mana star closes its outer ring from six, a Twins octagon has eight. */
    public static final int CROWN_POINTS = 4, STAR_POINTS = 6, OCTAGON = 8;

    /** The corners of a family's barrage pattern: the fewest clumps, one drone each at least, that make it. */
    public static int patternCorners(HiveType type) {
        return switch (type) {
            case RF -> CROWN_POINTS;
            case MANA -> STAR_POINTS;
            case TWINS -> OCTAGON;
        };
    }

    /** The pattern's lines, as pairs of groups: the crown's ring, the star's zigzag and outer ring, the octagons and the struts between them. */
    public static int[][] clusterLinks(HiveType type, int groups) {
        if (groups < 2) return new int[0][];
        List<int[]> links = new ArrayList<>();
        switch (type) {
            case RF, MANA -> {
                for (int group = 0; group < (groups == 2 ? 1 : groups); group++) links.add(new int[]{group, (group + 1) % groups});
                if (type == HiveType.MANA && groups >= STAR_POINTS) {
                    int outer = (groups + 1) / 2;
                    for (int k = 0; k < outer; k++) {
                        int a = 2 * k, b = 2 * ((k + 1) % outer);
                        if (a != b) links.add(new int[]{a, b});
                    }
                }
            }
            case TWINS -> {
                for (int ring = 0; ring * OCTAGON < groups; ring++) {
                    int size = Math.min(OCTAGON, groups - ring * OCTAGON);
                    for (int index = 0; index < (size == 2 ? 1 : size); index++) {
                        if (size > 1) links.add(new int[]{ring * OCTAGON + index, ring * OCTAGON + (index + 1) % size});
                    }
                    if (ring > 0) for (int index = 0; index < size; index++) links.add(new int[]{(ring - 1) * OCTAGON + index, ring * OCTAGON + index});
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
    public static Vec3d station(AttackMode mode, HiveType type, int slot, int slots, Vec3d owner, Vec3d target, double targetWidth,
            double targetHeight, double time, double cycleStart, int interval) {
        return stationIn(mode, type, slot, slots, HiveSlots.groups(Math.max(1, slots), mode, type), owner, target, targetWidth, targetHeight,
                time, cycleStart, interval);
    }

    /** Ticks drones take to fly from their old places to their new ones when the swarm changes targets. */
    public static final int RETARGET_TICKS = 20;

    /**
     * The creatures a wing of {@code mode} takes on, in its own order, when the containment wing holds the
     * first {@code held}: containment those, one per construct; Droplet and Barrage the ones not held first,
     * then the held ones, so creatures beyond the constructs fall to them.
     */
    public static <T> List<T> wingTargets(AttackMode mode, List<T> targets, int held) {
        held = Math.clamp(held, 0, targets.size());
        if (mode == AttackMode.CONTAINMENT) return held == 0 ? targets : targets.subList(0, held);
        if (held == 0 || held == targets.size()) return targets;
        List<T> order = new ArrayList<>(targets.subList(held, targets.size()));
        order.addAll(targets.subList(0, held));
        return order;
    }

    /** How many of {@code targets} creatures a wing that makes {@code figures} figures ({@link HiveSlots#figures}) engages: one figure each at least. */
    public static int engaged(int targets, int figures) {
        return Math.max(1, Math.min(targets, Math.max(1, figures)));
    }

    /**
     * Where fighter place {@code slot} flies when the swarm is engaged with several creatures. Strike
     * group g attacks {@code targets[g % n]}, so the groups are shared out evenly. Droplet figures all form
     * up in one fan facing the first target and each flies at its own; barrage clumps and containment
     * constructs are laid out round each target from its own groups.
     */
    public static Vec3d station(AttackMode mode, HiveType type, int slot, int slots, Vec3d owner, List<HiveTarget> targets,
            double time, double cycleStart, int interval) {
        if (targets.isEmpty()) return owner;
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        int groups = HiveSlots.groups(slots, mode, type), engaged = engaged(targets.size(), HiveSlots.figures(groups, mode, type));
        int group = HiveSlots.group(slot, groups), member = HiveSlots.member(slot, groups), index = group % engaged;
        HiveTarget target = targets.get(index);
        if (mode == AttackMode.DROPLET) {
            HiveTarget first = targets.getFirst();
            return dropletStation(type, group, groups, member, HiveSlots.groupSize(group, slots, groups), owner, first.feet(),
                    target.feet(), target.height(), safeTime(time), cycleStart, interval);
        }
        int localGroups = HiveSlots.localGroups(index, engaged, groups), localSlots = HiveSlots.localSlots(index, engaged, slots, groups);
        return stationIn(mode, type, member * localGroups + group / engaged, localSlots, localGroups, owner, target.feet(),
                target.width(), target.height(), time, cycleStart, interval);
    }

    /**
     * {@link #station(AttackMode, HiveType, int, int, Vec3d, List, double, double, int)} while the swarm
     * changes targets: for {@link #RETARGET_TICKS} after {@code retargetedAt}, a place whose target or
     * layout changed flies from where it was (reckoned from the {@code previous} targets) to its new place.
     */
    public static Vec3d engagedStation(AttackMode mode, HiveType type, int slot, int slots, Vec3d owner, List<HiveTarget> targets,
            List<HiveTarget> previous, double retargetedAt, double time, double cycleStart, int interval) {
        Vec3d now = station(mode, type, slot, slots, owner, targets, time, cycleStart, interval);
        double progress = (time - retargetedAt) / RETARGET_TICKS;
        if (previous.isEmpty() || targets.isEmpty() || !(progress >= 0 && progress < 1)) return now;
        slots = Math.max(1, slots);
        int groups = HiveSlots.groups(slots, mode, type), group = HiveSlots.group(Math.clamp(slot, 0, slots - 1), groups);
        int figures = HiveSlots.figures(groups, mode, type), engaged = engaged(targets.size(), figures), before = engaged(previous.size(), figures);
        boolean unchanged = engaged == before && targets.get(group % engaged).id() == previous.get(group % before).id()
                && (mode != AttackMode.DROPLET || targets.getFirst().id() == previous.getFirst().id());
        if (unchanged) return now;
        Vec3d then = station(mode, type, slot, slots, owner, previous, time, cycleStart, interval);
        return path(then, now, progress, slot, type);
    }

    private static Vec3d dropletStation(HiveType type, int group, int groups, int member, int members, Vec3d owner, Vec3d fanTarget,
            Vec3d strike, double strikeHeight, double time, double cycleStart, int interval) {
        strikeHeight = saneSize(strikeHeight, 1.8);
        Vec3d centre = dropletCentre(type, owner, fanTarget, strike, strikeHeight, group, groups, time, cycleStart, interval);
        Vec3d home = muster(owner, fanTarget, group, groups, time), core = core(strike, strikeHeight);
        // A falling drop points down; everything else faces the target from its place in the fan.
        Vec3d facing = type == HiveType.MANA ? new Vec3d(0, -1, 0).lerp(core.subtract(home).normalize(), .15) : core.subtract(home);
        double size = shapeSize(members);
        return centre.add(switch (type) {
            case RF -> {
                // The tesseract closes into a cube as it winds up and rams; after the blow it flies apart in a
                // fan of its drones, which gather back into it on the way home.
                double sortie = sortie(owner, fanTarget, strike, strikeHeight, group, groups, time, cycleStart, interval);
                double collapse = windup(owner, fanTarget, strike, strikeHeight, group, groups, time, cycleStart, interval);
                Vec3d at = HiveShapes.tesseract(member, members, time + group * 17, size * (1 - .2 * collapse), collapse);
                if (sortie > 1 && sortie < 2) {
                    double apart = Math.sin(Math.PI * Math.min(1, (sortie - 1) * 1.6));
                    Vec3d out = at.lengthSqr() < 1e-8 ? new Vec3d(0, 1, 0) : at.normalize();
                    at = at.add(out.scale(apart * (1.6 + .8 * ((member * 0.618) % 1))));
                }
                yield at;
            }
            case MANA -> HiveShapes.droplet(member, members, time, facing, size);
            case TWINS -> HiveShapes.hexagons(member, members, time + group * 11, facing, size);
        });
    }

    /** A place laid out round one target by {@code groups} strike groups. */
    private static Vec3d stationIn(AttackMode mode, HiveType type, int slot, int slots, int groups, Vec3d owner, Vec3d target, double targetWidth,
            double targetHeight, double time, double cycleStart, int interval) {
        slots = Math.max(1, slots);
        slot = Math.clamp(slot, 0, slots - 1);
        groups = Math.clamp(groups, 1, slots);
        time = safeTime(time);
        targetWidth = saneSize(targetWidth, .6);
        targetHeight = saneSize(targetHeight, 1.8);
        int group = HiveSlots.group(slot, groups), member = HiveSlots.member(slot, groups);
        int members = HiveSlots.groupSize(group, slots, groups);
        Vec3d core = core(target, targetHeight);
        return switch (mode) {
            case DROPLET -> dropletStation(type, group, groups, member, members, owner, target, target, targetHeight, time, cycleStart, interval);
            case BARRAGE -> {
                // A few drones hop to the next clump every eight seconds, gliding over for a second.
                int home = group;
                Vec3d at = barragePoint(type, home, member, members, groups, target, targetWidth, targetHeight, time, -1);
                if (member % 7 == 3 && groups > 1) {
                    double clock = time + member * 37 + group * 53;
                    long hops = (long) Math.floor(clock / 160);
                    double into = clock - hops * 160;
                    int from = (int) Math.floorMod(home + hops - 1, groups), to = (int) Math.floorMod(home + hops, groups);
                    // A visiting drone circles just outside the clump it drops in on.
                    Vec3d there = barragePoint(type, to, member, members, groups, target, targetWidth, targetHeight, time, to == home ? -1 : home);
                    if (into < 24) {
                        Vec3d before = barragePoint(type, from, member, members, groups, target, targetWidth, targetHeight, time, from == home ? -1 : home);
                        double t = into / 24;
                        at = before.lerp(there, t * t * (3 - 2 * t)).add(0, Math.sin(Math.PI * t) * .8, 0);
                    } else at = there;
                }
                yield at;
            }
            case CONTAINMENT -> {
                double room = enclosure(targetWidth, targetHeight), age = constructAge(time, cycleStart);
                yield containmentCentre(type, target, targetWidth, targetHeight, slots).add(switch (type) {
                    case RF -> HiveConstructs.cage(slot, slots, age, time, room);
                    case MANA -> HiveConstructs.lotus(slot, slots, age, time, room);
                    case TWINS -> HiveConstructs.rift(slot, slots, age, time, room);
                });
            }
        };
    }

    // --- containment -----------------------------------------------------------------------------

    /**
     * Centre of a containment construct: the middle of the creature it holds. Every hold lifts its
     * creature just far enough for the construct round it to clear the ground (see the lifts below).
     */
    public static Vec3d containmentCentre(HiveType type, Vec3d target, double targetWidth, double targetHeight, int slots) {
        return core(target, saneSize(targetHeight, 1.8));
    }

    /** Radius of the room a construct leaves round a creature: its hitbox and a fifth of it on every side. */
    public static double enclosure(double targetWidth, double targetHeight) {
        return Math.max(saneSize(targetWidth, .6), saneSize(targetHeight, 1.8)) * ENCLOSURE;
    }

    /** Radius of the Twins black hole's horizon round a creature: it swallows the creature whole. */
    public static double horizon(double targetWidth, double targetHeight) {
        return enclosure(targetWidth, targetHeight) * HORIZON;
    }

    /**
     * Radius of the outer RF ({@code dense} false) or Twins ({@code dense} true) torus. The inner torus's
     * inner side runs round the enclosure, or on Twins round the black hole, which is wider still.
     */
    public static double ringsRadius(double targetWidth, double targetHeight, boolean dense) {
        double clearance = dense ? horizon(targetWidth, targetHeight) * HORIZON_CLEARANCE : enclosure(targetWidth, targetHeight);
        double inner = (clearance + HiveShapes.ringTubeBase(dense)) / (1 - HiveShapes.ringTubeScale(dense));
        return inner / HiveShapes.RING_RADII[0];
    }

    /** How far a hold lifts its target so the tori round it clear the ground: the outer torus, its tube and the drones on it. */
    /**
     * Ticks since a containment construct began to close round its creature: from a few ticks before its
     * drones arrive ({@code cycleStart}, the end of their flight out), so it is half shut as they get there.
     */
    public static double constructAge(double time, double cycleStart) {
        return safeTime(time) - cycleStart + HiveConstructs.CLOSING * .35;
    }

    /** How far a hold lifts its target so the construct round it clears the ground, the Twins rift four blocks at least. */
    public static double constructLift(HiveType type, double targetWidth, double targetHeight) {
        double room = enclosure(targetWidth, targetHeight), reach = switch (type) {
            case RF -> HiveConstructs.cageReach(room);
            case MANA -> HiveConstructs.lotusReach(room);
            case TWINS -> HiveConstructs.riftReach(room);
        };
        double lift = Math.max(0, reach + .15 - saneSize(targetHeight, 1.8) * .55);
        return type == HiveType.TWINS ? Math.max(4, lift) : lift;
    }

    public static double ringLift(double targetWidth, double targetHeight, boolean dense) {
        double radius = ringsRadius(targetWidth, targetHeight, dense), reach = radius + HiveShapes.ringTube(2, radius, dense) * 1.15;
        return Math.max(0, reach + .15 - saneSize(targetHeight, 1.8) * .55);
    }

    /** Twins lift their target at least four blocks, or as far as their tori round the black hole need. */
    public static double twinsLift(double targetWidth, double targetHeight) { return constructLift(HiveType.TWINS, targetWidth, targetHeight); }

    /** The Mana ward's scale: the rhombi's edges, its innermost lines (.9 of the scale from its middle), run round the enclosure. */
    public static double wardScale(double targetWidth, double targetHeight) {
        return enclosure(targetWidth, targetHeight) / (1.55 * 1.1 / Math.hypot(1.55, 1.1));
    }

    /** How far a Mana hold lifts its target so the ward's lower tips clear the ground. */
    public static double wardLift(double targetWidth, double targetHeight) {
        return Math.max(0, 1.55 * wardScale(targetWidth, targetHeight) + .15 - saneSize(targetHeight, 1.8) * .55);
    }

    /**
     * A drone's place in clump {@code group}. A visitor (from clump {@code visitorFrom}, or -1 for a clump's
     * own drones) circles just outside it, turned by its home clump so that visitors from different
     * clumps never share a place.
     */
    private static Vec3d barragePoint(HiveType type, int group, int member, int members, int groups, Vec3d target,
            double targetWidth, double targetHeight, double time, int visitorFrom) {
        Vec3d centre = clusterCentre(type, target, targetWidth, targetHeight, group, groups, time);
        Vec3d facing = core(target, targetHeight).subtract(centre);
        double turn = visitorFrom < 0 ? 0 : visitorFrom * 41 + 17;
        return centre.add(HiveShapes.clump(type, member, members, time + group * 29 + turn, facing, clumpRadius(members))
                .scale(visitorFrom < 0 ? 1 : 1.45));
    }

    /**
     * A deployed drone's position: flying out from the hive along its own curve until it reaches
     * {@code station}. {@code launched} is when this drone set off (the combat start, or when it
     * replaced a hit drone) and {@code travel} how long the flight out takes.
     */
    public static Vec3d deployed(Vec3d owner, float yaw, Vec3d station, int unit, int count, HiveType type,
            double time, double launched, int travel) {
        Vec3d rest = idle(owner, yaw, unit, count, type, time);
        double progress = (time - launched) / Math.max(1, travel);
        return path(rest, station, stagger(unit, type, progress), unit, type);
    }

    /** A drone's own staggered, curved flight from {@code from} to {@code to}, {@code progress} of the way (0..1) through the swarm's move. */
    public static Vec3d flight(Vec3d from, Vec3d to, int unit, HiveType type, double progress) {
        return path(from, to, stagger(unit, type, progress), unit, type);
    }

    /** A hit drone on its way home, from where it was struck back into the hive. */
    public static Vec3d returning(Vec3d owner, float yaw, Vec3d struckAt, int unit, int count, HiveType type, double time, double hitAt) {
        Vec3d rest = idle(owner, yaw, unit, count, type, time);
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
    private static Vec3d path(Vec3d from, Vec3d to, double progress, int index, HiveType type) {
        double p = Math.clamp(progress, 0, 1);
        if (p <= 0) return from;
        if (p >= 1) return to;
        double t = p * p * (3 - 2 * p);
        Vec3d span = to.subtract(from);
        double length = span.length();
        if (length < 1e-6) return to;
        Vec3d forward = span.scale(1 / length);
        Vec3d side = forward.cross(Math.abs(forward.y) > .9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0)).normalize();
        Vec3d lift = side.cross(forward);
        double angle = hash(index, type, 31) * Math.PI * 2;
        double bow = Math.min(2.2, length * (.18 + .22 * hash(index, type, 32)));
        Vec3d control = from.lerp(to, .5).add(side.scale(Math.cos(angle) * bow)).add(lift.scale(Math.abs(Math.sin(angle)) * bow * .8));
        double u = 1 - t;
        return from.scale(u * u).add(control.scale(2 * u * t)).add(to.scale(t * t));
    }

    private static Vec3d fibonacciSphere(int index, int count, double radius, double vertical) {
        double y = 1.0 - 2.0 * (index + .5) / count;
        double around = index * GOLDEN_ANGLE;
        double horizontal = Math.sqrt(Math.max(0, 1 - y * y));
        return new Vec3d(Math.cos(around) * horizontal * radius, y * radius * vertical, Math.sin(around) * horizontal * radius);
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
