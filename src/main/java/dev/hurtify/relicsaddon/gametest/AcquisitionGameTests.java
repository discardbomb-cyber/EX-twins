package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class AcquisitionGameTests {
    @GameTest(template = "test_room")
    public static void creativeTabContainsSixPlayableRelics(GameTestHelper helper) {
        var tab = dev.hurtify.relicsaddon.registry.ModCreativeTabs.MAIN.get();
        tab.buildContents(new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
                net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS, false, helper.getLevel().registryAccess()));
        helper.assertTrue(tab.getDisplayItems().size() == 6, "Exactly six creative entries");
        for (String id : new String[] {"rf_drone", "mana_drone", "twins_drone"}) {
            helper.assertFalse(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("relics_addon", id)),
                    "Standalone drone ID must be absent from the item registry: " + id);
        }
        long registered = BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals("relics_addon")).count();
        helper.assertTrue(registered == 6, "Exactly six addon item IDs exist, not just six creative entries");
        for (var stack : tab.getDisplayItems()) {
            helper.assertTrue(stack.getItem() instanceof dev.hurtify.relicsaddon.relic.AutonomousRelicItem item
                            && (item.role().isShield() || item.role().isHive()),
                    "Creative inventory contains only playable shields and hives");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void shieldAndHiveRecipesAndUnlocksLoad(GameTestHelper helper) {
        for (RelicRole role : java.util.stream.Stream.concat(
                java.util.Arrays.stream(RelicRole.shields()), java.util.Arrays.stream(RelicRole.hives())).toList()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("relics_addon", role.itemId());
            helper.assertTrue(helper.getLevel().getRecipeManager().byKey(id).isPresent(), "Recipe availability: " + id);
            helper.assertTrue(helper.getLevel().getServer().getAdvancements().get(
                    ResourceLocation.fromNamespaceAndPath("relics_addon", "recipes/" + role.itemId())) != null,
                    "Recipe-book availability: " + id);
        }
        craft(helper, "rf_shield", Items.IRON_INGOT, Items.REDSTONE, Items.IRON_INGOT,
                Items.REDSTONE, Items.DIAMOND, Items.REDSTONE, Items.IRON_INGOT, Items.COPPER_INGOT, Items.IRON_INGOT);
        craft(helper, "mana_shield", Items.GOLD_INGOT, Items.LAPIS_LAZULI, Items.GOLD_INGOT,
                Items.LAPIS_LAZULI, Items.DIAMOND, Items.AMETHYST_SHARD, Items.GOLD_INGOT, Items.AMETHYST_SHARD, Items.GOLD_INGOT);
        craft(helper, "twins_shield", Items.OBSIDIAN, Items.AMETHYST_SHARD, Items.OBSIDIAN,
                Items.ENDER_PEARL, Items.DIAMOND, Items.ENDER_PEARL, Items.OBSIDIAN, Items.AMETHYST_SHARD, Items.OBSIDIAN);
        craft(helper, "rf_hive", Items.REDSTONE_BLOCK, Items.COPPER_BLOCK, Items.REDSTONE_BLOCK,
                Items.COPPER_BLOCK, Items.ECHO_SHARD, Items.COPPER_BLOCK, Items.REDSTONE_BLOCK, Items.DIAMOND, Items.REDSTONE_BLOCK);
        craft(helper, "mana_hive", Items.AMETHYST_BLOCK, Items.LAPIS_BLOCK, Items.AMETHYST_BLOCK,
                Items.LAPIS_BLOCK, Items.EMERALD, Items.LAPIS_BLOCK, Items.AMETHYST_BLOCK, Items.GOLD_BLOCK, Items.AMETHYST_BLOCK);
        craft(helper, "twins_hive", Items.OBSIDIAN, Items.END_CRYSTAL, Items.OBSIDIAN,
                Items.END_CRYSTAL, Items.DIAMOND_BLOCK, Items.END_CRYSTAL, Items.OBSIDIAN, Items.END_CRYSTAL, Items.OBSIDIAN);
        noRecipe(helper, Items.IRON_INGOT, Items.PHANTOM_MEMBRANE, Items.IRON_INGOT,
                Items.PHANTOM_MEMBRANE, ModItems.RF_SHIELD.get(), Items.PHANTOM_MEMBRANE,
                Items.IRON_INGOT, Items.REDSTONE, Items.IRON_INGOT);
        noRecipe(helper, Items.IRON_INGOT, Items.PHANTOM_MEMBRANE, Items.IRON_INGOT,
                Items.PHANTOM_MEMBRANE, ModItems.MANA_SHIELD.get(), Items.PHANTOM_MEMBRANE,
                Items.GOLD_INGOT, Items.AMETHYST_SHARD, Items.GOLD_INGOT);
        noRecipe(helper, Items.IRON_INGOT, Items.PHANTOM_MEMBRANE, Items.IRON_INGOT,
                Items.PHANTOM_MEMBRANE, ModItems.TWINS_SHIELD.get(), Items.PHANTOM_MEMBRANE,
                Items.OBSIDIAN, Items.ENDER_PEARL, Items.OBSIDIAN);
        helper.succeed();
    }

    private static void noRecipe(GameTestHelper helper, Item... items) {
        var input = CraftingInput.of(3, 3, Arrays.stream(items).map(ItemStack::new).toList());
        helper.assertTrue(helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).isEmpty(),
                "Unrelated recipe must not match");
    }

    private static void craft(GameTestHelper helper, String id, Item... items) {
        CraftingInput input = CraftingInput.of(3, 3, Arrays.stream(items).map(ItemStack::new).toList());
        var recipe = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
        helper.assertTrue(recipe.isPresent(), "No matching crafting recipe: " + id);
        ItemStack result = recipe.orElseThrow().value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.getCount() == 1 && BuiltInRegistries.ITEM.getKey(result.getItem()).equals(
                ResourceLocation.fromNamespaceAndPath("relics_addon", id)), "Wrong crafting result: " + id);
    }
}
