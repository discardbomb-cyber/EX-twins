package dev.hurtify.relicsaddon.domain.shield;

/** Damage no field takes. */
public final class DamagePassPolicy {
    /**
     * The server's pass list wins outright; its absorb list overrides the {@code shield_passes} tag,
     * except for shield strikes, which a field must never eat. Facts are asked in that order, and only
     * as far as the answer needs them.
     */
    public static boolean passesField(DamageFacts facts) {
        return facts.passListed() || (facts.inPassTag() && (!facts.absorbListed() || facts.strike()));
    }

    private DamagePassPolicy() { }
}
