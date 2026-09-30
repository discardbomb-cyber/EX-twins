package dev.hurtify.relicsaddon.drone;

/**
 * An Armageddon's course in time, whichever hive fires it, as the server runs it and every client draws
 * it: when the swarm has gathered and the hive's charge starts to pour into the shot, when the shot
 * leaves and reaches its target, how the land round the target goes, when it bursts and how the blast
 * then sweeps out, and when the drones are home. Ages count ticks from the start of the shot;
 * {@code sinceImpact} counts them from the burst.
 */
public interface ArmageddonTimeline {
    /** The drones have gathered: from here until {@link #fire} the charge pours into the shot. */
    int assembled();

    /** The shot leaves with the whole charge. */
    int fire();

    /** The shot reaches its target, and the land round it starts to go. */
    int arrive();

    /** When every client near enough is told of the blast, to draw what it sees of it from there on. */
    int told();

    /** The burst: from here the blast sweeps out. */
    int impact();

    /** The drones start for home, and by {@link #end} they are there. */
    int recover();

    int end();

    /** How far round its target the shot takes the land. */
    double carveRadius();

    /** How far out the land has gone {@code age} ticks in. */
    double carved(double age);

    /** When the land {@code distance} blocks from the target goes: the age at which {@link #carved} first reaches it. */
    double carvedAt(double distance);

    /** Until this age the land still goes, catching up with all it marked. */
    int carvedUntil();

    /** Whether creatures round the target are dragged in {@code age} ticks in. */
    boolean drags(double age);

    /** How far the blast reaches. */
    double radius();

    /** How far the blast has struck {@code sinceImpact} ticks after the burst. */
    double reach(double sinceImpact);

    /** When the blast first strikes {@code distance} blocks out, in ticks after the burst. */
    double reaches(double distance);

    /** Ticks after the burst by which the blast has swept its whole radius and strikes nothing more. */
    int swept();

    /** The course of the Armageddon a hive of {@code type} fires. */
    static ArmageddonTimeline of(HiveType type) {
        return type == HiveType.MANA ? ManaArmageddon.TIMELINE : Armageddon.TIMELINE;
    }
}
