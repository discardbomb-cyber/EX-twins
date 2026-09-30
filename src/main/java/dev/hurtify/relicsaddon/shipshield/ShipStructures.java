package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.RelicsAddon;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Finds the structure a ship device stands on. With Sable loaded the airship locator in
 * {@code compat.aeronautics} answers first; a device off any airship, or any device without
 * Sable, gets the connected solid blocks around it.
 */
public final class ShipStructures {
    /** Answers which blocks belong to the structure at {@code anchor}, or null to defer to the static scan. */
    public interface Locator {
        ShipStructure locate(ServerLevel level, BlockPos anchor);
    }

    private static final int STATIC_SCAN_LIMIT_FLOOR = 64;
    private static Locator airships;
    private static boolean airshipsResolved;

    public static ShipStructure locate(ServerLevel level, BlockPos anchor) {
        Locator locator = airships();
        if (locator != null) {
            try {
                ShipStructure ship = locator.locate(level, anchor);
                if (ship != null) return ship;
            } catch (Throwable failure) {
                RelicsAddon.LOGGER.warn("Airship lookup failed; treating the build at {} as static", anchor, failure);
            }
        }
        return connected(level, anchor);
    }

    /** Whether the airship locator is in use, that is Sable is loaded and its bridge bound. */
    public static boolean airshipsAvailable() {
        return airships() != null;
    }

    private static synchronized Locator airships() {
        if (!airshipsResolved) {
            airshipsResolved = true;
            if (ModList.get().isLoaded("sable")) {
                try {
                    airships = (Locator) Class.forName("dev.hurtify.relicsaddon.compat.aeronautics.AeronauticsStructures")
                            .getConstructor().newInstance();
                    RelicsAddon.LOGGER.info("Ship shields follow Sable airships");
                } catch (ReflectiveOperationException | LinkageError failure) {
                    RelicsAddon.LOGGER.warn("Sable is loaded but the airship bridge did not bind; ship shields treat builds as static", failure);
                }
            }
        }
        return airships;
    }

    /**
     * Connected solid blocks around {@code anchor} (6-neighbourhood), within the configured radius
     * and block limit. Terrain counts too, so a generator on the ground reaches the limit and is
     * reported as truncated; ship shields are meant for airships and free-standing builds.
     */
    public static ShipStructure connected(ServerLevel level, BlockPos anchor) {
        int radius = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_STATIC_RADIUS.get() : 32;
        int limit = Math.max(STATIC_SCAN_LIMIT_FLOOR, AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_MAX_STRUCTURE_BLOCKS.get() : 4096);
        LongSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean truncated = false;
        seen.add(anchor.asLong());
        queue.enqueue(anchor.asLong());
        while (!queue.isEmpty() && !truncated) {
            long packed = queue.dequeueLong();
            int centerX = BlockPos.getX(packed), centerY = BlockPos.getY(packed), centerZ = BlockPos.getZ(packed);
            for (Direction direction : Direction.values()) {
                int x = centerX + direction.getStepX(), y = centerY + direction.getStepY(), z = centerZ + direction.getStepZ();
                if (Math.abs(x - anchor.getX()) > radius || Math.abs(y - anchor.getY()) > radius || Math.abs(z - anchor.getZ()) > radius) continue;
                long next = BlockPos.asLong(x, y, z);
                if (seen.contains(next)) continue;
                if (seen.size() >= limit) { truncated = true; break; }
                if (!level.isLoaded(cursor.set(x, y, z)) || !solid(level.getBlockState(cursor))) continue;
                seen.add(next);
                queue.enqueue(next);
            }
        }
        return new ShipStructure("static", anchor, seen, truncated);
    }

    /**
     * Blocks that make up a build: anything that is not air, a fluid, a loose plant a player can
     * walk through, or an invisible technical block (barriers, structure voids, lights).
     */
    public static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced()
                && !state.is(Blocks.BARRIER) && !state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.LIGHT);
    }

    private ShipStructures() {
    }
}
