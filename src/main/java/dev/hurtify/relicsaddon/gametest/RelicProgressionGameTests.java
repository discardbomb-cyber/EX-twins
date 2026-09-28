package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import it.hurts.sskirillss.relics.api.relics.data.LevelingData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Verifies Relics item levels advance independently from manually upgraded abilities. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class RelicProgressionGameTests {
    private static final RelicRole[] ITEM_ROLES = {
            RelicRole.RF_SHIELD, RelicRole.MANA_SHIELD, RelicRole.TWINS_SHIELD,
            RelicRole.RF_HIVE, RelicRole.MANA_HIVE, RelicRole.TWINS_HIVE
    };

    @GameTest(template = "test_room")
    public static void allRelicsGainNativeItemLevelsFromCombat(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (RelicRole role : ITEM_ROLES) {
            ItemStack stack = new ItemStack(item(role));
            AutonomousRelicItem relic = (AutonomousRelicItem) stack.getItem();
            var relicData = relic.getRelicData(player, stack);
            LevelingData leveling = relicData.getLevelingData();
            var ability = RelicRuntime.ability(player, stack);
            ability.setLevel(0);
            ability.getResearchData().complete();

            helper.assertTrue(leveling.getLevel() == 0 && leveling.getExperience() == 0,
                    role + " starts at native relic level zero with no overall XP");
            helper.assertTrue(relicData.calculateMaxLevel() > 0,
                    role + " native relic level cap comes from its ability points");
            helper.assertTrue(leveling.getTemplate().getMaxRank() == RelicProgression.MAX_RANK,
                    role + " uses Relics' multi-rank progression");

            // Five resolved eight-damage combat results reach the existing ten-XP first-level cost.
            for (int hit = 0; hit < 5; hit++) {
                RelicRuntime.awardCombatExperience(player, stack, 8.0F);
            }

            helper.assertTrue(leveling.getLevel() == 1 && leveling.getExperience() == 0,
                    role + " advances Relics' overall item level through native XP");
            helper.assertTrue(leveling.getPoints() == 1,
                    role + " receives the native ability-upgrade point for its item level");
            helper.assertTrue(leveling.getSourceExperience(role.abilityId(), RelicProgression.combatSource(role)) == 10.0,
                    role + " records the native combat XP source");
            helper.assertTrue(ability.getLevel() == 0 && ability.getResearchData().isResearched(),
                    role + " preserves researched ability state until the player spends its level point");
        }
        helper.succeed();
    }

    private static AutonomousRelicItem item(RelicRole role) {
        return switch (role) {
            case RF_SHIELD -> ModItems.RF_SHIELD.get();
            case MANA_SHIELD -> ModItems.MANA_SHIELD.get();
            case TWINS_SHIELD -> ModItems.TWINS_SHIELD.get();
            case RF_HIVE -> ModItems.RF_HIVE.get();
            case MANA_HIVE -> ModItems.MANA_HIVE.get();
            case TWINS_HIVE -> ModItems.TWINS_HIVE.get();
            default -> throw new IllegalArgumentException("Role is not item-backed: " + role);
        };
    }

    private RelicProgressionGameTests() {
    }
}
