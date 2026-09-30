package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.ship.ShipHiveBlock;
import dev.hurtify.relicsaddon.ship.ShipHiveKind;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RelicsAddon.MOD_ID);

    /** One ship hive block per kind: armoured like a hull plate, mined with a pickaxe. */
    public static final Map<ShipHiveKind, DeferredBlock<ShipHiveBlock>> SHIP_HIVES = new EnumMap<>(ShipHiveKind.class);

    static {
        for (ShipHiveKind kind : ShipHiveKind.values()) {
            SHIP_HIVES.put(kind, BLOCKS.register(kind.id, () -> new ShipHiveBlock(kind, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(5, 12).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()
                    .lightLevel(state -> 5))));
        }
    }

    private ModBlocks() {
    }
}
