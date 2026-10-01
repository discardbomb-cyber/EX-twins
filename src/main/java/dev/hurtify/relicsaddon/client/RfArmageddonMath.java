package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.hive.RfArmageddon;
import dev.hurtify.relicsaddon.domain.hive.ArmageddonState;
import net.minecraft.world.phys.Vec3;

/** Minecraft vector view of the shared RfArmageddon geometry. */
public final class RfArmageddonMath {
    public static Vec3 origin(Vec3 eye, Vec3 target) {
        return McVectors.toMc(RfArmageddon.origin(McVectors.toDomain(eye), McVectors.toDomain(target)));
    }

    public static Vec3[] frame(ArmageddonState state) {
        return McVectors.toMc(RfArmageddon.frame(state));
    }

    public static Vec3 body(ArmageddonState state, Vec3[] f, double along, double angle, double radius) {
        return McVectors.toMc(RfArmageddon.body(state, McVectors.toDomain(f), along, angle, radius));
    }

    public static Vec3[] panel(ArmageddonState state, Vec3[] f, int panel, double unfold) {
        return McVectors.toMc(RfArmageddon.panel(state, McVectors.toDomain(f), panel, unfold));
    }

    public static Vec3 onPanel(Vec3[] panel, double length, double width) {
        return McVectors.toMc(RfArmageddon.onPanel(McVectors.toDomain(panel), length, width));
    }

    public static Pose pose(ArmageddonState state, double age) {
        return new Pose(RfArmageddon.pose(state, age));
    }

    public static Vec3 assemble(ArmageddonState state, Vec3 from, Vec3 to, double progress) {
        return McVectors.toMc(RfArmageddon.assemble(state, McVectors.toDomain(from), McVectors.toDomain(to), progress));
    }

    public static Vec3 station(ArmageddonState state, int slot, int slots, double time) {
        return McVectors.toMc(RfArmageddon.station(state, slot, slots, time));
    }

    public static Vec3 station(ArmageddonState state, int slot, int slots, double time, Pose pose) {
        return McVectors.toMc(RfArmageddon.station(state, slot, slots, time, pose.domain()));
    }

    public static Vec3 nose(ArmageddonState state) {
        return McVectors.toMc(RfArmageddon.nose(state));
    }

    public static Vec3 hover(ArmageddonState state) {
        return McVectors.toMc(RfArmageddon.hover(state));
    }

    public static Vec3 ball(ArmageddonState state, double age) {
        return McVectors.toMc(RfArmageddon.ball(state, age));
    }

    public static double[] bowl(Vec3 normal, double dx, double dz, double radius, double depth) {
        return RfArmageddon.bowl(McVectors.toDomain(normal), dx, dz, radius, depth);
    }

    /** The already computed domain pose, with its vectors converted once for this frame. */
    public record Pose(RfArmageddon.Pose domain, Vec3[] frame, Vec3[][] panels, Vec3 rings, double ringRadius, double flying) {
        Pose(RfArmageddon.Pose domain) {
            this(domain, McVectors.toMc(domain.frame()), McVectors.toMc(domain.panels()), McVectors.toMc(domain.rings()), domain.ringRadius(), domain.flying());
        }
    }

    private RfArmageddonMath() { }
}
