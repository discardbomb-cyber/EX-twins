package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.math.Vec3d;

/** The cell a hit lands on. */
public final class CellSelection {
    /**
     * Turns {@code incoming} (from the field centre towards the attack) into the wearer's frame by yaw
     * alone, so pitch never changes the cell, and picks the nearest cell.
     */
    public static int select(float yaw, Vec3d incoming) {
        Vec3d forward = Vec3d.directionFromRotation(0, yaw);
        return ShieldTopology.INSTANCE.nearest(-incoming.x * forward.z + incoming.z * forward.x,
                incoming.y, incoming.x * forward.x + incoming.z * forward.z);
    }

    private CellSelection() { }
}
