package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** The last N slots are healers; assignment survives damage, repair and world reload. */
public record HiveSettings(int healers) {
    public static final HiveSettings DEFAULT = new HiveSettings(0);
    public static final Codec<HiveSettings> CODEC = Codec.intRange(0, HiveType.MAX_DRONES).xmap(HiveSettings::new, HiveSettings::healers);
    public static final StreamCodec<ByteBuf, HiveSettings> STREAM_CODEC = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSettings value) { buffer.writeShort(value.healers); }
        public HiveSettings decode(ByteBuf buffer) {
            int count = buffer.readUnsignedShort();
            if (count > HiveType.MAX_DRONES) throw new IllegalArgumentException("Invalid healer allocation");
            return new HiveSettings(count);
        }
    };
    public HiveSettings { healers = Math.clamp(healers, 0, HiveType.MAX_DRONES); }
    public int healerCount(int capacity) { return Math.min(healers, Math.clamp(capacity, 0, HiveType.MAX_DRONES)); }
    public int fighters(int capacity) { return Math.clamp(capacity, 0, HiveType.MAX_DRONES) - healerCount(capacity); }
    public boolean healer(int index, int capacity) { return index >= fighters(capacity) && index < capacity; }
}
