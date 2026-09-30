package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** A hive's standing orders: how many of its last drones heal, and how the fighters attack. Survives damage, repair and reloads. */
public record HiveSettings(int healers, AttackMode mode) {
    public static final HiveSettings DEFAULT = new HiveSettings(0, AttackMode.BARRAGE);
    private static final Codec<AttackMode> MODE = Codec.STRING.xmap(HiveSettings::modeById, AttackMode::id);
    private static final Codec<HiveSettings> RECORD = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, HiveType.MAX_DRONES).fieldOf("healers").forGetter(HiveSettings::healers),
            MODE.optionalFieldOf("mode", AttackMode.BARRAGE).forGetter(HiveSettings::mode)
    ).apply(i, HiveSettings::new));
    /** Older saves stored only the healer count as a bare number. */
    public static final Codec<HiveSettings> CODEC = Codec.withAlternative(RECORD,
            Codec.intRange(0, HiveType.MAX_DRONES).xmap(healers -> new HiveSettings(healers, AttackMode.BARRAGE), HiveSettings::healers));
    public static final StreamCodec<ByteBuf, HiveSettings> STREAM_CODEC = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSettings value) { buffer.writeShort(value.healers).writeByte(value.mode.ordinal()); }
        public HiveSettings decode(ByteBuf buffer) {
            int count = buffer.readUnsignedShort();
            if (count > HiveType.MAX_DRONES) throw new IllegalArgumentException("Invalid healer allocation");
            return new HiveSettings(count, AttackMode.byOrdinal(buffer.readUnsignedByte()));
        }
    };

    public HiveSettings {
        healers = Math.clamp(healers, 0, HiveType.MAX_DRONES);
        if (mode == null) mode = AttackMode.BARRAGE;
    }

    public HiveSettings withHealers(int value) { return new HiveSettings(value, mode); }
    public HiveSettings withMode(AttackMode value) { return new HiveSettings(healers, value); }
    public int healerCount(int capacity) { return Math.min(healers, Math.clamp(capacity, 0, HiveType.MAX_DRONES)); }
    public int fighters(int capacity) { return Math.clamp(capacity, 0, HiveType.MAX_DRONES) - healerCount(capacity); }
    public boolean healer(int index, int capacity) { return index >= fighters(capacity) && index < capacity; }

    private static AttackMode modeById(String id) {
        for (AttackMode mode : AttackMode.values()) if (mode.id().equals(id)) return mode;
        return AttackMode.BARRAGE;
    }
}
