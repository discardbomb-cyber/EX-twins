package dev.hurtify.relicsaddon.drone;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * A Twins hive's Armageddon in progress: when it started, where the cannon hangs and where the shot
 * lands, and whether a Twins shield feeds it. Transient, like the combat state: it only lives while
 * the shot is under way.
 */
public record ArmageddonState(long startedAt, Vec3 origin, Vec3 target, boolean shieldLinked) {
    public static final StreamCodec<ByteBuf, ArmageddonState> STREAM_CODEC = new StreamCodec<>() {
        public void encode(ByteBuf buffer, ArmageddonState value) {
            buffer.writeLong(value.startedAt);
            write(buffer, value.origin);
            write(buffer, value.target);
            buffer.writeBoolean(value.shieldLinked);
        }

        public ArmageddonState decode(ByteBuf buffer) {
            return new ArmageddonState(buffer.readLong(), read(buffer), read(buffer), buffer.readBoolean());
        }
    };

    public ArmageddonState {
        // A start before the world's first tick is fine (a head start in a young world): only the places are checked.
        origin = finite(origin);
        target = finite(target);
    }

    /** Ticks since the start at {@code time}. */
    public double age(double time) {
        return time - startedAt;
    }

    /** Whether the shot is still under way at {@code now}: from the start until the drones are home. */
    public boolean running(long now) {
        return now >= startedAt && now < startedAt + Armageddon.END;
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
