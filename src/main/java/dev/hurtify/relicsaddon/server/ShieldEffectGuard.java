package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.RelicRole;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * A field also stops what an attack leaves behind. A harmful effect that an attacker puts on someone
 * under a working field (a wither skeleton's wither, a cave spider's poison, a witch's potion, a
 * stray's slowness, a shulker's levitation) is cut off entirely, or, when the hit that carried it only
 * partly got through the field, trimmed to the share of damage that got through. The wearer's own
 * potions and effects of the world (a wither rose, a beacon) are left alone, and so are the effects
 * the server lists in {@code shield.keptEffects}. Cutting costs charge.
 */
public final class ShieldEffectGuard {
    /** Battery points per second of effect per level of its strength. */
    private static final int COST_PER_SECOND = 2;
    private static final Map<LivingEntity, Hit> LAST_HIT = new WeakHashMap<>();
    private static boolean reapplying;

    private record Hit(long tick, Entity attacker, float absorbed, float passed) { }

    /** Records how much of a hit the field stopped, so an effect that rides on it can be trimmed to match. */
    public static void recordHit(LivingEntity victim, Entity attacker, float absorbed, float passed) {
        if (attacker != null) LAST_HIT.put(victim, new Hit(victim.level().getGameTime(), attacker, absorbed, Math.max(0, passed)));
    }

    public static void onEffectApplicable(MobEffectEvent.Applicable event) {
        LivingEntity victim = event.getEntity();
        MobEffectInstance effect = event.getEffectInstance();
        if (reapplying || victim.level().isClientSide() || effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL
                || AddonConfig.KEPT_EFFECTS.matches(effect.getEffect())) return;
        Entity attacker = attacker(event.getEffectSource());
        if (attacker == null || attacker == victim) return;
        Coverage cover = coverage(victim, attacker);
        if (cover == null) return;
        Hit hit = LAST_HIT.get(victim);
        boolean trim = hit != null && hit.tick() == victim.level().getGameTime() && attacker(hit.attacker()) == attacker && hit.passed() > 0;
        int kept = trim ? (int) Math.floor(effect.getDuration() * hit.passed() / Math.max(1e-3F, hit.absorbed() + hit.passed())) : 0;
        int removed = effect.isInfiniteDuration() ? 20 * 60 : effect.getDuration() - kept;
        int cost = Math.max(1, (int) Math.ceil(removed / 20.0 * (effect.getAmplifier() + 1) * COST_PER_SECOND));
        if (!DevicePower.drain(cover.owner(), cover.shield(), cost)) return;
        event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        if (kept > 0) {
            reapplying = true;
            try {
                victim.addEffect(new MobEffectInstance(effect.getEffect(), kept, effect.getAmplifier(), effect.isAmbient(),
                        effect.isVisible(), effect.showIcon()), event.getEffectSource());
            } finally {
                reapplying = false;
            }
        }
        if (victim instanceof Player player) {
            player.displayClientMessage(Component.translatable(kept > 0 ? "message.relics_addon.shield_effect_trimmed" : "message.relics_addon.shield_effect_cut",
                    effect.getEffect().value().getDisplayName()), true);
        }
    }

    /** The living creature behind an effect: the thrower of a potion or the shooter of an arrow, not the missile. */
    private static Entity attacker(Entity source) {
        if (source instanceof Projectile projectile && projectile.getOwner() != null) return projectile.getOwner();
        return source;
    }

    private record Coverage(Player owner, ItemStack shield) { }

    /** The nearest working field that covers {@code victim} against {@code attacker}. */
    private static Coverage coverage(LivingEntity victim, Entity attacker) {
        var owners = new java.util.ArrayList<Player>(victim.level().players());
        if (victim instanceof Player wearer && !owners.contains(wearer)) owners.add(wearer);
        owners.sort(java.util.Comparator.comparingDouble(owner -> owner == victim ? -1 : owner.distanceToSqr(victim)));
        for (Player owner : owners) {
            if (!EquippedRelicSetResolver.isRealPlayer(owner) || ShieldCoverage.friendly(owner, attacker)) continue;
            ItemStack shield = EquippedRelicSetResolver.findFirstActive(owner, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
            if (!shield.isEmpty() && ShieldCoverage.covers(owner, shield, victim)) return new Coverage(owner, shield);
        }
        return null;
    }

    private ShieldEffectGuard() {
    }
}
