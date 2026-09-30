package dev.hurtify.relicsaddon.drone;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * A hive's Armageddon in progress: which hive fires it (a Twins, a Mana or an RF hive, each with its own
 * {@link ArmageddonTimeline}), when it started, where its construct hangs and where the shot lands, and
 * whether a worn shield of the same family feeds it. Transient, like the combat state: it only lives
 * while the shot is under way.
 */
public record ArmageddonState(HiveType type, long startedAt, Vec3 origin, Vec3 target, boolean shieldLinked) {
    public static final StreamCodec<ByteBuf, ArmageddonState> STREAM_CODEC = new StreamCodec<>() {
        public void encode(ByteBuf buffer, ArmageddonState value) {
            VarInt.write(buffer, value.type.ordinal());
            VarLong.write(buffer, value.startedAt);
            write(buffer, value.origin);
            write(buffer, value.target);
            buffer.writeBoolean(value.shieldLinked);
        }

        public ArmageddonState decode(ByteBuf buffer) {
            int type = VarInt.read(buffer);
            return new ArmageddonState(type >= 0 && type < HiveType.values().length ? HiveType.values()[type] : HiveType.TWINS,
                    VarLong.read(buffer), read(buffer), read(buffer), buffer.readBoolean());
        }
    };

    public ArmageddonState {
        // Every hive family fires an Armageddon of its own; a missing family is taken for Twins. A start before the
        // world's first tick is fine (a head start in a young world): only the places are checked.
        type = type == null ? HiveType.TWINS : type;
        origin = finite(origin);
        target = finite(target);
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

    private static Vec3 finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z) ? value : Vec3.ZERO;
    }

    private static void write(ByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x).writeDouble(value.y).writeDouble(value.z);
    }

    private static Vec3 read(ByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
