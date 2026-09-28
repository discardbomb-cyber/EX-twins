package dev.hurtify.relicsaddon.relic;

import it.hurts.sskirillss.relics.api.relics.abilities.AbilityTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import it.hurts.sskirillss.relics.init.RelicsScalingModels;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ShieldUpgrades {
    public static final String DISTRIBUTION = "damage_distribution";
    public static final String GATHER = "shield_gather";
    public static final String RESTORATION = "shield_restoration";
    public static final String STABILIZATION = "shield_stabilization";

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

    public static AbilityTemplate restoration(RelicRole role) {
        int target = role == RelicRole.MANA_SHIELD ? 3 : 2;
        int level = role == RelicRole.RF_SHIELD ? 5 : role == RelicRole.MANA_SHIELD ? 4 : 3;
        int ranks = role == RelicRole.MANA_SHIELD ? 3 : 2;
        return AbilityTemplate.builder(RESTORATION).passive().requiredLevel(level).requiredPoints(1)
                .initialMaxLevel(ranks).maxLevelRankModifier(0)
                .research(ShieldResearch.restoration(role))
                .stat(AbilityStatTemplate.builder("repair_steps").initialValue(1, 1)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), target).thresholdValue(1, target)
                        .formatValue(Math::round).build()).build();
    }

    public static AbilityTemplate stabilization(RelicRole role) {
        if (role != RelicRole.TWINS_SHIELD) throw new IllegalArgumentException("Only Twins stabilizes repairs");
        return AbilityTemplate.builder(STABILIZATION).passive().requiredLevel(4).requiredPoints(1)
                .initialMaxLevel(3).maxLevelRankModifier(0)
                .research(ShieldResearch.stabilization(role))
                .stat(AbilityStatTemplate.builder("quiet_ticks").initialValue(40, 40)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), 16).thresholdValue(16, 40)
                        .formatValue(Math::round).build()).build();
    }

    public static double sharing(Player player, ItemStack stack) {
        return value(player, stack, DISTRIBUTION, "share", 0, .5);
    }

    public static int gathering(Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || item.role() == RelicRole.TWINS_SHIELD) return 0;
        return (int) Math.round(value(player, stack, GATHER, "cells", 0, 3));
    }

    public static int repairSteps(Player player, ItemStack stack) {
        return (int) Math.round(value(player, stack, RESTORATION, "repair_steps", 1, 1, 3));
    }

    public static int quietTicks(Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || item.role() != RelicRole.TWINS_SHIELD) {
            return dev.hurtify.relicsaddon.shield.ShieldStackState.BUFFER_REPAIR_QUIET_TICKS;
        }
        return (int) Math.round(value(player, stack, STABILIZATION, "quiet_ticks",
                dev.hurtify.relicsaddon.shield.ShieldStackState.BUFFER_REPAIR_QUIET_TICKS, 0,
                dev.hurtify.relicsaddon.shield.ShieldStackState.BUFFER_REPAIR_QUIET_TICKS));
    }

    private static double value(Player player, ItemStack stack, String id, String stat, double min, double max) {
        return value(player, stack, id, stat, 0, min, max);
    }

    private static double value(Player player, ItemStack stack, String id, String stat, double fallback, double min, double max) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isShield()
                || !RelicRuntime.canOperate(player, stack)) return fallback;
        var abilities = item.getRelicData(player, stack).getAbilitiesData();
        // Existing customized Relics configs may not contain the newly added abilities.
        if (!abilities.getAbilityIDs().contains(id)) return fallback;
        var ability = abilities.getAbilityData(id);
        if (!ability.canPlayerUse(player) || !ability.getTemplate().getStats().containsKey(stat)) return fallback;
        double value = ability.getStatData(stat).getValue();
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    private ShieldUpgrades() { }
}
