package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldUpgradeEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Standalone shield upgrades, purchased with this mod's own device points. */
public final class ShieldUpgrades {
    public static final String DISTRIBUTION = DeviceUpgrade.DISTRIBUTION.id();
    public static final String GATHER = DeviceUpgrade.GATHER.id();
    public static final String RESTORATION = DeviceUpgrade.RESTORATION.id();
    public static final String STABILIZATION = DeviceUpgrade.STABILIZATION.id();
    public static double sharing(Player player, ItemStack stack) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item)) return 0;
        return ShieldUpgradeEffects.sharing(item.role(), RelicRuntime.progression(stack).rank(DISTRIBUTION));
    }
    public static int gathering(Player player, ItemStack stack) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item) || item.role() == RelicRole.TWINS_SHIELD) return 0;
        return ShieldUpgradeEffects.gathering(item.role(), RelicRuntime.progression(stack).rank(GATHER));
    }
    public static int repairSteps(Player player, ItemStack stack) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item)) return 1;
        return ShieldUpgradeEffects.repairSteps(item.role(), RelicRuntime.progression(stack).rank(RESTORATION));
    }
    public static int quietTicks(Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || item.role() != RelicRole.TWINS_SHIELD || !RelicRuntime.canOperate(player, stack)) return 40;
        return ShieldUpgradeEffects.quietTicks(item.role(), RelicRuntime.progression(stack).rank(STABILIZATION));
    }
    private ShieldUpgrades() { }
}
