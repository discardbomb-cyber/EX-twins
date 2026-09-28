package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.relic.ShieldUpgrades;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class ShieldProjectileInterceptor {
    private static final String ABSORBED_FOR = "relics_addon:field_absorbed_for";
    private static final String PASSED_FOR = "relics_addon:field_passed_for";
    private static final String CREDIT_FOR = "relics_addon:field_credit_for";
    private static final String CREDIT_STACK = "relics_addon:field_credit_stack";
    public static final TagKey<EntityType<?>> INTERCEPTABLE = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "shield_interceptable_projectiles"));

    public static boolean supported(Projectile projectile) {
        if (projectile.isRemoved() || projectile instanceof ThrownTrident) return false;
        return projectile instanceof AbstractArrow arrow ? !arrow.isNoPhysics() : projectile.getType().is(INTERCEPTABLE);
    }

    public static boolean threatens(Projectile projectile, Player player) {
        Entity owner = projectile.getOwner();
        return supported(projectile) && owner != player && (owner == null || !ShieldCoverage.friendly(player, owner))
                && !alreadyAbsorbed(projectile, player) && !passedThrough(projectile, player)
                && (!(owner instanceof Player shooter) || player.canHarmPlayer(shooter));
    }

    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Projectile projectile)
                || !(projectile.level() instanceof ServerLevel level) || !supported(projectile)) return;
        record Candidate(Player player, double time) { }
        var candidates = new java.util.ArrayList<Candidate>();
        var vicinity = projectile.getBoundingBox().expandTowards(projectile.getDeltaMovement())
                .inflate(dev.hurtify.relicsaddon.AddonConfig.SHIELD_MAX_RADIUS.get() + 1);
        for (Player player : level.players()) {
            if (!vicinity.contains(player.position()) || !EquippedRelicSetResolver.isRealPlayer(player) || !player.isAlive() || player.isSpectator()
                    || !threatens(projectile, player)) continue;
            var crossing = crossing(projectile, player, 1);
            if (crossing != null) candidates.add(new Candidate(player, crossing.time()));
        }
        // Gather mutates the shield, so visit crossings in flight order before starting any moves.
        candidates.sort(java.util.Comparator.comparingDouble(Candidate::time));
        for (var candidate : candidates) {
            if (intercept(projectile, candidate.player())) {
                event.setCanceled(projectile.isRemoved());
                break;
            }
        }
    }

    public static ShieldField.Crossing crossing(Projectile projectile, Player player, double ticks) {
        return crossing(projectile, player, ticks, equipped(player));
    }

    public static ShieldField.Crossing crossing(Projectile projectile, Player player, double ticks, ItemStack shield) {
        if (shield.isEmpty()) return null;
        return ShieldField.intercept(projectile.position().subtract(player.position().add(0, ShieldField.CENTER_Y, 0)),
                projectile.getDeltaMovement(), ticks, ShieldParameters.radius(player, shield));
    }

    public static boolean unobstructed(Projectile projectile, double ticks) {
        if (ticks <= 0) return true;
        Vec3 end = projectile.position().add(projectile.getDeltaMovement().scale(ticks));
        if (projectile.level().clip(new ClipContext(projectile.position(), end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, projectile)).getType() != HitResult.Type.MISS) return false;
        Entity owner = projectile.getOwner();
        return ProjectileUtil.getEntityHitResult(projectile.level(), projectile, projectile.position(), end,
                projectile.getBoundingBox().expandTowards(end.subtract(projectile.position())).inflate(1),
                candidate -> candidate.canBeHitByProjectile() && (owner == null || candidate.getRootVehicle() != owner.getRootVehicle())) == null;
    }

    public static boolean intercept(Projectile projectile, Player player) {
        if (player.level().isClientSide() || !player.isAlive() || player.isSpectator()
                || !EquippedRelicSetResolver.isRealPlayer(player) || !threatens(projectile, player)) return false;
        var crossing = crossing(projectile, player, 1);
        if (crossing == null || !unobstructed(projectile, crossing.time())) return false;
        ItemStack shield = equipped(player);
        if (shield.isEmpty()) return false;
        ShieldStackState state = prepared(player, shield, crossing.normal());
        int cell = ShieldController.selectCell(player, crossing.normal());
        if (state.sharedBuffer() <= 0 && state.availableHp(cell, player.level().getGameTime()) <= 0) {
            projectile.getPersistentData().putBoolean(PASSED_FOR + player.getUUID(), true);
            return false;
        }
        int cost = HiveController.remainingCost(projectile, player, impactCost(projectile));
        var damage = ShieldCellDefense.damage(state, cell, cost, ShieldUpgrades.sharing(player, shield), player.level().getGameTime());
        boolean stopped = damage.spent() >= cost;
        float absorbed = stopped ? cost : projectile instanceof AbstractArrow ? damage.spent() : 0;
        long now = player.level().getGameTime();
        ShieldStackState next = damage.apply(state, cell, absorbed, now);
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), next);
        ShieldImpact impact = ShieldImpact.of(crossing.normal(), now, cell, absorbed, state, next);
        Vec3 point = projectile.position().add(projectile.getDeltaMovement().scale(crossing.time()));
        double distance = point.distanceTo(player.position().add(0, ShieldField.CENTER_Y, 0));
        if (distance < ShieldParameters.radius(player, shield) - 1e-4) impact = impact.atDistance(distance);
        shield.set(ModDataComponents.SHIELD_IMPACT.get(), impact);
        shield.set(ModDataComponents.SHIELD_IMPACTS.get(), shield.getOrDefault(ModDataComponents.SHIELD_IMPACTS.get(),
                dev.hurtify.relicsaddon.shield.ShieldImpactHistory.EMPTY).append(impact));
        dev.hurtify.relicsaddon.sound.RelicSounds.shield((ServerLevel) player.level(), point,
                ((dev.hurtify.relicsaddon.relic.AutonomousRelicItem) shield.getItem()).role(), impact.broken(), next.totalIntegrity() == 0);
        if (absorbed > 0) {
            projectile.getPersistentData().putUUID(ABSORBED_FOR, player.getUUID());
            projectile.getPersistentData().putBoolean(ABSORBED_FOR + player.getUUID(), true);
            RelicRuntime.awardAbsorption(player, shield, absorbed);
        }
        if (stopped) {
            projectile.setPos(projectile.position().add(projectile.getDeltaMovement().scale(crossing.time())));
            if (projectile instanceof AbstractArrow arrow && arrow.pickup == AbstractArrow.Pickup.ALLOWED) {
                arrow.spawnAtLocation(arrow.getPickupItemStackOrigin().copy());
            }
            projectile.discard();
        } else if (projectile instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(arrow.getBaseDamage() * (1 - damage.spent() / (double) cost));
        } else {
            // Generic projectiles have no mutable damage value. Apply the paid HP once at impact.
            if (!shield.has(ModDataComponents.INSTANCE_ID.get())) {
                shield.set(ModDataComponents.INSTANCE_ID.get(), java.util.UUID.randomUUID().toString());
            }
            var data = projectile.getPersistentData();
            data.putFloat(CREDIT_FOR + player.getUUID(), damage.spent());
            data.putString(CREDIT_STACK + player.getUUID(), shield.get(ModDataComponents.INSTANCE_ID.get()));
            data.putBoolean(PASSED_FOR + player.getUUID(), true);
        }
        return true;
    }

    public static int impactCost(Projectile projectile) {
        if (projectile instanceof AbstractArrow arrow) {
            double damage = arrow.getBaseDamage() * arrow.getDeltaMovement().length();
            if (arrow.isCritArrow()) damage = Math.ceil(damage) * 1.5D + 1;
            return Double.isFinite(damage) ? Math.clamp(Mth.ceil(damage), 1, 10000) : 10000;
        }
        EntityType<?> type = projectile.getType();
        if (type == EntityType.FIREBALL || type == EntityType.DRAGON_FIREBALL || type == EntityType.WITHER_SKULL) return 8;
        if (type == EntityType.SMALL_FIREBALL) return 5;
        if (type == EntityType.SNOWBALL || type == EntityType.EGG || type == EntityType.LLAMA_SPIT) return 1;
        return 4; // Datapack opt-in mod bullets and shulker bullets use a fixed field-integrity cost.
    }

    public static boolean alreadyAbsorbed(Projectile projectile, Player player) {
        return projectile.getPersistentData().getBoolean(ABSORBED_FOR + player.getUUID())
                || (projectile.getPersistentData().hasUUID(ABSORBED_FOR)
                && player.getUUID().equals(projectile.getPersistentData().getUUID(ABSORBED_FOR)));
    }

    public static boolean passedThrough(Projectile projectile, Player player) {
        return projectile.getPersistentData().getBoolean(PASSED_FOR + player.getUUID());
    }

    public static float consumePaidDamage(Projectile projectile, Player player, float amount) {
        var data = projectile.getPersistentData();
        String key = CREDIT_FOR + player.getUUID(), stackKey = CREDIT_STACK + player.getUUID();
        float paid = data.getFloat(key);
        if (!(paid > 0) || !Float.isFinite(paid)) return 0;
        float absorbed = Math.min(amount, paid);
        ItemStack shield = equipped(player);
        if (!shield.isEmpty() && data.getString(stackKey).equals(shield.get(ModDataComponents.INSTANCE_ID.get()))) {
            RelicRuntime.awardAbsorption(player, shield, absorbed);
        }
        data.remove(key);
        data.remove(stackKey);
        return absorbed;
    }

    private static ShieldStackState prepared(Player player, ItemStack shield, Vec3 normal) {
        ShieldStackState state = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        ShieldStackState gathered = ShieldCellDefense.gather(state, ShieldController.selectCell(player, normal),
                ShieldUpgrades.gathering(player, shield), player.level().getGameTime());
        if (gathered != state) shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), gathered);
        return gathered;
    }

    private static ItemStack equipped(Player player) {
        return EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
    }

    private ShieldProjectileInterceptor() {
    }
}
