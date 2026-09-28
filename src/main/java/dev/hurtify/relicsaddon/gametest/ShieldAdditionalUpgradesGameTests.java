package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.relic.ShieldUpgrades;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldAdditionalUpgradesGameTests {
    @GameTest(template = "test_room")
    public static void everyShieldHasThreeResearchableExtras(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var abilities = item.getRelicData(player, new ItemStack(item)).getAbilitiesData();
            helper.assertTrue(abilities.getAbilityIDs().size() == 4, "Main ability plus three shield upgrades");
            helper.assertTrue(abilities.getAbilityIDs().contains(ShieldUpgrades.DISTRIBUTION)
                    && abilities.getAbilityIDs().contains(ShieldUpgrades.RESTORATION), "Distribution and restoration are native upgrades");
            String finalUpgrade = item.role() == dev.hurtify.relicsaddon.relic.RelicRole.TWINS_SHIELD
                    ? ShieldUpgrades.STABILIZATION : ShieldUpgrades.GATHER;
            helper.assertTrue(abilities.getAbilityIDs().contains(finalUpgrade), "Role-specific third upgrade is present");
            helper.assertTrue(abilities.getAbilityData(ShieldUpgrades.RESTORATION).getTemplate().getStats().containsKey("repair_steps"),
                    "Restoration exposes repair steps, not maximum HP");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void restorationAndStabilizationRespectRepairBounds(GameTestHelper helper) {
        int cell = 17;
        ShieldStackState damaged = ShieldStackState.DEFAULT.damageLocalCell(cell, 3, 3, 100)
                .withCellsAndBuffer(ShieldStackState.DEFAULT.damageLocalCell(cell, 3, 3, 100).cells(), 0, List.of(), -1);
        helper.assertTrue(ShieldCellDefense.repair(damaged, 139, 5000, 40, 3).equals(damaged),
                "Normal quiet delay still blocks early repair");
        ShieldStackState stabilized = ShieldCellDefense.repair(damaged, 116, 5000, 16, 2);
        helper.assertTrue(stabilized.cellHp(cell) == 11 && stabilized.sharedBuffer() == 0,
                "Two restoration steps repair existing cell HP after Twins stabilization");
        ShieldStackState nearCap = ShieldStackState.DEFAULT.withCellsAndBuffer(ShieldStackState.DEFAULT.cells(), 4999, List.of(), -1);
        helper.assertTrue(ShieldCellDefense.repair(nearCap, 100, 5000, 0, 3).sharedBuffer() == 5000,
                "Restoration never raises the 5000 shared-buffer maximum");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void upgradesRequireResearchAndRetainSafeFallbacks(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        var rf = new ItemStack(ModItems.RF_SHIELD.get());
        helper.assertTrue(ShieldUpgrades.repairSteps(player, rf) == 1 && ShieldUpgrades.quietTicks(player, rf) == 40,
                "Missing or locked upgrade data retains base repair behavior");

        int[] expectedRepair = {2, 3, 2};
        int index = 0;
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = new ItemStack(item);
            unlockMainAnd(player, stack, ShieldUpgrades.RESTORATION);
            var restoration = item.getRelicData(player, stack).getAbilitiesData().getAbilityData(ShieldUpgrades.RESTORATION);
            restoration.setLevel(restoration.getTemplate().getInitialMaxLevel());
            helper.assertTrue(ShieldUpgrades.repairSteps(player, stack) == expectedRepair[index++], "Role-balanced restoration rate");
            if (item.role() == dev.hurtify.relicsaddon.relic.RelicRole.TWINS_SHIELD) {
                unlockMainAnd(player, stack, ShieldUpgrades.STABILIZATION);
                var stabilization = item.getRelicData(player, stack).getAbilitiesData().getAbilityData(ShieldUpgrades.STABILIZATION);
                stabilization.setLevel(stabilization.getTemplate().getInitialMaxLevel());
                helper.assertTrue(ShieldUpgrades.quietTicks(player, stack) == 16, "Twins stabilization reduces only quiet delay");
            }
        }
        helper.succeed();
    }

    private static void unlockMainAnd(ServerPlayer player, ItemStack stack, String id) {
        var item = (dev.hurtify.relicsaddon.relic.AutonomousRelicItem) stack.getItem();
        var data = item.getRelicData(player, stack);
        data.getLevelingData().setLevel(5);
        RelicRuntime.ability(player, stack).getResearchData().complete();
        var ability = data.getAbilitiesData().getAbilityData(id);
        ability.getLockData().setUnlocks(ability.getLockData().getMaxUnlocks());
        ability.getResearchData().complete();
    }

    private ShieldAdditionalUpgradesGameTests() { }
}
