package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.hive.ManaArmageddon;
import dev.hurtify.relicsaddon.domain.hive.ArmageddonState;
import net.minecraft.world.phys.Vec3;

/** Minecraft vector view of the shared ManaArmageddon geometry. */
public final class ManaArmageddonMath {
    public static Vec3[] ringFrame(ArmageddonState state, int side, int ring, double age) {
        return McVectors.toMc(ManaArmageddon.ringFrame(state, side, ring, age));
    }

    public static Vec3[] centralFrame(ArmageddonState state) {
        return McVectors.toMc(ManaArmageddon.centralFrame(state));
    }

    public static Vec3 origin(Vec3 eye, Vec3 target) {
        return McVectors.toMc(ManaArmageddon.origin(McVectors.toDomain(eye), McVectors.toDomain(target)));
    }

    public static Vec3[] frame(ArmageddonState state) {
        return McVectors.toMc(ManaArmageddon.frame(state));
    }

    public static Vec3 heart(ArmageddonState state, int side) {
        return McVectors.toMc(ManaArmageddon.heart(state, side));
    }

    public static Vec3[] face(ArmageddonState state, int side) {
        return McVectors.toMc(ManaArmageddon.face(state, side));
    }

    public static Vec3 onFace(ArmageddonState state, int side, Vec3[] face, double right, double up, double out) {
        return McVectors.toMc(ManaArmageddon.onFace(state, side, McVectors.toDomain(face), right, up, out));
    }

    public static Vec3 spiral(ArmageddonState state, int side, Vec3 from, Vec3 to, double progress) {
        return McVectors.toMc(ManaArmageddon.spiral(state, side, McVectors.toDomain(from), McVectors.toDomain(to), progress));
    }

    public static Vec3 station(ArmageddonState state, int slot, int slots, double time) {
        return McVectors.toMc(ManaArmageddon.station(state, slot, slots, time));
    }

    public static Vec3 stream(ArmageddonState state, int side, double u) {
        return McVectors.toMc(ManaArmageddon.stream(state, side, u));
    }

    public static Vec3[] streamAxes(ArmageddonState state, int side, double u) {
        return McVectors.toMc(ManaArmageddon.streamAxes(state, side, u));
    }

    private ManaArmageddonMath() { }
}
