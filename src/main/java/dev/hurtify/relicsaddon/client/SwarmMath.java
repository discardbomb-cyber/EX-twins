package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveFormation;
import dev.hurtify.relicsaddon.domain.hive.HiveShapes;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.domain.math.Vec3d;
import net.minecraft.world.phys.Vec3;

/**
 * {@link HiveFormation} and {@link HiveShapes} for the renderer, in Minecraft's {@code Vec3}: the members
 * the client uses, with the same names and parameters. Every vector is converted exactly on the way in
 * and out ({@link McVectors}), so drones are drawn precisely where the server's shared geometry puts them.
 */
public final class SwarmMath {
    public static final double IMPACT = HiveFormation.IMPACT;
    public static final double FIRE = HiveFormation.FIRE;
    public static final int RETURN_TICKS = HiveFormation.RETURN_TICKS;
    public static final int[][] TESSERACT_EDGES = HiveShapes.TESSERACT_EDGES;

    // --- HiveFormation ---------------------------------------------------------------------------

    public static Vec3 idle(Vec3 owner, float yaw, int index, int count, HiveType type, double time) {
        return McVectors.toMc(HiveFormation.idle(McVectors.toDomain(owner), yaw, index, count, type, time));
    }

    public static Vec3 belt(Vec3 owner, float yaw, HiveType type) {
        return McVectors.toMc(HiveFormation.belt(McVectors.toDomain(owner), yaw, type));
    }

    public static Vec3 healing(Vec3 owner, float yaw, int index, int count, HiveType type, double time, double progress) {
        return McVectors.toMc(HiveFormation.healing(McVectors.toDomain(owner), yaw, index, count, type, time, progress));
    }

    public static double groupPhase(double time, double cycleStart, int interval, int group, int groups) {
        return HiveFormation.groupPhase(time, cycleStart, interval, group, groups);
    }

    public static Vec3 core(Vec3 target, double targetHeight) {
        return McVectors.toMc(HiveFormation.core(McVectors.toDomain(target), targetHeight));
    }

    public static Vec3 muster(Vec3 owner, Vec3 target, int group, int groups, double time) {
        return McVectors.toMc(HiveFormation.muster(McVectors.toDomain(owner), McVectors.toDomain(target), group, groups, time));
    }

    public static double sortie(Vec3 owner, Vec3 target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return HiveFormation.sortie(McVectors.toDomain(owner), McVectors.toDomain(target), targetHeight, group, groups,
                time, cycleStart, interval);
    }

    public static Vec3 dropletCentre(Vec3 owner, Vec3 target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.dropletCentre(McVectors.toDomain(owner), McVectors.toDomain(target), targetHeight, group, groups,
                time, cycleStart, interval));
    }

    public static double shapeSize(int members) {
        return HiveFormation.shapeSize(members);
    }

    public static Vec3 clusterCentre(HiveType type, Vec3 target, double targetWidth, double targetHeight, int group, int groups, double time) {
        return McVectors.toMc(HiveFormation.clusterCentre(type, McVectors.toDomain(target), targetWidth, targetHeight, group, groups, time));
    }

    public static int[][] clusterLinks(HiveType type, int groups) {
        return HiveFormation.clusterLinks(type, groups);
    }

    public static double clumpRadius(int members) {
        return HiveFormation.clumpRadius(members);
    }

    public static Vec3 station(AttackMode mode, HiveType type, int slot, int slots, Vec3 owner, Vec3 target, double targetWidth,
            double targetHeight, double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.station(mode, type, slot, slots, McVectors.toDomain(owner), McVectors.toDomain(target), targetWidth,
                targetHeight, time, cycleStart, interval));
    }

    public static Vec3 deployed(Vec3 owner, float yaw, Vec3 station, int unit, int count, HiveType type,
            double time, double launched, int travel) {
        return McVectors.toMc(HiveFormation.deployed(McVectors.toDomain(owner), yaw, McVectors.toDomain(station), unit, count, type,
                time, launched, travel));
    }

    public static Vec3 returning(Vec3 owner, float yaw, Vec3 struckAt, int unit, int count, HiveType type, double time, double hitAt) {
        return McVectors.toMc(HiveFormation.returning(McVectors.toDomain(owner), yaw, McVectors.toDomain(struckAt), unit, count, type, time, hitAt));
    }

    // --- HiveShapes ------------------------------------------------------------------------------

    public static Vec3 tesseractCorner(int corner, double time, double size) {
        return McVectors.toMc(HiveShapes.tesseractCorner(corner, time, size));
    }

    public static Vec3[] axes(Vec3 forward) {
        Vec3d[] axes = HiveShapes.axes(McVectors.toDomain(forward));
        Vec3[] result = new Vec3[axes.length];
        for (int index = 0; index < axes.length; index++) result[index] = McVectors.toMc(axes[index]);
        return result;
    }

    public static Vec3 hexagonCentre(int ring, int rings, double time, Vec3 forward, double size) {
        return McVectors.toMc(HiveShapes.hexagonCentre(ring, rings, time, McVectors.toDomain(forward), size));
    }

    public static int hexagonCount(int count) {
        return HiveShapes.hexagonCount(count);
    }

    public static Vec3 hexagonPoint(double along, double spin, Vec3 forward, double radius) {
        return McVectors.toMc(HiveShapes.hexagonPoint(along, spin, McVectors.toDomain(forward), radius));
    }

    public static Vec3 rhombusPoint(double t, double angle) {
        return McVectors.toMc(HiveShapes.rhombusPoint(t, angle));
    }

    public static Vec3 riftCentre(int sphere, double time, double distance) {
        return McVectors.toMc(HiveShapes.riftCentre(sphere, time, distance));
    }

    public static double riftSpin(int sphere, double time) {
        return HiveShapes.riftSpin(sphere, time);
    }

    private SwarmMath() { }
}
