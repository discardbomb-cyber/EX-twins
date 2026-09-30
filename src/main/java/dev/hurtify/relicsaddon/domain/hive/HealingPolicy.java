package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.energy.EnergyCosts;

/** Healer drones mend their owner once a second, taking turns. */
public final class HealingPolicy {
    /** Ticks between mending pulses. */
    public static final int PERIOD = 20;
    /** Ticks a healer rests after mending. */
    public static final int NEXT_MEND = 100;

    /** A new healer's first mend, staggered by its place so the healers take turns. */
    public static long firstMendAt(long now, int index) {
        return now + 20L * (1 + index % 5);
    }

    /** Health one healer asks to restore: half a point times the upgrade, within the budget and what is missing. */
    public static float request(double multiplier, float budget, float missing) {
        return Math.min((float) (.5 * multiplier), Math.min(budget, missing));
    }

    /** Battery points for {@code restored} health. */
    public static int cost(float restored) {
        return (int) Math.ceil(restored * EnergyCosts.HEAL_PER_HP);
    }

    private HealingPolicy() { }
}
