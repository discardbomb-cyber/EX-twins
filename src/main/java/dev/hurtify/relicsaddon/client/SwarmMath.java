package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveFormation;
import dev.hurtify.relicsaddon.domain.hive.HiveShapes;
import dev.hurtify.relicsaddon.domain.hive.HiveTarget;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Minecraft vector view of the shared formation and shape geometry; every coordinate is copied exactly. */
public final class SwarmMath {
    public static final double IMPACT = HiveFormation.IMPACT;
    public static final double FIRE = HiveFormation.FIRE;
    public static final int RETURN_TICKS = HiveFormation.RETURN_TICKS;
    public static final int[][] TESSERACT_EDGES = HiveShapes.TESSERACT_EDGES;

    public static Vec3 idle(Vec3 owner, float yaw, int index, int count, HiveType type, double time) {
        return McVectors.toMc(HiveFormation.idle(McVectors.toDomain(owner), yaw, index, count, type, time));
    }

    public static Vec3 belt(Vec3 owner, float yaw, HiveType type) {
        return McVectors.toMc(HiveFormation.belt(McVectors.toDomain(owner), yaw, type));
    }

    public static Vec3 healing(Vec3 owner, float yaw, int index, int count, HiveType type, double time, double progress) {
        return McVectors.toMc(HiveFormation.healing(McVectors.toDomain(owner), yaw, index, count, type, time, progress));
    }

    public static int travelTicks(double distance) {
        return HiveFormation.travelTicks(distance);
    }

    public static double groupPhase(double time, double cycleStart, int interval, int group, int groups) {
        return HiveFormation.groupPhase(time, cycleStart, interval, group, groups);
    }

    public static boolean passes(long now, long cycleStart, int interval, int group, int groups, double mark) {
        return HiveFormation.passes(now, cycleStart, interval, group, groups, mark);
    }

    public static Vec3 core(Vec3 target, double targetHeight) {
        return McVectors.toMc(HiveFormation.core(McVectors.toDomain(target), targetHeight));
    }

    public static Vec3 muster(Vec3 owner, Vec3 target, int group, int groups, double time) {
        return McVectors.toMc(HiveFormation.muster(McVectors.toDomain(owner), McVectors.toDomain(target), group, groups, time));
    }

    public static double flightTicks(double distance, int interval) {
        return HiveFormation.flightTicks(distance, interval);
    }

    public static double sortie(Vec3 owner, Vec3 target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return HiveFormation.sortie(McVectors.toDomain(owner), McVectors.toDomain(target), targetHeight, group, groups, time, cycleStart, interval);
    }

    public static double sortie(Vec3 owner, Vec3 fanTarget, Vec3 strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return HiveFormation.sortie(McVectors.toDomain(owner), McVectors.toDomain(fanTarget), McVectors.toDomain(strike), strikeHeight, group, groups, time, cycleStart, interval);
    }

    public static double windup(Vec3 owner, Vec3 fanTarget, Vec3 strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return HiveFormation.windup(McVectors.toDomain(owner), McVectors.toDomain(fanTarget), McVectors.toDomain(strike), strikeHeight, group, groups, time, cycleStart, interval);
    }

    public static boolean dropletHidden(HiveType type, double sortie) {
        return HiveFormation.dropletHidden(type, sortie);
    }

    public static Vec3 dropletCentre(Vec3 owner, Vec3 target, double targetHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.dropletCentre(McVectors.toDomain(owner), McVectors.toDomain(target), targetHeight, group, groups, time, cycleStart, interval));
    }

    public static Vec3 dropletCentre(Vec3 owner, Vec3 fanTarget, Vec3 strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.dropletCentre(McVectors.toDomain(owner), McVectors.toDomain(fanTarget), McVectors.toDomain(strike), strikeHeight, group, groups, time, cycleStart, interval));
    }

    public static Vec3 dropletCentre(HiveType type, Vec3 owner, Vec3 fanTarget, Vec3 strike, double strikeHeight, int group, int groups,
            double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.dropletCentre(type, McVectors.toDomain(owner), McVectors.toDomain(fanTarget), McVectors.toDomain(strike), strikeHeight, group, groups, time, cycleStart, interval));
    }

    public static Vec3 arcPath(Vec3 home, Vec3 core, double sortie, int group) {
        return McVectors.toMc(HiveFormation.arcPath(McVectors.toDomain(home), McVectors.toDomain(core), sortie, group));
    }

    public static double shapeSize(int members) {
        return HiveFormation.shapeSize(members);
    }

    public static Vec3 clusterCentre(HiveType type, Vec3 target, double targetWidth, double targetHeight, int group, int groups, double time) {
        return McVectors.toMc(HiveFormation.clusterCentre(type, McVectors.toDomain(target), targetWidth, targetHeight, group, groups, time));
    }

    public static int patternCorners(HiveType type) {
        return HiveFormation.patternCorners(type);
    }

    public static int[][] clusterLinks(HiveType type, int groups) {
        return HiveFormation.clusterLinks(type, groups);
    }

    public static double clumpRadius(int members) {
        return HiveFormation.clumpRadius(members);
    }

    public static Vec3 station(AttackMode mode, HiveType type, int slot, int slots, Vec3 owner, Vec3 target, double targetWidth,
            double targetHeight, double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.station(mode, type, slot, slots, McVectors.toDomain(owner), McVectors.toDomain(target), targetWidth, targetHeight, time, cycleStart, interval));
    }

    public static int engaged(int targets, int figures) {
        return HiveFormation.engaged(targets, figures);
    }

    public static Vec3 station(AttackMode mode, HiveType type, int slot, int slots, Vec3 owner, List<HiveTarget> targets,
            double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.station(mode, type, slot, slots, McVectors.toDomain(owner), targets, time, cycleStart, interval));
    }

    public static Vec3 engagedStation(AttackMode mode, HiveType type, int slot, int slots, Vec3 owner, List<HiveTarget> targets,
            List<HiveTarget> previous, double retargetedAt, double time, double cycleStart, int interval) {
        return McVectors.toMc(HiveFormation.engagedStation(mode, type, slot, slots, McVectors.toDomain(owner), targets, previous, retargetedAt, time, cycleStart, interval));
    }

    public static Vec3 containmentCentre(HiveType type, Vec3 target, double targetWidth, double targetHeight, int slots) {
        return McVectors.toMc(HiveFormation.containmentCentre(type, McVectors.toDomain(target), targetWidth, targetHeight, slots));
    }

    public static double enclosure(double targetWidth, double targetHeight) {
        return HiveFormation.enclosure(targetWidth, targetHeight);
    }

    public static double horizon(double targetWidth, double targetHeight) {
        return HiveFormation.horizon(targetWidth, targetHeight);
    }

    public static double ringsRadius(double targetWidth, double targetHeight, boolean dense) {
        return HiveFormation.ringsRadius(targetWidth, targetHeight, dense);
    }

    public static double constructAge(double time, double cycleStart) {
        return HiveFormation.constructAge(time, cycleStart);
    }

    public static double constructLift(HiveType type, double targetWidth, double targetHeight) {
        return HiveFormation.constructLift(type, targetWidth, targetHeight);
    }

    public static double ringLift(double targetWidth, double targetHeight, boolean dense) {
        return HiveFormation.ringLift(targetWidth, targetHeight, dense);
    }

    public static double twinsLift(double targetWidth, double targetHeight) {
        return HiveFormation.twinsLift(targetWidth, targetHeight);
    }

    public static double wardScale(double targetWidth, double targetHeight) {
        return HiveFormation.wardScale(targetWidth, targetHeight);
    }

    public static double wardLift(double targetWidth, double targetHeight) {
        return HiveFormation.wardLift(targetWidth, targetHeight);
    }

    public static Vec3 deployed(Vec3 owner, float yaw, Vec3 station, int unit, int count, HiveType type,
            double time, double launched, int travel) {
        return McVectors.toMc(HiveFormation.deployed(McVectors.toDomain(owner), yaw, McVectors.toDomain(station), unit, count, type, time, launched, travel));
    }

    public static Vec3 flight(Vec3 from, Vec3 to, int unit, HiveType type, double progress) {
        return McVectors.toMc(HiveFormation.flight(McVectors.toDomain(from), McVectors.toDomain(to), unit, type, progress));
    }

    public static Vec3 returning(Vec3 owner, float yaw, Vec3 struckAt, int unit, int count, HiveType type, double time, double hitAt) {
        return McVectors.toMc(HiveFormation.returning(McVectors.toDomain(owner), yaw, McVectors.toDomain(struckAt), unit, count, type, time, hitAt));
    }

    public static double stagger(int index, HiveType type, double progress) {
        return HiveFormation.stagger(index, type, progress);
    }

    public static Vec3 tesseract(int m, int count, double time, double size) {
        return McVectors.toMc(HiveShapes.tesseract(m, count, time, size));
    }

    public static Vec3 tesseract(int m, int count, double time, double size, double collapse) {
        return McVectors.toMc(HiveShapes.tesseract(m, count, time, size, collapse));
    }

    public static Vec3 tesseractCorner(int corner, double time, double size) {
        return McVectors.toMc(HiveShapes.tesseractCorner(corner, time, size));
    }

    public static Vec3 tesseractCorner(int corner, double time, double size, double collapse) {
        return McVectors.toMc(HiveShapes.tesseractCorner(corner, time, size, collapse));
    }

    public static Vec3 droplet(int m, int count, double time, Vec3 forward, double size) {
        return McVectors.toMc(HiveShapes.droplet(m, count, time, McVectors.toDomain(forward), size));
    }

    public static Vec3 hexagons(int m, int count, double time, Vec3 forward, double size) {
        return McVectors.toMc(HiveShapes.hexagons(m, count, time, McVectors.toDomain(forward), size));
    }

    public static int hexagonCount(int count) {
        return HiveShapes.hexagonCount(count);
    }

    public static Vec3 hexagonCentre(int ring, int rings, double time, Vec3 forward, double size) {
        return McVectors.toMc(HiveShapes.hexagonCentre(ring, rings, time, McVectors.toDomain(forward), size));
    }

    public static Vec3 hexagonPoint(double along, double spin, Vec3 forward, double radius) {
        return McVectors.toMc(HiveShapes.hexagonPoint(along, spin, McVectors.toDomain(forward), radius));
    }

    public static Vec3 clump(HiveType type, int m, int count, double time, Vec3 facing, double radius) {
        return McVectors.toMc(HiveShapes.clump(type, m, count, time, McVectors.toDomain(facing), radius));
    }

    public static Vec3 clumpPattern(HiveType type, int m, int count, double time, double radius) {
        return McVectors.toMc(HiveShapes.clumpPattern(type, m, count, time, radius));
    }

    public static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        return McVectors.toMc(HiveShapes.rotate(McVectors.toDomain(v), McVectors.toDomain(axis), angle));
    }

    public static int ringRows(boolean dense) {
        return HiveShapes.ringRows(dense);
    }

    public static double ringTube(int ring, double radius, boolean dense) {
        return HiveShapes.ringTube(ring, radius, dense);
    }

    public static double ringTubeScale(boolean dense) {
        return HiveShapes.ringTubeScale(dense);
    }

    public static double ringTubeBase(boolean dense) {
        return HiveShapes.ringTubeBase(dense);
    }

    public static int ringColumns(int ring, double radius, boolean dense) {
        return HiveShapes.ringColumns(ring, radius, dense);
    }

    public static int ringCorners(boolean dense) {
        return HiveShapes.ringCorners(dense);
    }

    public static Vec3 ringPlace(int s, int count, double time, double radius, boolean dense) {
        return McVectors.toMc(HiveShapes.ringPlace(s, count, time, radius, dense));
    }

    public static Vec3 dysonRing(int s, int count, double time, double radius, boolean dense) {
        return McVectors.toMc(HiveShapes.dysonRing(s, count, time, radius, dense));
    }

    public static int ringStart(int ring, int count) {
        return HiveShapes.ringStart(ring, count);
    }

    public static Vec3 ringHexCorner(int ring, int column, int row, int corner, double time, double radius, boolean dense) {
        return McVectors.toMc(HiveShapes.ringHexCorner(ring, column, row, corner, time, radius, dense));
    }

    public static Vec3 ringHexCorner(Vec3[] frame, int ring, int column, int row, int corner, double time, double radius, boolean dense) {
        return McVectors.toMc(HiveShapes.ringHexCorner(McVectors.toDomain(frame), ring, column, row, corner, time, radius, dense));
    }

    public static Vec3 ringPoint(int ring, double u, double v, double out, double time, double radius, boolean dense) {
        return McVectors.toMc(HiveShapes.ringPoint(ring, u, v, out, time, radius, dense));
    }

    public static Vec3 ringPoint(Vec3[] frame, int ring, double u, double v, double out, double time, double radius, boolean dense) {
        return McVectors.toMc(HiveShapes.ringPoint(McVectors.toDomain(frame), ring, u, v, out, time, radius, dense));
    }

    public static Vec3[] ringFrame(int ring, double time) {
        return McVectors.toMc(HiveShapes.ringFrame(ring, time));
    }

    public static Vec3 wardPlace(int s, int count, double time, double scale) {
        return McVectors.toMc(HiveShapes.wardPlace(s, count, time, scale));
    }

    public static Vec3 wardCorner(int corner, double turn) {
        return McVectors.toMc(HiveShapes.wardCorner(corner, turn));
    }

    public static Vec3 ward(int s, int count, double time, double scale) {
        return McVectors.toMc(HiveShapes.ward(s, count, time, scale));
    }

    public static Vec3 wardHexagonPoint(double t, double turn) {
        return McVectors.toMc(HiveShapes.wardHexagonPoint(t, turn));
    }

    public static Vec3 rhombusPoint(double t, double angle) {
        return McVectors.toMc(HiveShapes.rhombusPoint(t, angle));
    }

    public static Vec3[] axes(Vec3 forward) {
        return McVectors.toMc(HiveShapes.axes(McVectors.toDomain(forward)));
    }

    private SwarmMath() { }
}
