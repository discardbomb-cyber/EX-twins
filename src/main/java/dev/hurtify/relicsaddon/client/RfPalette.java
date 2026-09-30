package dev.hurtify.relicsaddon.client;

/** RF Armageddon's colours: the hologram's blue, steel and silver, copper, redstone, and the ball's electric blue. */
final class RfPalette {
    /** The hologram's lines: its cyan, a deep blue for the panels' grid, and a pale blue for what burns brightest. */
    static final int HOLO = 0x3FE8FF, HOLO_DEEP = 0x1C6BFF, HOLO_PALE = 0xB8F6FF;
    static final int STEEL = 0x8FA9BC, SILVER = 0xDCEBF5;
    static final int COPPER = 0xE3873A, COPPER_PALE = 0xFFC08A;
    static final int REDSTONE = 0xFF2E2E;
    /** The ball's rim and lightning, the white-blue of a spark, and the dome's ice. */
    static final int ELECTRIC = 0x2FA8FF, SPARK = 0xD2FCFF, ICE = 0x9FE9FF;
    static final int SOOT = 0x0C0B0A;

    private RfPalette() {
    }
}
