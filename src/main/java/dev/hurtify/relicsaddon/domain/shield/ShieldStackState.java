package dev.hurtify.relicsaddon.domain.shield;

import java.util.ArrayList;
import java.util.List;

/** Cell HP is authoritative; the four old sector fields remain as migration summaries. */
public record ShieldStackState(boolean enabled, int front, int left, int right, int back,
        int lastHitPanel, float lastAbsorbed, long lastActiveGameTime,
        int sharedBuffer, List<Integer> cells, List<ShieldCellMove> moves, long gatherTime) {
    /** Aliases of the ShieldTopology sectors for existing callers. */
    public static final int PANEL_FRONT = ShieldTopology.PANEL_FRONT, PANEL_LEFT = ShieldTopology.PANEL_LEFT,
            PANEL_RIGHT = ShieldTopology.PANEL_RIGHT, PANEL_BACK = ShieldTopology.PANEL_BACK, PANEL_NONE = ShieldTopology.PANEL_NONE;
    public static final int MAX_PANEL_INTEGRITY = 12;
    /** New shields have the former 42-cell total as a common pool before local cells can break. */
    public static final int MAX_SHARED_BUFFER = 504;
    public static final int MAX_BUFFER_CAPACITY = 5000;
    public static final int BUFFER_REPAIR_QUIET_TICKS = 40;
    public static final int MAX_TOTAL_INTEGRITY = MAX_SHARED_BUFFER + ShieldTopology.CELL_COUNT * MAX_PANEL_INTEGRITY;
    public static final ShieldStackState DEFAULT = new ShieldStackState(true, 12, 12, 12, 12, -1, 0, 0, MAX_SHARED_BUFFER, List.of(), List.of(), -1);

    public ShieldStackState(boolean enabled, int front, int left, int right, int back,
            int lastHitPanel, float lastAbsorbed, long lastActiveGameTime) {
        this(enabled, front, left, right, back, lastHitPanel, lastAbsorbed, lastActiveGameTime, MAX_SHARED_BUFFER, List.of(), List.of(), -1);
    }

    public ShieldStackState {
        boolean legacyCells = cells.isEmpty() || cells.size() == ShieldTopology.LEGACY_CELL_COUNT;
        if (cells.isEmpty()) {
            int[] legacy = {front, left, right, back};
            cells = java.util.Arrays.stream(ShieldTopology.INSTANCE.cells()).map(cell -> clamp(legacy[cell.panel()])).toList();
        } else if (cells.size() == ShieldTopology.LEGACY_CELL_COUNT) {
            cells = migrateLegacyCells(cells);
        } else {
            if (cells.size() != ShieldTopology.CELL_COUNT) throw new IllegalArgumentException("Invalid shield cell count");
            cells = cells.stream().map(ShieldStackState::clamp).toList();
        }
        int[] minimum = {12, 12, 12, 12};
        for (var cell : ShieldTopology.INSTANCE.cells()) minimum[cell.panel()] = Math.min(minimum[cell.panel()], cells.get(cell.id()));
        front = minimum[0]; left = minimum[1]; right = minimum[2]; back = minimum[3];
        moves = legacyCells ? migrateLegacyMoves(moves) : List.copyOf(moves);
        if (moves.size() > 3 || moves.stream().anyMatch(m -> m.from() < 0 || m.to() < 0 || m.from() >= ShieldTopology.CELL_COUNT || m.to() >= ShieldTopology.CELL_COUNT)) {
            throw new IllegalArgumentException("Invalid shield relocation");
        }
        lastHitPanel = lastHitPanel >= 0 && lastHitPanel < 4 ? lastHitPanel : PANEL_NONE;
        lastAbsorbed = Float.isFinite(lastAbsorbed) ? Math.max(0, lastAbsorbed) : 0;
        lastActiveGameTime = Math.max(0, lastActiveGameTime);
        gatherTime = Math.max(-1, gatherTime);
        sharedBuffer = Math.clamp(sharedBuffer, 0, MAX_BUFFER_CAPACITY);
    }

    public int integrity(int panel) {
        return switch (panel) { case 0 -> front; case 1 -> left; case 2 -> right; case 3 -> back; default -> 0; };
    }

    public int cellHp(int cell) { return cells.get(cell); }
    public boolean gathering(double time) {
        return !moves.isEmpty() && gatherTime >= 0 && time < gatherTime + ShieldCellDefense.MOVE_TICKS;
    }
    public boolean moving(int cell, double time) {
        return gathering(time) && moves.stream().anyMatch(move -> move.to() == cell);
    }
    public int availableHp(int cell, long time) { return moving(cell, time) ? 0 : cellHp(cell); }
    public int livingCells() { return (int) cells.stream().filter(hp -> hp > 0).count(); }
    public int totalIntegrity() { return sharedBuffer + cells.stream().mapToInt(Integer::intValue).sum(); }
    public boolean needsRepair() { return needsRepair(MAX_SHARED_BUFFER); }
    public boolean needsRepair(int capacity) { return sharedBuffer < capacity || cells.stream().anyMatch(hp -> hp < MAX_PANEL_INTEGRITY); }

    public ShieldStackState withEnabled(boolean value, long gameTime) {
        return new ShieldStackState(value, front, left, right, back, lastHitPanel, lastAbsorbed, lastActiveGameTime, sharedBuffer, cells, moves, gatherTime);
    }

    public ShieldStackState withActivity(long gameTime) {
        return new ShieldStackState(enabled, front, left, right, back, lastHitPanel, lastAbsorbed, gameTime, sharedBuffer, cells, moves, gatherTime);
    }

    public ShieldStackState withCells(List<Integer> health, List<ShieldCellMove> relocation, long gatheredAt) {
        return new ShieldStackState(enabled, front, left, right, back, lastHitPanel, lastAbsorbed, lastActiveGameTime, sharedBuffer, health, relocation, gatheredAt);
    }

    public ShieldStackState withCellsAndBuffer(List<Integer> health, int buffer, List<ShieldCellMove> relocation, long gatheredAt) {
        return new ShieldStackState(enabled, front, left, right, back, lastHitPanel, lastAbsorbed, lastActiveGameTime, buffer, health, relocation, gatheredAt);
    }

    public ShieldStackState withHit(List<Integer> health, int cell, float absorbed, long time) {
        return withHit(health, sharedBuffer, cell, absorbed, time);
    }

    public ShieldStackState withHit(List<Integer> health, int buffer, int cell, float absorbed, long time) {
        return new ShieldStackState(enabled, front, left, right, back, ShieldTopology.INSTANCE.cells()[cell].panel(),
                absorbed, time, buffer, health, moves, gatherTime);
    }

    public ShieldStackState damageCell(int cell, int amount, float absorbed, long time) {
        return damageLocalCell(cell, amount, absorbed, time);
    }

    /** Convenience route for callers outside ShieldCellDefense that need pool-first damage. */
    public ShieldStackState routeCellDamage(int cell, int amount, float absorbed, long time) {
        int spentFromBuffer = Math.min(sharedBuffer, Math.max(0, amount));
        int localDamage = Math.max(0, amount) - spentFromBuffer;
        var health = new ArrayList<>(cells);
        health.set(cell, Math.max(0, cellHp(cell) - localDamage));
        return withHit(health, sharedBuffer - spentFromBuffer, cell, absorbed, time);
    }

    /** Explicit local mutation for migration/test fixtures that must model an already-open region. */
    public ShieldStackState damageLocalCell(int cell, int amount, float absorbed, long time) {
        var health = new ArrayList<>(cells);
        health.set(cell, Math.max(0, cellHp(cell) - Math.max(0, amount)));
        return withHit(health, sharedBuffer, cell, absorbed, time);
    }

    /** Legacy test/helper API now targets the central cell of the chosen sector. */
    public ShieldStackState damagePanel(int panel, int amount, float absorbed, long time) {
        int cell = switch (panel) {
            case 0 -> ShieldTopology.INSTANCE.nearest(0, 0, 1);
            case 1 -> ShieldTopology.INSTANCE.nearest(-1, 0, 0);
            case 2 -> ShieldTopology.INSTANCE.nearest(1, 0, 0);
            case 3 -> ShieldTopology.INSTANCE.nearest(0, 0, -1);
            default -> -1;
        };
        return cell < 0 ? this : damageCell(cell, amount, absorbed, time);
    }

    public ShieldStackState repairFirstDamagedPanel(long gameTime) {
        return repairFirstDamagedPanel(gameTime, MAX_SHARED_BUFFER);
    }

    public ShieldStackState repairFirstDamagedPanel(long gameTime, int capacity) {
        capacity = Math.clamp(capacity, 0, MAX_BUFFER_CAPACITY);
        if (gameTime < lastActiveGameTime + BUFFER_REPAIR_QUIET_TICKS) return this;
        var health = new ArrayList<>(cells);
        for (int cell = 0; cell < health.size(); cell++) {
            if (health.get(cell) < MAX_PANEL_INTEGRITY) {
                health.set(cell, health.get(cell) + 1);
                return withCells(health, moves, gatherTime);
            }
        }
        if (sharedBuffer < capacity) {
            return new ShieldStackState(enabled, front, left, right, back, lastHitPanel, lastAbsorbed, lastActiveGameTime,
                    sharedBuffer + 1, cells, moves, gatherTime);
        }
        return this;
    }

    private static int clamp(int hp) { return Math.clamp(hp, 0, MAX_PANEL_INTEGRITY); }

    private static List<Integer> migrateLegacyCells(List<Integer> legacyCells) {
        var migrated = new ArrayList<Integer>(ShieldTopology.CELL_COUNT);
        for (var cell : ShieldTopology.INSTANCE.cells()) migrated.add(clamp(legacyCells.get(ShieldTopology.INSTANCE.legacyRegionFor(cell.center()))));
        return List.copyOf(migrated);
    }

    private static List<ShieldCellMove> migrateLegacyMoves(List<ShieldCellMove> legacyMoves) {
        var migrated = new ArrayList<ShieldCellMove>(legacyMoves.size());
        for (ShieldCellMove move : legacyMoves) {
            if (move.from() < 0 || move.to() < 0 || move.from() >= ShieldTopology.LEGACY_CELL_COUNT || move.to() >= ShieldTopology.LEGACY_CELL_COUNT) {
                throw new IllegalArgumentException("Invalid legacy shield relocation");
            }
            migrated.add(new ShieldCellMove(ShieldTopology.INSTANCE.migrateLegacyCell(move.from()), ShieldTopology.INSTANCE.migrateLegacyCell(move.to())));
        }
        return List.copyOf(migrated);
    }
}
