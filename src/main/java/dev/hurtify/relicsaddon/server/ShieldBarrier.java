package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The shell is a wall for hostile mobs: anything hostile that reaches inside the shield radius is
 * shoved back out to the surface, so melee has to land on the field instead of on the wearer.
 * Friendly mobs, pets and players are never pushed.
 */
public final class ShieldBarrier {
    /** Charge spent each tick a mob is held out. */
    private static final int PUSH_COST = 1;

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player owner = event.getEntity();
        if (owner.level().isClientSide() || !owner.isAlive() || owner.isSpectator() || !EquippedRelicSetResolver.isRealPlayer(owner)) return;
        ItemStack shield = EquippedRelicSetResolver.findFirstActive(owner, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
        if (shield.isEmpty()) return;
        double radius = ShieldParameters.radius(owner, shield);
        Vec3 center = owner.position().add(0, ShieldField.CENTER_Y, 0);
        AABB area = new AABB(center, center).inflate(radius + 2);
        for (Mob mob : owner.level().getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && hostile(owner, mob))) {
            Vec3 offset = mob.getBoundingBox().getCenter().subtract(center);
            double reach = radius + mob.getBbWidth() * .5;
            double distance = offset.length();
            if (distance >= reach) continue;
            if (!DevicePower.drain(owner, shield, PUSH_COST)) return;
            Vec3 out = distance < 1e-3 ? owner.getLookAngle().multiply(1, 0, 1) : offset.scale(1 / distance);
            if (out.lengthSqr() < 1e-6) out = new Vec3(1, 0, 0);
            out = out.normalize();
            // Move back to the surface along the ground plane (collisions respected), then keep drifting outward.
            Vec3 horizontal = new Vec3(out.x, 0, out.z);
            if (horizontal.lengthSqr() < 1e-6) horizontal = new Vec3(1, 0, 0);
            horizontal = horizontal.normalize();
            double depth = reach - distance;
            mob.move(MoverType.SELF, horizontal.scale(Math.min(depth, 1.5)));
            mob.setDeltaMovement(horizontal.scale(.35).add(0, Math.max(mob.getDeltaMovement().y, .05), 0));
            mob.hurtMarked = true;
        }
    }

    private static boolean hostile(Player owner, Mob mob) {
        if (ShieldCoverage.friendly(owner, mob)) return false;
        return mob instanceof Enemy || mob.getTarget() == owner;
    }

    private ShieldBarrier() {
    }
}
