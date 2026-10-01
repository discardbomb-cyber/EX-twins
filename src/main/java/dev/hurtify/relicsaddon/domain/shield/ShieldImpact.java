package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.math.Vec3d;
import java.util.List;

/**
 * Last server-confirmed impact, independent of repairs and the player's current facing.
 *
 * <p>{@code strike} is zero for an absorbed hit. For the shell striking a mob it is how far beyond the
 * surface the struck body sits, so clients can aim the discharge at it.
 */
public record ShieldImpact(Vec3d normal, long gameTime, int panel, float absorbed, boolean broken, List<Integer> brokenCells, double distance,
        float strike) {
    public ShieldImpact(Vec3d normal, long gameTime, int panel, float absorbed, boolean broken) {
        this(normal, gameTime, panel, absorbed, broken, List.of(), -1, 0);
    }

    public ShieldImpact(Vec3d normal, long gameTime, int panel, float absorbed, boolean broken, List<Integer> brokenCells) {
        this(normal, gameTime, panel, absorbed, broken, brokenCells, -1, 0);
    }

    public ShieldImpact atDistance(double value) {
        return new ShieldImpact(normal, gameTime, panel, absorbed, broken, brokenCells, value, strike);
    }

    /** The shell struck a body {@code reach} blocks beyond its surface along {@link #normal()}. */
    public static ShieldImpact strike(Vec3d normal, long time, int panel, float damage, float reach) {
        return new ShieldImpact(normal, time, panel, damage, false, List.of(), -1, Math.max(.05F, reach));
    }

    public boolean isStrike() {
        return strike > 0;
    }

    public static ShieldImpact of(Vec3d normal, long time, int cell, float absorbed, ShieldStackState before, ShieldStackState after) {
        var broken = java.util.stream.IntStream.range(0, ShieldTopology.CELL_COUNT)
                .filter(id -> before.cellHp(id) > 0 && after.cellHp(id) == 0).boxed().toList();
        return new ShieldImpact(normal, time, ShieldTopology.INSTANCE.cells()[cell].panel(), absorbed, !broken.isEmpty(), broken);
    }

    public ShieldImpact {
        normal = normal != null && Double.isFinite(normal.lengthSqr()) && normal.lengthSqr() > 1e-10D
                ? normal.normalize() : new Vec3d(0, 0, 1);
        gameTime = Math.max(0L, gameTime);
        panel = Math.clamp(panel, 0, 3);
        absorbed = Float.isFinite(absorbed) ? Math.max(0, absorbed) : 0;
        brokenCells = List.copyOf(brokenCells);
        distance = Double.isFinite(distance) && distance >= 0 ? Math.min(24, distance) : -1;
        strike = Float.isFinite(strike) ? Math.clamp(strike, 0, 4) : 0;
    }
}
