package dev.hurtify.relicsaddon.domain.shield;

/**
 * The field's own hurt immunity. Vanilla ignores a hit landing within half a second of a stronger one,
 * but absorbed hits are cancelled, so that immunity never starts; the field keeps its own window of
 * what it absorbed. The tick is kept as a float, as it always was, so late game times round.
 */
public record HitImmunity(float tick, float absorbed) {
    public static final int TICKS = 10;

    /** Whether {@code now} still falls within the window. */
    public boolean covers(long now) {
        return !(now - (long) tick >= TICKS || now < (long) tick);
    }

    /** The window after absorbing {@code absorbed} at {@code now}; {@code previous} (may be null) adds to it while it covers {@code now}. */
    public static HitImmunity record(HitImmunity previous, long now, float absorbed) {
        return new HitImmunity((float) now, (previous != null && previous.covers(now) ? previous.absorbed() : 0) + absorbed);
    }
}
