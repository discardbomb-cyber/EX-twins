package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldField;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpactHistory;
import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import dev.hurtify.relicsaddon.domain.shield.StrikeRules;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The shell hits back. A hostile mob that touches the field is thrown clear and takes damage of the
 * shield's own kind: the RF shell discharges into it (armour takes part of the blow), the Mana shell
 * bursts through armour, and the Twins shell surges with both. Every strike costs battery charge and
 * shows on the shell like a hit; one mob is struck at most once per cooldown.
 */
public final class ShieldStrike {
    public static final ResourceKey<DamageType> DISCHARGE = key("shield_discharge");
    public static final ResourceKey<DamageType> MANA_BURST = key("shield_mana_burst");
    public static final ResourceKey<DamageType> TWIN_SURGE = key("shield_twin_surge");
    /** Every strike type. The tag is part of {@code shield_passes}, so no field absorbs a strike, its owner's included. */
    public static final TagKey<DamageType> STRIKES = TagKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "shield_strike"));
    private static final Map<Mob, Long> READY_AT = new WeakHashMap<>();

    public static ResourceKey<DamageType> damageType(RelicRole role) {
        return switch (StrikeRules.damageKind(role)) {
            case SHIELD_MANA_BURST -> MANA_BURST;
            case SHIELD_TWIN_SURGE -> TWIN_SURGE;
            default -> DISCHARGE;
        };
    }

    /**
     * Mobs the shell retaliates against: anything targeting the wearer, and monsters that are not merely
     * neutral. Endermen, zombified piglins and piglins are struck only once they turn on the wearer.
     */
    public static boolean aggressive(Player owner, Mob mob) {
        if (ShieldCoverage.friendly(owner, mob)) return false;
        if (mob.getTarget() == owner) return true;
        if (mob instanceof NeutralMob neutral) return neutral.isAngryAt(owner);
        return mob instanceof Enemy && !(mob instanceof AbstractPiglin);
    }

    /**
     * Strikes a mob held at the shell. {@code normal} points from the field centre to the mob and
     * {@code push} is its horizontal part. Returns false when the mob is not aggressive, is still
     * cooling down, or the batteries cannot pay; the barrier then only holds it at the surface.
     */
    static boolean strike(Player owner, ItemStack shield, RelicRole role, Mob mob, Vec3 normal, Vec3 push, double radius) {
        if (!(owner.level() instanceof ServerLevel level) || !aggressive(owner, mob)) return false;
        long now = level.getGameTime();
        Long ready = READY_AT.get(mob);
        if (ready != null && now < ready) return false;
        float damage = ShieldParameters.strikeDamage(shield);
        double knockback = ShieldParameters.strikeKnockback(role);
        if (!(damage > 0) && !(knockback > 0)) return false;
        if (!DevicePower.canAfford(owner, shield, DevicePower.STRIKE)) return false;
        DevicePower.drain(owner, shield, DevicePower.STRIKE);
        READY_AT.put(mob, now + ShieldParameters.strikeCooldown());
        if (damage > 0) mob.hurt(level.damageSources().source(damageType(role), owner), damage);
        if (knockback > 0 && mob.isAlive()) {
            // knockback() takes the direction towards the attacker; the field throws away from its centre.
            mob.knockback(knockback, -push.x, -push.z);
            mob.hurtMarked = true;
        }
        record(owner, shield, normal, now, damage, mob.getBbWidth() * .5F);
        Vec3 contact = owner.position().add(0, ShieldField.CENTER_Y, 0).add(normal.scale(radius));
        RelicSounds.strike(level, contact, role);
        if (damage > 0) RelicRuntime.awardCombatExperience(owner, shield, damage);
        return true;
    }

    /** The strike shows on the shell with the flash, wave and traces of a hit, plus an arc out to the mob. */
    private static void record(Player owner, ItemStack shield, Vec3 normal, long now, float damage, float reach) {
        int panel = ShieldTopology.INSTANCE.cells()[ShieldController.selectCell(owner, normal)].panel();
        ShieldImpact impact = ShieldImpact.strike(McVectors.toDomain(normal), now, panel, Math.max(1, damage), reach);
        shield.set(ModDataComponents.SHIELD_IMPACT.get(), impact);
        shield.set(ModDataComponents.SHIELD_IMPACTS.get(),
                shield.getOrDefault(ModDataComponents.SHIELD_IMPACTS.get(), ShieldImpactHistory.EMPTY).append(impact));
    }

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, path));
    }

    private ShieldStrike() {
    }
}
