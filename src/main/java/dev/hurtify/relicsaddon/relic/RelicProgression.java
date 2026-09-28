package dev.hurtify.relicsaddon.relic;

import it.hurts.sskirillss.relics.items.relics.base.data.leveling.LevelingTemplate;

/** Native Relics item-level progression shared by every playable relic. */
public final class RelicProgression {
    // Relics stores the starting tooltip rank as zero, then displays it as rank one.
    // Its rank-up guard is storedRank < maxRank, so four yields ranks 1 through 5.
    public static final int MAX_RANK = 4;
    public static final double INITIAL_LEVEL_COST = 10.0;
    public static final double LEVEL_COST_STEP = 5.0;

    public static String combatSource(RelicRole role) {
        return role.abilityId() + "_activity";
    }

    public static LevelingTemplate levelingTemplate() {
        return LevelingTemplate.builder()
                .initialCost(INITIAL_LEVEL_COST)
                .step(LEVEL_COST_STEP)
                .maxRank(MAX_RANK)
                .build();
    }

    private RelicProgression() {
    }
}
