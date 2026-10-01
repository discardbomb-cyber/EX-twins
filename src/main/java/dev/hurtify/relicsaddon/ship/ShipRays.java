package dev.hurtify.relicsaddon.ship;

import dev.ryanhcode.sable.companion.SableCompanion;
import javax.annotation.Nullable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Straight lines through the world, ships and all. With Sable, a ray also meets the ships' hulls, but reports where
 * it met one in that ship's plot, far from the world; these carry every such point back out to where the ship is.
 */
public final class ShipRays {
    /** Where the line from {@code from} to {@code to} first meets something solid, in the world; null if nothing. */
    @Nullable
    public static Vec3 firstSolid(Level level, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return null;
        Vec3 at = SableCompanion.INSTANCE.projectOutOfSubLevel(level, hit.getLocation());
        // Never past the end of the line, whatever a ship's pose did to the point.
        return at.distanceToSqr(from) > to.distanceToSqr(from) ? to : at;
    }

    /** Whether nothing solid stands between the two points. */
    public static boolean clear(Level level, Vec3 from, Vec3 to) {
        Vec3 hit = firstSolid(level, from, to);
        return hit == null || hit.distanceToSqr(from) >= to.distanceToSqr(from) - 1e-3;
    }

    /** Where a line from {@code from} along {@code direction} (a unit vector) stops: at the first solid thing, or after {@code length}. */
    public static Vec3 reach(Level level, Vec3 from, Vec3 direction, double length) {
        Vec3 to = from.add(direction.scale(length));
        Vec3 hit = firstSolid(level, from, to);
        return hit == null ? to : hit;
    }

    private ShipRays() {
    }
}
