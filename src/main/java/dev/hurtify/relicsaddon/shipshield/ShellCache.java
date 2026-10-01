package dev.hurtify.relicsaddon.shipshield;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Shells already traced, by the blocks they were traced round, the offset and the cell limit. A
 * ship whose blocks have not changed gets its shell back at once; a changed one is traced
 * off-thread and the old shell stands until the new one is ready. Never saved: the blocks are in
 * the level, and a shell is a few milliseconds to a second of work. Both sides keep one.
 */
public final class ShellCache {
    private static final int KEPT = 24;
    private static final Map<Long, ShellMesh> MESHES = new LinkedHashMap<>(32, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, ShellMesh> eldest) { return size() > KEPT; }
    };

    private static long key(ShellField field, int cellLimit) {
        return field.fingerprint() * 31 + cellLimit;
    }

    /** The shell for these blocks, if it was traced before. */
    public static synchronized ShellMesh cached(ShellField field, int cellLimit) {
        return MESHES.get(key(field, cellLimit));
    }

    /** Traces the shell on {@code executor}, or answers at once from the cache. */
    public static CompletableFuture<ShellMesh> trace(LongSet blocks, double offset, int cellLimit, Executor executor) {
        ShellField field = new ShellField(blocks, offset);
        ShellMesh cached = cached(field, cellLimit);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> {
            ShellMesh mesh = ShellMesh.build(field, cellLimit);
            synchronized (ShellCache.class) {
                MESHES.put(key(field, cellLimit), mesh);
            }
            return mesh;
        }, executor);
    }

    public static synchronized void clear() {
        MESHES.clear();
    }

    private ShellCache() {
    }
}
