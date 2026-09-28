package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldTopology;

/** Quality settings must not change the authoritative cell IDs. */
final class ShieldHexMesh {
    private static final ShieldHexMesh INSTANCE = new ShieldHexMesh();

    static ShieldHexMesh forQuality(ShieldVisualQuality quality) {
        return INSTANCE;
    }

    ShieldTopology.Cell[] cells() {
        return ShieldTopology.INSTANCE.cells();
    }
}
