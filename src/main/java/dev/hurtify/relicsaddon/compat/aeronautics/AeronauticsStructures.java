package dev.hurtify.relicsaddon.compat.aeronautics;

import dev.hurtify.relicsaddon.shipshield.ShipStructure;
import dev.hurtify.relicsaddon.shipshield.ShipStructures;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Sable airships as ship structures. A block on a sub-level lives in that sub-level's plot (real
 * chunks far away in the shipyard), so {@code SableCompanion.getContaining} answers by chunk and
 * the plot's loaded chunks hold every block of the ship. Only the chunk sections that are not
 * empty are read, within the plot's block bounds, up to the configured block limit; the block
 * entities are listed from every chunk whatever that limit, so the generators, docks and stores
 * of a large ship are always all found. Only this package refers to Sable types;
 * {@link ShipStructures} loads it by name when the mod is present.
 */
public final class AeronauticsStructures implements ShipStructures.Locator {
    @Override public ShipStructure locate(ServerLevel level, BlockPos anchor) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, anchor);
        if (!(access instanceof SubLevel ship) || ship.isRemoved()) return null;
        BoundingBox3ic bounds = ship.getPlot().getBoundingBox();
        int limit = ShipStructures.blockLimit();
        LongSet blocks = new LongOpenHashSet();
        List<BlockPos> entities = new ArrayList<>();
        boolean truncated = false;
        for (PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            int chunkX = chunk.getPos().getMinBlockX(), chunkZ = chunk.getPos().getMinBlockZ();
            if (chunkX + 15 < bounds.minX() || chunkX > bounds.maxX() || chunkZ + 15 < bounds.minZ() || chunkZ > bounds.maxZ()) continue;
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                if (bounds.contains(pos.getX(), pos.getY(), pos.getZ())) entities.add(pos.immutable());
            }
            if (!truncated) truncated = !collect(chunk, bounds, blocks, limit);
        }
        return new ShipStructure(ship.getUniqueId().toString(), anchor, blocks, entities, truncated);
    }

    /** Adds the chunk's solid blocks inside {@code bounds} to {@code blocks}; false once the limit is hit. */
    private static boolean collect(LevelChunk chunk, BoundingBox3ic bounds, LongSet blocks, int limit) {
        int chunkX = chunk.getPos().getMinBlockX(), chunkZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
            if (section == null || section.hasOnlyAir() || sectionY + 15 < bounds.minY() || sectionY > bounds.maxY()) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldX = chunkX + x, worldY = sectionY + y, worldZ = chunkZ + z;
                        if (!bounds.contains(worldX, worldY, worldZ) || !ShipStructures.solid(section.getBlockState(x, y, z))) continue;
                        if (blocks.size() >= limit) return false;
                        blocks.add(BlockPos.asLong(worldX, worldY, worldZ));
                    }
                }
            }
        }
        return true;
    }
}
