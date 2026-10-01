package dev.hurtify.relicsaddon.shipshield;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/**
 * The blocks a ship device stands on: a Sable sub-level (an airship) when Create Aeronautics is
 * present, otherwise the connected solid blocks around the device. Positions are in the level's
 * own coordinates (for an airship, its plot far away in the shipyard). The block set may stop at
 * the configured limit, but the block entities are always complete, so devices find each other
 * on any size of ship and two devices share a structure when one lists the other's position.
 */
public final class ShipStructure {
    /** Stable identity: the sub-level's UUID, or "static" for a build that is not an airship. */
    private final String id;
    private final BlockPos anchor;
    private final LongSet blocks;
    private final List<BlockPos> blockEntities;
    private final boolean truncated;

    public ShipStructure(String id, BlockPos anchor, LongSet blocks, List<BlockPos> blockEntities, boolean truncated) {
        this.id = id;
        this.anchor = anchor.immutable();
        this.blocks = blocks;
        this.blockEntities = List.copyOf(blockEntities);
        this.truncated = truncated;
    }

    public static ShipStructure empty(BlockPos anchor) {
        return new ShipStructure("static", anchor, new LongOpenHashSet(), List.of(), false);
    }

    public String id() { return id; }
    public BlockPos anchor() { return anchor; }
    /** True for an airship; a static build only knows itself by its blocks. */
    public boolean airship() { return !"static".equals(id); }
    public int size() { return blocks.size(); }
    /** Whether the scan stopped at the configured limit, so the count is a lower bound. */
    public boolean truncated() { return truncated; }
    public boolean contains(BlockPos pos) { return blocks.contains(pos.asLong()); }
    /** Every block entity on the structure, whatever the block limit cut off: where devices and stores are found. */
    public List<BlockPos> blockEntities() { return blockEntities; }

    /** A stable fingerprint of the block set: the same blocks give the same value, so shells can be cached by it. */
    public long fingerprint() {
        long sum = 0, xor = 0;
        for (long packed : blocks) {
            long mixed = packed * 0x9E3779B97F4A7C15L;
            mixed ^= mixed >>> 29;
            sum += mixed;
            xor ^= mixed;
        }
        return sum ^ xor << 1 ^ (long) blocks.size() << 48;
    }

    public void forEach(Consumer<BlockPos> action) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        blocks.forEach(packed -> action.accept(cursor.set(packed)));
    }
}
