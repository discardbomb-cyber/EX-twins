package dev.hurtify.relicsaddon.client;

import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Every ray that the original full-screen lens shader can affect must remain inside the rectangle. */
public final class LensScreenBoundsCheck {
    public static void main(String[] args) {
        Matrix4f[] projections = {
                new Matrix4f().perspective((float) Math.toRadians(70), 16F / 9, .05F, 4096),
                new Matrix4f().perspective((float) Math.toRadians(110), 9F / 16, .05F, 4096),
                new Matrix4f().frustum(-.03F, .08F, -.04F, .05F, .05F, 4096)
        };
        Random random = new Random(0x51E7);
        long checked = 0, affected = 0;
        boolean smaller = false, culled = false;
        for (Matrix4f projection : projections) {
            Matrix4f inverse = new Matrix4f(projection).invert();
            for (int scene = 0; scene < 240; scene++) {
                Vector3f centre = new Vector3f(random.nextFloat() * 800 - 400,
                        random.nextFloat() * 400 - 200, -random.nextFloat() * 800);
                float reach = 1 + random.nextFloat() * 160;
                LensScreenBounds.Bounds bounds = LensScreenBounds.of(centre, reach, projection, 1920, 1080);
                smaller |= !bounds.empty() && (bounds.right() - bounds.left()) * (bounds.top() - bounds.bottom()) < 1;
                culled |= bounds.empty();
                for (int y = 0; y < 80; y++) for (int x = 0; x < 120; x++) {
                    float sx = (x + .5F) / 120 * 2 - 1, sy = (y + .5F) / 80 * 2 - 1;
                    Vector4f far = inverse.transform(new Vector4f(sx, sy, 1, 1));
                    Vector3f ray = new Vector3f(far.x / far.w, far.y / far.w, far.z / far.w).normalize();
                    float t = centre.dot(ray);
                    Vector3f offset = new Vector3f(ray).mul(t).sub(centre);
                    // The two original GLSL discard predicates, independent of the cube projection.
                    if (t > 0 && offset.length() < reach) {
                        affected++;
                        require(sx >= bounds.left() && sx <= bounds.right() && sy >= bounds.bottom() && sy <= bounds.top(),
                                "An affected ray was clipped: " + centre + ", reach=" + reach + ", bounds=" + bounds);
                    }
                    checked++;
                }
            }
            LensScreenBounds.Bounds inside = LensScreenBounds.of(new Vector3f(0, 0, -1), 2, projection, 3840, 2160);
            require(inside.left() == -1 && inside.right() == 1 && inside.bottom() == -1 && inside.top() == 1,
                    "Camera inside the reach needs the full screen");
        }
        require(smaller && culled && affected > 0, "Exercise small, offscreen and visible effects");
        System.out.println("Lens screen bounds: " + checked + " rays, " + affected + " affected, no clipping");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
