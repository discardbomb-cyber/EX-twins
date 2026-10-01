package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModEntities;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

/**
 * The Twins spear: a long glass spear for close work that throws its own reflection. The reflection
 * ({@link TwinsSpearEntity}) pins whatever it strikes for a while, the worn Twins hive's drones circle and strike
 * the pinned creature, and then it flies home; the spear itself never leaves the hand. Sneaking with it while its
 * reflection is pinned somewhere pulls its wielder there.
 */
public class TwinsSpearItem extends Item {
    /** How long it must be drawn back before it throws, in ticks. */
    public static final int DRAW = 10;
    /** The wait after the reflection comes home before it can be thrown again, in ticks. */
    public static final int COOLDOWN = 40;
    private static final ResourceLocation REACH = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "twins_spear_reach");

    public TwinsSpearItem(Properties properties) {
        super(properties.attributes(attributes()));
    }

    /** A thrust as hard as a heavy axe, a little slower than a sword, from a block and a half further away. */
    private static ItemAttributeModifiers attributes() {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, 8, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, -2.9, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(REACH, 1.5, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .build();
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
        TwinsSpearEntity thrown = TwinsSpearEntity.of(player);
        if (thrown != null) {
            // Its reflection is out: a sneak pulls the wielder to where it is pinned, and nothing else can be done.
            if (player.isShiftKeyDown() && thrown.pull()) return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player) || getUseDuration(stack, entity) - timeLeft < DRAW) return;
        if (level instanceof ServerLevel server) throwFrom(server, player);
    }

    /** Throws the spear's reflection from where {@code player} looks, unless one is out already; returns it. */
    public static TwinsSpearEntity throwFrom(ServerLevel level, Player player) {
        if (TwinsSpearEntity.of(player) != null) return null;
        TwinsSpearEntity spear = new TwinsSpearEntity(ModEntities.TWINS_SPEAR.get(), level);
        spear.setOwner(player);
        spear.setPos(player.getEyePosition().subtract(0, .15, 0));
        spear.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, 2.6F, .5F);
        level.addFreshEntity(spear);
        RelicSounds.spear(level, spear.position(), RelicSounds.Spear.THROW);
        return spear;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
        lines.add(net.minecraft.network.chat.Component.translatable("item.relics_addon.twins_spear.tooltip.throw").withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(net.minecraft.network.chat.Component.translatable("item.relics_addon.twins_spear.tooltip.pull").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
