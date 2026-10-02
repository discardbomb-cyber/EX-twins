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
        NeoForge.EVENT_BUS.addListener(HiveVisualRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ShipHiveRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(ShipHiveRenderer::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ArmageddonShake::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(EffectLights::onFrame);
        NeoForge.EVENT_BUS.addListener(HiveMenuKey::tick);
        NeoForge.EVENT_BUS.addListener(ShiftHoverOpener::onClientTick);
        NeoForge.EVENT_BUS.addListener(ShiftHoverOpener::onMouseClick);
        NeoForge.EVENT_BUS.addListener(ShiftHoverOpener::onTooltip);
        modEventBus.addListener(HiveMenuKey::register);
        modEventBus.addListener(AnimatedRelicItemRenderer::registerAdditionalModels);
        modEventBus.addListener(ClientEventRegistrar::registerItemExtensions);
        modEventBus.addListener(ClientEventRegistrar::registerScreens);
        modEventBus.addListener(ClientEventRegistrar::registerShaders);
        modEventBus.addListener(dev.hurtify.relicsaddon.client.fx.ExFx::registerReloadListener);
        modEventBus.addListener(dev.hurtify.relicsaddon.client.workbench.RfWorkbenchModel::registerReloadListener);
    }

    private static void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            ShieldRefraction.registerShaders(event);
            BlackHoleLens.registerShaders(event);
            ArmageddonVolume.registerShaders(event);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException("Failed to load the refraction shaders", exception);
        }
    }

    private static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(dev.hurtify.relicsaddon.registry.ModMenus.DEVICE_CONTROL.get(), DeviceControlScreen::new);
        event.register(dev.hurtify.relicsaddon.registry.ModMenus.SHIP_HIVE.get(), ShipHiveScreen::new);
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
