package dev.hurtify.relicsaddon.domain.shield;

/** Why a worn shield is or is not the one protecting its wearer, as the status command reports it. */
public enum ShieldStatus {
    INACTIVE_SLOT("inactive_slot"),
    DISABLED("disabled"),
    PRIORITY("priority"),
    BROKEN("broken"),
    ACTIVE("active");

    private final String id;

    ShieldStatus(String id) { this.id = id; }

    public String id() { return id; }

    /** The first reason that applies, in this order: an inactive slot, switched off, another shield first, no integrity left. */
    public static ShieldStatus of(boolean slotActive, boolean enabled, boolean isActiveShield, int totalIntegrity) {
        if (!slotActive) return INACTIVE_SLOT;
        if (!enabled) return DISABLED;
        if (!isActiveShield) return PRIORITY;
        return totalIntegrity == 0 ? BROKEN : ACTIVE;
    }
}
