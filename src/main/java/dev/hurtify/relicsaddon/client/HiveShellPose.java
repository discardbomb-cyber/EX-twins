package dev.hurtify.relicsaddon.client;

/** Joint order matches the closed shell groups in build_hive_meshes.py. */
final class HiveShellPose {
    private static final double A = .5257311121191336, B = .85065080835204;
    private static final double[][] NORMALS = {
            {0, -A, -B}, {0, -A, B}, {0, A, -B}, {0, A, B},
            {-A, -B, 0}, {-A, B, 0}, {A, -B, 0}, {A, B, 0},
            {-B, 0, -A}, {B, 0, -A}, {-B, 0, A}, {B, 0, A}
    };

    record Pose(double x, double y, double z, double offset, float tilt, float spin) { }

    /** A separately rotating mechanical layer of the Ex-Twins hive. */
    record LayerPose(float axisX, float axisY, float axisZ, float degrees) { }

    static Pose sample(int shells, int index, double time) {
        if (index < 0 || index >= shells || shells != 4 && shells != 6 && shells != 12) throw new IllegalArgumentException("Invalid hive joint");
        double phase = time * (shells == 12 ? .027 + index * .0013 : .035) + index * .91;
        double opening = shells == 12 ? .020 + .010 * (.5 + .5 * Math.sin(phase))
                : .010 + .008 * (.5 + .5 * Math.sin(phase));
        if (shells == 12) {
            double[] n = NORMALS[index];
            return new Pose(n[0], n[1], n[2], opening, (float) (1.5 * Math.sin(phase * .83)),
                    degrees(time * (1.35 + index * .13) + index * 27.5));
        }
        double angle = index * Math.PI * 2 / shells;
        return new Pose(Math.cos(angle), Math.sin(angle), 0, opening,
                (float) (1.5 * Math.sin(phase)), 0.0F);
    }

    /**
     * The body, amethyst heart and rune lattice deliberately use unlike axes
     * and angular rates.  The time argument is absolute, so a menu reload
     * cannot reset their phase or make the layers appear locked together.
     */
    static LayerPose twinsLayer(int layer, double time) {
        if (layer < 0 || layer > 2) throw new IllegalArgumentException("Invalid Twins hive layer");
        return switch (layer) {
            case 0 -> new LayerPose(.21F, 1.0F, .43F, degrees(time * 1.18));
            case 1 -> new LayerPose(1.0F, .34F, -.18F, degrees(-time * 2.07 + 34.0));
            default -> new LayerPose(-.36F, .58F, 1.0F, degrees(time * 3.31 - 19.0));
        };
    }

    private static float degrees(double value) {
        double wrapped = value % 360.0D;
        return (float) (wrapped < 0.0D ? wrapped + 360.0D : wrapped);
    }

    private HiveShellPose() { }
}
