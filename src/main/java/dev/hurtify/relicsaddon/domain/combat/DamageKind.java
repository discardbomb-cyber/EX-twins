package dev.hurtify.relicsaddon.domain.combat;

/** The mod's own damage types, by their registry path. */
public enum DamageKind {
    DRONE_SHOT("drone_shot"),
    SWARM_STRIKE("swarm_strike"),
    SWARM_VOID("swarm_void"),
    SWARM_REFLECT("swarm_reflect"),
    SHIELD_DISCHARGE("shield_discharge"),
    SHIELD_MANA_BURST("shield_mana_burst"),
    SHIELD_TWIN_SURGE("shield_twin_surge");

    private final String id;

    DamageKind(String id) { this.id = id; }

    public String id() { return id; }
}
