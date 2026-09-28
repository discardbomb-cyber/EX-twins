package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import it.hurts.sskirillss.relics.api.relics.RelicTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.AbilitiesTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.AbilityTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.ExperienceSourcesTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationTemplate;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationType;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationContext;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationStage;
import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import it.hurts.sskirillss.relics.init.RelicsRelicContainers;
import it.hurts.sskirillss.relics.init.RelicsScalingModels;
import it.hurts.sskirillss.relics.items.relics.base.WearableRelicItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import top.theillusivec4.curios.api.SlotContext;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;

import java.util.List;
import java.util.UUID;

public abstract class AutonomousRelicItem extends WearableRelicItem {

    protected AutonomousRelicItem(Properties properties) {
        super(properties);
    }

    public abstract RelicRole role();

    @Override
    public boolean canEquip(SlotContext context, ItemStack stack) {
        return super.canEquip(context, stack);
    }

    @Override
    public boolean canEquipFromUse(SlotContext context, ItemStack stack) {
        return super.canEquipFromUse(context, stack);
    }

    @Override
    public String getConfigRoute() {
        return RelicsAddon.MOD_ID;
    }

    @Override
    public RelicTemplate constructDefaultRelicTemplate() {
        var builder = AbilityTemplate.builder(role().abilityId())
                .initialMaxLevel(role().isShield() ? 10 : 5)
                .maxLevelRankModifier(0)
                .requiredLevel(0)
                .requiredRank(0)
                .requiredPoints(1)
                .active(AbilityActivationTemplate.builder(AbilityActivationType.TOGGLEABLE)
                        .container(RelicsRelicContainers.CURIOS.get())
                        .build())
                .stat(AbilityStatTemplate.builder("absorption")
                        .initialValue(1, 1)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), 1)
                        .thresholdValue(role().isShield() ? 1 : 0.05, role().isShield() ? 1 : 0.90)
                        .formatValue(value -> Math.round(value * 100))
                        .build())
                .experienceSources(ExperienceSourcesTemplate.builder()
                        .source(RelicProgression.combatSource(role()))
                        .build());
        if (role().isShield()) builder.research(ShieldResearch.protection(role()))
                .stat(AbilityStatTemplate.builder("buffer_capacity").initialValue(504, 504)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), 5000).thresholdValue(504, 5000)
                        .formatValue(Math::round).build())
                .stat(AbilityStatTemplate.builder("radius").initialValue(2, 2)
                        .targetValue(RelicsScalingModels.ADDITIVE.get(), 12).thresholdValue(2, 24)
                        .formatValue(value -> Math.round(value * 10) / 10.0).build());
        AbilityTemplate ability = builder.build();
        var abilities = AbilitiesTemplate.builder().ability(ability);
        if (role().isShield()) {
            abilities.ability(ShieldUpgrades.distribution(role()));
            abilities.ability(ShieldUpgrades.restoration(role()));
            if (role() != RelicRole.TWINS_SHIELD) abilities.ability(ShieldUpgrades.gather(role()));
            else abilities.ability(ShieldUpgrades.stabilization(role()));
        }

        return RelicTemplate.builder()
                .abilities(abilities.build())
                .leveling(RelicProgression.levelingTemplate())
                .build();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(held);
        }

        if (!EquippedRelicSetResolver.isRealPlayer(player)) {
            return InteractionResultHolder.pass(held);
        }
        boolean enabled = !RelicRuntime.enabled(held);
        if (enabled && !RelicRuntime.ability(player, held).canPlayerUse(player)) {
            player.displayClientMessage(Component.translatable("message.relics_addon.ability_locked"), true);
            return InteractionResultHolder.fail(held);
        }
        RelicRuntime.setEnabled(player, held, enabled);
        notifyToggle(player, enabled);
        return InteractionResultHolder.consume(held);
    }

    @Override
    public void activateAbility(AbilityActivationContext context) {
        if (context.player().level().isClientSide() || !EquippedRelicSetResolver.isRealPlayer(context.player())
                || context.stage() == AbilityActivationStage.TICK) {
            return;
        }
        boolean enabled = context.stage() == AbilityActivationStage.START;
        if (RelicRuntime.enabled(context.stack()) != enabled) {
            RelicRuntime.setEnabled(context.player(), context.stack(), enabled);
            notifyToggle(context.player(), enabled);
        }
    }

    @Override
    public void curioTick(SlotContext context, ItemStack stack) {
        if (context.cosmetic() || !(context.entity() instanceof Player player) || player.level().isClientSide()
                || !EquippedRelicSetResolver.isRealPlayer(player)) {
            return;
        }
        ensureIdentity(stack);
        var ability = RelicRuntime.ability(player, stack);
        boolean ticking = RelicRuntime.canOperate(player, stack);
        if (ability.isActivationTicking() != ticking) {
            ability.setActivationTicking(ticking);
        }
    }

    private void notifyToggle(Player player, boolean enabled) {
        player.displayClientMessage(Component.translatable("message.relics_addon."
                + (role().isHive() ? "hive" : role().isShield() ? "shield" : "drone") + (enabled ? "_enabled" : "_disabled")), true);
    }

    private static void ensureIdentity(ItemStack stack) {
        if (!stack.has(ModDataComponents.INSTANCE_ID.get())) {
            stack.set(ModDataComponents.INSTANCE_ID.get(), UUID.randomUUID().toString());
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!level.isClientSide()) {
            ensureIdentity(stack);
        }
    }

    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        super.verifyComponentsAfterLoad(stack);
        if (role().isHive()) {
            stack.set(ModDataComponents.HIVE_STACK_STATE.get(), stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(),
                    dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT));
        } else if (role().isShield()) {
            stack.set(
                    ModDataComponents.SHIELD_STACK_STATE.get(),
                    stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT));
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return role().isShield() && totalShieldIntegrity(stack) < ShieldParameters.totalCapacity(null, stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        if (!role().isShield()) {
            return 0;
        }

        return Math.clamp(Math.round(13.0F * totalShieldIntegrity(stack) / ShieldParameters.totalCapacity(null, stack)), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        double hp = totalShieldIntegrity(stack) / (double) ShieldParameters.totalCapacity(null, stack);
        return hp <= .25 ? 0xEF4242 : hp <= .45 ? 0xF08232 : hp <= .65 ? 0xEBD34C : role().color();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        if (role().isHive()) {
            var state = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT);
            long now = context.level() == null ? 0 : context.level().getGameTime();
            lines.add(Component.translatable("tooltip.relics_addon.hive", Component.translatable(
                    "tooltip.relics_addon.state." + (state.enabled() ? "enabled" : "disabled")),
                    state.readyCount(now), state.units().size()).withStyle(style()));
            var type = dev.hurtify.relicsaddon.drone.HiveType.of(role());
            int maxHealth = (int) Math.round(RelicRuntime.stat(null, stack, "drone_health", type.initialHealth, 1, 1000));
            lines.add(Component.translatable("tooltip.relics_addon.hive.health", maxHealth).withStyle(style()));
            return;
        }
        if (role().isShield()) {
            ShieldStackState state = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            Component enabled = state.enabled()
                    ? Component.translatable("tooltip.relics_addon.state.enabled")
                    : Component.translatable("tooltip.relics_addon.state.disabled");
            lines.add(Component.translatable(
                    "tooltip.relics_addon.shield.autonomous",
                    enabled,
                    state.livingCells(),
                    dev.hurtify.relicsaddon.shield.ShieldTopology.CELL_COUNT,
                    state.totalIntegrity(),
                    ShieldParameters.totalCapacity(null, stack)
            ).withStyle(style()));
            lines.add(Component.translatable("tooltip.relics_addon.shield.buffer", state.sharedBuffer(), ShieldParameters.capacity(null, stack)).withStyle(style()));
            lines.add(Component.translatable("tooltip.relics_addon.shield.radius", ShieldParameters.radius(null, stack),
                    ShieldParameters.maxRadius(null, stack)).withStyle(style()));
            lines.add(Component.translatable("tooltip.relics_addon.shield.coverage", Component.translatable(
                    "message.relics_addon.coverage." + ShieldParameters.settings(stack).coverage())).withStyle(style()));
            return;
        }

    }

    private int totalShieldIntegrity(ItemStack stack) {
        ShieldStackState state = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        return state.totalIntegrity();
    }

    private ChatFormatting style() {
        return switch (role()) {
            case RF_SHIELD, RF_DRONE, RF_HIVE -> ChatFormatting.AQUA;
            case MANA_SHIELD, MANA_DRONE, MANA_HIVE -> ChatFormatting.GREEN;
            case TWINS_SHIELD, TWINS_DRONE, TWINS_HIVE -> ChatFormatting.LIGHT_PURPLE;
        };
    }

    public static final class RfShield extends AutonomousRelicItem {
        public RfShield(Properties properties) {
            super(properties);
        }

        @Override
        public RelicRole role() {
            return RelicRole.RF_SHIELD;
        }
    }

    public static final class ManaShield extends AutonomousRelicItem {
        public ManaShield(Properties properties) {
            super(properties);
        }

        @Override
        public RelicRole role() {
            return RelicRole.MANA_SHIELD;
        }
    }

    public static final class TwinsShield extends AutonomousRelicItem {
        public TwinsShield(Properties properties) {
            super(properties);
        }

        @Override
        public RelicRole role() {
            return RelicRole.TWINS_SHIELD;
        }
    }

}
