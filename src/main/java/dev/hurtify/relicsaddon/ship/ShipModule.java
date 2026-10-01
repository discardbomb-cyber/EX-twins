package dev.hurtify.relicsaddon.ship;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * What a ship hive of one kind does, and what it remembers of it: what lasts (saved with the hive) apart from what
 * only means something while the hive runs (sent to clients to draw the drones, never saved, so a world loaded
 * again never shows a beam or a shield that is no longer there).
 */
public interface ShipModule {
    /** A server tick of the hive, out in the world at {@code frame}, its ship's threats on {@code brain}. */
    void tick(ShipHiveBlockEntity hive, ServerLevel level, ShipFrame frame, ShipBrain brain, long now);

    /** The hive is gone or unloaded: let go of whatever is under way. */
    default void stop(ShipHiveBlockEntity hive) {
    }

    /** What the drones are doing, for the hive's status. */
    ShipStatus.Line status();

    /** What lasts, saved with the hive (and sent to clients). */
    void save(CompoundTag tag);

    void load(CompoundTag tag);

    /** What the drones are doing right now, sent to clients only. */
    void saveSync(CompoundTag tag);

    void loadSync(CompoundTag tag);
}
