package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.hive.Armageddon;
import dev.hurtify.relicsaddon.domain.hive.ArmageddonState;
import net.minecraft.world.phys.Vec3;

/** Minecraft vector view of the shared Armageddon geometry. */
public final class ArmageddonMath {
    public static Vec3 origin(Vec3 eye, Vec3 target) {
        return McVectors.toMc(Armageddon.origin(McVectors.toDomain(eye), McVectors.toDomain(target)));
    }

    public static Vec3[] frame(ArmageddonState state) {
        return McVectors.toMc(Armageddon.frame(state));
    }

    public static Vec3 point(ArmageddonState state, Vec3[] frame, double along, double side, double up) {
        return McVectors.toMc(Armageddon.point(state, McVectors.toDomain(frame), along, side, up));
    }

    public static Vec3 muzzle(ArmageddonState state) {
        return McVectors.toMc(Armageddon.muzzle(state));
    }

    public static Vec3 shot(ArmageddonState state, double age) {
        return McVectors.toMc(Armageddon.shot(state, age));
    }

    public static Vec3[] hoopFrame(Vec3[] frame, int hoop, double age) {
        return McVectors.toMc(Armageddon.hoopFrame(McVectors.toDomain(frame), hoop, age));
    }

    public static Vec3 hoopPoint(ArmageddonState state, Vec3[] frame, int hoop, double angle, double age) {
        return McVectors.toMc(Armageddon.hoopPoint(state, McVectors.toDomain(frame), hoop, angle, age));
    }

    public static Vec3 station(ArmageddonState state, int slot, int slots, double time) {
        return McVectors.toMc(Armageddon.station(state, slot, slots, time));
    }

    public static Vec3 shellPoint(ArmageddonState state, Vec3[] frame, int index, int count, double age) {
        return McVectors.toMc(Armageddon.shellPoint(state, McVectors.toDomain(frame), index, count, age));
    }

    public static Vec3 funnelPoint(ArmageddonState state, Vec3[] frame, int index, int count, double age) {
        return McVectors.toMc(Armageddon.funnelPoint(state, McVectors.toDomain(frame), index, count, age));
    }

    private ArmageddonMath() { }
}
