package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.domain.hive.HiveType;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * The three kinds of ship hive, blocks built into a ship (or a base) that fight on their own. Their drones are big,
 * a block across, and each kind works them in a different number at once; each kind takes the look of one hive family.
 * <ul>
 *   <li>AEGIS, one by one: each drone takes its own place round the whole ship and holds up its piece of a shield,
 *   stopping what is shot at the ship and guarding whoever is aboard.</li>
 *   <li>ESCORT, two by two: each linked pair flies out on its own with a small swarm of its own, keeps watch round the
 *   ship and goes after whatever threatens it, as far as its leash lets it, coming home to be mended.</li>
 *   <li>LANCE, three together: the drones join over the hive into a turret and hold a continuous beam on the worst
 *   threat in reach, until the turret overheats and must cool.</li>
 * </ul>
 */
public enum ShipHiveKind implements StringRepresentable {
    AEGIS("aegis_hive", HiveType.MANA, 6, 1, 400_000),
    ESCORT("escort_hive", HiveType.RF, 4, 2, 400_000),
    LANCE("lance_hive", HiveType.TWINS, 3, 3, 400_000);

    public static final com.mojang.serialization.Codec<ShipHiveKind> CODEC = StringRepresentable.fromEnum(ShipHiveKind::values);

    /** The block's id, the family whose drone model its drones wear, how many drones it holds, how many work as one, and its battery in FE. */
    public final String id;
    public final HiveType look;
    public final int drones, linked, capacity;

    ShipHiveKind(String id, HiveType look, int drones, int linked, int capacity) {
        this.id = id;
        this.look = look;
        this.drones = drones;
        this.linked = linked;
        this.capacity = capacity;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
