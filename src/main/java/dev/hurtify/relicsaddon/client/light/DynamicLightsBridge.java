package dev.hurtify.relicsaddon.client.light;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.EffectLights;
import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
import dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * LambDynamicLights entrypoint ({@code lambdynlights:initializer} in neoforge.mods.toml). Nothing else
 * refers to this package, so it is only ever loaded when LambDynamicLights is installed. Once a tick
 * the merged {@link EffectLights} are handed to the behaviors that light them.
 */
public final class DynamicLightsBridge implements DynamicLightsInitializer {
    private static volatile EffectLightPool pool;
    private static Object level;

    @Override
    public void onInitializeDynamicLights(DynamicLightsContext context) {
        if (pool != null) return;
        pool = new EffectLightPool(context.dynamicLightBehaviorManager());
        NeoForge.EVENT_BUS.addListener(DynamicLightsBridge::onClientTick);
        EffectLights.attach();
        RelicsAddon.LOGGER.info("LambDynamicLights found: shields, swarm effects and blasts will light the world");
    }

    /** Still abstract in the 1.21.1 API, but LambDynamicLights only reaches it through the context overload above. */
    @Override
    @SuppressWarnings("removal")
    public void onInitializeDynamicLights(ItemLightSourceManager itemLightSourceManager) {
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level) {
            // LambDynamicLights drops every source when the world changes; ours must not linger in the pool.
            pool.clear();
            level = minecraft.level;
        }
        if (minecraft.level == null) return;
        pool.sync(EffectLights.lights(minecraft.level.getGameTime(), minecraft.gameRenderer.getMainCamera().getPosition()));
    }
}
