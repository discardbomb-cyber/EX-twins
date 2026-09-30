package dev.hurtify.relicsaddon.ship;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Where a ship hive is in the world and which way its ship faces. A block on a Create Aeronautics ship lives in its
 * ship's plot, a stretch of the level far off where Sable keeps the ship's blocks; the ship's pose carries it out into
 * the world where the ship really is. Off a ship the frame is simply the block where it stands, level and still.
 *
 * @param ship        the ship's id, or null off a ship
 * @param centre      the hive block's centre, in the world
 * @param orientation which way the ship is turned (none off a ship)
 * @param velocity    how fast the hive is moving with its ship, in blocks a tick
 * @param bounds      the whole ship's box in the world (the block's own off a ship)
 * @param hull        the ship's blocks' box in its own plot, the frame {@link #toWorld} carries out (the block's own off a ship)
 * @param pose        the ship's pose, or null off a ship
 */
public record ShipFrame(@Nullable UUID ship, Vec3 centre, Quaterniondc orientation, Vec3 velocity, AABB bounds, AABB hull, @Nullable Pose3dc pose) {
    private static final Quaterniondc LEVEL = new Quaterniond();

    /** The frame of a hive on the server: on a ship, carried out into the world by the ship's pose; off one, where it stands. */
    public static ShipFrame of(BlockEntity hive) {
        Level level = hive.getLevel();
        Vec3 centre = Vec3.atCenterOf(hive.getBlockPos());
        SubLevelAccess ship = level == null ? null : SableCompanion.INSTANCE.getContaining(hive);
        if (ship == null) return still(centre);
        return carried(level, centre, ship, ship.logicalPose());
    }

    /** The frame of a hive on a client as it is drawn this frame, the ship's pose between its last two ticks. */
    public static ShipFrame drawn(BlockEntity hive, float partialTick) {
        Level level = hive.getLevel();
        Vec3 centre = Vec3.atCenterOf(hive.getBlockPos());
        ClientSubLevelAccess ship = level == null ? null : SableCompanion.INSTANCE.getContainingClient(hive);
        if (ship == null) return still(centre);
        return carried(level, centre, ship, ship.renderPose(partialTick));
    }

    private static ShipFrame still(Vec3 centre) {
        AABB block = new AABB(centre, centre).inflate(.5);
        return new ShipFrame(null, centre, LEVEL, Vec3.ZERO, block, block, null);
    }

    private static ShipFrame carried(Level level, Vec3 centre, SubLevelAccess ship, Pose3dc pose) {
        // Sable gives the velocity in blocks a second, like its physics; everything here moves by the tick.
        Vec3 velocity = SableCompanion.INSTANCE.getVelocity(level, ship, centre);
        return new ShipFrame(ship.getUniqueId(), pose.transformPosition(centre), pose.orientation(), velocity == null ? Vec3.ZERO : velocity.scale(1 / 20.0),
                ship.boundingBox().toMojang(), SablePlots.hull(ship, centre), pose);
    }

    /** Whether the hive is aboard a ship. */
    public boolean aboard() {
        return ship != null;
    }

    /** A point of the ship's plot (a hive's own block, say), out in the world. */
    public Vec3 toWorld(Vec3 plot) {
        return pose == null ? plot : pose.transformPosition(plot);
    }

    /** A point of the world in the ship's plot (where the ship's own blocks are). */
    public Vec3 toPlot(Vec3 world) {
        return pose == null ? world : pose.transformPositionInverse(world);
    }

    /** A way in the ship's own terms (a block face's normal, say), as it points in the world. */
    public Vec3 turn(Vec3 local) {
        Vector3d way = orientation.transform(new Vector3d(local.x, local.y, local.z));
        return new Vec3(way.x, way.y, way.z);
    }

    /** A way in the world, in the ship's own terms. */
    public Vec3 unturn(Vec3 world) {
        Vector3d way = orientation.transformInverse(new Vector3d(world.x, world.y, world.z));
        return new Vec3(way.x, way.y, way.z);
    }

    /** The middle of the ship's blocks, in the world (the hive's own block off a ship). */
    public Vec3 middle() {
        return toWorld(hull.getCenter());
    }
}
