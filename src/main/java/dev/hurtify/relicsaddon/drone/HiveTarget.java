package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;

/**
 * One creature a swarm is engaged with: its entity id, where its feet were when last recorded and its
 * size. The swarm's formation around it is computed from these values alone, so the server and every
 * client place drones the same way even where the creature itself is not loaded.
 */
public record HiveTarget(int id, double x, double y, double z, double width, double height) {
    public static final Codec<HiveTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(HiveTarget::id),
            Codec.DOUBLE.fieldOf("x").forGetter(HiveTarget::x),
            Codec.DOUBLE.fieldOf("y").forGetter(HiveTarget::y),
            Codec.DOUBLE.fieldOf("z").forGetter(HiveTarget::z),
            Codec.DOUBLE.optionalFieldOf("width", .6).forGetter(HiveTarget::width),
            Codec.DOUBLE.optionalFieldOf("height", 1.8).forGetter(HiveTarget::height)
    ).apply(i, HiveTarget::new));

    public HiveTarget {
        x = finite(x);
        y = finite(y);
        z = finite(z);
        width = Double.isFinite(width) ? Math.clamp(width, .1, 16) : .6;
        height = Double.isFinite(height) ? Math.clamp(height, .1, 16) : 1.8;
    }

    public HiveTarget(int id, Vec3 feet, double width, double height) {
        this(id, feet.x, feet.y, feet.z, width, height);
    }

    public Vec3 feet() {
        return new Vec3(x, y, z);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -30_000_000D, 30_000_000D) : 0;
    }
}
