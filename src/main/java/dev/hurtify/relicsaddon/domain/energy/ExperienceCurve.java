package dev.hurtify.relicsaddon.domain.energy;

/** Vanilla's experience curve, which the experience mana source draws from. */
public final class ExperienceCurve {
    /** Vanilla's cumulative experience to reach {@code level}. */
    public static int pointsForLevel(int level) {
        if (level <= 16) return level * level + 6 * level;
        if (level <= 31) return (int) (2.5 * level * level - 40.5 * level + 360);
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    private ExperienceCurve() { }
}
