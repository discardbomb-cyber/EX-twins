package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Opens a device's console when the player hovers it in any inventory screen (including the
 * Curios screen) and holds Shift. The hold is short but deliberate so shift-clicking a device
 * still moves it: any click cancels the hold until Shift is released.
 */
public final class ShiftHoverOpener {
    private static final int HOLD_TICKS = 10;
    private static ItemStack hovered = ItemStack.EMPTY;
    private static int held;
    private static boolean suppressed;

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Screen.hasShiftDown()) suppressed = false;
        if (suppressed || minecraft.player == null || !(minecraft.screen instanceof AbstractContainerScreen<?> screen)
                || !Screen.hasShiftDown() || !minecraft.player.containerMenu.getCarried().isEmpty()) {
            reset();
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        ItemStack stack = slot == null ? ItemStack.EMPTY : slot.getItem();
        if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().available()) {
            reset();
            return;
        }
        if (stack != hovered) {
            hovered = stack;
            held = 0;
        }
        if (++held >= HOLD_TICKS) {
            DeviceTargets.Target target = DeviceTargets.locate(minecraft.player, stack);
            if (target != null) DeviceTargets.open(target);
            suppressed = true;
            reset();
        }
    }

    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (Screen.hasShiftDown()) {
            suppressed = true;
            reset();
        }
    }

    /** Hint line with a fill bar while the hold is in progress. */
    public static void onTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof AutonomousRelicItem item) || !item.role().available()
                || !(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>)) return;
        if (event.getItemStack() == hovered && held > 0) {
            int filled = Math.round(10F * held / HOLD_TICKS);
            event.getToolTip().add(Component.literal("▮".repeat(filled)).withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("▯".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY)));
        } else {
            event.getToolTip().add(Component.translatable("tooltip.relics_addon.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void reset() {
        hovered = ItemStack.EMPTY;
        held = 0;
    }

    private ShiftHoverOpener() {
    }
}
