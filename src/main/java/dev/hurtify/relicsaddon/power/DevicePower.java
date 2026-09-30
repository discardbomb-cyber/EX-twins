package dev.hurtify.relicsaddon.power;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.domain.device.DeviceProgression;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.energy.BatteryRules;
import dev.hurtify.relicsaddon.domain.energy.DeviceEnergy;
import dev.hurtify.relicsaddon.domain.energy.EnergyCosts;
import dev.hurtify.relicsaddon.domain.hive.HiveStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;
import dev.hurtify.relicsaddon.server.HiveController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Built-in batteries. RF devices carry an RF battery, Mana devices a mana battery and Twins both;
 * a device only operates while one of its switched-on batteries holds charge. Costs are expressed
 * in battery points: one point is {@value #FE_PER_POINT} FE or one unit of stored mana. Twins
 * split every cost between their batteries and fall back to whichever still has charge.
 */
public final class DevicePower {
    public static final int FE_PER_POINT = EnergyCosts.FE_PER_POINT;
    public static final int FE_TRANSFER_PER_TICK = EnergyCosts.FE_TRANSFER_PER_TICK;
    /** Idle upkeep per second while a device is switched on and running. */
    public static final int SHIELD_UPKEEP = EnergyCosts.SHIELD_UPKEEP, HIVE_UPKEEP = EnergyCosts.HIVE_UPKEEP;
    /** Per-event costs. */
    public static final int ABSORB_PER_HP = EnergyCosts.ABSORB_PER_HP, REPAIR_PER_HP = EnergyCosts.REPAIR_PER_HP, SHOT = EnergyCosts.SHOT,
            HEAL_PER_HP = EnergyCosts.HEAL_PER_HP, HIVE_REPAIR_PER_HP = EnergyCosts.HIVE_REPAIR_PER_HP, STRIKE = EnergyCosts.STRIKE;

    public static boolean hasRf(RelicRole role) {
        return BatteryRules.hasRf(role);
    }

    public static boolean hasMana(RelicRole role) {
        return BatteryRules.hasMana(role);
    }

    public static RelicRole role(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item ? item.role() : null;
    }

    public static DeviceEnergy energy(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.DEVICE_ENERGY.get(), DeviceEnergy.EMPTY);
    }

    /** Battery size in points; it grows with the device level (25 000 up to 100 000). */
    public static int capacity(ItemStack stack) {
        return BatteryRules.capacity(level(stack));
    }

    public static int feCapacity(ItemStack stack) {
        return BatteryRules.feCapacity(level(stack));
    }

    /** A freshly made device ships with full batteries. */
    public static DeviceEnergy full(ItemStack stack) {
        RelicRole role = role(stack);
        if (role == null) return DeviceEnergy.EMPTY;
        return BatteryRules.full(role, level(stack));
    }

    private static int level(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT).level();
    }

    public static boolean required() {
        return !AddonConfig.SPEC.isLoaded() || AddonConfig.POWER_REQUIRED.get();
    }

    private static boolean free(Player player) {
        return !required() || player != null && player.getAbilities().instabuild;
    }

    /** Whether the device has charge in a switched-on battery (always true in Creative or when power is disabled). */
    public static boolean powered(Player player, ItemStack stack) {
        RelicRole role = role(stack);
        if (role == null) return false;
        if (free(player)) return true;
        DeviceEnergy energy = energy(stack);
        return BatteryRules.usableRf(role, energy) + BatteryRules.usableMana(role, energy) > 0;
    }

    public static boolean canAfford(Player player, ItemStack stack, int points) {
        RelicRole role = role(stack);
        if (role == null) return false;
        if (points <= 0 || free(player)) return true;
        DeviceEnergy energy = energy(stack);
        return BatteryRules.usableRf(role, energy) + BatteryRules.usableMana(role, energy) >= points;
    }

    /**
     * Spends {@code points}. Returns false when the batteries could not cover it, in which case
     * every usable battery is emptied and the device stops until it is recharged.
     */
    public static boolean drain(Player player, ItemStack stack, int points) {
        RelicRole role = role(stack);
        if (role == null) return false;
        if (points <= 0 || free(player)) return true;
        BatteryRules.Drain drain = BatteryRules.drain(role, energy(stack), points);
        stack.set(ModDataComponents.DEVICE_ENERGY.get(), drain.after());
        return drain.paid();
    }

    /** FE insertion used by chargers (item capability) and the console's charge slot. */
    public static int receiveFe(ItemStack stack, int amount, boolean simulate) {
        RelicRole role = role(stack);
        if (role == null || !hasRf(role) || amount <= 0) return 0;
        DeviceEnergy energy = energy(stack);
        int accepted = BatteryRules.acceptFe(role, energy, level(stack), amount);
        if (!simulate && accepted > 0) stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withRf(energy.rf() + accepted));
        return accepted;
    }

    public static void setBattery(ItemStack stack, boolean rf, boolean on) {
        DeviceEnergy energy = energy(stack);
        stack.set(ModDataComponents.DEVICE_ENERGY.get(), rf ? energy.withRfOn(on) : energy.withManaOn(on));
    }

    public static void setManaSource(ItemStack stack, DeviceEnergy.ManaSource source) {
        stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy(stack).withSource(source));
    }

    /** Upkeep once a second, and mana refills twice a second for every equipped device. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !EquippedRelicSetResolver.isRealPlayer(player)
                || !player.isAlive() || player.isSpectator()) return;
        long now = player.level().getGameTime();
        if (now % 20 == 0) {
            EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields())
                    .ifPresent(shield -> drain(player, shield, SHIELD_UPKEEP));
            for (HiveController.Equipped hive : HiveController.active(player)) {
                int drones = hive.stack().getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT).units().size();
                drain(player, hive.stack(), HIVE_UPKEEP + Math.min(drones, dev.hurtify.relicsaddon.domain.hive.HiveType.MAX_DEPLOYED) / 10);
            }
        }
        if (now % 10 != 5 || free(player)) return;
        CuriosApi.getCuriosInventory(player).flatMap(handler -> handler.getStacksHandler(RelicRole.EQUIPMENT_SLOT)).ifPresent(handler -> {
            for (int slot = 0; slot < handler.getStacks().getSlots(); slot++) chargeMana(player, handler.getStacks().getStackInSlot(slot));
        });
    }

    private static void chargeMana(ServerPlayer player, ItemStack stack) {
        RelicRole role = role(stack);
        if (role == null || !hasMana(role) || !RelicRuntime.enabled(stack)) return;
        DeviceEnergy energy = energy(stack);
        int missing = capacity(stack) - energy.mana();
        if (!energy.manaOn() || missing <= 0) return;
        int gained = ManaSources.draw(player, stack, Math.min(missing, EnergyCosts.MANA_CHARGE_PER_PULSE), energy.source());
        if (gained > 0) stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withMana(Math.min(capacity(stack), energy.mana() + gained)));
    }

    private DevicePower() {
    }
}
