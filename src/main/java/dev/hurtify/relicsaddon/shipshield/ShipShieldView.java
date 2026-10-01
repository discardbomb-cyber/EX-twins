package dev.hurtify.relicsaddon.shipshield;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

/**
 * What a generator tells clients about its shield, in its block update tag: whether the shield
 * stands, how the shell is traced (the client traces the same shell from its own blocks), where
 * the drones are and what each is doing, which seats are held, and the integrity of every patch
 * on every layer (outermost layer last, seat-major within a layer).
 *
 * @param drones three floats a drone (x, y, z in the structure's coordinates), {@code states} one
 *               byte a drone ({@link EmitterDrone.State} ordinal), {@code seats} three floats a seat
 */
public record ShipShieldView(boolean active, double offset, int layers, int cellLimit, long overloadedUntil, int patchMax,
        List<Float> seats, List<Float> drones, List<Integer> states, List<Integer> integrity) {
    public static final ShipShieldView NONE = new ShipShieldView(false, 2, 1, 4096, 0, 1, List.of(), List.of(), List.of(), List.of());

    public static final Codec<ShipShieldView> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("active", false).forGetter(ShipShieldView::active),
            Codec.DOUBLE.optionalFieldOf("offset", 2.0).forGetter(ShipShieldView::offset),
            Codec.INT.optionalFieldOf("layers", 1).forGetter(ShipShieldView::layers),
            Codec.INT.optionalFieldOf("cells", 4096).forGetter(ShipShieldView::cellLimit),
            Codec.LONG.optionalFieldOf("overloaded_until", 0L).forGetter(ShipShieldView::overloadedUntil),
            Codec.INT.optionalFieldOf("patch_max", 1).forGetter(ShipShieldView::patchMax),
            Codec.FLOAT.listOf().optionalFieldOf("seats", List.of()).forGetter(ShipShieldView::seats),
            Codec.FLOAT.listOf().optionalFieldOf("drones", List.of()).forGetter(ShipShieldView::drones),
            Codec.INT.listOf().optionalFieldOf("states", List.of()).forGetter(ShipShieldView::states),
            Codec.INT.listOf().optionalFieldOf("integrity", List.of()).forGetter(ShipShieldView::integrity)
    ).apply(instance, ShipShieldView::new));

    public ShipShieldView {
        layers = Math.clamp(layers, 1, 3);
        cellLimit = Math.max(64, cellLimit);
        patchMax = Math.max(1, patchMax);
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

    /** The integrity of a seat's patch on a layer (0 = innermost), 0 when unknown. */
    public int integrity(int layer, int seat) {
        int index = layer * seatCount() + seat;
        return index < 0 || index >= integrity.size() ? 0 : integrity.get(index);
    }

    /** Whether the drone of a seat holds it (the seat has a drone and it is at its seat). */
    public boolean held(int seat) {
        return seat < droneCount() && state(seat) == EmitterDrone.State.HOLDING;
    }
}
