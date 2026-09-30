package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RelicsAddon.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.relics_addon.main"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.RF_SHIELD.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.RF_SHIELD.get());
                        output.accept(ModItems.MANA_SHIELD.get());
                        output.accept(ModItems.TWINS_SHIELD.get());
                        output.accept(ModItems.RF_HIVE.get());
                        output.accept(ModItems.MANA_HIVE.get());
                        output.accept(ModItems.TWINS_HIVE.get());
                        ModItems.COMPONENTS.forEach(item -> output.accept(item.get()));
                        for (var family : dev.hurtify.relicsaddon.shipshield.ShipFamily.values()) {
                            output.accept(ShipBlocks.item(family.generator));
                            output.accept(ShipBlocks.item(family.dock));
                            output.accept(ModItems.EMITTER_DRONES.get(family).get());
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
