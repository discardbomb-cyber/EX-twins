package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlock;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceItem;
import dev.hurtify.relicsaddon.shipshield.ShipFamily;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Ship shield blocks: a generator and a drone dock per family, one block entity type for all six. */
public final class ShipBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RelicsAddon.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RelicsAddon.MOD_ID);

    public static final Map<ShipFamily, DeferredBlock<ShipDeviceBlock>> GENERATORS = new EnumMap<>(ShipFamily.class);
    public static final Map<ShipFamily, DeferredBlock<ShipDeviceBlock>> DOCKS = new EnumMap<>(ShipFamily.class);
    public static final Map<RelicRole, DeferredItem<ShipDeviceItem>> ITEMS = new EnumMap<>(RelicRole.class);

    static {
        for (ShipFamily family : ShipFamily.values()) {
            GENERATORS.put(family, block(family.generator, MapColor.COLOR_CYAN));
            DOCKS.put(family, block(family.dock, MapColor.METAL));
        }
    }

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShipDeviceBlockEntity>> DEVICE = BLOCK_ENTITIES.register("ship_device",
            () -> BlockEntityType.Builder.of(ShipDeviceBlockEntity::new, blocks()).build(null));

    private static DeferredBlock<ShipDeviceBlock> block(RelicRole role, MapColor color) {
        DeferredBlock<ShipDeviceBlock> block = BLOCKS.register(role.itemId(), () -> new ShipDeviceBlock(role, BlockBehaviour.Properties.of()
                .mapColor(color).strength(4, 12).sound(SoundType.COPPER).requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(ShipDeviceBlock.LIT) ? 9 : 3)));
        ITEMS.put(role, ModItems.ITEMS.register(role.itemId(), () -> new ShipDeviceItem(block.get(),
                new Item.Properties().stacksTo(1).rarity(role.isShipGenerator() ? Rarity.RARE : Rarity.UNCOMMON))));
        return block;
    }

    private static Block[] blocks() {
        Block[] all = new Block[GENERATORS.size() + DOCKS.size()];
        int index = 0;
        for (ShipFamily family : ShipFamily.values()) {
            all[index++] = GENERATORS.get(family).get();
            all[index++] = DOCKS.get(family).get();
        }
        return all;
    }

    public static ShipDeviceBlock block(RelicRole role) {
        ShipFamily family = ShipFamily.of(role);
        return (role.isShipGenerator() ? GENERATORS : DOCKS).get(family).get();
    }

    public static ShipDeviceItem item(RelicRole role) { return ITEMS.get(role).get(); }

    /** RF batteries take Forge Energy from any side; docks show their drones to hoppers and pipes. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, DEVICE.get(), (device, side) -> device.energyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, DEVICE.get(), (device, side) -> device.droneHandler());
    }

    private ShipBlocks() {
    }
}
