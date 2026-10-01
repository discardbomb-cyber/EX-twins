package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.client.EffectLights;
import dev.hurtify.relicsaddon.client.EffectLights.Light;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.BlockPos;

public final class EffectLightPoolCheck {
    /** LambDynamicLights' own ratio: level 15 reaches 7.75 blocks. */
    private static final double FALLOFF = 15 / 7.75;

    public static void main(String[] args) {
        List<DynamicLightBehavior> added = new ArrayList<>();
        var pool = new EffectLightPool(manager(added));

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

        matchesReference();
        allocations();
        System.out.println("Effect light sources: spheres light and fall off, sources follow their effects, relight only on real change,"
                + " the pair buffers match the pair list sync for sync");
    }

    // --- equivalence with the reference pool -------------------------------------------------------------

    /**
     * The same wanted lights, tick for tick, must keep, aim, add and retire the same sources in the same order
     * in both pools, and every source must light and report change alike.
     */
    private static void matchesReference() {
        var random = new Random(0x900L);
        List<DynamicLightBehavior> expectedAdded = new ArrayList<>(), actualAdded = new ArrayList<>();
        var expected = new EffectLightPoolReference(manager(expectedAdded));
        var actual = new EffectLightPool(manager(actualAdded));
        int sources = EffectLights.MAX_LIGHTS;
        double[] x = new double[sources], y = new double[sources], z = new double[sources];
        for (int s = 0; s < sources; s++) {
            x[s] = random.nextGaussian() * 12;
            y[s] = 64 + random.nextInt(4);
            z[s] = random.nextGaussian() * 12;
        }
        int ticks = 0;
        for (int tick = 0; tick < 1500; tick++) {
            int count = tick % 500 > 470 ? 0 : switch (random.nextInt(5)) {
                case 0 -> 1;
                case 1 -> random.nextInt(sources + 1);
                default -> sources;
            };
            List<Light> wanted = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                int s = (k + tick / 97) % sources;
                double step = random.nextInt(10) == 0 ? 4 : random.nextInt(3) == 0 ? .6 : .15;
                double px = x[s] + random.nextGaussian() * step, py = y[s] + random.nextGaussian() * step * .2, pz = z[s] + random.nextGaussian() * step;
                if (random.nextInt(7) == 0) {
                    x[s] = px;
                    z[s] = pz;
                }
                if (random.nextInt(40) == 0) px = x[(s + 1) % sources] + random.nextGaussian() * 2;
                wanted.add(new Light(px, py, pz, 1 + random.nextInt(15) + (random.nextBoolean() ? .4 : 0), random.nextDouble() * 4));
            }
            if (tick % 400 == 399) {
                expected.clear();
                actual.clear();
            }
            expected.sync(wanted);
            actual.sync(wanted);
            require(expectedAdded.size() == actualAdded.size(), "tick " + tick + ": " + expectedAdded.size() + " sources added vs " + actualAdded.size());
            require(expected.active().size() == actual.active().size(), "tick " + tick + ": " + expected.active().size() + " sources kept vs " + actual.active().size());
            for (int k = 0; k < expected.active().size(); k++) {
                int wanted1 = expectedAdded.indexOf(expected.active().get(k)), wanted2 = actualAdded.indexOf(actual.active().get(k));
                require(wanted1 == wanted2, "tick " + tick + ": active source " + k + " is #" + wanted1 + " in the reference, #" + wanted2 + " here");
            }
            for (int k = 0; k < expectedAdded.size(); k++) {
                var one = (EffectLightBehavior) expectedAdded.get(k);
                var two = (EffectLightBehavior) actualAdded.get(k);
                require(one.isRemoved() == two.isRemoved(), "tick " + tick + ": source " + k + " removed " + one.isRemoved() + " vs " + two.isRemoved());
                boolean changed1 = one.hasChanged(), changed2 = two.hasChanged();
                require(changed1 == changed2, "tick " + tick + ": source " + k + " changed " + changed1 + " vs " + changed2);
                var box1 = one.getBoundingBox();
                var box2 = two.getBoundingBox();
                require(box1.startX() == box2.startX() && box1.startY() == box2.startY() && box1.startZ() == box2.startZ()
                        && box1.endX() == box2.endX() && box1.endY() == box2.endY() && box1.endZ() == box2.endZ(), "tick " + tick + ": source " + k + " boxes differ");
                var probe = new BlockPos(box1.startX() + 1, box1.startY(), box1.endZ());
                require(one.lightAtPos(probe, FALLOFF) == two.lightAtPos(probe, FALLOFF), "tick " + tick + ": source " + k + " lights differently");
            }
            ticks++;
        }
        require(ticks >= 1000 && expectedAdded.size() > sources, "The scenario must cover at least 1000 ticks and turn sources over");
    }

    // --- allocations -----------------------------------------------------------------------------------

    /** A sync of the full cap of moving lights onto as many sources must allocate at least half less than it did. */
    private static void allocations() {
        var random = new Random(0xA110C);
        List<Light>[] frames = frames(random);
        List<DynamicLightBehavior> added = new ArrayList<>();
        var reference = new EffectLightPoolReference(manager(added));
        var pool = new EffectLightPool(manager(added));
        int[] frame = {0};
        long was = measure(() -> reference.sync(frames[frame[0]++ % frames.length]));
        long is = measure(() -> pool.sync(frames[frame[0]++ % frames.length]));
        require(reference.size() == EffectLights.MAX_LIGHTS && pool.size() == EffectLights.MAX_LIGHTS, "Both pools keep the full cap lit");
        System.out.printf(Locale.ROOT, "A sync of %d moving lights onto %d sources: allocated bytes %,d -> %,d%n",
                EffectLights.MAX_LIGHTS, EffectLights.MAX_LIGHTS, was, is);
        require(is * 2 <= was, "A sync's allocations must at least halve");
    }

    /** The full cap of lights, drifting a little each frame so every source is re-aimed but none is replaced. */
    @SuppressWarnings("unchecked")
    private static List<Light>[] frames(Random random) {
        int sources = EffectLights.MAX_LIGHTS;
        double[] x = new double[sources], z = new double[sources];
        for (int s = 0; s < sources; s++) {
            x[s] = s % 6 * 5;
            z[s] = s / 6 * 5;
        }
        List<Light>[] frames = new List[16];
        for (int f = 0; f < frames.length; f++) {
            List<Light> wanted = new ArrayList<>();
            for (int s = 0; s < sources; s++) {
                wanted.add(new Light(x[s] + Math.sin(f * .4 + s) * .3, 64, z[s] + Math.cos(f * .4 + s) * .3, 8 + random.nextInt(7), 1 + random.nextDouble()));
            }
            frames[f] = wanted;
        }
        return frames;
    }

    /** Runs {@code tick} warmed up and returns the least bytes one run allocated. */
    private static long measure(Runnable tick) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        for (int k = 0; k < 200; k++) tick.run();
        long bytes = Long.MAX_VALUE;
        for (int k = 0; k < 10; k++) {
            long start = threads.getThreadAllocatedBytes(id);
            tick.run();
            bytes = Math.min(bytes, threads.getThreadAllocatedBytes(id) - start);
        }
        return bytes;
    }

    private static DynamicLightBehaviorManager manager(List<DynamicLightBehavior> added) {
        return new DynamicLightBehaviorManager() {
            @Override
            public void add(DynamicLightBehavior source) {
                added.add(source);
            }

            @Override
            public boolean remove(DynamicLightBehavior source) {
                return added.remove(source);
            }
        };
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
