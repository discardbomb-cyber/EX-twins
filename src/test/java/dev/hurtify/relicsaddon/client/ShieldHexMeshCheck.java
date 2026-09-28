package dev.hurtify.relicsaddon.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Runs without a game window and exports the actual cached geometry for visual review. */
public final class ShieldHexMeshCheck {
    public static void main(String[] args) throws Exception {
        JsonObject report = new JsonObject();
        for (ShieldVisualQuality quality : ShieldVisualQuality.values()) {
            ShieldHexMesh mesh = ShieldHexMesh.forQuality(quality);
            require(mesh == ShieldHexMesh.forQuality(quality), "Mesh must be cached");
            require(mesh.cells().length == ShieldTopology.CELL_COUNT, "Exactly 420 authoritative cells");
            Set<Integer> sectors = new HashSet<>();
            boolean hasCrown = false;
            boolean hasBottom = false;
            double minEdge = 1, maxEdge = 0;
            int hexagons = 0;
            JsonArray cells = new JsonArray();
            for (var cell : mesh.cells()) {
                sectors.add(cell.panel());
                require(cell.perimeter().length >= 15 && cell.perimeter().length % 3 == 0, "Closed honeycomb polygon required");
                if (cell.perimeter().length == 18) hexagons++;
                hasCrown |= cell.center()[1] > 0.99;
                hasBottom |= cell.center()[1] < -0.99;
                checkUnit(cell.center(), 0);
                for (int i = 0; i < cell.perimeter().length; i += 3) {
                    checkUnit(cell.perimeter(), i);
                    int next = (i + 3) % cell.perimeter().length;
                    double edgeLength = distance(cell.perimeter(), i, cell.perimeter(), next);
                    minEdge = Math.min(minEdge, edgeLength); maxEdge = Math.max(maxEdge, edgeLength);
                    require(edgeLength > .002 && edgeLength < .25, "Degenerate or oversized edge: cell=" + cell.id() + " length=" + edgeLength);
                    require(distance(cell.perimeter(), i, cell.center(), 0) < 0.5, "Cell detached from center");
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("panel", cell.panel());
                entry.add("center", array(cell.center()));
                entry.add("perimeter", array(cell.perimeter()));
                cells.add(entry);
            }
            require(sectors.size() == 4, "Every directional sector must be present");
            require(hexagons >= 350, "Most cells must retain a legible six-sided shape");
            require(hasCrown, "Dome must cover the crown");
            require(hasBottom, "Reactive boundary must also show impacts from below");
            report.add(quality.name(), cells);
            System.out.println(quality + ": " + cells.size() + " cells; normalized, cached, four sectors, closed crown; edges=" + minEdge + ".." + maxEdge);
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static JsonArray array(float[] values) {
        JsonArray result = new JsonArray();
        for (float value : values) result.add(value);
        return result;
    }

    private static double distance(float[] a, int i, float[] b, int j) {
        double x = a[i] - b[j], y = a[i + 1] - b[j + 1], z = a[i + 2] - b[j + 2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void checkUnit(float[] point, int index) {
        double length = 0;
        for (int i = 0; i < 3; i++) {
            require(Float.isFinite(point[index + i]), "Nonfinite coordinate");
            length += point[index + i] * point[index + i];
        }
        require(Math.abs(length - 1) < 0.00001, "Point must lie on unit sphere");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
