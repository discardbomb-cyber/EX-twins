package dev.hurtify.relicsaddon.domain.shield;

/** What the field asks about a hit's damage type. Each fact is looked up only when it is asked for. */
public interface DamageFacts {
    /** The server's list lets this type through every field. */
    boolean passListed();

    /** The type is in the {@code shield_passes} tag. */
    boolean inPassTag();

    /** The server's list makes fields absorb this type after all. */
    boolean absorbListed();

    /** The type is a shield strike, which no field absorbs. */
    boolean strike();
}
