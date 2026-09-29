package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.client.EffectLights;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * One effect light as LambDynamicLights sees it: a sphere of full brightness fading beyond its edge.
 * The client thread aims it every tick; it only reports a change, and so relights chunk sections, once
 * it has moved half a block or brightened or dimmed by a whole level.
 */
final class EffectLightBehavior implements DynamicLightBehavior {
    private static final double MOVE = .5, RESIZE = .25;

    private record Shape(double x, double y, double z, double luminance, double radius) {
        static Shape of(EffectLights.Light light) {
            return new Shape(light.x(), light.y(), light.z(), light.luminance(), light.radius());
        }
    }

    /** What chunk meshing threads light with; replaced whole so they never see half an update. */
    private volatile Shape lit;
    private Shape aimed;
    private volatile boolean removed;

    EffectLightBehavior(EffectLights.Light light) {
        lit = aimed = Shape.of(light);
    }

    void aim(EffectLights.Light light) {
        aimed = Shape.of(light);
    }

    /** Goes dark at once; LambDynamicLights drops it and relights its sections on its next tick. */
    void retire() {
        Shape last = lit;
        lit = new Shape(last.x(), last.y(), last.z(), 0, last.radius());
        removed = true;
    }

    double distanceSqr(EffectLights.Light light) {
        double dx = aimed.x() - light.x(), dy = aimed.y() - light.y(), dz = aimed.z() - light.z();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public double lightAtPos(BlockPos pos, double falloffRatio) {
        Shape shape = lit;
        double dx = pos.getX() + .5 - shape.x(), dy = pos.getY() + .5 - shape.y(), dz = pos.getZ() + .5 - shape.z();
        double beyond = Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - shape.radius());
        return Mth.clamp(shape.luminance() - beyond * falloffRatio, 0, 15);
    }

    @Override
    public BoundingBox getBoundingBox() {
        Shape shape = lit;
        double r = shape.radius();
        return new BoundingBox(Mth.floor(shape.x() - r), Mth.floor(shape.y() - r), Mth.floor(shape.z() - r),
                Mth.floor(shape.x() + r), Mth.floor(shape.y() + r), Mth.floor(shape.z() + r));
    }

    @Override
    public boolean hasChanged() {
        if (removed) return false;
        Shape now = lit, next = aimed;
        double dx = next.x() - now.x(), dy = next.y() - now.y(), dz = next.z() - now.z();
        if (dx * dx + dy * dy + dz * dz < MOVE * MOVE && Math.abs(next.luminance() - now.luminance()) < 1
                && Math.abs(next.radius() - now.radius()) < RESIZE) return false;
        lit = next;
        return true;
    }

    @Override
    public boolean isRemoved() {
        return removed;
    }
}
