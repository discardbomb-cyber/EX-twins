package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.DeviceItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The block item of a ship generator or dock. Its stack carries the same device components as a
 * worn relic (progression, batteries, instance id) plus {@link ShipDeviceState}, so a picked-up
 * block keeps its level and charge and a fresh one comes fully charged.
 */
public final class ShipDeviceItem extends BlockItem implements DeviceItem {
    private final RelicRole role;

    public ShipDeviceItem(ShipDeviceBlock block, Properties properties) {
        super(block, properties);
        this.role = block.role();
    }

    @Override public RelicRole role() { return role; }
    public ShipFamily family() { return ShipFamily.of(role); }

    public static void ensureState(ItemStack stack) {
        if (!(stack.getItem() instanceof ShipDeviceItem)) return;
        if (!stack.has(ModDataComponents.INSTANCE_ID.get())) stack.set(ModDataComponents.INSTANCE_ID.get(), UUID.randomUUID().toString());
        if (!stack.has(ModDataComponents.DEVICE_PROGRESSION.get())) stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT);
        if (!stack.has(ModDataComponents.DEVICE_ENERGY.get())) stack.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(stack));
        if (!stack.has(ModDataComponents.SHIP_DEVICE_STATE.get())) stack.set(ModDataComponents.SHIP_DEVICE_STATE.get(), ShipDeviceState.DEFAULT);
    }

    @Override public void verifyComponentsAfterLoad(ItemStack stack) {
        super.verifyComponentsAfterLoad(stack);
        ensureState(stack);
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        lines.add(Component.translatable("item.relics_addon." + role.itemId() + ".desc").withStyle(ChatFormatting.GRAY));
        DeviceProgression progression = stack.getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT);
        lines.add(Component.translatable("tooltip.relics_addon.device_level", progression.level(), DeviceProgression.MAX_LEVEL, progression.points()).withStyle(family().style));
        var energy = DevicePower.energy(stack);
        if (DevicePower.hasRf(role)) {
            lines.add(Component.translatable(energy.rfOn() ? "tooltip.relics_addon.battery_rf" : "tooltip.relics_addon.battery_rf_off",
                    energy.rf(), DevicePower.feCapacity(stack)).withStyle(ChatFormatting.RED));
        }
        if (DevicePower.hasMana(role)) {
            lines.add(Component.translatable(energy.manaOn() ? "tooltip.relics_addon.battery_mana" : "tooltip.relics_addon.battery_mana_off",
                    energy.mana(), DevicePower.capacity(stack)).withStyle(ChatFormatting.BLUE));
        }
        if (role.isDroneDock()) {
            ShipDeviceState state = stack.getOrDefault(ModDataComponents.SHIP_DEVICE_STATE.get(), ShipDeviceState.DEFAULT);
            lines.add(Component.translatable("tooltip.relics_addon.dock_drones", state.drones(), ShipDeviceBlockEntity.dockCapacity(progression.level())).withStyle(family().style));
        }
    }
}
