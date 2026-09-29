package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

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

        // Axis-aligned normals survive the constructor's re-normalisation bit for bit.
        roundTrip(ShieldImpact.STREAM_CODEC, new ShieldImpact(new Vec3(1, 0, 0), 99L, 1, 6, true, List.of(3), 1.5, 0), "absorbed hit");
        ShieldImpact strike = ShieldImpact.strike(new Vec3(0, 0, 1), 1_234L, 2, 4.5F, .3F);
        roundTrip(ShieldImpact.STREAM_CODEC, strike, "shield strike");
        require(strike.isStrike() && !new ShieldImpact(new Vec3(0, 1, 0), 5L, 0, 2, false).isStrike(), "Only strikes are marked as strikes");
        System.out.println("Network codecs: 500-drone swarm (" + swarmBytes + " bytes), shots, batteries and shield impacts round-trip exactly");
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
