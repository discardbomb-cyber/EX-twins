package dev.hurtify.releaseprobe;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Tests the release jar from mods/, never the mutable main compiler output. */
@Mod(value = "release_probe", dist = Dist.CLIENT)
@EventBusSubscriber(modid = "release_probe", value = Dist.CLIENT)
public final class ReleaseProbe {
    private static int ticks;
    private static int stableTicks;
    private static boolean captured;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (++ticks > 2400) {
            LogUtils.getLogger().error("Packaged release timed out before verification");
            minecraft.stop();
        }
        if (minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) stableTicks++;
        else stableTicks = 0;
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (stableTicks < 40 || captured || !(minecraft.screen instanceof TitleScreen)) return;
        captured = true;
        try {
            Class<?> controller = Class.forName("dev.hurtify.relicsaddon.server.HiveCombatController");
            String location = controller.getProtectionDomain().getCodeSource().getLocation().toString();
            if (!location.contains(".jar")) throw new IllegalStateException("Not a release jar: " + location);
            LogUtils.getLogger().info("Packaged controller loaded from {}", location);
            Screenshot.grab(minecraft.gameDirectory, "packaged-release-startup.png", minecraft.getMainRenderTarget(), message -> {
                LogUtils.getLogger().info("Packaged release verified: TitleScreen, no preview; {}", message.getString());
                minecraft.execute(minecraft::stop);
            });
        } catch (Exception | LinkageError error) {
            LogUtils.getLogger().error("Packaged release verification failed", error);
            minecraft.stop();
        }
    }
}
