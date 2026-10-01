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
        modEventBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Loading.class, AddonConfig::onConfigLoad);
        modEventBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Reloading.class, AddonConfig::onConfigLoad);
        dev.hurtify.relicsaddon.sound.RelicSounds.register(modEventBus);
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);
        dev.hurtify.relicsaddon.registry.ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        dev.hurtify.relicsaddon.registry.ModEntities.ENTITIES.register(modEventBus);
        dev.hurtify.relicsaddon.registry.ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        modEventBus.addListener(dev.hurtify.relicsaddon.registry.ModBlockEntities::registerCapabilities);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        dev.hurtify.relicsaddon.registry.ModMenus.MENUS.register(modEventBus);
        modEventBus.addListener(dev.hurtify.relicsaddon.network.OpenDevicePayload::register);
        modEventBus.addListener(dev.hurtify.relicsaddon.network.ArmageddonPayloads::register);
        modEventBus.addListener(dev.hurtify.relicsaddon.network.HiveAllocationPayload::register);
        modEventBus.addListener(dev.hurtify.relicsaddon.network.NoctisPayloads::register);
        modEventBus.addListener(dev.hurtify.relicsaddon.power.DeviceEnergyStorage::register);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.power.DevicePower::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(ShieldController::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ShieldEffectGuard::onEffectApplicable);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ShieldEffectGuard::onDamagePost);
        NeoForge.EVENT_BUS.addListener(ShieldController::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ShieldBarrier::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(ShieldProjectileInterceptor::onEntityTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveController::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveCombatController::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveCombatController::onExplosion);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveCombatController::onOwnerDamaged);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, dev.hurtify.relicsaddon.server.HiveContainment::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveContainment::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveContainment::onTeleport);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveContainment::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveContainment::onLevelTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ArmageddonController::onLevelTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ArmageddonController::onServerStopping);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.HiveCombatController::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.ShieldStatusCommand::register);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.relic.TwinsSpearEntity::onEnderTeleport);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.NoctisCombat::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.NoctisCombat::onAttack);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.NoctisCombat::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.NoctisBeam::onLevelTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.server.NoctisWormhole::onLevelTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.ShipBrain::onDamage);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.ShipBrain::onExplosion);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.ShipBrain::onProjectileImpact);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.ShipBrain::onServerStopping);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.ShipBrain::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.AegisFields::onEntityTick);
        // The ship's shield is the outer one: it takes a blow before a personal shield would.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, dev.hurtify.relicsaddon.ship.AegisFields::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.AegisFields::onExplosion);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.AegisFields::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.hurtify.relicsaddon.ship.AegisFields::onServerStopping);
        registerClientOnly(modEventBus);
        LOGGER.info("Loaded three shields, three typed defender hives and three ship hives");
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
