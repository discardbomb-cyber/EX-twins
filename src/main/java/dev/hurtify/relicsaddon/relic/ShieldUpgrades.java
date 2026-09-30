package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
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
        int rank = RelicRuntime.progression(stack).rank(DISTRIBUTION);
        if (rank == 0) return 0;
        double start = item.role() == RelicRole.RF_SHIELD ? .25 : item.role() == RelicRole.MANA_SHIELD ? .20 : .35;
        double end = item.role() == RelicRole.RF_SHIELD ? .45 : item.role() == RelicRole.MANA_SHIELD ? .35 : .50;
        return start + (end - start) * rank / 3D;
    }
    public static int gathering(Player player, ItemStack stack) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item) || item.role() == RelicRole.TWINS_SHIELD) return 0;
        int rank = RelicRuntime.progression(stack).rank(GATHER);
        return rank == 0 ? 0 : Math.min(item.role() == RelicRole.MANA_SHIELD ? 3 : 2, rank);
    }
    public static int repairSteps(Player player, ItemStack stack) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item)) return 1;
        int rank = RelicRuntime.progression(stack).rank(RESTORATION);
        return Math.min(item.role() == RelicRole.MANA_SHIELD ? 3 : 2, 1 + rank);
    }
    public static int quietTicks(Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || item.role() != RelicRole.TWINS_SHIELD || !RelicRuntime.canOperate(player, stack)) return 40;
        int rank = RelicRuntime.progression(stack).rank(STABILIZATION);
        return rank == 0 ? 40 : 40 - 8 * rank;
    }
    private ShieldUpgrades() { }
}
