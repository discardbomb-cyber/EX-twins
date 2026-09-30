package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import dev.hurtify.relicsaddon.domain.shield.ShieldField;
import dev.hurtify.relicsaddon.domain.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.domain.shield.CellSelection;
import dev.hurtify.relicsaddon.domain.shield.DamageFacts;
import dev.hurtify.relicsaddon.domain.shield.DamagePassPolicy;
import dev.hurtify.relicsaddon.domain.shield.HitImmunity;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import dev.hurtify.relicsaddon.relic.ShieldUpgrades;
import net.minecraft.network.chat.Component;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Locale;

public final class ShieldController {
    /** Damage the field lets through: starvation, drowning, suffocation, the void and similar. Server config adjusts it, see {@link #passesField}. */
    public static final TagKey<DamageType> PASSES_SHIELD = TagKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(dev.hurtify.relicsaddon.RelicsAddon.MOD_ID, "shield_passes"));
    /** Absorbed hits are cancelled outright, so vanilla hurt-immunity never starts; the field keeps its own. */
    private static final Map<LivingEntity, HitImmunity> RECENT_HITS = new WeakHashMap<>();

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !victim.isAlive() || victim.isSpectator()
                || (victim instanceof Player player && !EquippedRelicSetResolver.isRealPlayer(player))
                || event.isCanceled() || !(event.getAmount() > 0) || !Float.isFinite(event.getAmount())
                || passesField(event.getSource())) {
            return;
        }
        // Select one coverage owner. Overlapping fields never spend twice on the same melee event.
        var owners = new java.util.ArrayList<Player>(victim.level().players());
        if (victim instanceof Player wearer && !owners.contains(wearer)) owners.add(wearer);
        owners.sort(java.util.Comparator.comparingDouble(owner -> owner == victim ? -1 : owner.distanceToSqr(victim)));
        for (Player owner : owners) {
            if (!EquippedRelicSetResolver.isRealPlayer(owner)) continue;
            ItemStack shield = EquippedRelicSetResolver.findFirstActive(owner, RelicRole.EQUIPMENT_SLOT, RelicRole.shields())
                    .orElse(ItemStack.EMPTY);
            if (shield.isEmpty() || !ShieldCoverage.covers(owner, shield, victim)) continue;
            // A field that covers everyone near it never shelters a stranger from its own side's blows,
            // such as its owner's sword or swarm hitting a mob that stands inside it.
            Entity attacker = event.getSource().getEntity();
            if (attacker != null && !ShieldCoverage.friendly(owner, victim) && ShieldCoverage.friendly(owner, attacker)) continue;
            if (event.getSource().getDirectEntity() instanceof Projectile projectile) {
            float prepaid = ShieldProjectileInterceptor.consumePaidDamage(projectile, owner, event.getAmount());
            if (prepaid > 0) {
                settle(event, victim, prepaid);
                return;
            }
            if (ShieldProjectileInterceptor.alreadyAbsorbed(projectile, owner)
                    || ShieldProjectileInterceptor.passedThrough(projectile, owner)) continue;
            }
            if (withinImmunity(event, victim)) return;
            if (tryShieldBlock(event, owner, shield)) return;
        }
    }

    /**
     * Damage no field takes. The server's pass list wins outright; its absorb list overrides the
     * {@code shield_passes} tag, except for shield strikes, which a field must never eat. Swarm blows
     * are not in the tag: another player's field stops them like any blow, and a field never shelters
     * a stranger from its own owner's swarm (see {@link #onIncomingDamage}).
     */
    public static boolean passesField(DamageSource source) {
        Holder<DamageType> type = source.typeHolder();
        return DamagePassPolicy.passesField(new DamageFacts() {
            @Override public boolean passListed() { return AddonConfig.PASSING_DAMAGE.matches(type); }
            @Override public boolean inPassTag() { return type.is(PASSES_SHIELD); }
            @Override public boolean absorbListed() { return AddonConfig.ABSORBED_DAMAGE.matches(type); }
            @Override public boolean strike() { return type.is(ShieldStrike.STRIKES); }
        });
    }

    /**
     * Vanilla ignores a hit landing within half a second of a stronger one. Because absorbed hits are
     * cancelled, that immunity never starts, so the field applies the same rule itself: repeated
     * fire, lava or cactus ticks do not drain it twenty times a second.
     */
    private static boolean withinImmunity(LivingIncomingDamageEvent event, LivingEntity victim) {
        HitImmunity last = RECENT_HITS.get(victim);
        long now = victim.level().getGameTime();
        if (last == null || !last.covers(now)) return false;
        if (event.getAmount() <= last.absorbed()) {
            event.setCanceled(true);
            return true;
        }
        event.setAmount(event.getAmount() - last.absorbed());
        return false;
    }

    /** Applies an absorbed amount: a fully absorbed hit is cancelled so it causes no knockback, flash or on-hit effects. */
    private static void settle(LivingIncomingDamageEvent event, LivingEntity victim, float absorbed) {
        var source = event.getSource();
        ShieldEffectGuard.recordHit(victim, source.getEntity() != null ? source.getEntity() : source.getDirectEntity(),
                absorbed, event.getAmount() - absorbed);
        HitImmunity last = RECENT_HITS.get(victim);
        long now = victim.level().getGameTime();
        RECENT_HITS.put(victim, HitImmunity.record(last, now, absorbed));
        if (absorbed >= event.getAmount()) event.setCanceled(true);
        else event.setAmount(event.getAmount() - absorbed);
    }

    private static boolean tryShieldBlock(LivingIncomingDamageEvent event, Player player, ItemStack shield) {
        ShieldStackState state = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        Vec3 sourcePosition = event.getSource().getSourcePosition();
        if (sourcePosition == null && event.getSource().getDirectEntity() != null) sourcePosition = event.getSource().getDirectEntity().position();
        Vec3 direction = sourcePosition == null ? Vec3.directionFromRotation(0, player.getYRot())
                : sourcePosition.subtract(player.position().add(0, ShieldField.CENTER_Y, 0));
        int cell = selectCell(player, direction);
        long now = player.level().getGameTime();
        ShieldStackState gathered = ShieldCellDefense.gather(state, cell, ShieldUpgrades.gathering(player, shield), now);
        if (gathered != state) shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), gathered);
        state = gathered;
        int cost = Math.clamp(Mth.ceil(event.getAmount()), 1, 10000);
        var damage = ShieldCellDefense.damage(state, cell, cost, ShieldUpgrades.sharing(player, shield), now);
        float absorbed = Math.min(event.getAmount(), damage.spent());
        if (absorbed <= 0) {
            return false;
        }
        settle(event, event.getEntity(), absorbed);
        ShieldStackState next = damage.apply(state, cell, absorbed, player.level().getGameTime());
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), next);
        ShieldImpact impact = ShieldImpact.of(McVectors.toDomain(direction), player.level().getGameTime(), cell, absorbed, state, next);
        if (sourcePosition != null && direction.length() < ShieldParameters.radius(player, shield)) impact = impact.atDistance(direction.length());
        shield.set(ModDataComponents.SHIELD_IMPACT.get(), impact);
        shield.set(ModDataComponents.SHIELD_IMPACTS.get(), shield.getOrDefault(ModDataComponents.SHIELD_IMPACTS.get(),
                dev.hurtify.relicsaddon.domain.shield.ShieldImpactHistory.EMPTY).append(impact));
        RelicSounds.shield((net.minecraft.server.level.ServerLevel) player.level(), player.position().add(0, ShieldField.CENTER_Y, 0)
                .add(McVectors.toMc(impact.normal()).scale(impact.distance() >= 0 ? impact.distance() : ShieldParameters.radius(player, shield))),
                ((AutonomousRelicItem) shield.getItem()).role(), impact.broken(), next.totalIntegrity() == 0);
        RelicRuntime.awardAbsorption(player, shield, absorbed);
        DevicePower.drain(player, shield, Mth.ceil(absorbed * DevicePower.ABSORB_PER_HP));
        player.displayClientMessage(Component.translatable("message.relics_addon.shield_blocked",
                formatDamage(absorbed), next.sharedBuffer(), ShieldParameters.capacity(player, shield),
                next.cellHp(cell), ShieldStackState.MAX_PANEL_INTEGRITY), true);
        return true;
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !EquippedRelicSetResolver.isRealPlayer(player)) {
            return;
        }
        ItemStack shield = EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields())
                .orElse(ItemStack.EMPTY);
        if (shield.isEmpty()) {
            return;
        }
        RelicRole role = ((AutonomousRelicItem) shield.getItem()).role();
        long now = player.level().getGameTime();
        ShieldStackState state = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        int capacity = ShieldParameters.capacity(player, shield);
        if (state.sharedBuffer() > capacity) {
            state = state.withCellsAndBuffer(state.cells(), capacity, state.moves(), state.gatherTime());
            shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), state);
        }
        int interval = role.repairInterval();
        if (now % interval != 0 || !state.needsRepair(capacity)) {
            return;
        }
        ShieldStackState repaired = ShieldCellDefense.repair(state, now, capacity,
                ShieldUpgrades.quietTicks(player, shield), ShieldUpgrades.repairSteps(player, shield));
        int restored = repaired.totalIntegrity() - state.totalIntegrity();
        if (restored > 0 && !DevicePower.drain(player, shield, restored * DevicePower.REPAIR_PER_HP)) return;
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), repaired);
    }

    public static float reduction(float damage, double ratio, float capacity) {
        if (!Float.isFinite(damage) || !Double.isFinite(ratio) || !Float.isFinite(capacity) || damage <= 0 || capacity <= 0) {
            return 0;
        }
        return (float) Math.min(damage, Math.min(damage * Math.clamp(ratio, 0, 1), capacity));
    }

    public static int selectPanel(Player player, DamageSource source) {
        Vec3 position = source.getSourcePosition();
        if (position == null && source.getEntity() != null) {
            position = source.getEntity().position();
        }
        if (position == null) {
            return ShieldStackState.PANEL_FRONT;
        }
        return selectPanel(player, position.subtract(player.position()));
    }

    public static int selectPanel(Player player, Vec3 incoming) {
        Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
        double angle = Math.atan2(incoming.x * -forward.z + incoming.z * forward.x,
                incoming.x * forward.x + incoming.z * forward.z);
        // These exact quadrant boundaries match ShieldHexMesh.panelFor(). Pitch must not change sectors.
        if (angle >= -Math.PI / 4 && angle < Math.PI / 4) {
            return ShieldStackState.PANEL_FRONT;
        }
        if (angle >= Math.PI / 4 && angle < 3 * Math.PI / 4) {
            return ShieldStackState.PANEL_RIGHT;
        }
        if (angle >= -3 * Math.PI / 4 && angle < -Math.PI / 4) {
            return ShieldStackState.PANEL_LEFT;
        }
        return ShieldStackState.PANEL_BACK;
    }

    public static int selectCell(Player player, Vec3 incoming) {
        return CellSelection.select(player.getYRot(), McVectors.toDomain(incoming));
    }

    public static String formatDamage(float damage) {
        return String.format(Locale.ROOT, "%.1f", damage);
    }

    private ShieldController() {
    }
}
