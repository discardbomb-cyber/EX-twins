package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldTopology;

public final class TwinsShieldGlyphMeshCheck {
    public static void main(String[] args) {
        for (boolean low : new boolean[]{true, false}) {
            var mesh = TwinsShieldGlyphMesh.forQuality(low);
            require(mesh == TwinsShieldGlyphMesh.forQuality(low), "Mesh must be cached");
            int count = 0;
            boolean[] materials = new boolean[5];
            for (var cell : ShieldTopology.INSTANCE.cells()) {
                require(mesh.cell(cell.id()).length > 0, "Every combat cell must have visual geometry");
                for (var triangle : mesh.cell(cell.id())) {
                    require(triangle.cell() == cell.id(), "Cell assignment");
                    require(Double.isFinite(triangle.phase()), "Finite light phase");
                    materials[triangle.material()] = true;
                    for (var p : new TwinsShieldGlyphMesh.Point[]{triangle.a(), triangle.b(), triangle.c()}) {
                        require(Math.abs(p.dot(p) - 1) < 1e-6, "All geometry lies on the shield sphere");
                        double own = dot(p, cell.center());
                        for (var other : ShieldTopology.INSTANCE.cells()) {
                            require(dot(p, other.center()) <= own + 1e-7, "Glyph or halo leaks across combat-cell boundary");
                        }
                    }
                    count++;
                }
            }
            require(materials[0] && materials[2] && materials[3], "Membrane, glyphs and orbits required");
            require(materials[1] != low, "Glow layer is reserved for high quality");
            require(materials[4], "Hit-triggered mana-circuit material required");
            int circuitCount = 0;
            for (var cell : ShieldTopology.INSTANCE.cells()) {
                for (var triangle : mesh.circuits(cell.id())) {
                    require(triangle.material() == TwinsShieldGlyphMesh.CIRCUIT, "Circuit subset must contain only tracks");
                    circuitCount++;
                }
            }
            require(circuitCount > 100 && circuitCount < 8000, "Bounded circuit pass: " + circuitCount);
            require(count > 3000 && count < 60000, "Bounded triangle budget: " + count);
            System.out.println("Twins glyphs " + (low ? "LOW" : "HIGH") + ": " + count + " cached triangles, exact " + ShieldTopology.CELL_COUNT + "-cell clipping");
        }
    }

    private static double dot(TwinsShieldGlyphMesh.Point p, float[] center) {
        return p.x() * center[0] + p.y() * center[1] + p.z() * center[2];
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
