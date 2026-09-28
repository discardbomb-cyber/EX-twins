package dev.hurtify.relicsaddon.drone;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Transient support deployment; no persisted healing session can resume on another owner. */
public record HiveSupportState(boolean active, long changedAt) {
    public static final HiveSupportState DEFAULT = new HiveSupportState(false, 0);
    public static final StreamCodec<ByteBuf, HiveSupportState> STREAM_CODEC = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSupportState value) { buffer.writeBoolean(value.active).writeLong(value.changedAt); }
        public HiveSupportState decode(ByteBuf buffer) { return new HiveSupportState(buffer.readBoolean(), buffer.readLong()); }
    };
    public HiveSupportState { changedAt = Math.max(0, changedAt); }
}
