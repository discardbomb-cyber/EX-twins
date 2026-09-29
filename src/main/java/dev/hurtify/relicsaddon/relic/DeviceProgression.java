package dev.hurtify.relicsaddon.relic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Persistent, per-stack progression owned entirely by this mod. */
public record DeviceProgression(int experience, int level, int points, int modules, int upgrades) {
    public static final int MAX_LEVEL = 10;
    public static final int MODULE_SLOTS = 3;
    public static final DeviceProgression DEFAULT = new DeviceProgression(0, 0, 0, 0, 0);
    public static final Codec<DeviceProgression> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("experience").forGetter(DeviceProgression::experience),
            Codec.intRange(0, MAX_LEVEL).fieldOf("level").forGetter(DeviceProgression::level),
            Codec.INT.fieldOf("points").forGetter(DeviceProgression::points),
            Codec.INT.fieldOf("modules").forGetter(DeviceProgression::modules),
            Codec.INT.fieldOf("upgrades").forGetter(DeviceProgression::upgrades)
    ).apply(instance, DeviceProgression::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeviceProgression> STREAM_CODEC = new StreamCodec<>() {
        @Override public DeviceProgression decode(RegistryFriendlyByteBuf buffer) {
            return new DeviceProgression(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()).normalized();
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, DeviceProgression value) {
            DeviceProgression state = value.normalized();
            buffer.writeVarInt(state.experience).writeVarInt(state.level).writeVarInt(state.points).writeVarInt(state.modules).writeVarInt(state.upgrades);
        }
    };

    public DeviceProgression normalized() {
        return new DeviceProgression(Math.max(0, experience), Math.clamp(level, 0, MAX_LEVEL), Math.max(0, points), modules, upgrades);
    }

    public int rank(String id) { return (upgrades >>> (DeviceUpgrade.byId(id).bit() * 2)) & 3; }
    public boolean hasModule(int slot) { return slot >= 0 && slot < MODULE_SLOTS && ((modules >>> slot) & 1) != 0; }
    public DeviceProgression withModule(int slot, boolean installed) {
        if (slot < 0 || slot >= MODULE_SLOTS) return this;
        int mask = 1 << slot;
        return new DeviceProgression(experience, level, points, installed ? modules | mask : modules & ~mask, upgrades);
    }
    public DeviceProgression withRank(String id, int rank) {
        DeviceUpgrade upgrade = DeviceUpgrade.byId(id);
        int shift = upgrade.bit() * 2;
        int mask = 3 << shift;
        return new DeviceProgression(experience, level, points, modules, (upgrades & ~mask) | (Math.clamp(rank, 0, 3) << shift));
    }
    public DeviceProgression withPoints(int value) { return new DeviceProgression(experience, level, Math.max(0, value), modules, upgrades); }
}
