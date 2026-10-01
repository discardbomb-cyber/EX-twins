package dev.hurtify.relicsaddon.client;

/**
 * Mechanical clocks for the separated Ex-Twins shield arcs.  These are kept
 * outside {@link TwinsFacetPose}: that class owns the actual mesh-facet pivots.
 * Layer axes are constant; only the angles depend on the time.
 */
final class TwinsShieldLayerPose {
    // Cage, heart and rune/smoke layer turn on unlike axes. Their
    // modest rates preserve the clear air gap between the arc shells.
    private static final float[][] AXES = {{.18F, 1.0F, .37F}, {1.0F, -.31F, .16F}, {-.42F, .51F, 1.0F}};

    record Layer(float axisX, float axisY, float axisZ, float degrees) { }

    static Layer layer(int index, double time) {
        float[] axis = axis(index);
        return new Layer(axis[0], axis[1], axis[2], degrees(index, time));
    }

    /** The constant rotation axis {x, y, z} of a layer; the array is shared, do not write to it. */
    static float[] axis(int index) {
        if (index < 0 || index > 2) throw new IllegalArgumentException("Invalid Twins shield layer");
        return AXES[index];
    }

    static float degrees(int index, double time) {
        double ticks = Double.isFinite(time) ? time : 0.0D;
        return switch (index) {
            case 0 -> degrees(ticks * .92D);
            case 1 -> degrees(-ticks * 1.61D + 41.0D);
            case 2 -> degrees(ticks * 2.43D - 23.0D);
            default -> throw new IllegalArgumentException("Invalid Twins shield layer");
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
