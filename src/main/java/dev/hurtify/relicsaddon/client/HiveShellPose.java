package dev.hurtify.relicsaddon.client;

/**
 * Joint order matches the closed shell groups in build_hive_meshes.py. The joint axes are
 * constant per (shell count, index) and are tabulated once; only the opening, tilt and spin
 * depend on the time, so a renderer can read them without building a pose object per frame.
 */
final class HiveShellPose {
    private static final double A = .5257311121191336, B = .85065080835204;
    private static final double[][] NORMALS = {
            {0, -A, -B}, {0, -A, B}, {0, A, -B}, {0, A, B},
            {-A, -B, 0}, {-A, B, 0}, {A, -B, 0}, {A, B, 0},
            {-B, 0, -A}, {B, 0, -A}, {-B, 0, A}, {B, 0, A}
    };
    private static final double[][] RING_4 = ring(4), RING_6 = ring(6);
    private static final float[][] TWINS_LAYER_AXES = {{.21F, 1.0F, .43F}, {1.0F, .34F, -.18F}, {-.36F, .58F, 1.0F}};

    record Pose(double x, double y, double z, double offset, float tilt, float spin) { }

    /** A separately rotating mechanical layer of the Ex-Twins hive. */
    record LayerPose(float axisX, float axisY, float axisZ, float degrees) { }

    static Pose sample(int shells, int index, double time) {
        double[] axis = axis(shells, index);
        return new Pose(axis[0], axis[1], axis[2], opening(shells, index, time), tilt(shells, index, time), spin(shells, index, time));
    }

    /** The constant joint axis {x, y, z} of a shell; the array is shared, do not write to it. */
    static double[] axis(int shells, int index) {
        if (index < 0 || index >= shells || shells != 4 && shells != 6 && shells != 12) throw new IllegalArgumentException("Invalid hive joint");
        return switch (shells) {
            case 12 -> NORMALS[index];
            case 6 -> RING_6[index];
            default -> RING_4[index];
        };
    }

    static double opening(int shells, int index, double time) {
        double phase = phase(shells, index, time);
        return shells == 12 ? .020 + .010 * (.5 + .5 * Math.sin(phase))
                : .010 + .008 * (.5 + .5 * Math.sin(phase));
    }

    static float tilt(int shells, int index, double time) {
        double phase = phase(shells, index, time);
        return (float) (1.5 * Math.sin(shells == 12 ? phase * .83 : phase));
    }

    static float spin(int shells, int index, double time) {
        return shells == 12 ? degrees(time * (1.35 + index * .13) + index * 27.5) : 0.0F;
    }

    private static double phase(int shells, int index, double time) {
        return time * (shells == 12 ? .027 + index * .0013 : .035) + index * .91;
    }

    private static double[][] ring(int shells) {
        double[][] ring = new double[shells][];
        for (int index = 0; index < shells; index++) {
            double angle = index * Math.PI * 2 / shells;
            ring[index] = new double[] {Math.cos(angle), Math.sin(angle), 0};
        }
        return ring;
    }

    /**
     * The body, amethyst heart and rune lattice deliberately use unlike axes
     * and angular rates.  The time argument is absolute, so a menu reload
     * cannot reset their phase or make the layers appear locked together.
     */
    static LayerPose twinsLayer(int layer, double time) {
        float[] axis = twinsLayerAxis(layer);
        return new LayerPose(axis[0], axis[1], axis[2], twinsLayerDegrees(layer, time));
    }

    /** The constant rotation axis {x, y, z} of a Twins hive layer; the array is shared, do not write to it. */
    static float[] twinsLayerAxis(int layer) {
        if (layer < 0 || layer > 2) throw new IllegalArgumentException("Invalid Twins hive layer");
        return TWINS_LAYER_AXES[layer];
    }

    static float twinsLayerDegrees(int layer, double time) {
        return switch (layer) {
            case 0 -> degrees(time * 1.18);
            case 1 -> degrees(-time * 2.07 + 34.0);
            case 2 -> degrees(time * 3.31 - 19.0);
            default -> throw new IllegalArgumentException("Invalid Twins hive layer");
        };
    }

    private static float degrees(double value) {
        double wrapped = value % 360.0D;
        return (float) (wrapped < 0.0D ? wrapped + 360.0D : wrapped);
    }

    private HiveShellPose() { }
}
