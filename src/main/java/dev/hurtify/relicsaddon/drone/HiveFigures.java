package dev.hurtify.relicsaddon.drone;

/**
 * The fewest drones each attack mode's figure is built from. Drones stand on a figure's corners, so a
 * mode is either off (no drones) or has at least one drone per corner of one figure. The counts come
 * from the geometry that puts the drones on those corners ({@link HiveShapes}, {@link HiveFormation}),
 * so they follow the figures if the figures change.
 */
public final class HiveFigures {
    /** Corners of one figure of {@code mode} in a hive of {@code type}. */
    public static int minimum(HiveType type, AttackMode mode) {
        return switch (mode) {
            case DROPLET -> switch (type) {
                case RF -> HiveShapes.TESSERACT_CORNERS;
                case MANA -> HiveShapes.DROPLET_CORNERS;
                case TWINS -> HiveShapes.MIN_HEXAGONS * HiveShapes.HEXAGON_CORNERS;
            };
            case BARRAGE -> HiveFormation.patternCorners(type);
            case CONTAINMENT -> switch (type) {
                case RF -> HiveShapes.RING_RADII.length * HiveShapes.ringCorners(false);
                case MANA -> HiveShapes.WARD_CORNERS;
                case TWINS -> HiveShapes.RING_RADII.length * HiveShapes.ringCorners(true);
            };
        };
    }

    /** Whether {@code count} drones may be given to {@code mode}: none, or enough for one figure. */
    public static boolean allowed(HiveType type, AttackMode mode, int count) {
        return count == 0 || count >= minimum(type, mode);
    }

    private HiveFigures() { }
}
