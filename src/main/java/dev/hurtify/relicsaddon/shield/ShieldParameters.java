package dev.hurtify.relicsaddon.shield;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ShieldParameters {
    public static int capacity(Player player, ItemStack stack) {
        return (int) Math.round(RelicRuntime.stat(player, stack, "buffer_capacity", 504, 504, 5500));
    }
    public static int totalCapacity(Player player, ItemStack stack) {
        return capacity(player, stack) + ShieldTopology.CELL_COUNT * ShieldStackState.MAX_PANEL_INTEGRITY;
    }
    public static double maxRadius(Player player, ItemStack stack) {
        double limit = AddonConfig.SHIELD_MAX_RADIUS.get();
        return Math.min(limit, RelicRuntime.stat(player, stack, "radius", 2, 2, 24));
    }
    public static double radius(Player player, ItemStack stack) {
        return Math.min(maxRadius(player, stack), settings(stack).radius());
    }
    public static ShieldSettings settings(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SHIELD_SETTINGS.get(), ShieldSettings.DEFAULT);
    }
    private ShieldParameters() { }
}
