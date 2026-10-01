package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.client.EffectLights;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The behaviors lighting the current effects. Each sync keeps every behavior on the wanted light
 * nearest it, so a moving effect relights only the sections it leaves and enters; a light with no
 * behavior near it gets a new one, and behaviors left over go dark and are dropped.
 *
 * <p>The candidate pairs live in primitive buffers sized for the light cap and grown on demand, and
 * the behavior lists are swapped between syncs, so a sync allocates only the behaviors it adds.
 */
final class EffectLightPool {
    /** Farther than this a light is a different effect, not the same one moved. */
    private static final double REUSE_DISTANCE = 6;

    private final DynamicLightBehaviorManager manager;
    private List<EffectLightBehavior> active = new ArrayList<>(EffectLights.MAX_LIGHTS), spare = new ArrayList<>(EffectLights.MAX_LIGHTS);

    // Candidate pairs (light, behavior) within reach, in light-major order, and their order by distance.
    private int[] pairLight = new int[EffectLights.MAX_LIGHTS * EffectLights.MAX_LIGHTS];
    private int[] pairBehavior = new int[pairLight.length], pairOrder = new int[pairLight.length], sortScratch = new int[pairLight.length];
    private double[] pairDistance = new double[pairLight.length];
    private boolean[] lit = new boolean[EffectLights.MAX_LIGHTS], kept = new boolean[EffectLights.MAX_LIGHTS];

    EffectLightPool(DynamicLightBehaviorManager manager) {
        this.manager = manager;
    }

    void sync(List<EffectLights.Light> wanted) {
        int lights = wanted.size(), behaviors = active.size();
        fit(lights, behaviors);
        int pairs = 0;
        for (int light = 0; light < lights; light++) {
            EffectLights.Light want = wanted.get(light);
            for (int behavior = 0; behavior < behaviors; behavior++) {
                double distanceSqr = active.get(behavior).distanceSqr(want);
                if (distanceSqr < REUSE_DISTANCE * REUSE_DISTANCE) {
                    pairLight[pairs] = light;
                    pairBehavior[pairs] = behavior;
                    pairDistance[pairs] = distanceSqr;
                    pairOrder[pairs] = pairs;
                    pairs++;
                }
            }
        }
        sortStable(pairOrder, pairs, pairDistance);
        Arrays.fill(lit, 0, lights, false);
        Arrays.fill(kept, 0, behaviors, false);
        List<EffectLightBehavior> next = spare;
        next.clear();
        for (int k = 0; k < pairs; k++) {
            int pair = pairOrder[k], light = pairLight[pair], behavior = pairBehavior[pair];
            if (lit[light] || kept[behavior]) continue;
            lit[light] = kept[behavior] = true;
            EffectLightBehavior found = active.get(behavior);
            found.aim(wanted.get(light));
            next.add(found);
        }
        for (int behavior = 0; behavior < behaviors; behavior++) if (!kept[behavior]) active.get(behavior).retire();
        for (int light = 0; light < lights; light++) {
            if (lit[light]) continue;
            var behavior = new EffectLightBehavior(wanted.get(light));
            manager.add(behavior);
            next.add(behavior);
        }
        spare = active;
        spare.clear();
        active = next;
    }

    /** Forgets every behavior, dark; after a world change LambDynamicLights has already dropped them. */
    void clear() {
        active.forEach(EffectLightBehavior::retire);
        active.clear();
    }

    int size() {
        return active.size();
    }

    /** The behaviors as they stand after the last sync: the kept ones nearest first, then the new ones. */
    List<EffectLightBehavior> active() {
        return active;
    }

    private void fit(int lights, int behaviors) {
        if (lights > lit.length) lit = new boolean[lights];
        if (behaviors > kept.length) kept = new boolean[behaviors];
        int pairs = lights * behaviors;
        if (pairs > pairLight.length) {
            pairLight = new int[pairs];
            pairBehavior = new int[pairs];
            pairOrder = new int[pairs];
            sortScratch = new int[pairs];
            pairDistance = new double[pairs];
        }
    }

    /** Stable merge sort of {@code index[0..n)} by {@code key[index]} ascending, as {@code List.sort} orders it. */
    private void sortStable(int[] index, int n, double[] key) {
        for (int width = 1; width < n; width *= 2) {
            for (int low = 0; low < n - width; low += 2 * width) {
                int middle = low + width, high = Math.min(low + 2 * width, n);
                int a = low, b = middle, out = low;
                while (a < middle && b < high) sortScratch[out++] = Double.compare(key[index[a]], key[index[b]]) <= 0 ? index[a++] : index[b++];
                while (a < middle) sortScratch[out++] = index[a++];
                while (b < high) sortScratch[out++] = index[b++];
                System.arraycopy(sortScratch, low, index, low, high - low);
            }
        }
    }
}
