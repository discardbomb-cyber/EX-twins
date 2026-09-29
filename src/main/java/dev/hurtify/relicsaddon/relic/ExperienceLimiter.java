package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.ItemStack;

/**
 * Caps how much experience one device earns per minute, so a 500-drone swarm or a mob farm
 * cannot max a device in a few minutes. Windows are kept per device instance, in memory only.
 */
final class ExperienceLimiter {
    private static final long WINDOW_TICKS = 1_200;
    private static final int MAX_TRACKED = 4_096;
    private static final Map<String, long[]> WINDOWS = new HashMap<>();

    /** Returns how much of {@code gain} the device may still earn in the current minute. */
    static synchronized int allow(ItemStack stack, long now, int gain) {
        int cap = AddonConfig.SPEC.isLoaded() ? AddonConfig.XP_PER_MINUTE.get() : 30;
        String id = stack.getOrDefault(ModDataComponents.INSTANCE_ID.get(), "");
        if (id.isEmpty()) return 0;
        long[] window = WINDOWS.get(id);
        if (window == null || now < window[0] || now - window[0] >= WINDOW_TICKS) {
            if (WINDOWS.size() >= MAX_TRACKED) WINDOWS.entrySet().removeIf(entry -> now < entry.getValue()[0] || now - entry.getValue()[0] >= WINDOW_TICKS);
            window = new long[]{now, 0};
            WINDOWS.put(id, window);
        }
        int granted = (int) Math.max(0, Math.min(gain, cap - window[1]));
        window[1] += granted;
        return granted;
    }

    private ExperienceLimiter() {
    }
}
