package dev.hurtify.relicsaddon.adapter.out.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Saved and synced forms of the device components (device_energy and device_progression), moved
 * unchanged out of the records they encode. The bytes and NBT are frozen by golden/codecs.txt.
 */
public final class DeviceCodecs {
    public static final Codec<DeviceEnergy> ENERGY = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("rf").forGetter(DeviceEnergy::rf),
            Codec.INT.fieldOf("mana").forGetter(DeviceEnergy::mana),
            Codec.BOOL.optionalFieldOf("rf_on", true).forGetter(DeviceEnergy::rfOn),
            Codec.BOOL.optionalFieldOf("mana_on", true).forGetter(DeviceEnergy::manaOn),
            Codec.STRING.optionalFieldOf("source", "auto").xmap(DeviceEnergy.ManaSource::byId, DeviceEnergy.ManaSource::id).forGetter(DeviceEnergy::source)
    ).apply(instance, DeviceEnergy::new));

    public static final StreamCodec<ByteBuf, DeviceEnergy> ENERGY_STREAM = new StreamCodec<>() {
        @Override public DeviceEnergy decode(ByteBuf buffer) {
            int rf = net.minecraft.network.VarInt.read(buffer), mana = net.minecraft.network.VarInt.read(buffer);
            byte flags = buffer.readByte();
            return new DeviceEnergy(rf, mana, (flags & 1) != 0, (flags & 2) != 0,
                    DeviceEnergy.ManaSource.values()[Math.clamp(flags >> 2, 0, DeviceEnergy.ManaSource.values().length - 1)]);
        }

        @Override public void encode(ByteBuf buffer, DeviceEnergy value) {
            net.minecraft.network.VarInt.write(buffer, value.rf());
            net.minecraft.network.VarInt.write(buffer, value.mana());
            buffer.writeByte((value.rfOn() ? 1 : 0) | (value.manaOn() ? 2 : 0) | value.source().ordinal() << 2);
        }
    };

    public static final Codec<DeviceProgression> PROGRESSION = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("experience").forGetter(DeviceProgression::experience),
            Codec.intRange(0, DeviceProgression.MAX_LEVEL).fieldOf("level").forGetter(DeviceProgression::level),
            Codec.INT.fieldOf("points").forGetter(DeviceProgression::points),
            Codec.INT.fieldOf("upgrades").forGetter(DeviceProgression::upgrades)
    ).apply(instance, DeviceProgression::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeviceProgression> PROGRESSION_STREAM = new StreamCodec<>() {
        @Override public DeviceProgression decode(RegistryFriendlyByteBuf buffer) {
            return new DeviceProgression(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()).normalized();
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, DeviceProgression value) {
            DeviceProgression state = value.normalized();
            buffer.writeVarInt(state.experience()).writeVarInt(state.level()).writeVarInt(state.points()).writeVarInt(state.upgrades());
        }
    };

    private DeviceCodecs() {
    }
}
