package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RelicsAddon.MOD_ID);

    public static final DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> RF_HIVE = hive(dev.hurtify.relicsaddon.domain.hive.HiveType.RF);
    public static final DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> MANA_HIVE = hive(dev.hurtify.relicsaddon.domain.hive.HiveType.MANA);
    public static final DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> TWINS_HIVE = hive(dev.hurtify.relicsaddon.domain.hive.HiveType.TWINS);

    // Crafting parts: a shared circuit, one battery per energy family, a core per shield and drone parts per hive.
    public static final DeferredItem<Item> RESONANT_CIRCUIT = component("resonant_circuit", Rarity.COMMON);
    public static final DeferredItem<Item> ENERGY_CELL = component("energy_cell", Rarity.UNCOMMON);
    public static final DeferredItem<Item> MANA_CELL = component("mana_cell", Rarity.UNCOMMON);
    public static final DeferredItem<Item> RF_SHIELD_CORE = component("rf_shield_core", Rarity.UNCOMMON);
    public static final DeferredItem<Item> MANA_SHIELD_CORE = component("mana_shield_core", Rarity.UNCOMMON);
    public static final DeferredItem<Item> TWINS_SHIELD_CORE = component("twins_shield_core", Rarity.RARE);
    public static final DeferredItem<Item> RF_DRONE_FRAME = component("rf_drone_frame", Rarity.COMMON);
    public static final DeferredItem<Item> MANA_DRONE_SHELL = component("mana_drone_shell", Rarity.COMMON);
    public static final DeferredItem<Item> TWINS_DRONE_PLATE = component("twins_drone_plate", Rarity.UNCOMMON);

    public static final List<DeferredItem<Item>> COMPONENTS = List.of(RESONANT_CIRCUIT, ENERGY_CELL, MANA_CELL,
            RF_SHIELD_CORE, MANA_SHIELD_CORE, TWINS_SHIELD_CORE, RF_DRONE_FRAME, MANA_DRONE_SHELL, TWINS_DRONE_PLATE);

    private static DeferredItem<Item> component(String id, Rarity rarity) {
        return ITEMS.register(id, () -> new dev.hurtify.relicsaddon.relic.ComponentItem(new Item.Properties().rarity(rarity)));
    }

    private static DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> hive(dev.hurtify.relicsaddon.domain.hive.HiveType type) {
        return ITEMS.register(type.role.itemId(), () -> {
            var properties = new Item.Properties().stacksTo(1).rarity(Rarity.RARE);
            return switch (type) {
                case RF -> new dev.hurtify.relicsaddon.relic.HiveRelicItem.Rf(properties);
                case MANA -> new dev.hurtify.relicsaddon.relic.HiveRelicItem.Mana(properties);
                case TWINS -> new dev.hurtify.relicsaddon.relic.HiveRelicItem.Twins(properties);
            };
        });
    }

    public static final DeferredItem<AutonomousRelicItem> RF_SHIELD = ITEMS.register(
            RelicRole.RF_SHIELD.itemId(),
            () -> new AutonomousRelicItem.RfShield(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredItem<AutonomousRelicItem> MANA_SHIELD = ITEMS.register(
            RelicRole.MANA_SHIELD.itemId(),
            () -> new AutonomousRelicItem.ManaShield(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    public static final DeferredItem<AutonomousRelicItem> TWINS_SHIELD = ITEMS.register(
            RelicRole.TWINS_SHIELD.itemId(),
            () -> new AutonomousRelicItem.TwinsShield(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    private ModItems() {
    }
}
