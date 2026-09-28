package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.registry.ModItems;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public final class ClientEventRegistrar {
    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ShieldVisualRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(ShieldThreatTracker::onTick);
        NeoForge.EVENT_BUS.addListener(HiveVisualRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(HiveMenuKey::tick);
        modEventBus.addListener(HiveMenuKey::register);
        modEventBus.addListener(AnimatedRelicItemRenderer::registerAdditionalModels);
        modEventBus.addListener(ClientEventRegistrar::registerItemExtensions);
    }

    private static void registerItemExtensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions extension = new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return AnimatedRelicItemRenderer.getInstance();
            }
        };
        event.registerItem(extension, ModItems.RF_SHIELD.get());
        event.registerItem(extension, ModItems.MANA_SHIELD.get());
        event.registerItem(extension, ModItems.TWINS_SHIELD.get());
        event.registerItem(extension, ModItems.RF_HIVE.get());
        event.registerItem(extension, ModItems.MANA_HIVE.get());
        event.registerItem(extension, ModItems.TWINS_HIVE.get());
    }

    private ClientEventRegistrar() {
    }
}
