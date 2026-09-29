package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.drone.HiveOrbit;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.relic.HiveUpgrades;
import dev.hurtify.relicsaddon.shield.ShieldField;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

public final class HiveController {
    public static final double RADIUS = 2.65;
    private static final String ATTEMPTED = "relics_addon:hive_attempted_";
    private static final String CREDIT = "relics_addon:hive_credit_";

    public record Equipped(HiveType type, ItemStack stack) { }

    /** The single operable hive in a charm slot; extra hives are ignored even if Curios holds them. */
    public static List<Equipped> active(Player player) {
        if (!EquippedRelicSetResolver.isRealPlayer(player) || !player.isAlive() || player.isSpectator()) return List.of();
        return CuriosApi.getCuriosInventory(player).map(handler -> {
            var result = new ArrayList<Equipped>(1);
            var optional = handler.getStacksHandler(RelicRole.EQUIPMENT_SLOT);
            if (optional.isEmpty()) return result;
            var stacks = optional.get().getStacks();
            for (int slot = 0; slot < stacks.getSlots(); slot++) {
                ItemStack stack = stacks.getStackInSlot(slot);
                if (handler.isSlotActive(RelicRole.EQUIPMENT_SLOT, slot) && stack.getItem() instanceof AutonomousRelicItem item
                        && item.role().isHive() && RelicRuntime.canOperate(player, stack)) {
                    result.add(new Equipped(HiveType.of(item.role()), stack));
                    break;
                }
            }
            return result;
        }).orElseGet(ArrayList::new);
    }

    public static int capacity(Player player, ItemStack stack) {
        HiveType type = HiveType.of(((AutonomousRelicItem) stack.getItem()).role());
        return (int) Math.round(RelicRuntime.stat(player, stack, "drone_count", type.initialCount, 12, HiveType.MAX_DRONES));
    }

    public static int health(Player player, ItemStack stack) {
        HiveType type = HiveType.of(((AutonomousRelicItem) stack.getItem()).role());
        return (int) Math.round(RelicRuntime.stat(player, stack, "drone_health", type.initialHealth, 1, 1000));
    }

    public static HiveStackState prepare(Player player, ItemStack stack, boolean repair) {
        HiveStackState old = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        HiveStackState next = old.prepare(capacity(player, stack), health(player, stack), player.level().getGameTime(), repair,
                repair ? HiveUpgrades.repairAmount(player, stack) : 1);
        if (old != next) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), next);
        return next;
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        HiveCombatController.tick(player);
        if (player.level().getGameTime() % 10 != 0) return;
        for (Equipped hive : active(player)) prepare(player, hive.stack(), true);
    }

    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!AddonConfig.HIVE_INTERCEPTION.get() || event.isCanceled() || !(event.getEntity() instanceof Projectile projectile)
                || !(projectile.level() instanceof ServerLevel level) || !ShieldProjectileInterceptor.supported(projectile)) return;
        record Target(Player player, double time) { }
        var candidates = new ArrayList<Target>();
        Vec3 start = projectile.position(), velocity = projectile.getDeltaMovement();
        if (velocity.lengthSqr() < 1e-10) return;
        var bounds = projectile.getBoundingBox().expandTowards(velocity).inflate(RADIUS + 1);
        for (Player player : level.players()) {
            if (!bounds.contains(player.position()) || !player.isAlive() || player.isSpectator()) continue;
            var crossing = crossing(projectile, player);
            if (crossing != null) candidates.add(new Target(player, crossing.time()));
        }
        candidates.sort(java.util.Comparator.comparingDouble(Target::time));
        for (Target target : candidates) {
            intercept(projectile, target.player());
            if (projectile.isRemoved()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    public static ShieldField.Crossing crossing(Projectile projectile, Player player) {
        double scale = ShieldField.RADIUS / RADIUS;
        return ShieldField.incoming(projectile.position().subtract(player.position().add(0, ShieldField.CENTER_Y, 0)).scale(scale),
                projectile.getDeltaMovement().scale(scale), 1);
    }

    public static boolean intercept(Projectile projectile, Player player) {
        if (!AddonConfig.HIVE_INTERCEPTION.get() || player.level().isClientSide() || !EquippedRelicSetResolver.isRealPlayer(player)
                || !player.isAlive() || player.isSpectator() || !ShieldProjectileInterceptor.threatens(projectile, player)
                || projectile.getPersistentData().getBoolean(ATTEMPTED + player.getUUID())) return false;
        var crossing = crossing(projectile, player);
        if (crossing == null || !ShieldProjectileInterceptor.unobstructed(projectile, crossing.time())) return false;
        List<Equipped> hives = active(player);
        if (hives.isEmpty()) return false;
        int cost = ShieldProjectileInterceptor.impactCost(projectile), remaining = cost;
        long now = player.level().getGameTime();
        for (Equipped hive : hives) {
            if (hive.stack().getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(),
                    dev.hurtify.relicsaddon.drone.HiveCombatState.DEFAULT).active()) continue;
            HiveStackState state = prepare(player, hive.stack(), false);
            var next = new ArrayList<>(state.units());
            var order = new ArrayList<Integer>(next.size());
            for (int index = 0; index < next.size(); index++) if (next.get(index).ready(now)
                    && !HiveTaskController.settings(hive.stack()).healer(index, next.size())) order.add(index);
            order.sort(java.util.Comparator.comparingDouble(index -> {
                HiveOrbit.Point p = HiveOrbit.at(index, next.size(), hive.type().ordinal(), now);
                return -(p.x() * crossing.normal().x + p.y() * crossing.normal().y + p.z() * crossing.normal().z);
            }));
            int before = remaining;
            int rebuild = Math.max(20, (int) Math.round(RelicRuntime.stat(player, hive.stack(), "cooldown", hive.type().initialCooldown, 20, 1200)
                    * HiveUpgrades.rebuildMultiplier(player, hive.stack())));
            for (int index : order) {
                if (remaining == 0) break;
                HiveStackState.Unit unit = next.get(index);
                int paid = Math.min(remaining, unit.hp()), hp = unit.hp() - paid;
                remaining -= paid;
                next.set(index, new HiveStackState.Unit(hp, now + (hp == 0 ? rebuild : 8), now,
                        (float) crossing.normal().x, (float) crossing.normal().y, (float) crossing.normal().z,
                        unit.attackReadyAt()));
            }
            if (before > remaining) {
                hive.stack().set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(state.enabled(), next));
                RelicRuntime.awardAbsorption(player, hive.stack(), before - remaining);
            }
            if (remaining == 0) break;
        }
        if (remaining == cost) return false;
        projectile.getPersistentData().putBoolean(ATTEMPTED + player.getUUID(), true);
        if (remaining == 0) {
            projectile.setPos(projectile.position().add(projectile.getDeltaMovement().scale(crossing.time())));
            if (projectile instanceof AbstractArrow arrow && arrow.pickup == AbstractArrow.Pickup.ALLOWED) {
                arrow.spawnAtLocation(arrow.getPickupItemStackOrigin().copy());
            }
            projectile.discard();
        } else if (projectile instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(arrow.getBaseDamage() * remaining / cost);
        } else {
            projectile.getPersistentData().putFloat(CREDIT + player.getUUID(), cost - remaining);
        }
        return true;
    }

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Player player) || player.level().isClientSide()
                || !(event.getSource().getDirectEntity() instanceof Projectile projectile)
                || event.getSource().is(DamageTypeTags.BYPASSES_SHIELD)
                || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        String key = CREDIT + player.getUUID();
        float paid = projectile.getPersistentData().getFloat(key);
        projectile.getPersistentData().remove(key);
        if (Float.isFinite(paid) && paid > 0) event.setAmount(Math.max(0, event.getAmount() - paid));
    }

    public static int remainingCost(Projectile projectile, Player player, int original) {
        float paid = projectile.getPersistentData().getFloat(CREDIT + player.getUUID());
        return Float.isFinite(paid) && paid > 0 ? Math.max(1, original - (int) paid) : original;
    }

    private HiveController() { }
}
