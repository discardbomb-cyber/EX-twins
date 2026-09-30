package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.relic.RelicRole;
import java.util.Locale;
import net.minecraft.ChatFormatting;

/**
 * The three ship shield families. Each has a generator block, a drone dock block and an emitter
 * drone item; a generator only takes docks and drones of its own family.
 */
public enum ShipFamily {
    RF(RelicRole.RF_SHIP_GENERATOR, RelicRole.RF_DRONE_DOCK, ChatFormatting.AQUA),
    MANA(RelicRole.MANA_SHIP_GENERATOR, RelicRole.MANA_DRONE_DOCK, ChatFormatting.GREEN),
    TWINS(RelicRole.TWINS_SHIP_GENERATOR, RelicRole.TWINS_DRONE_DOCK, ChatFormatting.LIGHT_PURPLE);

    public final RelicRole generator;
    public final RelicRole dock;
    public final ChatFormatting style;

    ShipFamily(RelicRole generator, RelicRole dock, ChatFormatting style) {
        this.generator = generator;
        this.dock = dock;
        this.style = style;
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String generatorId() { return generator.itemId(); }
    public String dockId() { return dock.itemId(); }
    public String droneId() { return id() + "_emitter_drone"; }

    public static ShipFamily of(RelicRole role) {
        for (ShipFamily family : values()) if (family.generator == role || family.dock == role) return family;
        throw new IllegalArgumentException("Not a ship device role: " + role);
    }
}
