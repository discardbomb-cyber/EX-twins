package dev.hurtify.relicsaddon.compat.aeronautics;

import dev.hurtify.relicsaddon.shipshield.ShipShieldFields;
import net.neoforged.neoforge.common.NeoForge;
import rbasamoyai.createbigcannons.events.ProjectileDamageEvent;

/**
 * Create Big Cannons posts {@link ProjectileDamageEvent} before a shell breaks or goes through a
 * block (the position is the block's real one, in a ship's plot for a ship's block). A shell is
 * normally stopped on the shield's shell before it gets this far; this keeps the blocks under a
 * shield that holds whole when a shell slipped between two of its sweeps. Only this class names
 * CBC's types; {@code ShipShieldCompat} loads it by name when CBC is present.
 */
public final class CbcShellGuard {
    public static void register() {
        NeoForge.EVENT_BUS.addListener(CbcShellGuard::onProjectileDamage);
    }

    private static void onProjectileDamage(ProjectileDamageEvent event) {
        if (ShipShieldFields.guardsBlock(event.getLevel(), event.getPos())) event.setCanceled(true);
    }

    private CbcShellGuard() {
    }
}
