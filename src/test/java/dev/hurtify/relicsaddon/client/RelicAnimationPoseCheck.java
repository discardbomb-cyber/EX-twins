package dev.hurtify.relicsaddon.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RelicAnimationPoseCheck {
    public static void main(String[] args) throws Exception {
        checkRfDeploymentContract();
        checkDroneMotionContract();
        JsonArray samples = new JsonArray();
        for (int tick = 0; tick <= 1200; tick++) {
            bounded(RelicAnimationPose.sharedBob(tick), -.01201, .01201);
            bounded(RelicAnimationPose.coreDegrees(tick), 0, 360);
            JsonArray wings = new JsonArray();
            JsonArray deployment = new JsonArray();
            JsonArray manaShellOffsets = new JsonArray();
            JsonArray manaShellTilts = new JsonArray();
            for (int part = 0; part < 6; part++) {
                float manaOffset = RelicAnimationPose.manaDroneShellOffset(tick, part);
                float manaTilt = RelicAnimationPose.manaDroneShellTiltDegrees(tick, part);
                bounded(manaOffset, RelicAnimationPose.MANA_DRONE_MIN_OFFSET - .00001, RelicAnimationPose.MANA_DRONE_MAX_OFFSET + .00001);
                bounded(manaTilt, -RelicAnimationPose.MANA_DRONE_MAX_TILT_DEGREES - .001, RelicAnimationPose.MANA_DRONE_MAX_TILT_DEGREES + .001);
                manaShellOffsets.add(manaOffset);
                manaShellTilts.add(manaTilt);
                if (part < 4) {
                    float flex = RelicAnimationPose.rfWingFlexDegrees(tick, part);
                    bounded(flex, RelicAnimationPose.RF_FOLDED_DEGREES - .001,
                            RelicAnimationPose.RF_DEPLOYED_DEGREES + RelicAnimationPose.RF_IDLE_DEGREES + .001);
                    wings.add(flex);
                    deployment.add(RelicAnimationPose.rfDeployment(tick, part));
                }
            }
            JsonObject sample = new JsonObject();
            sample.addProperty("tick", tick);
            sample.addProperty("bob", RelicAnimationPose.sharedBob(tick));
            sample.addProperty("coreDegrees", RelicAnimationPose.coreDegrees(tick));
            sample.add("rfWings", wings);
            sample.add("rfDeployment", deployment);
            sample.add("manaShellOffsets", manaShellOffsets);
            sample.add("manaShellTilts", manaShellTilts);
            samples.add(sample);
        }
        for (double time : new double[] {-1000, 0, 1e9, 1e12}) bounded(RelicAnimationPose.coreDegrees(time), 0, 360);
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(samples));
        System.out.println("Animation: 1201 ticks, bounded RF deployment, staggered Mana drone, wrapped core angles");
    }

    private static void checkDroneMotionContract() {
        checkDroneMotion("Mana", 6, RelicAnimationPose.MANA_DRONE_CYCLE_TICKS,
                RelicAnimationPose.MANA_DRONE_MIN_OFFSET, RelicAnimationPose.MANA_DRONE_MAX_OFFSET,
                RelicAnimationPose.MANA_DRONE_MAX_TILT_DEGREES);
    }

    private static void checkDroneMotion(String name, int shells, double cycleTicks, double minOffset, double maxOffset,
            double maxTilt) {
        for (int index = 0; index < shells; index++) {
            double offsetLow = Double.POSITIVE_INFINITY;
            double offsetHigh = Double.NEGATIVE_INFINITY;
            double tiltLow = Double.POSITIVE_INFINITY;
            double tiltHigh = Double.NEGATIVE_INFINITY;
            for (int tick = -1200; tick <= 1200; tick++) {
                float offset = RelicAnimationPose.manaDroneShellOffset(tick, index);
                float tilt = RelicAnimationPose.manaDroneShellTiltDegrees(tick, index);
                bounded(offset, minOffset - .00001, maxOffset + .00001);
                bounded(tilt, -maxTilt - .001, maxTilt + .001);
                offsetLow = Math.min(offsetLow, offset);
                offsetHigh = Math.max(offsetHigh, offset);
                tiltLow = Math.min(tiltLow, tilt);
                tiltHigh = Math.max(tiltHigh, tilt);
            }
            if (offsetHigh - offsetLow < (maxOffset - minOffset) * .9 || tiltHigh - tiltLow < maxTilt * 1.8) {
                throw new AssertionError(name + " shell " + index + " does not move through its expected range");
            }
            assertClose(RelicAnimationPose.manaDroneShellOffset(17, index), RelicAnimationPose.manaDroneShellOffset(17 + cycleTicks, index), name + " offset wraps");
            assertClose(RelicAnimationPose.manaDroneShellTiltDegrees(17, index), RelicAnimationPose.manaDroneShellTiltDegrees(17 + cycleTicks, index), name + " tilt wraps");
        }
        for (int index = 1; index < shells; index++) {
            assertDifferent(RelicAnimationPose.manaDroneShellOffset(17, 0), RelicAnimationPose.manaDroneShellOffset(17, index), name + " shell offsets are phase staggered");
            assertDifferent(RelicAnimationPose.manaDroneShellTiltDegrees(17, 0), RelicAnimationPose.manaDroneShellTiltDegrees(17, index), name + " shell tilts are phase staggered");
        }
        for (double nonFiniteTime : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            for (int index = 0; index < shells; index++) {
                bounded(RelicAnimationPose.manaDroneShellOffset(nonFiniteTime, index), minOffset - .00001, maxOffset + .00001);
                bounded(RelicAnimationPose.manaDroneShellTiltDegrees(nonFiniteTime, index), -maxTilt - .001, maxTilt + .001);
            }
        }
    }

    private static void checkRfDeploymentContract() {
        assertClose(RelicAnimationPose.rfDeployment(0, 0), 0, "RF starts folded");
        assertClose(RelicAnimationPose.rfDeployment(40, 0), 1, "RF finishes deploying at tick 40");
        assertClose(RelicAnimationPose.rfDeployment(160, 0), 1, "RF holds deployed through tick 160");
        assertClose(RelicAnimationPose.rfDeployment(200, 0), 0, "RF finishes folding at tick 200");
        assertClose(RelicAnimationPose.rfDeployment(240, 0), 0, "RF cycle wraps at tick 240");
        assertClose(RelicAnimationPose.rfWingFlexDegrees(0, 0), RelicAnimationPose.rfWingFlexDegrees(240, 0),
                "RF flex is continuous across the cycle wrap");
        assertClose(RelicAnimationPose.rfDeployment(-1, 0), RelicAnimationPose.rfDeployment(239, 0), "negative time wraps");
        assertClose(RelicAnimationPose.rfDeployment(1_000_000_000_000D, 0),
                RelicAnimationPose.rfDeployment(160, 0), "long time wraps");
        assertClose(RelicAnimationPose.rfDeployment(4, 1), 0, "wing stagger starts four ticks later");
        assertClose(RelicAnimationPose.rfDeployment(40, 1), RelicAnimationPose.rfDeployment(36, 0), "wing stagger is four ticks");
        assertClose(RelicAnimationPose.rfWingFlexDegrees(0, 0), RelicAnimationPose.RF_FOLDED_DEGREES, "folded flex");
        assertClose(RelicAnimationPose.rfWingFlexDegrees(40, 0), RelicAnimationPose.RF_DEPLOYED_DEGREES, "deployed flex");
        assertClose(RelicAnimationPose.rfWingFlexDegrees(160, 0), RelicAnimationPose.RF_DEPLOYED_DEGREES, "idle ends continuously");

        for (int index = 0; index < 4; index++) {
            for (double boundary : new double[] {0, 40, 160, 200, 240}) {
                assertRfBoundaryIsSmooth(boundary + index * RelicAnimationPose.RF_WING_OFFSET_TICKS, index);
            }
        }
        for (int tick = -1200; tick <= 1200; tick++) {
            for (int index = 0; index < 4; index++) {
                bounded(RelicAnimationPose.rfDeployment(tick, index), 0, 1);
                bounded(RelicAnimationPose.rfWingFlexDegrees(tick, index), RelicAnimationPose.RF_FOLDED_DEGREES - .001,
                        RelicAnimationPose.RF_DEPLOYED_DEGREES + RelicAnimationPose.RF_IDLE_DEGREES + .001);
            }
        }
        for (double nonFiniteTime : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            for (int index = 0; index < 4; index++) {
                bounded(RelicAnimationPose.rfDeployment(nonFiniteTime, index), 0, 1);
                bounded(RelicAnimationPose.rfWingFlexDegrees(nonFiniteTime, index), RelicAnimationPose.RF_FOLDED_DEGREES - .001,
                        RelicAnimationPose.RF_DEPLOYED_DEGREES + RelicAnimationPose.RF_IDLE_DEGREES + .001);
            }
        }
    }

    private static void assertRfBoundaryIsSmooth(double boundary, int index) {
        double epsilon = .001D;
        double difference = Math.abs(RelicAnimationPose.rfWingFlexDegrees(boundary + epsilon, index)
                - RelicAnimationPose.rfWingFlexDegrees(boundary - epsilon, index));
        if (difference >= .01D) {
            throw new AssertionError("RF flex is not smooth at tick " + boundary + " for wing " + index + ": " + difference);
        }
    }

    private static void assertClose(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > .0001) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertDifferent(double first, double second, String message) {
        if (Math.abs(first - second) <= .0001) {
            throw new AssertionError(message + ": both were " + first);
        }
    }

    private static void bounded(double value, double low, double high) {
        if (!Double.isFinite(value) || value < low || value > high) throw new AssertionError("Out of bounds: " + value);
    }
}
