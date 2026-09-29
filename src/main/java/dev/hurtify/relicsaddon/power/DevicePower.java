package dev.hurtify.relicsaddon.power;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
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
    public static final int FE_PER_POINT = 10;
    public static final int FE_TRANSFER_PER_TICK = 20_000;
    /** Idle upkeep per second while a device is switched on and running. */
    public static final int SHIELD_UPKEEP = 20, HIVE_UPKEEP = 20;
    /** Per-event costs. */
    public static final int ABSORB_PER_HP = 10, REPAIR_PER_HP = 2, SHOT = 3, HEAL_PER_HP = 5, HIVE_REPAIR_PER_HP = 1;
    private static final int MANA_CHARGE_PER_PULSE = 250;

    public static boolean hasRf(RelicRole role) {
        return role == RelicRole.RF_SHIELD || role == RelicRole.RF_HIVE || role == RelicRole.TWINS_SHIELD || role == RelicRole.TWINS_HIVE;
    }

    public static boolean hasMana(RelicRole role) {
        return role == RelicRole.MANA_SHIELD || role == RelicRole.MANA_HIVE || role == RelicRole.TWINS_SHIELD || role == RelicRole.TWINS_HIVE;
    }

    public static RelicRole role(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item ? item.role() : null;
    }

    public static DeviceEnergy energy(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.DEVICE_ENERGY.get(), DeviceEnergy.EMPTY);
    }

    /** Battery size in points; it grows with the device level (25 000 up to 100 000). */
    public static int capacity(ItemStack stack) {
        int level = stack.getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT).level();
        return 25_000 + 7_500 * level;
    }

    public static int feCapacity(ItemStack stack) {
        return capacity(stack) * FE_PER_POINT;
    }

    /** A freshly made device ships with full batteries. */
    public static DeviceEnergy full(ItemStack stack) {
        RelicRole role = role(stack);
        if (role == null) return DeviceEnergy.EMPTY;
        return new DeviceEnergy(hasRf(role) ? feCapacity(stack) : 0, hasMana(role) ? capacity(stack) : 0, true, true, DeviceEnergy.ManaSource.AUTO);
    }

    public static boolean required() {
        return !AddonConfig.SPEC.isLoaded() || AddonConfig.POWER_REQUIRED.get();
    }

    private static boolean free(Player player) {
        return !required() || player != null && player.getAbilities().instabuild;
    }

    private static int usableRf(RelicRole role, DeviceEnergy energy) {
        return hasRf(role) && energy.rfOn() ? energy.rf() / FE_PER_POINT : 0;
    }

    private static int usableMana(RelicRole role, DeviceEnergy energy) {
        return hasMana(role) && energy.manaOn() ? energy.mana() : 0;
    }

    /** Whether the device has charge in a switched-on battery (always true in Creative or when power is disabled). */
    public static boolean powered(Player player, ItemStack stack) {
        RelicRole role = role(stack);
        if (role == null) return false;
        if (free(player)) return true;
        DeviceEnergy energy = energy(stack);
        return usableRf(role, energy) + usableMana(role, energy) > 0;
    }

    public static boolean canAfford(Player player, ItemStack stack, int points) {
        RelicRole role = role(stack);
        if (role == null) return false;
        if (points <= 0 || free(player)) return true;
        DeviceEnergy energy = energy(stack);
        return usableRf(role, energy) + usableMana(role, energy) >= points;
    }

    /**
     * Spends {@code points}. Returns false when the batteries could not cover it, in which case
     * every usable battery is emptied and the device stops until it is recharged.
     */
    public static boolean drain(Player player, ItemStack stack, int points) {
        RelicRole role = role(stack);
        if (role == null) return false;
        if (points <= 0 || free(player)) return true;
        DeviceEnergy energy = energy(stack);
        int rf = usableRf(role, energy), mana = usableMana(role, energy);
        if (rf + mana < points) {
            stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withRf(energy.rf() - rf * FE_PER_POINT).withMana(energy.mana() - mana));
            return false;
        }
        int[] share = split(rf, mana, points);
        stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withRf(energy.rf() - share[0] * FE_PER_POINT).withMana(energy.mana() - share[1]));
        return true;
    }

    /**
     * How a cost of {@code points} is shared between usable RF and mana charge (both in points);
     * requires {@code rf + mana >= points}. Twins split evenly and a short battery hands the rest
     * to the other one.
     */
    static int[] split(int rf, int mana, int points) {
        if (rf > 0 && mana > 0) {
            int fromMana = Math.min(mana, points - Math.min(rf, (points + 1) / 2));
            return new int[]{points - fromMana, fromMana};
        }
        return rf > 0 ? new int[]{points, 0} : new int[]{0, points};
    }

    /** FE insertion used by chargers (item capability) and the console's charge slot. */
    public static int receiveFe(ItemStack stack, int amount, boolean simulate) {
        RelicRole role = role(stack);
        if (role == null || !hasRf(role) || amount <= 0) return 0;
        DeviceEnergy energy = energy(stack);
        int accepted = Math.min(Math.min(amount, FE_TRANSFER_PER_TICK), Math.max(0, feCapacity(stack) - energy.rf()));
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
                drain(player, hive.stack(), HIVE_UPKEEP + drones / 10);
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
        int gained = ManaSources.draw(player, stack, Math.min(missing, MANA_CHARGE_PER_PULSE), energy.source());
        if (gained > 0) stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withMana(Math.min(capacity(stack), energy.mana() + gained)));
    }

    private DevicePower() {
    }
}
