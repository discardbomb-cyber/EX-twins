package dev.hurtify.relicsaddon.domain.hive;

/** What hurts a drone and how long it then stays away: a hit drone flies home and is repaired, or rebuilt if destroyed. */
public final class DroneDamage {
    /** Ticks a hit drone needs per point of damage before it is whole again (a destroyed one is rebuilt instead). */
    public static final int REPAIR_TICKS_PER_HP = 40;

    /**
     * Whether a blow now would do nothing: the target still reels from the last one (vanilla hurt
     * immunity). Such a blow glances off without spending charge.
     */
    public static boolean reeling(int invulnerableTime) {
        return invulnerableTime > 10;
    }

    /** Damage a target's swing does to the drone it hits: two from a heavy hitter, else one. */
    public static int swingDamage(double attack) {
        return attack >= 6 ? 2 : 1;
    }

    /** Damage a blast does to a drone {@code distance} from its centre: three HP at the centre down to one at the edge of its {@code reach}. */
    public static int explosionDamage(double distance, double reach) {
        return (int) Math.ceil(3 * (1 - distance / reach));
    }

    /** Ticks a drone left with {@code hpLeft} stays away after flying home: the rebuild cooldown, or repair per lost HP, times {@code pace}. */
    public static long away(int hpLeft, double cooldown, double pace) {
        return hpLeft <= 0 ? Math.round(cooldown * pace) : Math.round(REPAIR_TICKS_PER_HP * (HiveType.DRONE_HP - hpLeft) * pace);
    }

    /** When a drone hit at {@code now} is whole and home again. */
    public static long backAt(long now, long away) {
        return now + HiveFormation.RETURN_TICKS + away;
    }

    private DroneDamage() { }
}
