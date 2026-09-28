package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import it.hurts.sskirillss.relics.api.relics.data.AbilityData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class RelicRuntime {
    public static AbilityData ability(Player player, ItemStack stack) {
        AutonomousRelicItem item = (AutonomousRelicItem) stack.getItem();
        return item.getRelicData(player, stack).getAbilitiesData().getAbilityData(item.role().abilityId());
    }

    public static boolean enabled(ItemStack stack) {
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().available()) {
            return false;
        }
        if (item.role().isHive()) return stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(),
                dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT).enabled();
        return item.role().isShield()
                && stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT).enabled();
    }

    public static boolean canOperate(Player player, ItemStack stack) {
        return enabled(stack) && ability(player, stack).canPlayerUse(player);
    }

    public static void setEnabled(Player player, ItemStack stack, boolean enabled) {
        AutonomousRelicItem item = (AutonomousRelicItem) stack.getItem();
        if (!item.role().available()) {
            ability(player, stack).setActivationTicking(false);
            return;
        }
        long now = player.level().getGameTime();
        if (item.role().isHive()) {
            var state = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT);
            stack.set(ModDataComponents.HIVE_STACK_STATE.get(), state.withEnabled(enabled));
            if (!enabled) stack.remove(ModDataComponents.HIVE_COMBAT_STATE.get());
            if (state.enabled() != enabled && player.level() instanceof net.minecraft.server.level.ServerLevel level)
                dev.hurtify.relicsaddon.sound.RelicSounds.summon(level, player.position(),
                        dev.hurtify.relicsaddon.drone.HiveType.of(item.role()), enabled);
        } else if (item.role().isShield()) {
            ShieldStackState state = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), state.withEnabled(enabled, now));
        }
        ability(player, stack).setActivationTicking(enabled);
    }

    public static double stat(Player player, ItemStack stack, String id, double fallback, double minimum, double maximum) {
        AbilityData ability = ability(player, stack);
        // Old world configs can retain the prototype's stat names until the owner regenerates them.
        double value = ability.getTemplate().getStats().containsKey(id) ? ability.getStatData(id).getValue() : fallback;
        return Double.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }

    public static void awardAbsorption(Player player, ItemStack stack, float absorbed) {
        if (!(absorbed > 0) || !Float.isFinite(absorbed) || player.isCreative()) {
            return;
        }
        AutonomousRelicItem item = (AutonomousRelicItem) stack.getItem();
        if (!item.role().available()) return;
        item.getRelicData(player, stack).getLevelingData().addExperience(
                item.role().abilityId(), item.role().abilityId() + "_activity", Math.min(2.0, absorbed * 0.25));
    }

    private RelicRuntime() {
    }
}
