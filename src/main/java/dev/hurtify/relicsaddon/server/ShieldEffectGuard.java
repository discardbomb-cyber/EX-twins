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
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * A field also stops what an attack leaves behind. A harmful effect that an attacker puts on someone
 * under a working field (a wither skeleton's wither, a cave spider's poison, a witch's potion, a
 * stray's slowness, a shulker's levitation) is cut off entirely, or, when the hit that carried it only
 * partly got through the field, trimmed to the share of damage that got through. A hit that got
 * through untouched brings its effect with it. The wearer's own potions and lingering clouds, and
 * effects of the world (a wither rose, a beacon, a dispenser's arrow), are left alone, and so are the
 * effects the server lists in {@code shield.keptEffects}. Cutting costs charge; a field that cannot
 * pay lets the effect through.
 */
public final class ShieldEffectGuard {
    /** Battery points per second of effect per level of its strength. */
    private static final int COST_PER_SECOND = 2;
    private static final Map<LivingEntity, Hit> LAST_HIT = new WeakHashMap<>();
    private static boolean reapplying;

    /**
     * The last hit a creature took: when, from whom (an entity id, never the entity, so the weak map
     * can let go of both), how much the field stopped and how much got through.
     */
    private record Hit(long tick, int attacker, float absorbed, float passed) { }

    /** Records how much of a hit the field stopped, so an effect that rides on it can be trimmed to match. */
    public static void recordHit(LivingEntity victim, Entity attacker, float absorbed, float passed) {
        Entity dealer = attacker(attacker);
        if (dealer != null) LAST_HIT.put(victim, new Hit(victim.level().getGameTime(), dealer.getId(), absorbed, Math.max(0, passed)));
    }

    /** A hit that landed without the field taking any of it: its effect is left alone. */
    public static void onDamagePost(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        var source = event.getSource();
        Entity dealer = attacker(source.getEntity() != null ? source.getEntity() : source.getDirectEntity());
        if (dealer == null) return;
        long now = victim.level().getGameTime();
        Hit hit = LAST_HIT.get(victim);
        // The field already recorded its share of this hit.
        if (hit != null && hit.tick() == now && hit.attacker() == dealer.getId()) return;
        LAST_HIT.put(victim, new Hit(now, dealer.getId(), 0, event.getNewDamage()));
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
        boolean struck = hit != null && hit.tick() == victim.level().getGameTime() && hit.attacker() == attacker.getId();
        // A blow that got through untouched brings its effect with it; one the field partly stopped, the matching share.
        if (struck && hit.absorbed() <= 0) return;
        int kept = struck && hit.passed() > 0 ? (int) Math.floor(effect.getDuration() * hit.passed() / Math.max(1e-3F, hit.absorbed() + hit.passed())) : 0;
        int removed = effect.isInfiniteDuration() ? 20 * 60 : effect.getDuration() - kept;
        int cost = Math.max(1, (int) Math.ceil(removed / 20.0 * (effect.getAmplifier() + 1) * COST_PER_SECOND));
        // Draining more than the batteries hold would empty them and still fail; a field that cannot pay lets the effect be.
        if (!DevicePower.canAfford(cover.owner(), cover.shield(), cost) || !DevicePower.drain(cover.owner(), cover.shield(), cost)) return;
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

    /**
     * The creature behind an effect: the thrower of a potion, the shooter of an arrow, whoever left a
     * lingering cloud, not the missile or the cloud. Null for one nobody owns (a dispenser's), which is
     * the world's doing.
     */
    private static Entity attacker(Entity source) {
        return source instanceof TraceableEntity traceable ? traceable.getOwner() : source;
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
