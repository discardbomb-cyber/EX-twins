package dev.hurtify.relicsaddon.client;

import net.minecraft.world.phys.Vec3;

/** Suppress the far-side pattern without hiding the surface when viewed from inside. */
final class ShieldSurfaceLighting {
    static double visibility(double x, double y, double z, Vec3 eyeDirection) {
        if (eyeDirection == Vec3.ZERO) return 1;
        double facing = x * eyeDirection.x + y * eyeDirection.y + z * eyeDirection.z;
        double edge = Math.clamp((facing + .05) / .35, 0, 1);
        return .025 + .975 * edge * edge * (3 - 2 * edge);
    }
    private ShieldSurfaceLighting() { }
}
