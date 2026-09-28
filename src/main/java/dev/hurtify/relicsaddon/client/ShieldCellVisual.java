package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

final class ShieldCellVisual {
    private static final float[][][] BOUNDARIES = boundaries();

    private static float[][][] boundaries() {
        var cells = ShieldTopology.INSTANCE.cells();
        float[][][] result = new float[cells.length][][];
        for (var cell : cells) {
            int[] neighbors = ShieldTopology.INSTANCE.neighbors(cell.id());
            result[cell.id()] = new float[neighbors.length][3];
            for (int i = 0; i < neighbors.length; i++) {
                float[] other = cells[neighbors[i]].center();
                for (int axis = 0; axis < 3; axis++) result[cell.id()][i][axis] = cell.center()[axis] - other[axis];
            }
        }
        return result;
    }
    static boolean justBroken(ShieldImpact impact, int cell, double forwardX, double forwardZ) {
        if (impact == null || !impact.broken() || cell < 0) return false;
        if (!impact.brokenCells().isEmpty()) return impact.brokenCells().contains(cell);
        var n = impact.normal();
        return cell == ShieldTopology.INSTANCE.nearest(-n.x * forwardZ + n.z * forwardX, n.y, n.x * forwardX + n.z * forwardZ);
    }
    static int color(int healthy, int hp) {
        return hp <= 3 ? 0xED4242 : hp <= 5 ? 0xF08232 : hp <= 8 ? 0xE8D34B : healthy;
    }

    static int violetHealth(int healthy, int hp) {
        double brightness = .45 + .55 * Math.clamp(hp / (double) ShieldStackState.MAX_PANEL_INTEGRITY, 0, 1);
        return (int) ((healthy >> 16 & 255) * brightness) << 16
                | (int) ((healthy >> 8 & 255) * brightness) << 8 | (int) ((healthy & 255) * brightness);
    }

    static int warningHp(ShieldStackState state, int cellHp) {
        return Math.min(cellHp, (int) Math.ceil(state.totalIntegrity() / (double) ShieldTopology.CELL_COUNT));
    }

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
            result[i] = p.x; result[i + 1] = p.y; result[i + 2] = p.z;
        }
        return result;
    }

    record Frame(float[][] movingCenters, float[][][] boundaries, ShieldStackState state, int averageHp) {
        static Frame at(ShieldStackState state, double time) {
            float[][] centers = null;
            float[][][] planes = BOUNDARIES;
            if (time - state.gatherTime() >= 0 && time - state.gatherTime() < ShieldCellDefense.MOVE_TICKS && !state.moves().isEmpty()) {
                centers = new float[ShieldTopology.CELL_COUNT][];
                planes = BOUNDARIES.clone();
                for (var cell : ShieldTopology.INSTANCE.cells()) {
                    var rotation = relocation(state, cell.id(), time);
                    centers[cell.id()] = transform(cell.center(), rotation);
                    if (state.moving(cell.id(), time)) {
                        planes[cell.id()] = new float[BOUNDARIES[cell.id()].length][];
                        for (int edge = 0; edge < planes[cell.id()].length; edge++) planes[cell.id()][edge] = transform(BOUNDARIES[cell.id()][edge], rotation);
                    }
                }
            }
            return new Frame(centers, planes, state, (int) Math.ceil(state.totalIntegrity() / (double) ShieldTopology.CELL_COUNT));
        }

        List<Vec3> clip(int cell, List<Vec3> polygon, double fx, double fz) {
            for (float[] plane : boundaries[cell]) {
                if (polygon.size() < 3) return List.of();
                List<Vec3> next = new ArrayList<>(5);
                Vec3 previous = polygon.getLast();
                double before = side(previous, plane, fx, fz);
                for (Vec3 current : polygon) {
                    double now = side(current, plane, fx, fz);
                    if ((before >= 0) != (now >= 0)) next.add(previous.lerp(current, before / (before - now)).normalize());
                    if (now >= 0) next.add(current);
                    previous = current;
                    before = now;
                }
                polygon = next;
            }
            return polygon;
        }

        private static double side(Vec3 p, float[] n, double fx, double fz) {
            return (-p.x * fz + p.z * fx) * n[0] + p.y * n[1] + (p.x * fx + p.z * fz) * n[2];
        }
    }

    static int cellAt(double x, double y, double z, Frame frame) {
        if (frame.movingCenters() != null) {
            // During a move the membrane follows the moving cell, not the empty destination.
            int closest = -1;
            double best = -1;
            for (var cell : ShieldTopology.INSTANCE.cells()) {
                if (frame.state().cellHp(cell.id()) == 0) continue;
                float[] c = frame.movingCenters()[cell.id()];
                double dot = x * c[0] + y * c[1] + z * c[2];
                if (dot <= best) continue;
                boolean inside = true;
                for (float[] plane : frame.boundaries()[cell.id()]) if (x * plane[0] + y * plane[1] + z * plane[2] < -1e-7) {
                    inside = false;
                    break;
                }
                if (inside) { best = dot; closest = cell.id(); }
            }
            return closest;
        }
        return ShieldTopology.INSTANCE.nearest(x, y, z);
    }

    private ShieldCellVisual() { }
}
