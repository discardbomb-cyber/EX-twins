package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, RelicsAddon.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<DeviceControlMenu>> DEVICE_CONTROL = MENUS.register("device_control",
            () -> IMenuTypeExtension.create((id, inventory, buffer) -> new DeviceControlMenu(id, inventory, buffer.readBoolean(), buffer.readVarInt())));

    public static final DeferredHolder<MenuType<?>, MenuType<dev.hurtify.relicsaddon.menu.ShipHiveMenu>> SHIP_HIVE = MENUS.register("ship_hive",
            () -> IMenuTypeExtension.create(dev.hurtify.relicsaddon.menu.ShipHiveMenu::new));

    private ModMenus() {
    }
}
