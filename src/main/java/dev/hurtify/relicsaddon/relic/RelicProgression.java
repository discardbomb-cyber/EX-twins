package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.RelicRole;

/** Compatibility name for the former Relics progression constants. */
public final class RelicProgression {
    public static final int MAX_RANK = DeviceProgression.MAX_LEVEL;
    public static String combatSource(RelicRole role) { return role.itemId() + "_activity"; }
    private RelicProgression() { }
}
