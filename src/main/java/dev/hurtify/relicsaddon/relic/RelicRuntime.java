package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.DeviceProgression;
import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative runtime for standalone device state. */
public final class RelicRuntime {
    public static DeviceProgression progression(ItemStack stack) { return stack.getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT); }
    public static boolean enabled(ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().available()) return false;
        return item.role().isHive() ? stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), dev.hurtify.relicsaddon.domain.hive.HiveStackState.DEFAULT).enabled()
                : stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT).enabled();
    }
    public static boolean canOperate(Player player, ItemStack stack) {
        return enabled(stack) && player != null && !player.isSpectator() && dev.hurtify.relicsaddon.power.DevicePower.powered(player, stack);
    }
    public static void setEnabled(Player player, ItemStack stack, boolean enabled) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item)) return;
        AutonomousRelicItem.ensureState(stack);
        if (item.role().isHive()) {
            var state = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), dev.hurtify.relicsaddon.domain.hive.HiveStackState.DEFAULT);
            stack.set(ModDataComponents.HIVE_STACK_STATE.get(), state.withEnabled(enabled));
            if (!enabled) stack.remove(ModDataComponents.HIVE_COMBAT_STATE.get());
            if (state.enabled() != enabled && player.level() instanceof net.minecraft.server.level.ServerLevel level)
                dev.hurtify.relicsaddon.sound.RelicSounds.summon(level, player.position(), dev.hurtify.relicsaddon.domain.hive.HiveType.of(item.role()), enabled);
        } else if (item.role().isShield()) {
            ShieldStackState state = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), state.withEnabled(enabled, player.level().getGameTime()));
        }
    }
    public static double stat(Player player, ItemStack stack, String id, double fallback, double minimum, double maximum) {
        DeviceProgression progression = progression(stack);
        int level = progression.level();
        double value = switch (id) {
            case "buffer_capacity" -> 504 + (5000 - 504) * level / 10.0;
            case "radius" -> 2 + level;
            case "drone_count" -> 12 + (dev.hurtify.relicsaddon.domain.hive.HiveType.MAX_DRONES - 12) * level / 10.0;
            case "drone_health" -> dev.hurtify.relicsaddon.domain.hive.HiveType.DRONE_HP;
            case "attack_damage" -> hiveValue(stack, level, 2);
            case "attack_interval_max" -> 100 - 60 * level / 10.0;
            case "cooldown" -> hiveValue(stack, level, 3);
            default -> fallback;
        };
        return Double.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }
    private static double hiveValue(ItemStack stack, int level, int kind) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item)) return 0;
        var type = dev.hurtify.relicsaddon.domain.hive.HiveType.of(item.role());
        return switch (kind) {
            case 2 -> type.initialAttackDamage + (type.maxAttackDamage - type.initialAttackDamage) * level / 10.0;
            default -> type.initialCooldown + (type.minCooldown - type.initialCooldown) * level / 10.0;
        };
    }
    public static boolean purchaseUpgrade(ItemStack stack, String id) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item)) return false;
        DeviceUpgrade upgrade;
        try { upgrade = DeviceUpgrade.byId(id); } catch (IllegalArgumentException ignored) { return false; }
        if (item.role().isShield() != (upgrade.bit() <= DeviceUpgrade.STABILIZATION.bit())) return false;
        if (id.equals(ShieldUpgrades.GATHER) && item.role() == RelicRole.TWINS_SHIELD) return false;
        DeviceProgression state = progression(stack);
        int rank = state.rank(id);
        if (state.level() < upgrade.requiredLevel() || state.points() < 1 || rank >= 3) return false;
        stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), state.withRank(id, rank + 1).withPoints(state.points() - 1));
        return true;
    }
    /** Experience from one level to the next: 60, 120, 220 ... 2 040; 8 100 in total to reach level 10. */
    public static int experienceToNext(int level) { return 60 + 40 * level + 20 * level * level; }
    public static void awardAbsorption(Player player, ItemStack stack, float value) { awardCombatExperience(player, stack, value); }
    public static void awardCombatExperience(Player player, ItemStack stack, float value) {
        if (!(value > 0) || !Float.isFinite(value) || player.isCreative() || !(stack.getItem() instanceof AutonomousRelicItem)) return;
        int gain = ExperienceLimiter.allow(stack, player.level().getGameTime(), Math.clamp(Math.round(value * .25F), 1, 3));
        if (gain <= 0) return;
        DeviceProgression before = progression(stack);
        int experience = before.experience() + gain;
        int level = before.level(), points = before.points();
        while (level < DeviceProgression.MAX_LEVEL && experience >= experienceToNext(level)) { experience -= experienceToNext(level); level++; points++; }
        stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(experience, level, points, before.upgrades()));
    }
    private RelicRuntime() { }
}
