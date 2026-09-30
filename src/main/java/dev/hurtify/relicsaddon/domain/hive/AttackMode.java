package dev.hurtify.relicsaddon.domain.hive;

import java.util.Locale;

/**
 * How a hive's fighters attack. The swarm splits into two to sixteen strike groups.
 * <ul>
 *   <li>{@link #DROPLET}: each group gathers into one shape (a Mana droplet, an RF tesseract, a
 *       ring of Twins hexagons), dives at the target and hits it as a single blow.</li>
 *   <li>{@link #BARRAGE}: groups hang around the target in patterned clusters, each charging a
 *       glowing orb that it fires as ball lightning.</li>
 *   <li>{@link #CONTAINMENT}: the whole swarm builds one construct around the target and holds it
 *       fast: an RF torus of hexagons seals it and eats its shots, a Mana ward of three rhombi and two
 *       circles turns its attacks back on it, Twins spheres lift it into a black hole.</li>
 * </ul>
 */
public enum AttackMode {
    DROPLET,
    BARRAGE,
    CONTAINMENT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AttackMode byOrdinal(int ordinal) {
        AttackMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BARRAGE;
    }

    /** The mode with this id; an unknown id gives {@link #BARRAGE}, the default mode. */
    public static AttackMode byId(String id) {
        for (AttackMode mode : values()) if (mode.id().equals(id)) return mode;
        return BARRAGE;
    }
}
