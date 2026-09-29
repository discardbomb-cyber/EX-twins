package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.registry.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Moves real module items between the player's inventory and a device slot. */
public final class DeviceModules {
    public static boolean toggle(Player player, ItemStack device, int slot) {
        if (slot < 0 || slot >= DeviceProgression.MODULE_SLOTS) return false;
        boolean installed = RelicRuntime.progression(device).hasModule(slot);
        if (installed) {
            ItemStack module = new ItemStack(ModItems.DEVICE_MODULE.get());
            if (!player.getInventory().add(module)) player.drop(module, false);
            return RelicRuntime.setModule(device, slot, false);
        }
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            ItemStack candidate = player.getInventory().getItem(index);
            if (!candidate.is(ModItems.DEVICE_MODULE.get())) continue;
            player.getInventory().removeItem(index, 1);
            return RelicRuntime.setModule(device, slot, true);
        }
        return false;
    }
    private DeviceModules() { }
}
