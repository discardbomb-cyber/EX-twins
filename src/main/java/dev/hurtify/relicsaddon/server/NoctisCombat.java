package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.network.NoctisPayloads;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.EclipseScytheItem;
import dev.hurtify.relicsaddon.relic.NoctisCore;
import dev.hurtify.relicsaddon.relic.TwinsSpearEntity;
import dev.hurtify.relicsaddon.relic.TwinsSpearItem;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The blows of the Noctis weapons in hand: a thrust of the spear collapses the air where it lands, a swing of the
 * open scythe reaches everyone in its arc, a blow on a sinner (or the spear's own pinned creature) falls harder,
 * and every blow feeds the weapon's core. The spear's light cut lives here too.
 */
public final class NoctisCombat {
    public static final ResourceKey<DamageType> LIGHT = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(dev.hurtify.relicsaddon.RelicsAddon.MOD_ID, "noctis_light"));
    /** The light cut: its reach, what it does to each creature it crosses, and what its core costs. */
    public static final double CUT_REACH = 5;
    public static final float CUT_DAMAGE = 6;
    public static final int CUT_COST = 10;
    /** The scythe's arc: half its width in degrees. */
    private static final double SWEEP_HALF_ARC = 60;

    /** A blow on a sinner resonates; the spear's thrust on the creature its reflection holds absolves it. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!event.getSource().is(DamageTypes.PLAYER_ATTACK) || !(event.getSource().getDirectEntity() instanceof ServerPlayer owner)) return;
        ItemStack weapon = owner.getMainHandItem();
        boolean spear = weapon.getItem() instanceof TwinsSpearItem, scythe = weapon.getItem() instanceof EclipseScytheItem && EclipseScytheItem.open(weapon);
        if (!spear && !scythe) return;
        LivingEntity target = event.getEntity();
        boolean sinner = NoctisCore.sinner(owner, target);
        float weight = sinner ? NoctisCore.SIN_RESONANCE : 1;
        if (spear) {
            TwinsSpearEntity reflection = TwinsSpearEntity.of(owner);
            if (reflection != null && reflection.state() == TwinsSpearEntity.ANCHORED && reflection.target() == target) weight *= NoctisCore.ABSOLUTION;
        }
        event.setAmount(event.getAmount() * weight);
        NoctisCore.feed(weapon, sinner ? NoctisCore.SINNER_BLOW : NoctisCore.BLOW);
    }

    /** A thrust of the spear collapses the air where it lands; a swing of the open scythe takes in its whole arc. */
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer owner) || !(owner.level() instanceof ServerLevel level)) return;
        ItemStack weapon = owner.getMainHandItem();
        Entity target = event.getTarget();
        Vec3 look = owner.getLookAngle();
        if (weapon.getItem() instanceof TwinsSpearItem) {
            if (owner.getAttackStrengthScale(.5F) < .9F) return;
            Vec3 at = target.getBoundingBox().getCenter();
            NoctisPayloads.tell(level, NoctisPayloads.Kind.COLLAPSE, at, look, 1);
            RelicSounds.spear(level, at, RelicSounds.Spear.COLLAPSE);
        } else if (weapon.getItem() instanceof EclipseScytheItem && EclipseScytheItem.open(weapon)) {
            if (owner.getAttackStrengthScale(.5F) < .9F) return;
            double reach = owner.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + .5;
            float damage = (float) owner.getAttributeValue(Attributes.ATTACK_DAMAGE) * .6F;
            Vec3 eye = owner.getEyePosition();
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(reach),
                    other -> other != target && HiveCombatController.validTarget(owner, other, false))) {
                Vec3 to = other.getBoundingBox().getCenter().subtract(eye);
                double flat = Math.sqrt(to.x * to.x + to.z * to.z);
                if (flat > reach) continue;
                double angle = Math.toDegrees(Math.acos(Math.clamp(new Vec3(to.x, 0, to.z).normalize().dot(new Vec3(look.x, 0, look.z).normalize()), -1, 1)));
                if (angle > SWEEP_HALF_ARC) continue;
                other.hurt(level.damageSources().playerAttack(owner), damage);
            }
            NoctisPayloads.tell(level, NoctisPayloads.Kind.SWEEP, eye, look, reach);
            RelicSounds.spear(level, eye, RelicSounds.Spear.SCYTHE_SWING);
        }
    }

    /**
     * The light cut of shadow: a disc spun out level from the wielder to {@link #CUT_REACH}, that cuts every creature
     * it crosses and every shot in the air there. False, and nothing done, when the core is short.
     */
    public static boolean lightCut(ServerLevel level, ServerPlayer owner, ItemStack weapon) {
        if (!NoctisCore.spend(weapon, CUT_COST)) return false;
        Vec3 eye = owner.getEyePosition().subtract(0, .3, 0);
        AABB disc = new AABB(eye, eye).inflate(CUT_REACH, 1.6, CUT_REACH);
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, disc, other -> HiveCombatController.validTarget(owner, other, false))) {
            Vec3 middle = other.getBoundingBox().getCenter();
            if (Math.abs(middle.y - eye.y) > 1.6 || middle.subtract(eye).horizontalDistance() > CUT_REACH) continue;
            other.hurt(level.damageSources().source(LIGHT, owner), CUT_DAMAGE);
        }
        for (Projectile shot : level.getEntitiesOfClass(Projectile.class, disc, shot -> shot.getOwner() != owner && Math.abs(shot.getY() - eye.y) < 1.6)) {
            shot.discard();
        }
        NoctisPayloads.tell(level, NoctisPayloads.Kind.LIGHT_CUT, eye, owner.getLookAngle(), CUT_REACH);
        RelicSounds.spear(level, eye, RelicSounds.Spear.CUT);
        owner.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        return true;
    }

    /** A worn Twins hive feeds the core of the weapon in hand a point a second, from its own charge. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer owner) || owner.tickCount % 20 != 0) return;
        ItemStack weapon = owner.getMainHandItem();
        if (!(weapon.getItem() instanceof TwinsSpearItem) && !(weapon.getItem() instanceof EclipseScytheItem)) return;
        if (NoctisCore.charge(weapon) >= NoctisCore.MAX) return;
        ItemStack hive = NoctisCore.hive(owner);
        if (hive != null && DevicePower.drain(owner, hive, 1)) NoctisCore.feed(weapon, 1);
    }

    /** Whether {@code stack} is one of the Noctis weapons. */
    public static boolean noctis(ItemStack stack) {
        return stack.getItem() instanceof TwinsSpearItem || stack.getItem() instanceof EclipseScytheItem;
    }

    static boolean overdrive(ItemStack stack, long now) {
        return stack.getOrDefault(ModDataComponents.SCYTHE_OVERDRIVE.get(), 0L) > now;
    }

    private NoctisCombat() { }
}
