package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RelicsAddon.MOD_ID);

    public static final DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> RF_HIVE = hive(dev.hurtify.relicsaddon.drone.HiveType.RF);
    public static final DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> MANA_HIVE = hive(dev.hurtify.relicsaddon.drone.HiveType.MANA);
    public static final DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> TWINS_HIVE = hive(dev.hurtify.relicsaddon.drone.HiveType.TWINS);

    private static DeferredItem<dev.hurtify.relicsaddon.relic.HiveRelicItem> hive(dev.hurtify.relicsaddon.drone.HiveType type) {
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
