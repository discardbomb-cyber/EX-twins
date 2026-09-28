package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.relic.ShieldUpgrades;
import java.io.DataInputStream;
import java.util.HashSet;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldResearchGameTests {
    @GameTest(template = "test_room")
    public static void allEightGraphsAndNativeCardResources(GameTestHelper helper) throws Exception {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        int count = 0;
        var graphShapes = new HashSet<String>();
        var iconContents = new HashSet<String>();
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = new ItemStack(item);
            var abilities = item.getRelicData(player, stack).getAbilitiesData();
            for (String id : abilities.getAbilityIDs()) {
                var template = abilities.getAbilityData(id).getTemplate();
                var graph = template.getResearchTemplate();
                helper.assertTrue(graph.getStars().size() >= 6 && !graph.getLinks().isEmpty(), "Real research graph: " + id);
                var positions = new HashSet<String>();
                for (var star : graph.getStars().values()) {
                    helper.assertTrue(star.getX() >= 3 && star.getX() <= 27 && star.getY() >= 4 && star.getY() <= 28, "Native viewport bounds");
                    helper.assertTrue(positions.add(star.getX() + ":" + star.getY()), "No overlapping stars");
                }
                var connected = new HashSet<Integer>();
                connected.add(0);
                for (int pass = 0; pass < graph.getStars().size(); pass++) for (var edge : graph.getLinks().entries()) {
                    helper.assertTrue(!edge.getKey().equals(edge.getValue()) && graph.getStars().containsKey(edge.getKey())
                            && graph.getStars().containsKey(edge.getValue()), "Valid edge endpoints");
                    if (connected.contains(edge.getKey()) || connected.contains(edge.getValue())) {
                        connected.add(edge.getKey()); connected.add(edge.getValue());
                    }
                }
                helper.assertTrue(connected.size() == graph.getStars().size(), "Connected constellation");
                String shape = graph.getStars().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .map(e -> e.getValue().getX() + "," + e.getValue().getY()).collect(java.util.stream.Collectors.joining(";"));
                helper.assertTrue(graphShapes.add(shape), "Every ability has its own constellation");
                String icon = template.getIcon().apply(player, stack, id);
                // Relics 0.12.8 hardcodes its own namespace for addon ability cards.
                String path = "/assets/relics/textures/abilities/" + item.role().itemId() + "/" + icon + ".png";
                try (var input = ShieldResearchGameTests.class.getResourceAsStream(path)) {
                    helper.assertTrue(input != null, "Native card must resolve: " + path);
                    byte[] bytes = input.readAllBytes();
                    helper.assertTrue(iconContents.add(java.util.Base64.getEncoder().encodeToString(bytes)), "Every ability has its own icon");
                    var png = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
                    helper.assertTrue(png.readLong() == 0x89504E470D0A1A0AL, "PNG signature");
                    png.skipNBytes(8);
                    helper.assertTrue(png.readInt() == 22 && png.readInt() == 31, "Native 22x31 card");
                }
                count++;
            }
        }
        helper.assertTrue(count == 8, "Three main, three distribution, two gather constellations");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void researchIsRequiredAndBelongsToPlayer(GameTestHelper helper) {
        var owner = AutonomousRelicGameTests.survivalPlayer(helper);
        var other = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = new ItemStack(item);
            var ability = RelicRuntime.ability(owner, stack);
            helper.assertFalse(ability.canPlayerUse(owner), "Unresearched protection is locked");
            var research = ability.getResearchData();
            research.addLink(0, 1);
            helper.assertFalse(research.isComplete() || ability.canPlayerUse(owner), "Partial graph cannot unlock");
            research.complete();
            helper.assertTrue(research.isResearched() && research.isComplete() && ability.canPlayerUse(owner), "Native completion unlocks");
            helper.assertTrue(RelicRuntime.ability(owner, new ItemStack(item)).canPlayerUse(owner), "Research applies to another copy for same player");
            helper.assertFalse(RelicRuntime.ability(other, stack.copy()).canPlayerUse(other), "Giving item does not give player research");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void upgradeUnlockStillRequiresResearch(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        var item = ModItems.RF_SHIELD.get();
        var stack = new ItemStack(item);
        RelicRuntime.ability(player, stack).getResearchData().complete();
        var data = item.getRelicData(player, stack);
        data.getLevelingData().setLevel(4);
        for (String id : List.of(ShieldUpgrades.DISTRIBUTION, ShieldUpgrades.GATHER)) {
            var ability = data.getAbilitiesData().getAbilityData(id);
            var lock = ability.getLockData();
            lock.setUnlocks(lock.getMaxUnlocks());
            helper.assertFalse(ability.canPlayerUse(player), "Level and unlock cannot skip research");
            ability.getResearchData().complete();
            helper.assertTrue(ability.canPlayerUse(player), "Research and unlock together enable upgrade");
        }
        helper.succeed();
    }

    private ShieldResearchGameTests() { }

    @GameTest(template = "test_room")
    public static void differentShieldProgressionAndCaps(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        int index = 0;
        double[] initial = {.25,.20,.35}, targets = {.45,.35,.50};
        for (var item : List.of(ModItems.RF_SHIELD.get(),ModItems.MANA_SHIELD.get(),ModItems.TWINS_SHIELD.get())) {
            var stack = new ItemStack(item);
            RelicRuntime.ability(player,stack).getResearchData().complete();
            var data = item.getRelicData(player,stack);
            data.getLevelingData().setLevel(4);
            var ability = data.getAbilitiesData().getAbilityData(ShieldUpgrades.DISTRIBUTION);
            var lock = ability.getLockData();
            lock.setUnlocks(lock.getMaxUnlocks());
            ability.getResearchData().complete();
            helper.assertTrue(Math.abs(ShieldUpgrades.sharing(player,stack)-initial[index]) < .0001, "Distinct starting share");
            ability.setLevel(ability.getTemplate().getInitialMaxLevel());
            helper.assertTrue(Math.abs(ShieldUpgrades.sharing(player,stack)-targets[index]) < .0001, "Distinct fully upgraded share");
            helper.assertTrue(ability.getTemplate().getRequiredLevel() == (index == 1 ? 3 : 2), "Distinct distribution milestone");
            if (index < 2) {
                var gather = data.getAbilitiesData().getAbilityData(ShieldUpgrades.GATHER);
                var gatherLock = gather.getLockData();
                gatherLock.setUnlocks(gatherLock.getMaxUnlocks());
                gather.getResearchData().complete();
                gather.setLevel(gather.getTemplate().getInitialMaxLevel());
                helper.assertTrue(ShieldUpgrades.gathering(player,stack) == (index == 0 ? 2 : 3), "RF gathers two, Mana three at max");
                helper.assertTrue(gather.getTemplate().getRequiredLevel() == (index == 0 ? 4 : 2), "Distinct gather milestone");
            }
            index++;
        }
        helper.succeed();
    }
}
