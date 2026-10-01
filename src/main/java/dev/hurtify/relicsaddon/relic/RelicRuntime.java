package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.DeviceProgression;
import dev.hurtify.relicsaddon.domain.device.DeviceStat;
import dev.hurtify.relicsaddon.domain.device.DeviceStats;
import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.device.ProgressionRules;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.device.UpgradeRules;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import java.util.Optional;
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
    /** A device stat by id at the stack's level; an unknown id gives the fallback, clamped when it is finite. */
    public static double stat(Player player, ItemStack stack, String id, double fallback, double minimum, double maximum) {
        int level = progression(stack).level();
        Optional<DeviceStat> stat = DeviceStat.byId(id);
        if (stat.isEmpty()) return Double.isFinite(fallback) ? Math.clamp(fallback, minimum, maximum) : fallback;
        return DeviceStats.stat(role(stack), level, stat.get(), fallback, minimum, maximum);
    }
    /** The device's role, empty for a stack that is not a device. */
    private static Optional<RelicRole> role(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item ? Optional.of(item.role()) : Optional.empty();
    }
    public static boolean purchaseUpgrade(ItemStack stack, String id) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item)) return false;
        DeviceUpgrade upgrade;
        try { upgrade = DeviceUpgrade.byId(id); } catch (IllegalArgumentException ignored) { return false; }
        DeviceProgression state = progression(stack);
        if (!UpgradeRules.canPurchase(item.role(), upgrade, state)) return false;
        stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), UpgradeRules.afterPurchase(state, upgrade));
        return true;
    }
    /** Experience from one level to the next: 60, 120, 220 ... 2 040; 8 100 in total to reach level 10. */
    public static int experienceToNext(int level) { return ProgressionRules.experienceToNext(level); }
    public static void awardAbsorption(Player player, ItemStack stack, float value) { awardCombatExperience(player, stack, value); }
    public static void awardCombatExperience(Player player, ItemStack stack, float value) {
        if (!(value > 0) || !Float.isFinite(value) || player.isCreative() || !(stack.getItem() instanceof AutonomousRelicItem)) return;
        int gain = ExperienceLimiter.allow(stack, player.level().getGameTime(), ProgressionRules.gain(value));
        if (gain <= 0) return;
        DeviceProgression next = ProgressionRules.addExperience(progression(stack), gain);
        stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), next);
    }
    private RelicRuntime() { }
}
