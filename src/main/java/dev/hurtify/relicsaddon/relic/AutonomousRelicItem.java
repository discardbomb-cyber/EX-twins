package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A self-contained device item. Relics owns none of its state or activation. */
public abstract class AutonomousRelicItem extends Item {
    protected AutonomousRelicItem(Properties properties) { super(properties); }
    public abstract RelicRole role();

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);
        if (!EquippedRelicSetResolver.isRealPlayer(player)) return InteractionResultHolder.pass(stack);
        boolean enabled = !RelicRuntime.enabled(stack);
        RelicRuntime.setEnabled(player, stack, enabled);
        player.displayClientMessage(Component.translatable("message.relics_addon." + (role().isHive() ? "hive" : "shield") + (enabled ? "_enabled" : "_disabled")), true);
        return InteractionResultHolder.consume(stack);
    }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide()) ensureState(stack);
    }
    @Override public void verifyComponentsAfterLoad(ItemStack stack) { super.verifyComponentsAfterLoad(stack); ensureState(stack); }

    public static void ensureState(ItemStack stack) {
        if (!stack.has(ModDataComponents.INSTANCE_ID.get())) stack.set(ModDataComponents.INSTANCE_ID.get(), UUID.randomUUID().toString());
        if (!stack.has(ModDataComponents.DEVICE_PROGRESSION.get())) stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT);
        if (!stack.has(ModDataComponents.DEVICE_ENERGY.get())) stack.set(ModDataComponents.DEVICE_ENERGY.get(), dev.hurtify.relicsaddon.power.DevicePower.full(stack));
        if (stack.getItem() instanceof AutonomousRelicItem item && item.role().isHive()) {
            stack.set(ModDataComponents.HIVE_STACK_STATE.get(), stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT));
        } else if (stack.getItem() instanceof AutonomousRelicItem item && item.role().isShield()) {
            stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT));
        }
    }

    @Override public boolean isBarVisible(ItemStack stack) { return role().isShield() && integrity(stack) < ShieldParameters.totalCapacity(null, stack); }
    @Override public int getBarWidth(ItemStack stack) { return role().isShield() ? Math.clamp(Math.round(13F * integrity(stack) / ShieldParameters.totalCapacity(null, stack)), 0, 13) : 0; }
    @Override public int getBarColor(ItemStack stack) {
        double percent = integrity(stack) / (double) ShieldParameters.totalCapacity(null, stack);
        return percent <= .25 ? 0xEF4242 : percent <= .45 ? 0xF08232 : percent <= .65 ? 0xEBD34C : role().color();
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        DeviceProgression progression = stack.getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT);
        lines.add(Component.translatable("tooltip.relics_addon.device_level", progression.level(), DeviceProgression.MAX_LEVEL, progression.points()).withStyle(style()));
        var energy = dev.hurtify.relicsaddon.power.DevicePower.energy(stack);
        if (dev.hurtify.relicsaddon.power.DevicePower.hasRf(role())) {
            lines.add(Component.translatable(energy.rfOn() ? "tooltip.relics_addon.battery_rf" : "tooltip.relics_addon.battery_rf_off",
                    energy.rf(), dev.hurtify.relicsaddon.power.DevicePower.feCapacity(stack)).withStyle(ChatFormatting.RED));
        }
        if (dev.hurtify.relicsaddon.power.DevicePower.hasMana(role())) {
            lines.add(Component.translatable(energy.manaOn() ? "tooltip.relics_addon.battery_mana" : "tooltip.relics_addon.battery_mana_off",
                    energy.mana(), dev.hurtify.relicsaddon.power.DevicePower.capacity(stack)).withStyle(ChatFormatting.BLUE));
        }
        if (role().isHive()) {
            var state = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT);
            long now = context.level() == null ? 0 : context.level().getGameTime();
            lines.add(Component.translatable("tooltip.relics_addon.hive", Component.translatable("tooltip.relics_addon.state." + (state.enabled() ? "enabled" : "disabled")), state.readyCount(now), state.units().size()).withStyle(style()));
            return;
        }
        ShieldStackState state = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        lines.add(Component.translatable("tooltip.relics_addon.shield.autonomous", Component.translatable("tooltip.relics_addon.state." + (state.enabled() ? "enabled" : "disabled")), state.livingCells(), ShieldTopology.CELL_COUNT, state.totalIntegrity(), ShieldParameters.totalCapacity(null, stack)).withStyle(style()));
        lines.add(Component.translatable("tooltip.relics_addon.shield.buffer", state.sharedBuffer(), ShieldParameters.capacity(null, stack)).withStyle(style()));
        float strike = ShieldParameters.strikeDamage(stack);
        if (strike > 0) {
            String kind = role() == RelicRole.RF_SHIELD ? "rf" : role() == RelicRole.MANA_SHIELD ? "mana" : "twins";
            lines.add(Component.translatable("tooltip.relics_addon.shield.strike", String.format(java.util.Locale.ROOT, "%.1f", strike),
                    Component.translatable("tooltip.relics_addon.strike." + kind)).withStyle(style()));
        }
    }
    private int integrity(ItemStack stack) { return stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT).totalIntegrity(); }
    private ChatFormatting style() { return switch (role()) {
        case RF_SHIELD, RF_DRONE, RF_HIVE -> ChatFormatting.AQUA;
        case MANA_SHIELD, MANA_DRONE, MANA_HIVE -> ChatFormatting.GREEN;
        case TWINS_SHIELD, TWINS_DRONE, TWINS_HIVE -> ChatFormatting.LIGHT_PURPLE;
    }; }
    public static final class RfShield extends AutonomousRelicItem { public RfShield(Properties p) { super(p); } @Override public RelicRole role() { return RelicRole.RF_SHIELD; } }
    public static final class ManaShield extends AutonomousRelicItem { public ManaShield(Properties p) { super(p); } @Override public RelicRole role() { return RelicRole.MANA_SHIELD; } }
    public static final class TwinsShield extends AutonomousRelicItem { public TwinsShield(Properties p) { super(p); } @Override public RelicRole role() { return RelicRole.TWINS_SHIELD; } }
}
