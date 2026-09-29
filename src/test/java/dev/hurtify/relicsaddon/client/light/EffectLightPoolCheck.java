package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.client.EffectLights.Light;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

public final class EffectLightPoolCheck {
    /** LambDynamicLights' own ratio: level 15 reaches 7.75 blocks. */
    private static final double FALLOFF = 15 / 7.75;

    public static void main(String[] args) {
        List<DynamicLightBehavior> added = new ArrayList<>();
        var pool = new EffectLightPool(new DynamicLightBehaviorManager() {
            @Override
            public void add(DynamicLightBehavior source) {
                added.add(source);
            }

            @Override
            public boolean remove(DynamicLightBehavior source) {
                return added.remove(source);
            }
        });

        pool.sync(List.of(new Light(.5, 65, .5, 12, 1.5), new Light(20.5, 70, .5, 8, .3)));
        require(added.size() == 2 && pool.size() == 2, "Each new effect gets its own light source");
        var shield = (EffectLightBehavior) added.get(0);
        var charge = (EffectLightBehavior) added.get(1);
        require(shield.lightAtPos(new BlockPos(0, 64, 0), FALLOFF) == 12, "Full light inside the bright sphere");
        double edge = shield.lightAtPos(new BlockPos(4, 65, 0), FALLOFF);
        require(Math.abs(edge - (12 - (Math.sqrt(16.25) - 1.5) * FALLOFF)) < 1e-9, "Light falls off from the sphere's edge");
        require(shield.lightAtPos(new BlockPos(30, 65, 0), FALLOFF) == 0, "No light far away");
        var box = shield.getBoundingBox();
        require(box.startX() == -1 && box.endX() == 2 && box.startY() == 63 && box.endY() == 66 && box.startZ() == -1 && box.endZ() == 2,
                "The bounding box holds the whole bright sphere");
        require(!shield.hasChanged(), "A new source has nothing to report yet");

        pool.sync(List.of(new Light(.7, 65, .5, 12, 1.5), new Light(21.5, 70, .5, 8, .3)));
        require(added.size() == 2, "Moving effects keep their light sources");
        require(!shield.hasChanged(), "A fifth of a block is not worth relighting chunks");
        require(charge.hasChanged() && !charge.hasChanged(), "A block's move relights once");
        pool.sync(List.of(new Light(1.5, 65, .5, 12.6, 1.5), new Light(22.5, 70, .5, 8, .3)));
        require(shield.hasChanged() && !shield.hasChanged(), "Moves add up until they are worth relighting");
        pool.sync(List.of(new Light(1.5, 65, .5, 13.2, 1.5), new Light(23.5, 70, .5, 8, .3)));
        require(!shield.hasChanged(), "Less than a light level brighter relights nothing");
        pool.sync(List.of(new Light(1.5, 65, .5, 13.7, 1.5), new Light(24.5, 70, .5, 8, .3)));
        require(shield.hasChanged(), "A whole light level brighter relights");

        pool.sync(List.of(new Light(1.5, 65, .5, 13.7, 1.5)));
        require(charge.isRemoved() && charge.lightAtPos(new BlockPos(24, 70, 0), FALLOFF) == 0 && !charge.hasChanged(),
                "A finished effect goes dark at once and is dropped");
        require(!shield.isRemoved() && pool.size() == 1, "The shield keeps its source");

        pool.sync(List.of(new Light(0, 65, 3, 10, .5), new Light(1.5, 65, .5, 13.7, 1.5)));
        require(added.size() == 3 && !shield.isRemoved(), "The nearest light keeps the old source, the new one gets its own");
        var spark = (EffectLightBehavior) added.get(2);

        pool.sync(List.of(new Light(40, 65, 0, 12, 1.5)));
        require(shield.isRemoved() && spark.isRemoved() && added.size() == 4, "An effect far from every source is a new effect");

        pool.clear();
        require(pool.size() == 0 && ((EffectLightBehavior) added.get(3)).isRemoved(), "A world change retires every source");
        System.out.println("Effect light sources: spheres light and fall off, sources follow their effects, relight only on real change");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
