package dev.hurtify.relicsaddon.shipshield;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a ship device block reports to its console: whether it is switched on, the last reason it
 * could not run (a translation key with one detail argument, empty when fine), the blocks of the
 * structure it stands on, the drones it holds and can hold (a generator: the drones of all its docks
 * and how many the structure needs), and how many docks and item stores it found. Persistent on the block item so a picked-up generator keeps its readings, and synced
 * so the console can show them.
 */
public record ShipDeviceState(boolean enabled, String notice, String detail, int structureBlocks, int drones, int droneCapacity, int dronesWanted, int docks, int stores) {
    public static final ShipDeviceState DEFAULT = new ShipDeviceState(false, "", "", 0, 0, 0, 0, 0, 0);

    public static final Codec<ShipDeviceState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("enabled", false).forGetter(ShipDeviceState::enabled),
            Codec.STRING.optionalFieldOf("notice", "").forGetter(ShipDeviceState::notice),
            Codec.STRING.optionalFieldOf("detail", "").forGetter(ShipDeviceState::detail),
            Codec.INT.optionalFieldOf("structure_blocks", 0).forGetter(ShipDeviceState::structureBlocks),
            Codec.INT.optionalFieldOf("drones", 0).forGetter(ShipDeviceState::drones),
            Codec.INT.optionalFieldOf("drone_capacity", 0).forGetter(ShipDeviceState::droneCapacity),
            Codec.INT.optionalFieldOf("drones_wanted", 0).forGetter(ShipDeviceState::dronesWanted),
            Codec.INT.optionalFieldOf("docks", 0).forGetter(ShipDeviceState::docks),
            Codec.INT.optionalFieldOf("stores", 0).forGetter(ShipDeviceState::stores)
    ).apply(instance, ShipDeviceState::new));

    public static final StreamCodec<ByteBuf, ShipDeviceState> STREAM_CODEC = new StreamCodec<>() {
        @Override public ShipDeviceState decode(ByteBuf buffer) {
            boolean enabled = buffer.readBoolean();
            String notice = ByteBufCodecs.STRING_UTF8.decode(buffer);
            String detail = ByteBufCodecs.STRING_UTF8.decode(buffer);
            return new ShipDeviceState(enabled, notice, detail, VarInt.read(buffer), VarInt.read(buffer), VarInt.read(buffer), VarInt.read(buffer), VarInt.read(buffer), VarInt.read(buffer));
        }

        @Override public void encode(ByteBuf buffer, ShipDeviceState value) {
            buffer.writeBoolean(value.enabled);
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.notice);
            ByteBufCodecs.STRING_UTF8.encode(buffer, value.detail);
            VarInt.write(buffer, value.structureBlocks);
            VarInt.write(buffer, value.drones);
            VarInt.write(buffer, value.droneCapacity);
            VarInt.write(buffer, value.dronesWanted);
            VarInt.write(buffer, value.docks);
            VarInt.write(buffer, value.stores);
        }
    };

    public ShipDeviceState {
        if (notice == null) notice = "";
        if (detail == null) detail = "";
        structureBlocks = Math.max(0, structureBlocks);
        drones = Math.max(0, drones);
        droneCapacity = Math.max(0, droneCapacity);
        dronesWanted = Math.max(0, dronesWanted);
        docks = Math.max(0, docks);
        stores = Math.max(0, stores);
    }

    public ShipDeviceState withEnabled(boolean value) { return new ShipDeviceState(value, notice, detail, structureBlocks, drones, droneCapacity, dronesWanted, docks, stores); }
    public ShipDeviceState withNotice(String key, String argument) { return new ShipDeviceState(enabled, key, argument, structureBlocks, drones, droneCapacity, dronesWanted, docks, stores); }
    public ShipDeviceState withStructure(int blocks, int dockCount, int storeCount) { return new ShipDeviceState(enabled, notice, detail, blocks, drones, droneCapacity, dronesWanted, dockCount, storeCount); }
    public ShipDeviceState withDrones(int count, int capacity, int wanted) { return new ShipDeviceState(enabled, notice, detail, structureBlocks, count, capacity, wanted, docks, stores); }
}
