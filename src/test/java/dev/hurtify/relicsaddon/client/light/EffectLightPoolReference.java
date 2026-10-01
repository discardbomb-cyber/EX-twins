package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.client.EffectLights;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * EffectLightPool as it was before the pair buffers (a record per candidate pair, a sorted list of them and
 * a fresh behavior list per sync), kept as the reference the equivalence check draws against.
 */
final class EffectLightPoolReference {
    private static final double REUSE_DISTANCE = 6;

    private final DynamicLightBehaviorManager manager;
    private List<EffectLightBehavior> active = new ArrayList<>();

    EffectLightPoolReference(DynamicLightBehaviorManager manager) {
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

    void clear() {
        active.forEach(EffectLightBehavior::retire);
        active = new ArrayList<>();
    }

    List<EffectLightBehavior> active() {
        return active;
    }

    int size() {
        return active.size();
    }
}
