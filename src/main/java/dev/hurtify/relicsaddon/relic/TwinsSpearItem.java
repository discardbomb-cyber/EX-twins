package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModEntities;
import dev.hurtify.relicsaddon.server.NoctisBeam;
import dev.hurtify.relicsaddon.server.NoctisCombat;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
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
 * The Twins spear, the Judgment Spear of Lux Noctis: a long glass spear for close work, whose tip runs with
 * liquid violet fire, and which throws its own reflection. In hand:
 * <ul>
 *   <li>a thrust reaches a block and a half further and collapses the air where it lands;</li>
 *   <li>a tap of use is the light cut: a disc spun out level all round ({@link NoctisCombat#lightCut});</li>
 *   <li>a hold of {@link #DRAW} ticks throws the reflection ({@link TwinsSpearEntity}): where it strikes it pins and
 *   opens its event horizon; thrown steeply up it opens the Black Halo instead;</li>
 *   <li>sneaking, a hold of {@link #BEAM_CHARGE} ticks with a working Twins hive fires the all-piercing quantum
 *   ({@link NoctisBeam}); a sneaking tap while the reflection is pinned pulls its wielder there.</li>
 * </ul>
 * The spear itself never leaves the hand.
 */
public class TwinsSpearItem extends Item {
    /** How long it must be drawn back before it throws, and charged before it fires, in ticks. */
    public static final int DRAW = 10, BEAM_CHARGE = 30;
    /** The wait after the reflection comes home before it can be thrown again, in ticks. */
    public static final int COOLDOWN = 40;
    /** A throw this steep (degrees up) opens the Black Halo. */
    public static final float HALO_PITCH = -45;
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
        if (!(entity instanceof ServerPlayer player) || !(level instanceof ServerLevel server)) return;
        int held = getUseDuration(stack, entity) - timeLeft;
        if (player.isShiftKeyDown()) {
            if (held < BEAM_CHARGE) return;
            ItemStack hive = NoctisCore.hive(player);
            if (hive == null || !NoctisBeam.fire(server, player, stack, hive)) {
                player.displayClientMessage(Component.translatable(hive == null ? "message.relics_addon.noctis.no_hive" : "message.relics_addon.noctis.short")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            }
            return;
        }
        if (held >= DRAW) {
            throwFrom(server, player, stack);
            return;
        }
        if (!NoctisCombat.lightCut(server, player, stack)) {
            player.displayClientMessage(Component.translatable("message.relics_addon.noctis.short").withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
    }

    /** The charge of the beam as the spear is held, 0 to 1 (0 unless sneaking); for the client's overcharge. */
    public static double beamCharge(LivingEntity holder) {
        if (!holder.isUsingItem() || !holder.isShiftKeyDown() || !(holder.getUseItem().getItem() instanceof TwinsSpearItem)) return 0;
        return Math.min(1, holder.getTicksUsingItem() / (double) BEAM_CHARGE);
    }

    /**
     * Throws the spear's reflection from where {@code player} looks (steeply up: the Black Halo), unless one is out
     * already; returns it.
     */
    public static TwinsSpearEntity throwFrom(ServerLevel level, Player player, ItemStack weapon) {
        if (TwinsSpearEntity.of(player) != null) return null;
        boolean halo = player.getXRot() < HALO_PITCH;
        if (halo && !NoctisCore.spend(weapon, TwinsSpearEntity.HALO_COST)) {
            player.displayClientMessage(Component.translatable("message.relics_addon.noctis.short").withStyle(ChatFormatting.LIGHT_PURPLE), true);
            return null;
        }
        TwinsSpearEntity spear = new TwinsSpearEntity(ModEntities.TWINS_SPEAR.get(), level);
        spear.setOwner(player);
        spear.setPos(player.getEyePosition().subtract(0, .15, 0));
        spear.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, 2.6F, .5F);
        if (halo) spear.halo();
        level.addFreshEntity(spear);
        RelicSounds.spear(level, spear.position(), RelicSounds.Spear.THROW);
        return spear;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.relics_addon.noctis.core", NoctisCore.charge(stack)).withStyle(ChatFormatting.GRAY));
        for (String key : List.of("cut", "throw", "halo", "beam", "pull")) {
            lines.add(Component.translatable("item.relics_addon.twins_spear.tooltip." + key).withStyle(ChatFormatting.GRAY));
        }
    }
}
