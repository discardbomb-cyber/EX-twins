package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.NoctisWormhole;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

/**
 * The Eclipse scythe. Folded, it is a hilt and nothing more (its magic hidden); a use opens it. Open, it is a
 * heavy, slow blade that reaches a block further and takes in its whole arc (see {@code NoctisCombat}), with a
 * jet-black aura after it. Sneaking, a tap turns on its Overdrive for a while (its lines racing, its blade drawn out:
 * further and harder); a long hold with a working Twins hive plants it for the wormhole ({@link NoctisWormhole}).
 */
public class EclipseScytheItem extends Item {
    public static final int OVERDRIVE_TICKS = 100, OVERDRIVE_COST = 30, PLANT_HOLD = 40, FOLD_TICKS = 10;
    public static final float DAMAGE = 10, OVERDRIVE_DAMAGE = 15;
    private static final ResourceLocation REACH = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "scythe_reach");

    public EclipseScytheItem(Properties properties) {
        super(properties.attributes(ItemAttributeModifiers.EMPTY));
    }

    public static boolean open(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SCYTHE_OPEN.get(), false);
    }

    public static boolean overdriven(ItemStack stack, long now) {
        return stack.getOrDefault(ModDataComponents.SCYTHE_OVERDRIVE.get(), 0L) > now;
    }

    /** Opens or folds the scythe and sets the blade's weight accordingly. */
    public static void setOpen(ItemStack stack, boolean open, boolean overdrive) {
        stack.set(ModDataComponents.SCYTHE_OPEN.get(), open);
        if (!open) {
            stack.remove(DataComponents.ATTRIBUTE_MODIFIERS);
            stack.remove(ModDataComponents.SCYTHE_OVERDRIVE.get());
            return;
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, overdrive ? OVERDRIVE_DAMAGE : DAMAGE, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, -3.1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(REACH, overdrive ? 3 : 1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .build());
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72_000;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!open(stack)) return InteractionResultHolder.fail(stack);
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        if (level instanceof ServerLevel server) {
            boolean opening = !open(stack);
            setOpen(stack, opening, false);
            player.getCooldowns().addCooldown(this, FOLD_TICKS);
            RelicSounds.spear(server, player.position(), opening ? RelicSounds.Spear.SCYTHE_OPEN : RelicSounds.Spear.SCYTHE_CLOSE);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Sneaking: a tap turns the Overdrive on, a long hold plants the scythe. */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof ServerPlayer player) || !(level instanceof ServerLevel server) || !open(stack)) return;
        int held = getUseDuration(stack, entity) - timeLeft;
        if (held >= PLANT_HOLD) {
            ItemStack hive = NoctisCore.hive(player);
            if (hive == null || !NoctisWormhole.plant(server, player, stack, hive)) {
                player.displayClientMessage(Component.translatable(hive == null ? "message.relics_addon.noctis.no_hive" : "message.relics_addon.noctis.short")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            }
            return;
        }
        if (overdriven(stack, server.getGameTime())) return;
        if (!NoctisCore.spend(stack, OVERDRIVE_COST)) {
            player.displayClientMessage(Component.translatable("message.relics_addon.noctis.short").withStyle(ChatFormatting.LIGHT_PURPLE), true);
            return;
        }
        stack.set(ModDataComponents.SCYTHE_OVERDRIVE.get(), server.getGameTime() + OVERDRIVE_TICKS);
        setOpen(stack, true, true);
        RelicSounds.spear(server, player.position(), RelicSounds.Spear.OVERDRIVE);
    }

    /** The Overdrive runs out: the blade draws back in. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !open(stack)) return;
        long until = stack.getOrDefault(ModDataComponents.SCYTHE_OVERDRIVE.get(), 0L);
        if (until != 0 && until <= level.getGameTime()) {
            stack.remove(ModDataComponents.SCYTHE_OVERDRIVE.get());
            setOpen(stack, true, false);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable(open(stack) ? "item.relics_addon.eclipse_scythe.open" : "item.relics_addon.eclipse_scythe.folded").withStyle(ChatFormatting.DARK_PURPLE));
        lines.add(Component.translatable("item.relics_addon.noctis.core", NoctisCore.charge(stack)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.relics_addon.eclipse_scythe.tooltip.fold").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.relics_addon.eclipse_scythe.tooltip.overdrive").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.relics_addon.eclipse_scythe.tooltip.wormhole").withStyle(ChatFormatting.GRAY));
    }
}
