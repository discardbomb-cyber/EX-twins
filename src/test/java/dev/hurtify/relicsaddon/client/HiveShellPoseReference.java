package dev.hurtify.relicsaddon.client;

/**
 * HiveShellPose as it was before the joint axes were tabulated: trigonometry and a pose object per
 * sample. Kept as the reference the check compares the tabulated poses against, bit for bit.
 */
final class HiveShellPoseReference {
    private static final double A = .5257311121191336, B = .85065080835204;
    private static final double[][] NORMALS = {
            {0, -A, -B}, {0, -A, B}, {0, A, -B}, {0, A, B},
            {-A, -B, 0}, {-A, B, 0}, {A, -B, 0}, {A, B, 0},
            {-B, 0, -A}, {B, 0, -A}, {-B, 0, A}, {B, 0, A}
    };

    static HiveShellPose.Pose sample(int shells, int index, double time) {
        if (index < 0 || index >= shells || shells != 4 && shells != 6 && shells != 12) throw new IllegalArgumentException("Invalid hive joint");
        double phase = time * (shells == 12 ? .027 + index * .0013 : .035) + index * .91;
        double opening = shells == 12 ? .020 + .010 * (.5 + .5 * Math.sin(phase))
                : .010 + .008 * (.5 + .5 * Math.sin(phase));
        if (shells == 12) {
            double[] n = NORMALS[index];
            return new HiveShellPose.Pose(n[0], n[1], n[2], opening, (float) (1.5 * Math.sin(phase * .83)),
                    degrees(time * (1.35 + index * .13) + index * 27.5));
        }
        double angle = index * Math.PI * 2 / shells;
        return new HiveShellPose.Pose(Math.cos(angle), Math.sin(angle), 0, opening,
                (float) (1.5 * Math.sin(phase)), 0.0F);
    }

    static HiveShellPose.LayerPose twinsLayer(int layer, double time) {
        if (layer < 0 || layer > 2) throw new IllegalArgumentException("Invalid Twins hive layer");
        return switch (layer) {
            case 0 -> new HiveShellPose.LayerPose(.21F, 1.0F, .43F, degrees(time * 1.18));
            case 1 -> new HiveShellPose.LayerPose(1.0F, .34F, -.18F, degrees(-time * 2.07 + 34.0));
            default -> new HiveShellPose.LayerPose(-.36F, .58F, 1.0F, degrees(time * 3.31 - 19.0));
        };
    }

    static TwinsShieldLayerPose.Layer shieldLayer(int index, double time) {
        if (index < 0 || index > 2) throw new IllegalArgumentException("Invalid Twins shield layer");
        double ticks = Double.isFinite(time) ? time : 0.0D;
        return switch (index) {
            case 0 -> new TwinsShieldLayerPose.Layer(.18F, 1.0F, .37F, degrees(ticks * .92D));
            case 1 -> new TwinsShieldLayerPose.Layer(1.0F, -.31F, .16F, degrees(-ticks * 1.61D + 41.0D));
            default -> new TwinsShieldLayerPose.Layer(-.42F, .51F, 1.0F, degrees(ticks * 2.43D - 23.0D));
        };
    }

    private static float degrees(double value) {
        double wrapped = value % 360.0D;
        return (float) (wrapped < 0.0D ? wrapped + 360.0D : wrapped);
    }

    private HiveShellPoseReference() { }
}
