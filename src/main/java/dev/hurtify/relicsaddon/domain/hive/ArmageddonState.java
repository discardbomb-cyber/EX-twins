package dev.hurtify.relicsaddon.domain.hive;

import dev.hurtify.relicsaddon.domain.math.Vec3d;

/**
 * A hive's Armageddon in progress: which hive fires it (a Twins, a Mana or an RF hive, each with its own
 * {@link ArmageddonTimeline}), when it started, where its construct hangs and where the shot lands, the face of
 * the block it lands on (the top of it when it lands on nothing) and how much room there is out from that face
 * (how far out the RF ball can hang before it comes in), and whether a worn shield of the same family feeds it.
 * Transient, like the combat state: it only lives while the shot is under way.
 */
public record ArmageddonState(HiveType type, long startedAt, Vec3d origin, Vec3d target, Face face, double room, boolean shieldLinked) {
    /** Block face IDs follow the released network protocol, without depending on the game enum. */
    public enum Face {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
        private final Vec3d normal;
        Face(int x, int y, int z) { normal = new Vec3d(x, y, z); }
        public Vec3d normal() { return normal; }
        public int get3DDataValue() { return ordinal(); }
        public static Face from3DDataValue(int id) { return values()[Math.abs(id % values().length)]; }
    }

    /** The most room out from the landing face that matters: nothing hangs further out. */
    public static final double MOST_ROOM = 256;

    public ArmageddonState {
        // Every hive family fires an Armageddon of its own; a missing family is taken for Twins. A start before the
        // world's first tick is fine (a head start in a young world): only the places, the face and the room are checked.
        type = type == null ? HiveType.TWINS : type;
        origin = finite(origin);
        target = finite(target);
        face = face == null ? Face.UP : face;
        room = Double.isFinite(room) ? Math.clamp(room, 0, MOST_ROOM) : MOST_ROOM;
    }

    /** A shot landing on the top of a block, with all the room it could want over it. */
    public ArmageddonState(HiveType type, long startedAt, Vec3d origin, Vec3d target, boolean shieldLinked) {
        this(type, startedAt, origin, target, Face.UP, MOST_ROOM, shieldLinked);
    }

    /** The way out of the face the shot lands on, as a unit vector. */
    public Vec3d normal() {
        return face.normal();
    }

    /** This Armageddon's course in time. */
    public ArmageddonTimeline timeline() {
        return ArmageddonTimeline.of(type);
    }

    /** Ticks since the start at {@code time}. */
    public double age(double time) {
        return time - startedAt;
    }

    /** Whether the shot is still under way at {@code now}: from the start until the drones are home. */
    public boolean running(long now) {
        return now >= startedAt && now < startedAt + timeline().end();
    }

    private static Vec3d finite(Vec3d value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z) ? value : Vec3d.ZERO;
    }


}
