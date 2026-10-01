package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.RelicsAddon;
import net.neoforged.fml.ModList;

/**
 * Binds the parts of the ship shields that need another mod's classes, by name, only when that mod
 * is loaded: Create Big Cannons' block-damage event, whose listener lives in
 * {@code compat.aeronautics} where the CBC types are allowed.
 */
public final class ShipShieldCompat {
    public static void bind() {
        if (!ModList.get().isLoaded("createbigcannons")) return;
        try {
            Class.forName("dev.hurtify.relicsaddon.compat.aeronautics.CbcShellGuard").getMethod("register").invoke(null);
            RelicsAddon.LOGGER.info("Ship shields guard blocks from Create Big Cannons' shells");
        } catch (ReflectiveOperationException | LinkageError failure) {
            RelicsAddon.LOGGER.warn("Create Big Cannons is loaded but the shell guard did not bind", failure);
        }
    }

    private ShipShieldCompat() {
    }
}
