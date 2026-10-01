package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.combat.DamageKind;
import dev.hurtify.relicsaddon.domain.device.RelicRole;

/** The shell's strike: the RF shell discharges, the Mana shell bursts through armour and the Twins shell surges with both. */
public final class StrikeRules {
    public static DamageKind damageKind(RelicRole role) {
        return switch (role) {
            case MANA_SHIELD -> DamageKind.SHIELD_MANA_BURST;
            case TWINS_SHIELD -> DamageKind.SHIELD_TWIN_SURGE;
            default -> DamageKind.SHIELD_DISCHARGE;
        };
    }

    private StrikeRules() { }
}
