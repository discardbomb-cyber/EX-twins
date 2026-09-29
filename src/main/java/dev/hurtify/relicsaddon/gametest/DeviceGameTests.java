package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.DeviceUpgrade;
import dev.hurtify.relicsaddon.relic.HiveRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveController;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.SlotContext;

/** Progression, batteries, the one-hive rule and the console's module bay, driven directly. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class DeviceGameTests {
    @GameTest(template = TEMPLATE)
    public static void levelsGrowSlowlyAndExperienceIsCapped(GameTestHelper helper) {
        helper.assertTrue(RelicRuntime.experienceToNext(0) == 60 && RelicRuntime.experienceToNext(9) == 2040, "Level thresholds");
        int total = 0;
        for (int level = 0; level < DeviceProgression.MAX_LEVEL; level++) total += RelicRuntime.experienceToNext(level);
        helper.assertTrue(total == 8100, "8100 experience from level 0 to 10, got " + total);
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        for (int hit = 0; hit < 200; hit++) RelicRuntime.awardAbsorption(player, shield, 20);
        DeviceProgression state = RelicRuntime.progression(shield);
        helper.assertTrue(state.level() == 0 && state.experience() == 30, "At most 30 experience per minute, got level "
                + state.level() + " xp " + state.experience());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void upgradeRanksAreStoredIndependently(GameTestHelper helper) {
        DeviceProgression state = DeviceProgression.DEFAULT;
        DeviceUpgrade[] upgrades = DeviceUpgrade.values();
        for (int index = 0; index < upgrades.length; index++) state = state.withRank(upgrades[index].id(), index % 4);
        for (int index = 0; index < upgrades.length; index++) {
            helper.assertTrue(state.rank(upgrades[index].id()) == index % 4, "Rank of " + upgrades[index].id() + " survives its neighbours");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void onlyOneHiveCanBeWornOrRun(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        ItemStack second = new ItemStack(ModItems.MANA_HIVE.get());
        HiveRelicItem item = (HiveRelicItem) second.getItem();
        helper.assertFalse(item.canEquip(new SlotContext(RelicRole.EQUIPMENT_SLOT, player, 1, false, true), second),
                "Curios must refuse a second hive");
        helper.assertTrue(item.canEquip(new SlotContext(RelicRole.EQUIPMENT_SLOT, player, 0, false, true), second),
                "Swapping the worn hive in its own slot stays allowed");
        DeviceTestSupport.equip(helper, player, RelicRole.MANA_HIVE, 1);
        helper.assertTrue(HiveController.active(player).size() == 1, "Even if two are forced in, only one hive runs");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void twinsShareCostsAndFallBack(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack twins = DeviceTestSupport.equip(helper, player, RelicRole.TWINS_SHIELD, 0);
        DeviceEnergy before = DevicePower.energy(twins);
        helper.assertTrue(DevicePower.drain(player, twins, 20), "A full Twins pair pays 20 points");
        DeviceEnergy after = DevicePower.energy(twins);
        helper.assertTrue(before.rf() - after.rf() == 10 * DevicePower.FE_PER_POINT && before.mana() - after.mana() == 10,
                "Twins split a cost evenly between RF and mana");
        twins.set(ModDataComponents.DEVICE_ENERGY.get(), after.withRfOn(false));
        helper.assertTrue(DevicePower.drain(player, twins, 10) && DevicePower.energy(twins).rf() == after.rf(),
                "A switched-off battery is never drawn from");
        twins.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.energy(twins).withManaOn(false));
        helper.assertFalse(DevicePower.powered(player, twins), "With both batteries off the device has no power");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void rfBatteryTakesForgeEnergy(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        shield.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.energy(shield).withRf(0));
        var storage = shield.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(storage != null && storage.canReceive() && !storage.canExtract(), "RF devices expose a receive-only FE storage");
        helper.assertTrue(storage.receiveEnergy(5_000, false) == 5_000 && DevicePower.energy(shield).rf() == 5_000, "FE charges the battery");
        helper.assertTrue(new ItemStack(ModItems.MANA_SHIELD.get()).getCapability(Capabilities.EnergyStorage.ITEM) == null,
                "Mana devices have no RF battery");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void consoleModuleBayInstallsAndReturnsModules(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        DeviceControlMenu menu = new DeviceControlMenu(1, player.getInventory(), true, 0);
        helper.assertTrue(menu.stillValid(player), "The console tracks the worn shield");
        menu.getSlot(0).set(new ItemStack(ModItems.DEVICE_MODULE.get()));
        helper.assertTrue(RelicRuntime.progression(shield).hasModule(0), "A module placed in bay one is installed");
        ItemStack taken = menu.getSlot(0).remove(1);
        helper.assertTrue(taken.is(ModItems.DEVICE_MODULE.get()) && !RelicRuntime.progression(shield).hasModule(0),
                "Taking the module out uninstalls it and hands the item back");
        helper.assertFalse(menu.getSlot(0).mayPlace(new ItemStack(net.minecraft.world.item.Items.DIRT)), "Bays only take modules");
        helper.assertTrue(menu.clickMenuButton(player, DeviceControlMenu.BUTTON_TOGGLE) && !RelicRuntime.enabled(shield),
                "The power button switches the shield off");
        helper.succeed();
    }

    private DeviceGameTests() {
    }
}
