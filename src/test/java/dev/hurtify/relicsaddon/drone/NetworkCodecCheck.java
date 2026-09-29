package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.power.DeviceEnergy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.StreamCodec;

/** Every synced component must read back exactly what it wrote, or item sync packets desynchronise. */
public final class NetworkCodecCheck {
    public static void main(String[] args) {
        List<HiveStackState.Unit> units = new ArrayList<>();
        for (int index = 0; index < HiveType.MAX_DRONES; index++) {
            units.add(new HiveStackState.Unit(index % 61, 1_000_000L + index, index % 3 == 0 ? -1 : 2_000_000L + index,
                    index * .01F, -index * .02F, index * .03F, 3_000_000L + index));
        }
        HiveStackState swarm = new HiveStackState(true, units);
        int swarmBytes = roundTrip(HiveStackState.STREAM_CODEC, swarm, "full swarm");

        List<HiveCombatState.Shot> shots = new ArrayList<>();
        for (int index = 0; index < HiveCombatState.MAX_SHOTS; index++) {
            shots.add(new HiveCombatState.Shot(HiveType.MAX_DRONES - 1 - index, 5_000L + index, index % 4, 1, 2, 3, 4, 5, 6, 5_010L + index));
        }
        roundTrip(HiveCombatState.STREAM_CODEC, new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, shots), "combat shots");

        for (DeviceEnergy.ManaSource source : DeviceEnergy.ManaSource.values()) {
            roundTrip(DeviceEnergy.STREAM_CODEC, new DeviceEnergy(1_000_000, 100_000, false, true, source), "battery " + source);
        }
        System.out.println("Network codecs: 500-drone swarm (" + swarmBytes + " bytes), shots and batteries round-trip exactly");
    }

    private static <T> int roundTrip(StreamCodec<ByteBuf, T> codec, T value, String label) {
        ByteBuf buffer = Unpooled.buffer();
        codec.encode(buffer, value);
        int size = buffer.readableBytes();
        T decoded = codec.decode(buffer);
        require(decoded.equals(value), label + " changed in transit");
        require(buffer.readableBytes() == 0, label + " left " + buffer.readableBytes() + " unread bytes");
        return size;
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
