package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.shield.ShieldSettings;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The console's hive buttons, upkeep and mana refill driven by the real PlayerTick listeners, and
 * the shield commands, pinned as they behave today. The upkeep test posts the event on the game bus,
 * so it keeps proving the PlayerTick hooks whatever they are bound to.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class DeviceCharacterizationGameTests {
    @GameTest(template = TEMPLATE)
    public static void consoleConfiguresHealersAndMode(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        DeviceControlMenu menu = new DeviceControlMenu(1, player.getInventory(), true, 0);
        helper.assertTrue(menu.clickMenuButton(player, 4) && hiveSettings(hive).healers() == 10, "Button 4 adds ten healers");
        helper.assertTrue(menu.clickMenuButton(player, 2) && hiveSettings(hive).healers() == 9, "Button 2 takes one healer away");
        helper.assertTrue(menu.clickMenuButton(player, 4) && hiveSettings(hive).healers() == 12,
                "Healers are capped at the swarm's capacity of 12, got " + hiveSettings(hive).healers());
        helper.assertTrue(menu.clickMenuButton(player, 40 + AttackMode.CONTAINMENT.ordinal()) && hiveSettings(hive).mode() == AttackMode.CONTAINMENT,
                "The mode buttons switch the hive to containment");
        helper.assertFalse(menu.clickMenuButton(player, DeviceControlMenu.BUTTON_MANA_SOURCE_BASE + 1), "An RF hive has no mana source to choose");
        hive.set(ModDataComponents.INSTANCE_ID.get(), UUID.randomUUID().toString());
        for (int id = 0; id < DeviceControlMenu.BUTTON_MODE_BASE + AttackMode.values().length; id++) {
            helper.assertFalse(menu.clickMenuButton(player, id), "A console whose device was swapped ignores button " + id);
        }
        DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 1);
        DeviceControlMenu shieldMenu = new DeviceControlMenu(2, player.getInventory(), true, 1);
        helper.assertFalse(shieldMenu.clickMenuButton(player, 3), "A shield has no healers to add");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void upkeepAndRefillRunOnTheirTicks(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack rfShield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        ItemStack manaShield = DeviceTestSupport.equip(helper, player, RelicRole.MANA_SHIELD, 1);
        int capacity = DevicePower.energy(manaShield).mana();
        manaShield.set(ModDataComponents.DEVICE_ENERGY.get(),
                DevicePower.energy(manaShield).withMana(capacity - 100).withSource(DeviceEnergy.ManaSource.EXPERIENCE));
        player.giveExperiencePoints(1000);
        boolean[] upkeepPaid = {false};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            if (!upkeepPaid[0] && now % 20 == 0) {
                int rf = DevicePower.energy(rfShield).rf(), integrity = DeviceTestSupport.integrity(rfShield);
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
                int spent = rf - DevicePower.energy(rfShield).rf();
                helper.assertTrue(spent == DevicePower.SHIELD_UPKEEP * DevicePower.FE_PER_POINT,
                        "Once a second the running shield pays its upkeep, got " + spent + " FE");
                helper.assertTrue(DeviceTestSupport.integrity(rfShield) == integrity, "An intact shield needs no repair");
                upkeepPaid[0] = true;
            } else if (upkeepPaid[0] && now % 10 == 5) {
                int experience = player.totalExperience;
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
                helper.assertTrue(DevicePower.energy(manaShield).mana() == capacity,
                        "The mana battery refills from experience, got " + DevicePower.energy(manaShield).mana() + " of " + capacity);
                helper.assertTrue(experience - player.totalExperience == 10,
                        "100 points of mana cost 10 experience points, got " + (experience - player.totalExperience));
                helper.succeed();
            }
        });
    }

    @GameTest(template = TEMPLATE)
    public static void shieldCommandsChangeTheWornShield(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Commands commands = helper.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(player.createCommandSourceStack(), "relics_addon shield_radius 5");
        helper.assertTrue(shieldSettings(shield).radius() == 2, "A level 0 shield keeps radius 2, got " + shieldSettings(shield).radius());
        shield.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, 3, 0, 0));
        commands.performPrefixedCommand(player.createCommandSourceStack(), "relics_addon shield_radius 5");
        helper.assertTrue(shieldSettings(shield).radius() == 5, "A level 3 shield takes radius 5, got " + shieldSettings(shield).radius());
        commands.performPrefixedCommand(player.createCommandSourceStack(), "relics_addon shield_coverage owner");
        helper.assertTrue(shieldSettings(shield).coverage().equals("owner"), "The coverage command covers only the owner, got "
                + shieldSettings(shield).coverage());
        helper.succeed();
    }

    private static HiveSettings hiveSettings(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.DEFAULT);
    }

    private static ShieldSettings shieldSettings(ItemStack shield) {
        return shield.getOrDefault(ModDataComponents.SHIELD_SETTINGS.get(), ShieldSettings.DEFAULT);
    }

    private DeviceCharacterizationGameTests() {
    }
}
