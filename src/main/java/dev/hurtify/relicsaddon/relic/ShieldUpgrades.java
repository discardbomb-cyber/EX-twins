package dev.hurtify.relicsaddon.relic;

import it.hurts.sskirillss.relics.api.relics.abilities.AbilityTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import it.hurts.sskirillss.relics.init.RelicsScalingModels;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ShieldUpgrades {
    public static final String DISTRIBUTION = "damage_distribution";
    public static final String GATHER = "shield_gather";

    public static AbilityTemplate distribution(RelicRole role) {
        double initial = role == RelicRole.RF_SHIELD ? .25 : role == RelicRole.MANA_SHIELD ? .20 : .35;
        double target = role == RelicRole.RF_SHIELD ? .45 : role == RelicRole.MANA_SHIELD ? .35 : .50;
        return AbilityTemplate.builder(DISTRIBUTION).passive().requiredLevel(role == RelicRole.MANA_SHIELD ? 3 : 2)
                .requiredPoints(1).initialMaxLevel(3).maxLevelRankModifier(0)
                .research(ShieldResearch.distribution(role))
                .stat(AbilityStatTemplate.builder("share").initialValue(initial, initial)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), target).thresholdValue(0, target)
                        .formatValue(value -> Math.round(value * 100)).build()).build();
    }

    public static AbilityTemplate gather(RelicRole role) {
        if (role == RelicRole.TWINS_SHIELD) throw new IllegalArgumentException("Twins does not gather cells");
        boolean mana = role == RelicRole.MANA_SHIELD;
        return AbilityTemplate.builder(GATHER).passive().requiredLevel(mana ? 2 : 4).requiredPoints(1)
                .initialMaxLevel(mana ? 2 : 1).maxLevelRankModifier(0)
                .research(ShieldResearch.gather(role))
                .stat(AbilityStatTemplate.builder("cells").initialValue(1, 1)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), mana ? 3 : 2).thresholdValue(1, mana ? 3 : 2)
                        .formatValue(Math::round).build()).build();
    }

    public static double sharing(Player player, ItemStack stack) {
        return value(player, stack, DISTRIBUTION, "share", 0, .5);
    }

    public static int gathering(Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || item.role() == RelicRole.TWINS_SHIELD) return 0;
        return (int) Math.round(value(player, stack, GATHER, "cells", 0, 3));
    }

    private static double value(Player player, ItemStack stack, String id, String stat, double min, double max) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isShield()
                || !RelicRuntime.canOperate(player, stack)) return 0;
        var abilities = item.getRelicData(player, stack).getAbilitiesData();
        // Existing customized Relics configs may not contain the newly added abilities.
        if (!abilities.getAbilityIDs().contains(id)) return 0;
        var ability = abilities.getAbilityData(id);
        if (!ability.canPlayerUse(player) || !ability.getTemplate().getStats().containsKey(stat)) return 0;
        double value = ability.getStatData(stat).getValue();
        return Double.isFinite(value) ? Math.clamp(value, min, max) : 0;
    }

    private ShieldUpgrades() { }
}
