package dev.hurtify.relicsaddon.domain.hive;

/** The numbers of the swarm's blows, charges, holds and the target's swings at drones. */
public final class SwarmStrike {
    /** Battery points per drone taking part in a blow or a charge. */
    public static final int COST_PER_DRONE = 1;
    /** Share of the flying drones' damage a containment pulse deals. */
    public static final float CONTAINMENT_DAMAGE = .12F;
    /** Share of a charge's damage its blast deals to other hostiles close by. */
    public static final float SPLASH_DAMAGE = .4F;
    /** How far around the burst point a charge's blast looks for hostiles, and the squared distance it reaches. */
    public static final double SPLASH_INFLATE = 2, SPLASH_DISTANCE_SQR = 4;
    /** Share of a charge's knockback its blast deals. */
    public static final double SPLASH_KNOCKBACK = .5;
    /** How far past its box a target's swing reaches a drone. */
    public static final double SWING_INFLATE = 1.5;
    /** A target swings at drones once a second, on this tick of the second. */
    public static final int SWING_PERIOD = 20, SWING_PHASE = 7;
    /** Ticks between containment pulses. */
    public static final int CONTAINMENT_PERIOD = 20;
    /** Ward a Mana hold gains per flying drone and pulse. */
    public static final double WARD_PER_DRONE = .5;
    /** Ticks between the hum of a Mana hold's ward. */
    public static final int WARD_HUM_PERIOD = 60;
    /** Ticks between updates of a moving target's position. */
    public static final int TARGET_REFRESH_PERIOD = 5;
    /** Ticks a creature the owner hit, or that hit the owner, stays a candidate target. */
    public static final int TARGET_MEMORY_TICKS = 100;
    /** Owners further than 200 blocks from a blast are not checked for drones in it. */
    public static final int EXPLOSION_OWNER_RANGE_SQR = 200 * 200;

    /** Knockback of a group's blow: stronger with more drones, up to 1.4. */
    public static double knockback(int members) {
        return Math.min(1.4, .35 + .03 * members);
    }

    /** Battery points a containment pulse costs: one per five flying drones, at least one. */
    public static int containmentDrain(int flying) {
        return Math.max(1, flying / 5);
    }

    private SwarmStrike() { }
}
