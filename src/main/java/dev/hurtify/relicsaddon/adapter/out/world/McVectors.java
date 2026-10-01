package dev.hurtify.relicsaddon.adapter.out.world;

import dev.hurtify.relicsaddon.domain.math.Vec3d;
import net.minecraft.world.phys.Vec3;

/**
 * Exact conversions between Minecraft's {@code Vec3} and the domain's {@link Vec3d}: the three doubles
 * are copied unchanged, so a value survives any number of round trips bit for bit. A null stays null,
 * so a converted call behaves exactly like the call it replaces.
 */
public final class McVectors {
    public static Vec3d toDomain(Vec3 vector) {
        return vector == null ? null : new Vec3d(vector.x, vector.y, vector.z);
    }

    public static Vec3 toMc(Vec3d vector) {
        return vector == null ? null : new Vec3(vector.x, vector.y, vector.z);
    }

    public static Vec3[] toMc(Vec3d[] vectors) {
        if (vectors == null) return null;
        Vec3[] result = new Vec3[vectors.length];
        for (int i = 0; i < vectors.length; i++) result[i] = toMc(vectors[i]);
        return result;
    }

    public static Vec3[][] toMc(Vec3d[][] vectors) {
        if (vectors == null) return null;
        Vec3[][] result = new Vec3[vectors.length][];
        for (int i = 0; i < vectors.length; i++) result[i] = toMc(vectors[i]);
        return result;
    }

    public static Vec3d[] toDomain(Vec3[] vectors) {
        if (vectors == null) return null;
        Vec3d[] result = new Vec3d[vectors.length];
        for (int i = 0; i < vectors.length; i++) result[i] = toDomain(vectors[i]);
        return result;
    }

    public static Vec3d[][] toDomain(Vec3[][] vectors) {
        if (vectors == null) return null;
        Vec3d[][] result = new Vec3d[vectors.length][];
        for (int i = 0; i < vectors.length; i++) result[i] = toDomain(vectors[i]);
        return result;
    }

    private McVectors() { }
}
