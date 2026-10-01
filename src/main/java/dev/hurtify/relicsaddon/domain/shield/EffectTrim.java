package dev.hurtify.relicsaddon.domain.shield;

/** How much of a harmful effect a field lets through, and what stopping the rest costs. */
public final class EffectTrim {
    /** Battery points per second of effect per level of its strength. */
    private static final int COST_PER_SECOND = 2;

    /**
     * Ticks of the effect kept: when the hit that carried it was {@code struck} partly through the
     * field, the share of the duration that matches the damage that got through; otherwise none.
     */
    public static int kept(boolean struck, float absorbed, float passed, int duration) {
        return struck && passed > 0 ? (int) Math.floor(duration * passed / Math.max(1e-3F, absorbed + passed)) : 0;
    }

    /** Ticks the field stops; an endless effect counts as a minute. */
    public static int removed(boolean infinite, int duration, int kept) {
        return infinite ? 20 * 60 : duration - kept;
    }

    /** Battery points for stopping {@code removed} ticks of an effect of level {@code amplifier + 1}; at least one. */
    public static int cost(int removed, int amplifier) {
        return Math.max(1, (int) Math.ceil(removed / 20.0 * (amplifier + 1) * COST_PER_SECOND));
    }

    private EffectTrim() { }
}
