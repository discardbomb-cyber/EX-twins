package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;
import it.hurts.sskirillss.relics.api.relics.abilities.AbilityTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import it.hurts.sskirillss.relics.init.RelicsScalingModels;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Optional native research upgrades; their effects never change swarm or healing hard caps. */
public final class HiveUpgrades {
    public static final String COMBAT = "combat_protocol";
    public static final String SUPPORT = "support_protocol";
    public static final String RECOVERY = "recovery_protocol";

    public static AbilityTemplate combat(HiveType type) {
        double start = switch (type) { case RF -> .10; case MANA -> .08; case TWINS -> .12; };
        return base(COMBAT, type, 2)
                .stat(percent("damage_bonus", start, start * 3)).build();
    }

    public static AbilityTemplate support(HiveType type) {
        double start = switch (type) { case RF -> .15; case MANA -> .25; case TWINS -> .20; };
        double end = switch (type) { case RF -> .50; case MANA -> 1; case TWINS -> .75; };
        return base(SUPPORT, type, 3).stat(percent("healing_bonus", start, end)).build();
    }

    public static AbilityTemplate recovery(HiveType type) {
        double end = switch (type) { case RF -> 3; case MANA -> 2; case TWINS -> 4; };
        return base(RECOVERY, type, 4)
                .stat(AbilityStatTemplate.builder("repair_bonus").initialValue(1, 1)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), end).thresholdValue(0, 4)
                        .formatValue(Math::round).build())
                .stat(percent("rebuild_reduction", type == HiveType.TWINS ? .15 : .10,
                        type == HiveType.TWINS ? .35 : type == HiveType.MANA ? .20 : .30)).build();
    }

    private static AbilityTemplate.AbilityTemplateBuilder base(String id, HiveType type, int level) {
        return AbilityTemplate.builder(id).passive().requiredLevel(level).requiredRank(0)
                .requiredPoints(1).initialMaxLevel(3).maxLevelRankModifier(0).research(HiveResearch.upgrade(type, id));
    }

    private static AbilityStatTemplate percent(String id, double start, double end) {
        return AbilityStatTemplate.builder(id).initialValue(start, start)
                .targetValue(RelicsScalingModels.ADDITIVE.get(), end).thresholdValue(0, end)
                .formatValue(value -> Math.round(value * 100)).build();
    }

    public static double damageMultiplier(Player owner, ItemStack stack) { return 1 + value(owner, stack, COMBAT, "damage_bonus", .36); }
    public static double healingMultiplier(Player owner, ItemStack stack) { return 1 + value(owner, stack, SUPPORT, "healing_bonus", 1); }
    public static int repairAmount(Player owner, ItemStack stack) { return 1 + (int) Math.round(value(owner, stack, RECOVERY, "repair_bonus", 4)); }
    public static double rebuildMultiplier(Player owner, ItemStack stack) { return 1 - value(owner, stack, RECOVERY, "rebuild_reduction", .35); }

    private static double value(Player player, ItemStack stack, String id, String stat, double max) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive() || !RelicRuntime.canOperate(player, stack)) return 0;
        var abilities = item.getRelicData(player, stack).getAbilitiesData();
        if (!abilities.getAbilityIDs().contains(id)) return 0;
        var ability = abilities.getAbilityData(id);
        if (!ability.canPlayerUse(player) || !ability.getTemplate().getStats().containsKey(stat)) return 0;
        double amount = ability.getStatData(stat).getValue();
        return Double.isFinite(amount) ? Math.clamp(amount, 0, max) : 0;
    }

    private HiveUpgrades() { }
}
