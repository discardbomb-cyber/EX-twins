package dev.hurtify.relicsaddon.power;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import net.minecraft.network.codec.StreamCodec;

/**
 * Charge of a device's built-in batteries. {@code rf} is stored in FE, {@code mana} in battery
 * points; each battery has its own switch, and the mana battery remembers where it refills from.
 */
public record DeviceEnergy(int rf, int mana, boolean rfOn, boolean manaOn, ManaSource source) {
    /** Where an empty mana battery draws from: magic mods first, magic only, or player experience. */
    public enum ManaSource {
        AUTO, MAGIC, EXPERIENCE;

        public ManaSource next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static ManaSource byId(String id) {
            for (ManaSource value : values()) if (value.id().equals(id)) return value;
            return AUTO;
        }
    }

    public static final DeviceEnergy EMPTY = new DeviceEnergy(0, 0, true, true, ManaSource.AUTO);

    public static final Codec<DeviceEnergy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("rf").forGetter(DeviceEnergy::rf),
            Codec.INT.fieldOf("mana").forGetter(DeviceEnergy::mana),
            Codec.BOOL.optionalFieldOf("rf_on", true).forGetter(DeviceEnergy::rfOn),
            Codec.BOOL.optionalFieldOf("mana_on", true).forGetter(DeviceEnergy::manaOn),
            Codec.STRING.optionalFieldOf("source", "auto").xmap(ManaSource::byId, ManaSource::id).forGetter(DeviceEnergy::source)
    ).apply(instance, DeviceEnergy::new));

    public static final StreamCodec<ByteBuf, DeviceEnergy> STREAM_CODEC = new StreamCodec<>() {
        @Override public DeviceEnergy decode(ByteBuf buffer) {
            int rf = net.minecraft.network.VarInt.read(buffer), mana = net.minecraft.network.VarInt.read(buffer);
            byte flags = buffer.readByte();
            return new DeviceEnergy(rf, mana, (flags & 1) != 0, (flags & 2) != 0,
                    ManaSource.values()[Math.clamp(flags >> 2, 0, ManaSource.values().length - 1)]);
        }

        @Override public void encode(ByteBuf buffer, DeviceEnergy value) {
            net.minecraft.network.VarInt.write(buffer, value.rf);
            net.minecraft.network.VarInt.write(buffer, value.mana);
            buffer.writeByte((value.rfOn ? 1 : 0) | (value.manaOn ? 2 : 0) | value.source.ordinal() << 2);
        }
    };

    public DeviceEnergy {
        rf = Math.max(0, rf);
        mana = Math.max(0, mana);
        if (source == null) source = ManaSource.AUTO;
    }

    public DeviceEnergy withRf(int value) { return new DeviceEnergy(value, mana, rfOn, manaOn, source); }
    public DeviceEnergy withMana(int value) { return new DeviceEnergy(rf, value, rfOn, manaOn, source); }
    public DeviceEnergy withRfOn(boolean value) { return new DeviceEnergy(rf, mana, value, manaOn, source); }
    public DeviceEnergy withManaOn(boolean value) { return new DeviceEnergy(rf, mana, rfOn, value, source); }
    public DeviceEnergy withSource(ManaSource value) { return new DeviceEnergy(rf, mana, rfOn, manaOn, value); }
}
