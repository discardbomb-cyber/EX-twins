package dev.hurtify.relicsaddon.shield;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ShieldParameters {
    public static int capacity(Player player, ItemStack stack) {
        return (int) Math.round(RelicRuntime.stat(player, stack, "buffer_capacity", 504, 504, 5500));
    }
    public static int totalCapacity(Player player, ItemStack stack) {
        return capacity(player, stack) + ShieldTopology.CELL_COUNT * ShieldStackState.MAX_PANEL_INTEGRITY;
    }
    public static double maxRadius(Player player, ItemStack stack) {
        double limit = AddonConfig.SHIELD_MAX_RADIUS.get();
        return Math.min(limit, RelicRuntime.stat(player, stack, "radius", 2, 2, 24));
    }
    public static double radius(Player player, ItemStack stack) {
        return Math.min(maxRadius(player, stack), settings(stack).radius());
    }
    public static ShieldSettings settings(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SHIELD_SETTINGS.get(), ShieldSettings.DEFAULT);
    }

    /**
     * Damage the shell deals to a hostile mob it throws back: RF 3 to 7, Mana 2.5 to 6 (through
     * armour), Twins 4 to 9 (through armour) from level 0 to 10, times the server multiplier.
     */
    public static float strikeDamage(ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isShield()) return 0;
        int level = RelicRuntime.progression(stack).level();
        double base = switch (item.role()) {
            case MANA_SHIELD -> 2.5 + .35 * level;
            case TWINS_SHIELD -> 4 + .5 * level;
            default -> 3 + .4 * level;
        };
        double scale = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIELD_STRIKE_DAMAGE.get() : 1;
        return (float) (base * scale);
    }

    /** Knockback strength of a strike, in vanilla units (a plain melee hit is 0.4). */
    public static double strikeKnockback(RelicRole role) {
        double base = switch (role) {
            case MANA_SHIELD -> .8;
            case TWINS_SHIELD -> 1.2;
            default -> 1.0;
        };
        return base * (AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIELD_STRIKE_KNOCKBACK.get() : 1);
    }

    /** Ticks before the same mob can be struck again. */
    public static int strikeCooldown() {
        return AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIELD_STRIKE_COOLDOWN.get() : 20;
    }
    private ShieldParameters() { }
}
