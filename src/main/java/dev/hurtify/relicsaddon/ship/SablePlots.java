package dev.hurtify.relicsaddon.ship;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The one thing asked of Sable itself rather than of its companion library: the box of a ship's own blocks in its plot.
 * Only ever reached through a ship Sable handed over, so Sable is there whenever this class loads.
 */
final class SablePlots {
    /** The ship's blocks' box in its plot, or just the hive's own block if Sable keeps it from us. */
    static AABB hull(SubLevelAccess ship, Vec3 hive) {
        if (ship instanceof SubLevel level && level.getPlot() != null) {
            BoundingBox3ic box = level.getPlot().getBoundingBox();
            if (box != null && box.maxX() >= box.minX()) return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        }
        return new AABB(hive, hive).inflate(.5);
    }

    private SablePlots() {
    }
}
