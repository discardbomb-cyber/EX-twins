package dev.hurtify.relicsaddon.client;

/**
 * The Mana family's colours, as its shield, hive and drones wear them: turquoise light, gold and ivory
 * (see {@code tools/build_hive_meshes.py} and {@link ShieldShellVisual}), with the cold blue of its
 * seals on the ground.
 */
public final class ManaPalette {
    public static final int TURQUOISE = 0x42E6C8, BRIGHT_TURQUOISE = 0x4FF2DA, PALE_TURQUOISE = 0xBFF8EC, MIST = 0xE0FFF8, DEEP_TURQUOISE = 0x0B5E62;
    public static final int GOLD = 0xE8B54D, BRIGHT_GOLD = 0xFFD27A, PALE_GOLD = 0xFFF0C8, DEEP_GOLD = 0x96661F, IVORY = 0xFFF6E0;
    public static final int SEAL_BLUE = 0x5CC8FF, SEAL_DEEP = 0x0B73D4;

    /** The colour of flower {@code side}: turquoise on the left (-1), gold on the right (+1). */
    public static int side(int side, boolean bright) {
        return side < 0 ? bright ? BRIGHT_TURQUOISE : TURQUOISE : bright ? BRIGHT_GOLD : GOLD;
    }

    private ManaPalette() {
    }
}
