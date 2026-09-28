package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.HiveUpgrades;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class HiveAdditionalUpgradesGameTests {
    @GameTest(template = "test_room")
    public static void upgradesRequireNativeLevelAndResearch(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : hives()) {
            ItemStack stack = new ItemStack(item);
            RelicRuntime.ability(player, stack).getResearchData().complete();
            var data = item.getRelicData(player, stack);
            int[] levels = {2, 3, 4};
            for (int index = 0; index < upgradeIds().size(); index++) {
                var ability = data.getAbilitiesData().getAbilityData(upgradeIds().get(index));
                ability.getLockData().setUnlocks(ability.getLockData().getMaxUnlocks());
                helper.assertTrue(ability.getTemplate().getRequiredLevel() == levels[index]
                                && ability.getTemplate().getRequiredPoints() == 1
                                && ability.getTemplate().getInitialMaxLevel() == 3,
                        "Every hive upgrade uses its native level milestone, one relic point, and three ranks");
                data.getLevelingData().setLevel(levels[index] - 1);
                helper.assertFalse(ability.canPlayerUse(player),
                        "Research alone cannot bypass an upgrade's native Relics item-level milestone");
                ability.getResearchData().complete();
                helper.assertFalse(ability.canPlayerUse(player),
                        "Unlocking and researching an upgrade cannot bypass its native item-level milestone");
                data.getLevelingData().setLevel(levels[index]);
                helper.assertTrue(ability.canPlayerUse(player),
                        "The upgrade activates only after both native level and research requirements are met");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void upgradeTargetsAreDistinctAndBounded(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        double[] damageStarts = {.10, .08, .12};
        double[] damageEnds = {.30, .24, .36};
        double[] healStarts = {.15, .25, .20};
        double[] healEnds = {.50, 1, .75};
        int[] repairEnds = {4, 3, 5};
        double[] rebuildStarts = {.90, .90, .85};
        double[] rebuildEnds = {.70, .80, .65};
        for (int index = 0; index < hives().size(); index++) {
            var item = hives().get(index);
            ItemStack stack = enabledUpgrades(player, item);
            var abilities = item.getRelicData(player, stack).getAbilitiesData();
            helper.assertTrue(close(HiveUpgrades.damageMultiplier(player, stack), 1 + damageStarts[index])
                            && close(HiveUpgrades.healingMultiplier(player, stack), 1 + healStarts[index])
                            && HiveUpgrades.repairAmount(player, stack) == 2
                            && close(HiveUpgrades.rebuildMultiplier(player, stack), rebuildStarts[index]),
                    "Each hive starts from its balanced protocol values");
            for (String id : upgradeIds()) {
                var ability = abilities.getAbilityData(id);
                ability.setLevel(ability.getTemplate().getInitialMaxLevel());
            }
            helper.assertTrue(close(HiveUpgrades.damageMultiplier(player, stack), 1 + damageEnds[index])
                            && close(HiveUpgrades.healingMultiplier(player, stack), 1 + healEnds[index])
                            && HiveUpgrades.repairAmount(player, stack) == repairEnds[index]
                            && close(HiveUpgrades.rebuildMultiplier(player, stack), rebuildEnds[index]),
                    "Each hive reaches its own configured combat, support, repair, and rebuild target");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void upgradesKeepHealingCapAndExistingSwarmState(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : hives()) {
            ItemStack stack = enabledUpgrades(player, item);
            var support = item.getRelicData(player, stack).getAbilitiesData().getAbilityData(HiveUpgrades.SUPPORT);
            support.setLevel(support.getTemplate().getInitialMaxLevel());
            stack.set(ModDataComponents.HIVE_STACK_STATE.get(), fullSwarm(7, 12));
            stack.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(12));

            player.setHealth(10);
            HiveTaskController.tickHealing(player, List.of(new HiveController.Equipped(HiveType.of(item.role()), stack)), 20);
            helper.assertTrue(player.getHealth() == 14,
                    "Support protocol cannot exceed the shared four HP-per-second owner healing cap");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void researchingProtocolsPreservesStoredSwarmHealthAndMaxCount(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : hives()) {
            ItemStack stack = new ItemStack(item);
            HiveStackState original = fullSwarm(7, HiveType.MAX_DRONES);
            stack.set(ModDataComponents.HIVE_STACK_STATE.get(), original);
            enableUpgrades(player, item, stack);
            helper.assertTrue(stack.get(ModDataComponents.HIVE_STACK_STATE.get()).equals(original)
                            && original.units().size() == HiveType.MAX_DRONES
                            && original.units().stream().allMatch(unit -> unit.hp() == 7),
                    "Protocol research never resets persisted drone health or lowers the 250-drone component cap");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void recoveryProtocolRepairsWithoutResettingUnitHealth(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        int[] repairAmounts = {4, 3, 5};
        for (int index = 0; index < hives().size(); index++) {
            var item = hives().get(index);
            ItemStack stack = enabledUpgrades(player, item);
            var recovery = item.getRelicData(player, stack).getAbilitiesData().getAbilityData(HiveUpgrades.RECOVERY);
            recovery.setLevel(recovery.getTemplate().getInitialMaxLevel());
            HiveStackState swarm = new HiveStackState(true, List.of(new HiveStackState.Unit(1, 0, -1, 0, 0, 0, 600)));
            HiveStackState repaired = swarm.prepare(1, 12, 20, true, HiveUpgrades.repairAmount(player, stack));
            helper.assertTrue(repaired.units().getFirst().hp() == 1 + repairAmounts[index]
                            && repaired.units().getFirst().attackReadyAt() == 600,
                    "Recovery adds only its configured repair amount and preserves independent combat cooldowns");
        }
        helper.succeed();
    }

    private static ItemStack enabledUpgrades(ServerPlayer player, dev.hurtify.relicsaddon.relic.HiveRelicItem item) {
        ItemStack stack = new ItemStack(item);
        enableUpgrades(player, item, stack);
        return stack;
    }

    private static void enableUpgrades(ServerPlayer player, dev.hurtify.relicsaddon.relic.HiveRelicItem item, ItemStack stack) {
        RelicRuntime.ability(player, stack).getResearchData().complete();
        var data = item.getRelicData(player, stack);
        data.getLevelingData().setLevel(4);
        for (String id : upgradeIds()) {
            var ability = data.getAbilitiesData().getAbilityData(id);
            ability.getLockData().setUnlocks(ability.getLockData().getMaxUnlocks());
            ability.getResearchData().complete();
            ability.setLevel(0);
        }
    }

    private static List<dev.hurtify.relicsaddon.relic.HiveRelicItem> hives() {
        return List.of(ModItems.RF_HIVE.get(), ModItems.MANA_HIVE.get(), ModItems.TWINS_HIVE.get());
    }

    private static List<String> upgradeIds() {
        return List.of(HiveUpgrades.COMBAT, HiveUpgrades.SUPPORT, HiveUpgrades.RECOVERY);
    }

    private static HiveStackState fullSwarm(int health, int count) {
        var units = new ArrayList<HiveStackState.Unit>(count);
        for (int index = 0; index < count; index++) units.add(new HiveStackState.Unit(health, 0, -1, 0, 0, 0, 1));
        return new HiveStackState(true, units);
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < .0001;
    }

    private HiveAdditionalUpgradesGameTests() {
    }
}
