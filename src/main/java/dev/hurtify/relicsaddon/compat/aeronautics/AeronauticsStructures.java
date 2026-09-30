package dev.hurtify.relicsaddon.compat.aeronautics;

import dev.hurtify.relicsaddon.shipshield.ShipStructure;
import dev.hurtify.relicsaddon.shipshield.ShipStructures;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Sable airships as ship structures. A block on a sub-level lives in that sub-level's plot (real
 * chunks far away in the shipyard), so {@code SableCompanion.getContaining} answers by chunk and
 * the plot's block-aligned bounds enclose every block of the ship. Only this package refers to
 * Sable types; {@link ShipStructures} loads it by name when the mod is present.
 */
public final class AeronauticsStructures implements ShipStructures.Locator {
    @Override public ShipStructure locate(ServerLevel level, BlockPos anchor) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, anchor);
        if (!(access instanceof SubLevel ship) || ship.isRemoved()) return null;
        BoundingBox3ic bounds = ship.getPlot().getBoundingBox();
        LongSet blocks = new LongOpenHashSet();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (ShipStructures.solid(level.getBlockState(cursor.set(x, y, z)))) blocks.add(cursor.asLong());
                }
            }
        }
        return new ShipStructure(ship.getUniqueId().toString(), anchor, blocks, false);
    }
}
