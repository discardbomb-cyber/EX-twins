package dev.hurtify.relicsaddon.domain.shield;

/** Whom a field shelters besides its owner: nobody else, allies, or everyone inside it. */
public enum Coverage {
    OWNER("owner"),
    ALLIES("allies"),
    ALL("all");

    private final String id;

    Coverage(String id) { this.id = id; }

    public String id() { return id; }

    /** The coverage with this id; an unknown id means allies, the default (settings never hold one). */
    public static Coverage of(String id) {
        for (Coverage coverage : values()) if (coverage.id.equals(id)) return coverage;
        return ALLIES;
    }
}
