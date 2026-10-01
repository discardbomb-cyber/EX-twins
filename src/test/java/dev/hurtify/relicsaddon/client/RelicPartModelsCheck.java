package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.resources.model.ModelResourceLocation;

/**
 * The part-id table must name exactly the models the renderer used to parse on every part, and
 * looking a part up must no longer allocate. Also measures the bytes one frame of pose sampling
 * costs for the detailed Twins swarm budget, before (pose objects per facet, arcs sampled twice)
 * and after (tabulated bases, one clock sample per facet).
 */
public final class RelicPartModelsCheck {
    private static final int DETAILED_DRONES = 6;

    public static void main(String[] args) {
        int parts = 0;
        for (RelicRole role : RelicRole.values()) {
            List<ModelResourceLocation> expected = new ArrayList<>();
            for (String part : new String[] {"body", "core", "fx"}) expected.add(RelicPartModels.parse(role, part));
            boolean drone = !role.isShield() && !role.isHive();
            if (drone) {
                expected.add(RelicPartModels.parse(role, "swarm"));
                expected.add(RelicPartModels.parse(role, "dense"));
            }
            for (int index = 0; index < RelicPartModels.shellCount(role); index++) expected.add(RelicPartModels.parse(role, "shell_" + index));
            require(List.copyOf(expected).equals(RelicPartModels.all(role)), "Registered ids of " + role);
            require(RelicPartModels.body(role).equals(RelicPartModels.parse(role, "body")), "body of " + role);
            require(RelicPartModels.core(role).equals(RelicPartModels.parse(role, "core")), "core of " + role);
            require(RelicPartModels.fx(role).equals(RelicPartModels.parse(role, "fx")), "fx of " + role);
            for (int index = 0; index < RelicPartModels.shellCount(role); index++) {
                require(RelicPartModels.shell(role, index).equals(RelicPartModels.parse(role, "shell_" + index)), "shell " + index + " of " + role);
            }
            if (drone) {
                require(RelicPartModels.swarm(role, false).equals(RelicPartModels.parse(role, "swarm")), "swarm of " + role);
                require(RelicPartModels.swarm(role, true).equals(RelicPartModels.parse(role, "dense")), "dense of " + role);
            }
            parts += expected.size();
        }
        // 9 roles x body/core/fx, swarm + dense for the 3 drones, and the shells: 0+4+20, 4+6+20, 4+6+12.
        require(parts == 9 * 3 + 2 * 3 + 76, "Part count: " + parts);

        long[] ids = measure(RelicPartModelsCheck::parseFrame, RelicPartModelsCheck::lookupFrame);
        System.out.printf(Locale.ROOT, "Part ids, %d detailed Twins drones + shield + hive (%d parts): allocated bytes/frame %,d -> %,d%n",
                DETAILED_DRONES, frameParts(), ids[0], ids[1]);
        require(ids[1] == 0, "Looking up part ids must not allocate");

        long[] poses = measure(RelicPartModelsCheck::referencePoseFrame, RelicPartModelsCheck::poseFrame);
        System.out.printf(Locale.ROOT, "Poses, same frame: allocated bytes/frame %,d -> %,d%n", poses[0], poses[1]);
        require(poses[1] == 0, "Sampling the poses must not allocate");
        System.out.println("Part models: " + parts + " ids tabulated and equal to the parsed ones; frame lookups and pose samples allocate nothing");
    }

    private static int frameParts() {
        return DETAILED_DRONES * (3 + TwinsFacetPose.COUNT) + (3 + TwinsFacetPose.COUNT) + (3 + 12);
    }

    /** The renderer before: one ResourceLocation + ModelResourceLocation parsed per rendered part. */
    private static long parseFrame() {
        long hash = 0;
        for (int drone = 0; drone < DETAILED_DRONES; drone++) hash += parseRole(RelicRole.TWINS_DRONE);
        hash += parseRole(RelicRole.TWINS_SHIELD);
        hash += parseRole(RelicRole.TWINS_HIVE);
        return hash;
    }

    private static long parseRole(RelicRole role) {
        long hash = RelicPartModels.parse(role, "body").hashCode() + RelicPartModels.parse(role, "core").hashCode()
                + RelicPartModels.parse(role, "fx").hashCode();
        for (int index = 0; index < RelicPartModels.shellCount(role); index++) hash += RelicPartModels.parse(role, "shell_" + index).hashCode();
        return hash;
    }

    private static long lookupFrame() {
        long hash = 0;
        for (int drone = 0; drone < DETAILED_DRONES; drone++) hash += lookupRole(RelicRole.TWINS_DRONE);
        hash += lookupRole(RelicRole.TWINS_SHIELD);
        hash += lookupRole(RelicRole.TWINS_HIVE);
        return hash;
    }

    private static long lookupRole(RelicRole role) {
        long hash = RelicPartModels.body(role).hashCode() + RelicPartModels.core(role).hashCode() + RelicPartModels.fx(role).hashCode();
        for (int index = 0; index < RelicPartModels.shellCount(role); index++) hash += RelicPartModels.shell(role, index).hashCode();
        return hash;
    }

    /** The clocks before: a pose object per facet, each shield arc sampled twice, a pose per hive shell and layer. */
    private static long referencePoseFrame() {
        double time = 1234.5625D;
        double sum = 0;
        for (int drone = 0; drone < DETAILED_DRONES; drone++) {
            for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
                TwinsFacetPose.Pose pose = TwinsFacetPoseReference.sample(time + drone, index, false);
                sum += pose.normal().x() * pose.offset() + pose.pivot().y() + pose.tangent().z() + pose.tilt() + pose.twist();
            }
        }
        for (int layer = 0; layer < 3; layer++) sum += HiveShellPoseReference.shieldLayer(layer, time).degrees();
        for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
            TwinsFacetPose.Pose pose = TwinsFacetPoseReference.sample(time, index, true);
            sum += pose.normal().x() * pose.offset() + pose.pivot().y() + pose.tangent().z() + pose.tilt() + pose.twist();
            TwinsFacetPose.Pose again = TwinsFacetPoseReference.sample(time, index, true);
            sum += again.normal().x() + again.pivot().y() + TwinsShieldLayerPose.shellSpin(index, time);
        }
        for (int layer = 0; layer < 3; layer++) sum += HiveShellPoseReference.twinsLayer(layer, time).degrees();
        for (int index = 0; index < 12; index++) {
            HiveShellPose.Pose pose = HiveShellPoseReference.sample(12, index, time);
            sum += pose.x() * pose.offset() + pose.y() + pose.z() + pose.tilt() + pose.spin();
        }
        return Double.doubleToLongBits(sum);
    }

    /** The clocks after, read the way the renderer reads them. */
    private static long poseFrame() {
        double time = 1234.5625D;
        double sum = 0;
        for (int drone = 0; drone < DETAILED_DRONES; drone++) {
            for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
                TwinsFacetPose.Basis basis = TwinsFacetPose.basis(index);
                sum += basis.normal().x() * TwinsFacetPose.offset(time + drone, index, false) + basis.pivot(false).y() + basis.tangent().z()
                        + TwinsFacetPose.tilt(time + drone, index) + TwinsFacetPose.twist(time + drone, index, false);
            }
        }
        for (int layer = 0; layer < 3; layer++) sum += TwinsShieldLayerPose.axis(layer)[0] + TwinsShieldLayerPose.degrees(layer, time);
        for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
            TwinsFacetPose.Basis basis = TwinsFacetPose.basis(index);
            sum += basis.normal().x() * TwinsFacetPose.offset(time, index, true) + basis.pivot(true).y() + basis.tangent().z()
                    + TwinsFacetPose.tilt(time, index) + TwinsFacetPose.twist(time, index, true);
            sum += basis.normal().x() + basis.pivot(true).y() + TwinsShieldLayerPose.shellSpin(index, time);
        }
        for (int layer = 0; layer < 3; layer++) sum += HiveShellPose.twinsLayerAxis(layer)[0] + HiveShellPose.twinsLayerDegrees(layer, time);
        for (int index = 0; index < 12; index++) {
            double[] axis = HiveShellPose.axis(12, index);
            sum += axis[0] * HiveShellPose.opening(12, index, time) + axis[1] + axis[2] + HiveShellPose.tilt(12, index, time) + HiveShellPose.spin(12, index, time);
        }
        return Double.doubleToLongBits(sum);
    }

    /** Runs both frames warmed up and returns the fewest bytes either allocated in one run. */
    private static long[] measure(java.util.function.LongSupplier before, java.util.function.LongSupplier after) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long[] result = new long[2];
        java.util.function.LongSupplier[] frames = {before, after};
        long sink = 0;
        for (int which = 0; which < 2; which++) {
            for (int k = 0; k < 2000; k++) sink += frames[which].getAsLong();
            long bytes = Long.MAX_VALUE;
            for (int k = 0; k < 20; k++) {
                long start = threads.getThreadAllocatedBytes(id);
                sink += frames[which].getAsLong();
                bytes = Math.min(bytes, threads.getThreadAllocatedBytes(id) - start);
            }
            result[which] = bytes;
        }
        if (sink == 42) System.out.println();
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private RelicPartModelsCheck() { }
}
