package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.hive.HiveType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Standalone hive upgrade effects. */
public final class HiveUpgrades {
    public static final String COMBAT = "combat_protocol";
    public static final String SUPPORT = "support_protocol";
    public static final String RECOVERY = "recovery_protocol";
    public static double damageMultiplier(Player player, ItemStack stack) {
        return 1 + rankValue(player, stack, COMBAT, switch (type(stack)) { case RF -> .10; case MANA -> .08; case TWINS -> .12; }, 3);
    }
    public static double healingMultiplier(Player player, ItemStack stack) {
        HiveType type = type(stack);
        double cap = switch (type) { case RF -> .50; case MANA -> 1D; case TWINS -> .75; };
        return 1 + rankValue(player, stack, SUPPORT, cap / 3D, 3);
    }
    public static int repairAmount(Player player, ItemStack stack) { return 1 + (int) rankValue(player, stack, RECOVERY, 1, 3); }
    public static double rebuildMultiplier(Player player, ItemStack stack) {
        HiveType type = type(stack);
        double cap = switch (type) { case RF -> .30; case MANA -> .20; case TWINS -> .35; };
        return 1 - rankValue(player, stack, RECOVERY, cap / 3D, 3);
    }
    private static double rankValue(Player player, ItemStack stack, String id, double perRank, int max) {
        if (!RelicRuntime.canOperate(player, stack) || !(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive()) return 0;
        return Math.min(max, RelicRuntime.progression(stack).rank(id)) * perRank;
    }
    private static HiveType type(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item ? HiveType.of(item.role()) : HiveType.RF;
    }
    private HiveUpgrades() { }
}
