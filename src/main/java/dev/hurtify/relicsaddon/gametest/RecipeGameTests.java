package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises actual datapack recipes against Minecraft, Mekanism and Iron's Spells item registries. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class RecipeGameTests {
    private static final List<String> RECIPES = List.of(
            "resonant_circuit", "energy_cell", "mana_cell",
            "rf_shield_core", "mana_shield_core", "twins_shield_core",
            "rf_drone_frame", "mana_drone_shell", "twins_drone_plate",
            "rf_shield", "mana_shield", "twins_shield",
            "rf_hive", "mana_hive", "twins_hive", "aegis_hive", "escort_hive", "lance_hive");
    private static final List<String> DOUBLE_OUTPUTS = List.of(
            "resonant_circuit", "rf_drone_frame", "mana_drone_shell", "twins_drone_plate");
    private static final List<String> MANA_RECIPES = List.of(
            "mana_cell", "mana_drone_shell", "mana_shield_core", "mana_shield", "mana_hive");

    @GameTest(template = TEMPLATE)
    public static void mekanismRecipesLoadAndCraft(GameTestHelper helper) {
        var level = helper.getLevel();
        var manager = level.getRecipeManager();
        for (String name : RECIPES) {
            var id = ResourceLocation.fromNamespaceAndPath("relics_addon", name);
            var holder = manager.byKey(id).orElseThrow(() -> new AssertionError("Recipe missing: " + id));
            helper.assertTrue(holder.value() instanceof ShapedRecipe, "Shaped recipe: " + id);
            var recipe = (ShapedRecipe) holder.value();
            var stacks = new ArrayList<ItemStack>();
            boolean usesMekanism = false;
            boolean usesIronSpells = false;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    stacks.add(ItemStack.EMPTY);
                    continue;
                }
                ItemStack[] choices = ingredient.getItems();
                helper.assertTrue(choices.length > 0 && !choices[0].isEmpty(), "Unresolved ingredient: " + id);
                var stack = choices[0].copy();
                usesMekanism |= BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("mekanism");
                usesIronSpells |= BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("irons_spellbooks");
                stacks.add(stack);
            }
            helper.assertTrue(usesMekanism, "Recipe must require Mekanism: " + id);
            helper.assertTrue(usesIronSpells == MANA_RECIPES.contains(name), "Mana recipes must require Iron's Spells: " + id);
            var input = CraftingInput.of(recipe.getWidth(), recipe.getHeight(), stacks);
            helper.assertTrue(recipe.matches(input, level), "Ingredients must match: " + id);
            var selected = manager.getRecipeFor(RecipeType.CRAFTING, input, level);
            helper.assertTrue(selected.isPresent() && selected.get().id().equals(id), "Recipe selection/collision: " + id);
            var result = recipe.assemble(input, level.registryAccess());
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(result.getItem()).equals(id), "Wrong output: " + id);
            helper.assertTrue(result.getCount() == (DOUBLE_OUTPUTS.contains(name) ? 2 : 1), "Wrong output count: " + id);
            // An ordinary vanilla gem cannot substitute for any required Iron's Spells ingredient.
            for (int index = 0; index < stacks.size(); index++) {
                var stack = stacks.get(index);
                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("irons_spellbooks")) {
                    var substituted = new ArrayList<>(stacks);
                    substituted.set(index, new ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD));
                    helper.assertTrue(!recipe.matches(CraftingInput.of(recipe.getWidth(), recipe.getHeight(), substituted), level),
                            "Vanilla gem must not replace Iron's Spells material: " + id);
                }
            }
            for (int index = 0; index < stacks.size(); index++) {
                if (!stacks.get(index).isEmpty()) {
                    stacks.set(index, ItemStack.EMPTY);
                    break;
                }
            }
            helper.assertTrue(!recipe.matches(CraftingInput.of(recipe.getWidth(), recipe.getHeight(), stacks), level),
                    "Incomplete ingredients must not craft: " + id);
        }
        helper.succeed();
    }
}
