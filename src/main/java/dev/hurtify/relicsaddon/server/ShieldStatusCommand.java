package dev.hurtify.relicsaddon.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldSettings;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import top.theillusivec4.curios.api.CuriosApi;

/** Self-service shield diagnostics and persistent settings; neither command changes shield HP or progress. */
public final class ShieldStatusCommand {
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("relics_addon")
                .then(Commands.literal("shield_status").executes(context -> status(context.getSource().getPlayerOrException(), context)))
                .then(Commands.literal("shield_radius")
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(2.0, 24.0))
                                .executes(context -> radius(context.getSource().getPlayerOrException(),
                                        DoubleArgumentType.getDouble(context, "radius"), context))))
                .then(Commands.literal("shield_coverage")
                        .then(Commands.literal("owner").executes(context -> coverage(context.getSource().getPlayerOrException(), "owner", context)))
                        .then(Commands.literal("allies").executes(context -> coverage(context.getSource().getPlayerOrException(), "allies", context)))
                        .then(Commands.literal("all").executes(context -> coverage(context.getSource().getPlayerOrException(), "all", context)))));
    }

    private static int status(Player player, com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
        int found = 0;
        ItemStack active = EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        if (inventory != null) {
            var handler = inventory.getStacksHandler(RelicRole.EQUIPMENT_SLOT);
            if (handler.isPresent()) for (int slot = 0; slot < handler.get().getSlots(); slot++) {
                ItemStack stack = handler.get().getStacks().getStackInSlot(slot);
                if (!shield(stack)) continue;
                ShieldStackState state = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
                String reason = !inventory.isSlotActive(RelicRole.EQUIPMENT_SLOT, slot) ? "inactive_slot" : !state.enabled() ? "disabled"
                        : active != stack ? "priority"
                        : state.totalIntegrity() == 0 ? "broken" : "active";
                int index = slot;
                context.getSource().sendSuccess(() -> Component.translatable("message.relics_addon.shield_status",
                        stack.getHoverName(), index + 1, Component.translatable("message.relics_addon.status." + reason),
                        state.totalIntegrity(), ShieldParameters.totalCapacity(player, stack), state.livingCells(),
                        dev.hurtify.relicsaddon.domain.shield.ShieldTopology.CELL_COUNT, state.sharedBuffer(), ShieldParameters.capacity(player, stack)), false);
                found++;
            }
        }
        if (found == 0) context.getSource().sendSuccess(() -> Component.translatable("message.relics_addon.status.no_shield"), false);
        return found;
    }

    private static int radius(Player player, double value, com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
        ItemStack stack = selected(player);
        if (stack.isEmpty()) return missing(context);
        double maximum = ShieldParameters.maxRadius(player, stack);
        if (!Double.isFinite(value) || value < 2 || value > maximum) {
            context.getSource().sendFailure(Component.translatable("message.relics_addon.shield_radius.invalid", value, maximum));
            return 0;
        }
        ShieldSettings old = ShieldParameters.settings(stack);
        stack.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(value, old.coverage()));
        context.getSource().sendSuccess(() -> Component.translatable("message.relics_addon.shield_radius.changed", value, maximum), false);
        return 1;
    }

    private static int coverage(Player player, String value, com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
        ItemStack stack = selected(player);
        if (stack.isEmpty()) return missing(context);
        ShieldSettings old = ShieldParameters.settings(stack);
        stack.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(old.radius(), value));
        context.getSource().sendSuccess(() -> Component.translatable("message.relics_addon.shield_coverage.changed",
                Component.translatable("message.relics_addon.coverage." + value)), false);
        return 1;
    }

    private static ItemStack selected(Player player) {
        ItemStack active = EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
        if (!active.isEmpty()) return active;
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        if (inventory != null) {
            var handler = inventory.getStacksHandler(RelicRole.EQUIPMENT_SLOT);
            if (handler.isPresent()) for (int slot = 0; slot < handler.get().getSlots(); slot++) {
                ItemStack stack = handler.get().getStacks().getStackInSlot(slot);
                if (shield(stack)) return stack;
            }
        }
        ItemStack main = player.getMainHandItem();
        if (shield(main)) return main;
        ItemStack off = player.getOffhandItem();
        return shield(off) ? off : ItemStack.EMPTY;
    }

    private static boolean shield(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item && item.role().isShield();
    }

    private static int missing(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
        context.getSource().sendFailure(Component.translatable("message.relics_addon.status.no_shield"));
        return 0;
    }

    private ShieldStatusCommand() { }
}
