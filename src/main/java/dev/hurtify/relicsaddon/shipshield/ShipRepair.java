package dev.hurtify.relicsaddon.shipshield;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * A dock's repair work on its ship. The snapshot and the repairs themselves come with the repair
 * stage; for now a dock has no snapshot and nothing waiting.
 */
public final class ShipRepair {
    private final ShipDeviceBlockEntity dock;

    public ShipRepair(ShipDeviceBlockEntity dock) {
        this.dock = dock;
    }

    /** Blocks of the snapshot that are missing from the ship. */
    public int queue() { return 0; }

    public void tick(ServerLevel level, long now) {
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        return new CompoundTag();
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
    }
}
