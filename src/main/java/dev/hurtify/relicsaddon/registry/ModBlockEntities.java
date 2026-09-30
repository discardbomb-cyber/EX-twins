package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RelicsAddon.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShipHiveBlockEntity>> SHIP_HIVE = BLOCK_ENTITIES.register("ship_hive",
            () -> BlockEntityType.Builder.of(ShipHiveBlockEntity::new,
                    ModBlocks.SHIP_HIVES.values().stream().map(block -> (Block) block.get()).toArray(Block[]::new)).build(null));

    /** Ship hives take FE from any side. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, SHIP_HIVE.get(), (hive, side) -> hive.energy());
    }

    private ModBlockEntities() {
    }
}
