package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Per-cell helpers for the hexagonal shield cells: health colours, fresh breaks and the gather animation. */
final class ShieldCellVisual {
    /** Whether {@code cell} broke in one of these impacts; it still flashes once before it becomes a hole. */
    static boolean justBroken(List<ShieldImpact> impacts, int cell, double forwardX, double forwardZ) {
        for (ShieldImpact impact : impacts) {
            if (!impact.broken() || cell < 0) continue;
            if (!impact.brokenCells().isEmpty()) {
                if (impact.brokenCells().contains(cell)) return true;
                continue;
            }
            var n = impact.normal();
            if (cell == ShieldTopology.INSTANCE.nearest(-n.x * forwardZ + n.z * forwardX, n.y, n.x * forwardX + n.z * forwardZ)) return true;
        }
        return false;
    }

    /** RF cells warm from their own colour through yellow and orange to red as they lose integrity. */
    static int warm(int healthy, int hp) {
        return hp <= 3 ? 0xED4242 : hp <= 5 ? 0xF08232 : hp <= 8 ? 0xE8D34B : healthy;
    }

    /** Twins cells keep their violet and simply dim as they lose integrity. */
    static int dim(int healthy, int hp) {
        double brightness = .45 + .55 * Math.clamp(hp / (double) ShieldStackState.MAX_PANEL_INTEGRITY, 0, 1);
        return (int) ((healthy >> 16 & 255) * brightness) << 16
                | (int) ((healthy >> 8 & 255) * brightness) << 8 | (int) ((healthy & 255) * brightness);
    }

    /** While cells gather after a hit, a moving cell glides from its old place to its new one. */
    static Quaternionf relocation(ShieldStackState state, int cell, double time) {
        double age = time - state.gatherTime();
        if (age < 0 || age >= ShieldCellDefense.MOVE_TICKS) return new Quaternionf();
        for (var move : state.moves()) if (move.to() == cell) {
            float t = (float) (age / ShieldCellDefense.MOVE_TICKS);
            t = t * t * (3 - 2 * t);
            var cells = ShieldTopology.INSTANCE.cells();
            var to = new Vector3f(cells[cell].center());
            var from = new Vector3f(cells[move.from()].center());
            return new Quaternionf().rotationTo(to, from).slerp(new Quaternionf(), t);
        }
        return new Quaternionf();
    }

    static float[] transform(float[] points, Quaternionf rotation) {
        float[] result = points.clone();
        for (int i = 0; i < result.length; i += 3) {
            Vector3f p = new Vector3f(result[i], result[i + 1], result[i + 2]).rotate(rotation);
            result[i] = p.x;
            result[i + 1] = p.y;
            result[i + 2] = p.z;
        }
        return result;
    }

    private ShieldCellVisual() { }
}
