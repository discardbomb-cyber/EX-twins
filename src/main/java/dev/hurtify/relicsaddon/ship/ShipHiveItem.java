package dev.hurtify.relicsaddon.ship;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A ship hive as an item: what its kind does, and how it is set up. */
public final class ShipHiveItem extends BlockItem {
    private final ShipHiveKind kind;

    public ShipHiveItem(ShipHiveBlock block, Properties properties) {
        super(block, properties);
        kind = block.kind();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("block.relics_addon." + kind.id + ".desc").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("ship.relics_addon.tooltip.setup").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("ship.relics_addon.tooltip.energy", kind.capacity / 1000).withStyle(ChatFormatting.DARK_GRAY));
    }
}
