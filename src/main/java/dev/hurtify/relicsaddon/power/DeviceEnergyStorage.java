package dev.hurtify.relicsaddon.power;

import dev.hurtify.relicsaddon.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Exposes the RF battery as a Forge Energy item, so any FE charger (Mekanism, Thermal, Flux
 * Networks, ...) can fill it. It only accepts energy: machines cannot drain a worn shield.
 */
public record DeviceEnergyStorage(ItemStack stack) implements IEnergyStorage {
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, context) -> {
            var role = DevicePower.role(stack);
            return role != null && DevicePower.hasRf(role) ? new DeviceEnergyStorage(stack) : null;
        }, ModItems.RF_SHIELD.get(), ModItems.TWINS_SHIELD.get(), ModItems.RF_HIVE.get(), ModItems.TWINS_HIVE.get());
    }

    @Override public int receiveEnergy(int amount, boolean simulate) { return DevicePower.receiveFe(stack, amount, simulate); }
    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return DevicePower.energy(stack).rf(); }
    @Override public int getMaxEnergyStored() { return DevicePower.feCapacity(stack); }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return true; }
}
