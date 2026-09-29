package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.client.EffectLights;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The behaviors lighting the current effects. Each sync keeps every behavior on the wanted light
 * nearest it, so a moving effect relights only the sections it leaves and enters; a light with no
 * behavior near it gets a new one, and behaviors left over go dark and are dropped.
 */
final class EffectLightPool {
    /** Farther than this a light is a different effect, not the same one moved. */
    private static final double REUSE_DISTANCE = 6;

    private final DynamicLightBehaviorManager manager;
    private List<EffectLightBehavior> active = new ArrayList<>();

    EffectLightPool(DynamicLightBehaviorManager manager) {
        this.manager = manager;
    }

    void sync(List<EffectLights.Light> wanted) {
        record Pair(int light, int behavior, double distanceSqr) { }
        var pairs = new ArrayList<Pair>();
        for (int light = 0; light < wanted.size(); light++) {
            for (int behavior = 0; behavior < active.size(); behavior++) {
                double distanceSqr = active.get(behavior).distanceSqr(wanted.get(light));
                if (distanceSqr < REUSE_DISTANCE * REUSE_DISTANCE) pairs.add(new Pair(light, behavior, distanceSqr));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::distanceSqr));
        boolean[] lit = new boolean[wanted.size()], kept = new boolean[active.size()];
        var next = new ArrayList<EffectLightBehavior>(wanted.size());
        for (Pair pair : pairs) {
            if (lit[pair.light()] || kept[pair.behavior()]) continue;
            lit[pair.light()] = kept[pair.behavior()] = true;
            EffectLightBehavior behavior = active.get(pair.behavior());
            behavior.aim(wanted.get(pair.light()));
            next.add(behavior);
        }
        for (int behavior = 0; behavior < active.size(); behavior++) if (!kept[behavior]) active.get(behavior).retire();
        for (int light = 0; light < wanted.size(); light++) {
            if (lit[light]) continue;
            var behavior = new EffectLightBehavior(wanted.get(light));
            manager.add(behavior);
            next.add(behavior);
        }
        active = next;
    }

    /** Forgets every behavior, dark; after a world change LambDynamicLights has already dropped them. */
    void clear() {
        active.forEach(EffectLightBehavior::retire);
        active = new ArrayList<>();
    }

    int size() {
        return active.size();
    }
}
