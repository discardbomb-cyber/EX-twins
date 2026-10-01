package dev.hurtify.relicsaddon.shipshield;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * What a generator tells clients about its shield, in its block update tag: whether the shield
 * stands, how the shell is traced (the client traces the same shell from its own blocks), where
 * the drones are and what each is doing, which seats are held, and the integrity of every patch
 * on every layer (outermost layer last, seat-major within a layer). Positions are floats from the
 * generator's own block (the origin), so a shield in a plot millions of blocks out keeps its
 * shape; the view travels as bytes written with VarInts.
 *
 * @param seats  three floats a seat (x, y, z from the origin); {@code drones} three floats a drone;
 *               {@code states} one entry a drone ({@link EmitterDrone.State} ordinal)
 */
public record ShipShieldView(boolean active, double offset, int layers, int cellLimit, long overloadedUntil, int patchMax,
        BlockPos origin, List<Float> seats, List<Float> drones, List<Integer> states, List<Integer> integrity) {
    public static final ShipShieldView NONE = new ShipShieldView(false, 2, 1, 4096, 0, 1, BlockPos.ZERO, List.of(), List.of(), List.of(), List.of());

    public static final Codec<ShipShieldView> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("active", false).forGetter(ShipShieldView::active),
            Codec.DOUBLE.optionalFieldOf("offset", 2.0).forGetter(ShipShieldView::offset),
            Codec.INT.optionalFieldOf("layers", 1).forGetter(ShipShieldView::layers),
            Codec.INT.optionalFieldOf("cells", 4096).forGetter(ShipShieldView::cellLimit),
            Codec.LONG.optionalFieldOf("overloaded_until", 0L).forGetter(ShipShieldView::overloadedUntil),
            Codec.INT.optionalFieldOf("patch_max", 1).forGetter(ShipShieldView::patchMax),
            BlockPos.CODEC.optionalFieldOf("origin", BlockPos.ZERO).forGetter(ShipShieldView::origin),
            Codec.FLOAT.listOf().optionalFieldOf("seats", List.of()).forGetter(ShipShieldView::seats),
            Codec.FLOAT.listOf().optionalFieldOf("drones", List.of()).forGetter(ShipShieldView::drones),
            Codec.INT.listOf().optionalFieldOf("states", List.of()).forGetter(ShipShieldView::states),
            Codec.INT.listOf().optionalFieldOf("integrity", List.of()).forGetter(ShipShieldView::integrity)
    ).apply(instance, ShipShieldView::new));

    /** The view as bytes: counts and integers as VarInts, positions as floats. */
    public static final StreamCodec<ByteBuf, ShipShieldView> STREAM_CODEC = new StreamCodec<>() {
        @Override public ShipShieldView decode(ByteBuf buffer) {
            boolean active = buffer.readBoolean();
            double offset = buffer.readDouble();
            int layers = VarInt.read(buffer), cells = VarInt.read(buffer);
            long overloaded = VarLong.read(buffer);
            int patchMax = VarInt.read(buffer);
            BlockPos origin = BlockPos.of(buffer.readLong());
            List<Float> seats = floats(buffer), drones = floats(buffer);
            int count = VarInt.read(buffer);
            List<Integer> states = new ArrayList<>(count);
            for (int index = 0; index < count; index++) states.add(VarInt.read(buffer));
            count = VarInt.read(buffer);
            List<Integer> integrity = new ArrayList<>(count);
            for (int index = 0; index < count; index++) integrity.add(VarInt.read(buffer));
            return new ShipShieldView(active, offset, layers, cells, overloaded, patchMax, origin, seats, drones, states, integrity);
        }

        @Override public void encode(ByteBuf buffer, ShipShieldView value) {
            buffer.writeBoolean(value.active);
            buffer.writeDouble(value.offset);
            VarInt.write(buffer, value.layers);
            VarInt.write(buffer, value.cellLimit);
            VarLong.write(buffer, value.overloadedUntil);
            VarInt.write(buffer, value.patchMax);
            buffer.writeLong(value.origin.asLong());
            floats(buffer, value.seats);
            floats(buffer, value.drones);
            VarInt.write(buffer, value.states.size());
            for (int state : value.states) VarInt.write(buffer, state);
            VarInt.write(buffer, value.integrity.size());
            for (int patch : value.integrity) VarInt.write(buffer, patch);
        }

        private static List<Float> floats(ByteBuf buffer) {
            int count = VarInt.read(buffer);
            List<Float> values = new ArrayList<>(count);
            for (int index = 0; index < count; index++) values.add(buffer.readFloat());
            return values;
        }

        private static void floats(ByteBuf buffer, List<Float> values) {
            VarInt.write(buffer, values.size());
            for (float value : values) buffer.writeFloat(value);
        }
    };

    public ShipShieldView {
        layers = Math.clamp(layers, 1, 3);
        cellLimit = Math.max(64, cellLimit);
        patchMax = Math.max(1, patchMax);
        origin = origin.immutable();
        seats = List.copyOf(seats);
        drones = List.copyOf(drones);
        states = List.copyOf(states);
        integrity = List.copyOf(integrity);
    }

    public int seatCount() { return seats.size() / 3; }
    public int droneCount() { return Math.min(drones.size() / 3, states.size()); }
    public EmitterDrone.State state(int drone) {
        return EmitterDrone.State.values()[Math.clamp(states.get(drone), 0, EmitterDrone.State.values().length - 1)];
    }

    /** A seat's position in the structure's coordinates. */
    public Vec3 seat(int seat) {
        return new Vec3(origin.getX() + (double) seats.get(seat * 3), origin.getY() + (double) seats.get(seat * 3 + 1), origin.getZ() + (double) seats.get(seat * 3 + 2));
    }

    /** A drone's position in the structure's coordinates. */
    public Vec3 drone(int drone) {
        return new Vec3(origin.getX() + (double) drones.get(drone * 3), origin.getY() + (double) drones.get(drone * 3 + 1), origin.getZ() + (double) drones.get(drone * 3 + 2));
    }

    /** The integrity of a seat's patch on a layer (0 = innermost), 0 when unknown. */
    public int integrity(int layer, int seat) {
        int index = layer * seatCount() + seat;
        return index < 0 || index >= integrity.size() ? 0 : integrity.get(index);
    }

    /** Whether the drone of a seat holds it (the seat has a drone and it is at its seat). */
    public boolean held(int seat) {
        return seat < droneCount() && state(seat) == EmitterDrone.State.HOLDING;
    }

    /** The view as bytes for a block's update tag, and back. */
    public byte[] toBytes() {
        ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        STREAM_CODEC.encode(buffer, this);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    public static ShipShieldView fromBytes(byte[] bytes) {
        try {
            return STREAM_CODEC.decode(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        } catch (RuntimeException malformed) {
            return NONE;
        }
    }
}
