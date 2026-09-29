package dev.hurtify.relicsaddon.client;

import net.minecraft.world.phys.Vec3;

/** Suppress the far-side pattern, and keep the shell faint when the camera is inside it. */
final class ShieldSurfaceLighting {
    /**
     * From inside, the whole shell surrounds the camera and would wash over the screen (first
     * person looks out through it), so every layer is drawn at a fraction of its outside opacity.
     */
    static final double INSIDE = .3;

    static double visibility(double x, double y, double z, Vec3 eyeDirection) {
        if (inside(eyeDirection)) return INSIDE;
        double facing = x * eyeDirection.x + y * eyeDirection.y + z * eyeDirection.z;
        double edge = Math.clamp((facing + .05) / .35, 0, 1);
        return .025 + .975 * edge * edge * (3 - 2 * edge);
    }

    static boolean inside(Vec3 eyeDirection) {
        return eyeDirection.lengthSqr() < .01;
    }

    private ShieldSurfaceLighting() { }
}
