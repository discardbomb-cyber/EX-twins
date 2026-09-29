package dev.hurtify.relicsaddon;

import dev.hurtify.relicsaddon.registry.ModCreativeTabs;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RelicsAddon.MOD_ID)
public final class RelicsAddon {
    public static final String MOD_ID = "relics_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public RelicsAddon(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, AddonConfig.SPEC);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, dev.hurtify.relicsaddon.client.AddonClientConfig.SPEC);
        dev.hurtify.relicsaddon.sound.RelicSounds.register(modEventBus);
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        dev.hurtify.relicsaddon.registry.ModMenus.MENUS.register(modEventBus);
        modEventBus.addListener(dev.hurtify.relicsaddon.network.OpenDevicePayload::register);
        modEventBus.addListener(dev.hurtify.relicsaddon.power.DeviceEnergyStorage::register);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.power.DevicePower::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(ShieldController::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(ShieldController::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ShieldBarrier::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(ShieldProjectileInterceptor::onEntityTick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, dev.hurtify.relicsaddon.server.HiveController::onEntityTick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, dev.hurtify.relicsaddon.server.HiveController::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveController::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveCombatController::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveCombatController::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ShieldStatusCommand::register);
        registerClientOnly(modEventBus);
        LOGGER.info("Loaded three shields and three typed defender hives");
    }

    private static void registerClientOnly(IEventBus modEventBus) {
        try {
            Class<?> environmentClass = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = environmentClass.getField("dist").get(null);
            Object distName = dist.getClass().getMethod("name").invoke(dist);
            if (!"CLIENT".equals(distName)) {
                return;
            }

            Class<?> registrar = Class.forName("dev.hurtify.relicsaddon.client.ClientEventRegistrar");
            registrar.getMethod("register", IEventBus.class).invoke(null, modEventBus);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to register addon client hooks", exception);
        }
    }
}
