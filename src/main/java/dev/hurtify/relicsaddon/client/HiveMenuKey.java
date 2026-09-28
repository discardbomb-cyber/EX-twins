package dev.hurtify.relicsaddon.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

public final class HiveMenuKey {
    private static final KeyMapping OPEN = new KeyMapping("key.relics_addon.hive_tasks", GLFW.GLFW_KEY_H, "key.categories.relics_addon");
    public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    public static void tick(ClientTickEvent.Post event) {
        while (OPEN.consumeClick()) if (Minecraft.getInstance().screen == null && Minecraft.getInstance().player != null) HiveSettingsScreen.open();
    }
    private HiveMenuKey() { }
}
