package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;
import it.hurts.sskirillss.relics.api.relics.RelicTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.AbilitiesTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.AbilityTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.ExperienceSourcesTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationType;
import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import it.hurts.sskirillss.relics.init.RelicsRelicContainers;
import it.hurts.sskirillss.relics.init.RelicsScalingModels;

public abstract class HiveRelicItem extends AutonomousRelicItem {
    protected HiveRelicItem(Properties properties) {
        super(properties);
    }

    protected abstract HiveType type();
    @Override public RelicRole role() { return type().role; }

    @Override public RelicTemplate constructDefaultRelicTemplate() {
        // Relics calls this from Item's constructor, before instance fields initialize.
        HiveType type = type();
        var ability = AbilityTemplate.builder(role().abilityId())
                .initialMaxLevel(10).maxLevelRankModifier(0).requiredLevel(0).requiredRank(0).requiredPoints(1)
                .active(AbilityActivationTemplate.builder(AbilityActivationType.TOGGLEABLE)
                        .container(RelicsRelicContainers.CURIOS.get()).build())
                .research(HiveResearch.forType(type))
                .stat(stat("drone_count", type.initialCount, HiveType.MAX_DRONES, 12, HiveType.MAX_DRONES))
                .stat(stat("drone_health", type.initialHealth, type.maxHealth, 1, 1000))
                .stat(stat("attack_damage", type.initialAttackDamage, type.maxAttackDamage, 1, 100))
                .stat(stat("attack_interval_max", 100, 40, 20, 100))
                .stat(AbilityStatTemplate.builder("cooldown")
                        .initialValue(type.initialCooldown, type.initialCooldown)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), type.minCooldown)
                        .thresholdValue(20, 1200).formatValue(value -> Math.round(value / 2) / 10.0).build())
                .experienceSources(ExperienceSourcesTemplate.builder().source(RelicProgression.combatSource(role())).build())
                .build();
        return RelicTemplate.builder().abilities(AbilitiesTemplate.builder().ability(ability)
                        .ability(HiveUpgrades.combat(type)).ability(HiveUpgrades.support(type)).ability(HiveUpgrades.recovery(type)).build())
                .leveling(RelicProgression.levelingTemplate()).build();
    }

    private static AbilityStatTemplate stat(String id, double start, double target, double min, double max) {
        return AbilityStatTemplate.builder(id).initialValue(start, start)
                .targetValue(RelicsScalingModels.ADDITIVE.get(), target).thresholdValue(min, max)
                .formatValue(Math::round).build();
    }

    public static final class Rf extends HiveRelicItem {
        public Rf(Properties properties) { super(properties); }
        @Override protected HiveType type() { return HiveType.RF; }
    }

    public static final class Mana extends HiveRelicItem {
        public Mana(Properties properties) { super(properties); }
        @Override protected HiveType type() { return HiveType.MANA; }
    }

    public static final class Twins extends HiveRelicItem {
        public Twins(Properties properties) { super(properties); }
        @Override protected HiveType type() { return HiveType.TWINS; }
    }
}
