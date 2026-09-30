package dev.hurtify.relicsaddon.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

public final class HiveMenuKey {
    private static final KeyMapping OPEN = new KeyMapping("key.relics_addon.device_control", GLFW.GLFW_KEY_H, "key.categories.relics_addon");
    /** Offers the Twins hive's Armageddon at the point the player looks at. */
    private static final KeyMapping ARMAGEDDON = new KeyMapping("key.relics_addon.armageddon", GLFW.GLFW_KEY_G, "key.categories.relics_addon");
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
        event.register(ARMAGEDDON);
    }
    public static void tick(ClientTickEvent.Post event) {
        while (OPEN.consumeClick()) if (Minecraft.getInstance().screen == null && Minecraft.getInstance().player != null) DeviceTargets.openPreferred();
        while (ARMAGEDDON.consumeClick()) ArmageddonScreen.request();
    }
    private HiveMenuKey() { }
}
