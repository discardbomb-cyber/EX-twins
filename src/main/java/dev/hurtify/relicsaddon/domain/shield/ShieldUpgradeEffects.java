package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.device.RelicRole;

/** What the shield upgrades do at each rank. A device that cannot operate counts as rank 0; the caller decides that. */
public final class ShieldUpgradeEffects {
    /** Share of a hit the neighbouring cells take (distribution); nothing at rank 0. */
    public static double sharing(RelicRole role, int rank) {
        if (rank == 0) return 0;
        double start = role == RelicRole.RF_SHIELD ? .25 : role == RelicRole.MANA_SHIELD ? .20 : .35;
        double end = role == RelicRole.RF_SHIELD ? .45 : role == RelicRole.MANA_SHIELD ? .35 : .50;
        return start + (end - start) * rank / 3D;
    }

    /** Healthy cells moved in to cover a hit (gather); a Twins shield has none at any rank. */
    public static int gathering(RelicRole role, int rank) {
        if (role == RelicRole.TWINS_SHIELD) return 0;
        return rank == 0 ? 0 : Math.min(role == RelicRole.MANA_SHIELD ? 3 : 2, rank);
    }

    /** Cells mended per repair pulse (restoration). */
    public static int repairSteps(RelicRole role, int rank) {
        return Math.min(role == RelicRole.MANA_SHIELD ? 3 : 2, 1 + rank);
    }

    /** Quiet ticks after a hit before the buffer repairs; stabilization shortens them on a Twins shield only. */
    public static int quietTicks(RelicRole role, int rank) {
        if (role != RelicRole.TWINS_SHIELD) return 40;
        return rank == 0 ? 40 : 40 - 8 * rank;
    }

    private ShieldUpgradeEffects() { }
}
