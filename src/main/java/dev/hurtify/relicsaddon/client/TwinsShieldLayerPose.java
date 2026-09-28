package dev.hurtify.relicsaddon.client;

/**
 * Mechanical clocks for the separated Ex-Twins shield arcs.  These are kept
 * outside {@link TwinsFacetPose}: that class owns the actual mesh-facet pivots.
 */
final class TwinsShieldLayerPose {
    record Layer(float axisX, float axisY, float axisZ, float degrees) { }

    static Layer layer(int index, double time) {
        if (index < 0 || index > 2) throw new IllegalArgumentException("Invalid Twins shield layer");
        double ticks = Double.isFinite(time) ? time : 0.0D;
        return switch (index) {
            // Cage, heart and rune/smoke layer turn on unlike axes. Their
            // modest rates preserve the clear air gap between the arc shells.
            case 0 -> new Layer(.18F, 1.0F, .37F, degrees(ticks * .92D));
            case 1 -> new Layer(1.0F, -.31F, .16F, degrees(-ticks * 1.61D + 41.0D));
            default -> new Layer(-.42F, .51F, 1.0F, degrees(ticks * 2.43D - 23.0D));
        };
    }

    static float shellSpin(int index, double time) {
        if (index < 0 || index >= TwinsFacetPose.COUNT) throw new IllegalArgumentException("Invalid Twins shield arc");
        double ticks = Double.isFinite(time) ? time : 0.0D;
        // Each separated arc uses a unique rate and phase. The deliberately
        // bounded turn keeps neighbouring armor from sweeping through the
        // core or each other while the larger layers make full revolutions.
        return (float) (7.5D * Math.sin(ticks * (.029D + index * .0011D) + index * 1.71D));
    }

    private static float degrees(double value) {
        double wrapped = value % 360.0D;
        return (float) (wrapped < 0.0D ? wrapped + 360.0D : wrapped);
    }

    private TwinsShieldLayerPose() { }
}
