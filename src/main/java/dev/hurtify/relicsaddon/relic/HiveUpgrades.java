package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.domain.hive.HiveUpgradeEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Standalone hive upgrade effects. */
public final class HiveUpgrades {
    public static final String COMBAT = DeviceUpgrade.COMBAT.id();
    public static final String SUPPORT = DeviceUpgrade.SUPPORT.id();
    public static final String RECOVERY = DeviceUpgrade.RECOVERY.id();
    public static double damageMultiplier(Player player, ItemStack stack) {
        return HiveUpgradeEffects.damage(type(stack), rank(player, stack, COMBAT));
    }
    public static double healingMultiplier(Player player, ItemStack stack) {
        return HiveUpgradeEffects.healing(type(stack), rank(player, stack, SUPPORT));
    }
    public static int repairAmount(Player player, ItemStack stack) { return 1 + Math.min(3, rank(player, stack, RECOVERY)); }
    public static double rebuildMultiplier(Player player, ItemStack stack) {
        return HiveUpgradeEffects.rebuild(type(stack), rank(player, stack, RECOVERY));
    }
    /** The upgrade's rank, or 0 while the device is not an operable hive. */
    private static int rank(Player player, ItemStack stack, String id) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive()) return 0;
        return RelicRuntime.progression(stack).rank(id);
    }
    private static HiveType type(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item ? HiveType.of(item.role()) : HiveType.RF;
    }
    private HiveUpgrades() { }
}
