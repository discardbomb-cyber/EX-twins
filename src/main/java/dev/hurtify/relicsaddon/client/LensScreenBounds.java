package dev.hurtify.relicsaddon.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Conservative screen rectangle for a shader with finite spherical support in view space. */
final class LensScreenBounds {
    record Bounds(float left, float bottom, float right, float top) {
        boolean empty() { return left >= right || bottom >= top; }
        int leftPixel(int width) { return (int) Math.floor((left + 1) * .5 * width); }
        int bottomPixel(int height) { return (int) Math.floor((bottom + 1) * .5 * height); }
        int rightPixel(int width) { return (int) Math.ceil((right + 1) * .5 * width); }
        int topPixel(int height) { return (int) Math.ceil((top + 1) * .5 * height); }
    }

    private static final Bounds FULL = new Bounds(-1, -1, 1, 1);

    static Bounds of(Vector3f centre, double reach, Matrix4f projection, int width, int height) {
        float radius = Math.nextUp((float) reach);
        // The projected enclosing cube is convex only while it stays in front of the eye.
        // In particular, preserve the whole screen when the camera is inside the effect.
        if (!Float.isFinite(radius) || radius <= 0 || centre.z + radius >= 0 || width <= 0 || height <= 0) return FULL;
        float left = Float.POSITIVE_INFINITY, bottom = left;
        float right = Float.NEGATIVE_INFINITY, top = right;
        Vector4f corner = new Vector4f();
        for (int i = 0; i < 8; i++) {
            corner.set(centre.x + ((i & 1) == 0 ? -radius : radius),
                    centre.y + ((i & 2) == 0 ? -radius : radius),
                    centre.z + ((i & 4) == 0 ? -radius : radius), 1);
            projection.transform(corner);
            if (!(corner.w > 0) || !Float.isFinite(corner.x) || !Float.isFinite(corner.y)) return FULL;
            float x = corner.x / corner.w, y = corner.y / corner.w;
            left = Math.min(left, x); right = Math.max(right, x);
            bottom = Math.min(bottom, y); top = Math.max(top, y);
        }
        // Two pixels cover projection rounding and rasterisation at the boundary.
        float padX = 4F / width, padY = 4F / height;
        return new Bounds(Math.max(-1, left - padX), Math.max(-1, bottom - padY),
                Math.min(1, right + padX), Math.min(1, top + padY));
    }

    private LensScreenBounds() { }
}
