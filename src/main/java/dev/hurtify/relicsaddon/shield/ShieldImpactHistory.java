package dev.hurtify.relicsaddon.shield;

import java.util.ArrayList;
import java.util.List;

/** Short-lived network events, not saved shield HP. Keeps multiple hits within a single tick. */
public record ShieldImpactHistory(List<ShieldImpact> impacts) {
    public static final int LIMIT = 12;
    public static final ShieldImpactHistory EMPTY = new ShieldImpactHistory(List.of());
    public ShieldImpactHistory {
        impacts = List.copyOf(impacts.subList(Math.max(0, impacts.size() - LIMIT), impacts.size()));
    }
    public ShieldImpactHistory append(ShieldImpact impact) {
        var recent = new ArrayList<>(impacts.stream().filter(old -> old.gameTime() <= impact.gameTime()
                && impact.gameTime() - old.gameTime() < 36).toList());
        recent.add(impact);
        return new ShieldImpactHistory(recent);
    }
}
