package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.RelicsAddon;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Finds the structure a ship device stands on. With Sable loaded the airship locator in
 * {@code compat.aeronautics} answers first; a device off any airship, or any device without
 * Sable, gets the connected solid blocks around it. Works on both sides: a client finds the same
 * structure from its own copy of the blocks, so it can build the same shield shell.
 */
public final class ShipStructures {
    /** Answers which blocks belong to the structure at {@code anchor}, or null to defer to the static scan. */
    public interface Locator {
        ShipStructure locate(Level level, BlockPos anchor);
    }

    /**
     * Blocks that are land, not build: the connected-block scan never counts them and never passes
     * through them, so a generator standing on the ground shields the building, not the hill under
     * it. Natural stone, dirt, sand, gravel, terracotta, ores, leaves, snow, ice and the like; a
     * datapack can add a mod's own terrain.
     */
    public static final TagKey<Block> NATURAL_TERRAIN = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "natural_terrain"));

    private static final int STATIC_SCAN_LIMIT_FLOOR = 64;
    private static Locator airships;
    private static boolean airshipsResolved;

    public static ShipStructure locate(Level level, BlockPos anchor) {
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
     * Connected blocks of a build around {@code anchor} (6-neighbourhood), within the configured
     * radius and block limit. Natural terrain ({@link #NATURAL_TERRAIN}) and fluids are not part of
     * a build and stop the fill, so a house on a hill is the house: its walls, floors, furniture
     * and machines. A build made of raw terrain blocks (plain stone, dirt) is cut off there too;
     * cobblestone, bricks, planks and every processed block count.
     */
    public static ShipStructure connected(Level level, BlockPos anchor) {
        int radius = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_STATIC_RADIUS.get() : 32;
        int limit = blockLimit();
        LongSet seen = new LongOpenHashSet();
        java.util.List<BlockPos> entities = new java.util.ArrayList<>();
        if (level.getBlockEntity(anchor) != null) entities.add(anchor.immutable());
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
                if (!level.isLoaded(cursor.set(x, y, z)) || !build(level.getBlockState(cursor))) continue;
                seen.add(next);
                queue.enqueue(next);
                if (level.getBlockEntity(cursor) != null) entities.add(cursor.immutable());
            }
        }
        return new ShipStructure("static", anchor, seen, entities, truncated);
    }

    /**
     * Where a point of a structure is in the world: a block on an airship sits in the ship's plot far
     * away, so distances to players go through this. Sable Companion is bundled and answers with the
     * point itself where there is no Sable or no ship.
     */
    public static net.minecraft.world.phys.Vec3 worldPosition(Level level, net.minecraft.world.phys.Vec3 position) {
        return dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.projectOutOfSubLevel(level, (net.minecraft.core.Position) position);
    }

    /** Most blocks any structure scan counts (config {@code shipShield.maxStructureBlocks}). */
    public static int blockLimit() {
        return Math.max(STATIC_SCAN_LIMIT_FLOOR, AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_MAX_STRUCTURE_BLOCKS.get() : 4096);
    }

    /**
     * Blocks that make up a build: anything that is not air, a fluid, a loose plant a player can
     * walk through, or an invisible technical block (barriers, structure voids, lights). A
     * waterlogged slab or stair is still a block. Airships are made of these; a static build is
     * made of those that are not {@link #build natural terrain} as well.
     */
    public static boolean solid(BlockState state) {
        return !state.isAir() && !state.liquid() && !state.canBeReplaced()
                && !state.is(Blocks.BARRIER) && !state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.LIGHT);
    }

    /** A block of a static build: {@link #solid} and not natural terrain. */
    public static boolean build(BlockState state) {
        return solid(state) && !state.is(NATURAL_TERRAIN);
    }

    private ShipStructures() {
    }
}
