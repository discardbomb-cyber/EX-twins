package dev.hurtify.relicsaddon.ship;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Where a lance hive's three drones are, the same maths on the server (which fires from the focus) and on clients
 * (which draw the drones). Docked, the three lie in a ring just off the launch face; deployed, they stand further out
 * in a wider ring round the line of fire, their beams meeting at a focus in front of them, from which the lance runs.
 */
public final class LanceShape {
    /** How far out from the hive's centre the docked ring and the turret stand, their rings' radii, and the focus beyond the turret. */
    public static final double DOCK_OUT = 1.05, DOCK_RING = .56, TURRET_OUT = 2.3, TURRET_RING = 1.05, FOCUS = 1.25;
    /** A drone's size, in blocks. */
    public static final double DRONE = .9;
    public static final int DRONES = 3, DEPLOY_TICKS = 14;

    /** The turret's middle, in the world. */
    public static Vec3 mount(Vec3 centre, Vec3 normal) {
        return centre.add(normal.scale(TURRET_OUT));
    }

    /** Where the lance leaves the turret, in the world. */
    public static Vec3 focus(Vec3 centre, Vec3 normal, Vec3 aim) {
        return mount(centre, normal).add(aim.scale(FOCUS));
    }

    /**
     * The drones' centres in the world, for a hive at {@code centre} whose launch face points along {@code normal} and
     * whose ring's rest axis is {@code across} (both turned into the world), aiming along {@code aim}, {@code deploy}
     * of the way out (0 docked, 1 standing as a turret), its ring turned by {@code spin}.
     */
    public static Vec3[] drones(Vec3 centre, Vec3 normal, Vec3 across, Vec3 aim, double deploy, double spin) {
        double t = smooth(deploy);
        Vec3 side = normal.cross(across);
        // The ring's axes carried round from the face's normal onto the aim, so the ring never flips as the aim moves.
        Quaterniond carry = new Quaterniond().rotationTo(normal.x, normal.y, normal.z, aim.x, aim.y, aim.z);
        Vec3 u = rotate(carry, across), v = rotate(carry, side);
        Vec3 dock = centre.add(normal.scale(DOCK_OUT)), mount = mount(centre, normal);
        Vec3[] out = new Vec3[DRONES];
        for (int k = 0; k < DRONES; k++) {
            double restAngle = Math.PI * 2 * k / DRONES, angle = restAngle + spin * t;
            Vec3 docked = dock.add(across.scale(Math.cos(restAngle) * DOCK_RING)).add(side.scale(Math.sin(restAngle) * DOCK_RING));
            Vec3 standing = mount.add(u.scale(Math.cos(angle) * TURRET_RING)).add(v.scale(Math.sin(angle) * TURRET_RING));
            out[k] = docked.lerp(standing, t);
        }
        return out;
    }

    /** A direction across the launch face, in the ship's own terms: the ring's rest axis. */
    public static Vec3 across(Direction facing) {
        return facing.getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
    }

    /** Turns {@code from} towards {@code to} by at most {@code step} radians (both unit vectors). */
    public static Vec3 turnTowards(Vec3 from, Vec3 to, double step) {
        double cos = Math.clamp(from.dot(to), -1, 1), angle = Math.acos(cos);
        if (angle <= step || angle < 1e-6) return to;
        Vec3 axis = from.cross(to);
        if (axis.lengthSqr() < 1e-12) axis = from.cross(Math.abs(from.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0));
        Quaterniond turn = new Quaterniond().rotateAxis(step, axis.x, axis.y, axis.z);
        return rotate(turn, from).normalize();
    }

    /** Keeps {@code aim} out of the hull behind the launch face: at most a little below the face's plane. */
    public static Vec3 clampToFace(Vec3 aim, Vec3 normal, double lowest) {
        double along = aim.dot(normal);
        if (along >= lowest) return aim;
        Vec3 flat = aim.subtract(normal.scale(along));
        if (flat.lengthSqr() < 1e-9) return normal;
        return flat.normalize().scale(Math.sqrt(1 - lowest * lowest)).add(normal.scale(lowest)).normalize();
    }

    static Vec3 rotate(Quaterniond rotation, Vec3 vector) {
        Vector3d out = rotation.transform(new Vector3d(vector.x, vector.y, vector.z));
        return new Vec3(out.x, out.y, out.z);
    }

    public static double smooth(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private LanceShape() {
    }
}
