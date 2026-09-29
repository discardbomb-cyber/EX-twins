package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveSupportState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.HiveUpgrades;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

public final class HiveTaskController {
    public static HiveSettings settings(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.DEFAULT);
    }

    public static ItemStack locate(Player owner, boolean charm, int slot) {
        if (slot < 0) return ItemStack.EMPTY;
        if (!charm) return slot < owner.getInventory().getContainerSize() ? owner.getInventory().getItem(slot) : ItemStack.EMPTY;
        return CuriosApi.getCuriosInventory(owner).flatMap(handler -> handler.getStacksHandler(RelicRole.EQUIPMENT_SLOT))
                .map(handler -> slot < handler.getStacks().getSlots() ? handler.getStacks().getStackInSlot(slot) : ItemStack.EMPTY)
                .orElse(ItemStack.EMPTY);
    }

    public static boolean configure(Player owner, boolean charm, int slot, String identity, int healers) {
        if (owner.level().isClientSide() || !owner.isAlive() || owner.isSpectator() || !EquippedRelicSetResolver.isRealPlayer(owner)) return false;
        ItemStack stack = locate(owner, charm, slot);
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive() || identity.isEmpty()
                || !identity.equals(stack.get(ModDataComponents.INSTANCE_ID.get())) || healers < 0 || healers > HiveController.capacity(owner, stack)) return false;
        stack.set(ModDataComponents.HIVE_SETTINGS.get(), settings(stack).withHealers(healers));
        return true;
    }

    /** Switches how the hive's fighters attack; a swarm in combat regroups for the new mode. */
    public static boolean configureMode(Player owner, boolean charm, int slot, String identity, dev.hurtify.relicsaddon.drone.AttackMode mode) {
        if (owner.level().isClientSide() || !owner.isAlive() || owner.isSpectator() || !EquippedRelicSetResolver.isRealPlayer(owner) || mode == null) return false;
        ItemStack stack = locate(owner, charm, slot);
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive() || identity.isEmpty()
                || !identity.equals(stack.get(ModDataComponents.INSTANCE_ID.get()))) return false;
        stack.set(ModDataComponents.HIVE_SETTINGS.get(), settings(stack).withMode(mode));
        return true;
    }

    /** All hives share the owner's per-second budget, so three amulets cannot triple the healing cap. */
    public static void tickHealing(Player owner, List<HiveController.Equipped> hives, long now) {
        float budget = (float) AddonConfig.HIVE_HEAL_PER_SECOND.get().doubleValue();
        for (var hive : hives) {
            ItemStack stack = hive.stack();
            HiveStackState state = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
            HiveSettings settings = settings(stack);
            boolean active = owner.isAlive() && owner.getHealth() < owner.getMaxHealth()
                    && settings.healerCount(state.units().size()) > 0 && AddonConfig.HIVE_HEAL_PER_SECOND.get() > 0;
            HiveSupportState before = stack.getOrDefault(ModDataComponents.HIVE_SUPPORT_STATE.get(), HiveSupportState.DEFAULT);
            if (before.active() != active) stack.set(ModDataComponents.HIVE_SUPPORT_STATE.get(), new HiveSupportState(active, now));
            if (!active || now % 20 != 0 || budget <= 0) continue;
            var units = new ArrayList<>(state.units());
            boolean changed = false;
            int first = settings.fighters(units.size()), deployed = dev.hurtify.relicsaddon.drone.HiveSlots.healerSlots(units.size(), settings);
            for (int index = first; index < first + deployed && budget > 0; index++) {
                var unit = units.get(index);
                if (!unit.attackReady(now)) continue;
                if (unit.attackReadyAt() == 0) {
                    units.set(index, unit.withAttackReadyAt(now + 20L * (1 + index % 5)));
                    changed = true;
                    continue;
                }
                if (!dev.hurtify.relicsaddon.power.DevicePower.canAfford(owner, stack, dev.hurtify.relicsaddon.power.DevicePower.HEAL_PER_HP)) break;
                float requested = Math.min((float) (.5 * HiveUpgrades.healingMultiplier(owner, stack)),
                        Math.min(budget, owner.getMaxHealth() - owner.getHealth()));
                if (requested <= 0) break;
                float oldHealth = owner.getHealth();
                owner.heal(requested);
                float restored = Math.max(0, owner.getHealth() - oldHealth);
                dev.hurtify.relicsaddon.power.DevicePower.drain(owner, stack, (int) Math.ceil(restored * dev.hurtify.relicsaddon.power.DevicePower.HEAL_PER_HP));
                budget -= restored;
                units.set(index, unit.withAttackReadyAt(now + 100));
                changed = true;
            }
            if (changed) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(state.enabled(), units));
        }
    }

    private HiveTaskController() { }
}
