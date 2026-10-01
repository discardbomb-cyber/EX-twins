package dev.hurtify.relicsaddon.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class TwinsFacetPoseCheck {
    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        JsonObject report = new JsonObject();
        JsonArray facets = new JsonArray(), frames = new JsonArray();
        Set<Double> periods = new HashSet<>();
        Set<TwinsFacetPose.Point> normals = new HashSet<>();
        for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
            var drone = TwinsFacetPose.sample(0, index, false);
            var shield = TwinsFacetPose.sample(0, index, true);
            require(periods.add(TwinsFacetPose.period(index)), "Independent periods");
            require(normals.add(drone.normal()), "Independent normals");
            close(drone.normal().length(), 1);
            close(drone.tangent().length(), 1);
            JsonObject facet = new JsonObject();
            facet.add("normal", gson.toJsonTree(drone.normal()));
            facet.add("tangent", gson.toJsonTree(drone.tangent()));
            facet.add("dronePivot", gson.toJsonTree(drone.pivot()));
            facet.add("shieldPivot", gson.toJsonTree(shield.pivot()));
            facet.addProperty("period", TwinsFacetPose.period(index));
            facets.add(facet);
            double low = 1, high = 0;
            for (double tick = -1200; tick <= 1200; tick += .5D) {
                var pose = TwinsFacetPose.sample(tick, index, false);
                var next = TwinsFacetPose.sample(tick + .5D, index, false);
                bounded(pose.offset(), .05D, .10D);
                bounded(pose.tilt(), -2, 2);
                bounded(pose.twist(), -3.5D, 3.5D);
                require(Math.abs(next.offset() - pose.offset()) < .001D, "Continuous displacement");
                require(Math.abs(next.tilt() - pose.tilt()) < .04D, "Continuous tilt");
                require(angleDelta(next.twist(), pose.twist()) < 1.0D, "Continuous layer rotation");
                var shieldNext = TwinsFacetPose.sample(tick + .5D, index, true);
                bounded(TwinsFacetPose.sample(tick, index, true).twist(), 0, 360);
                require(angleDelta(shieldNext.twist(), TwinsFacetPose.sample(tick, index, true).twist()) < 1.0D,
                        "Continuous shield layer rotation");
                close(TwinsFacetPose.sample(tick, index, true).offset(), pose.offset() * .85D);
                low = Math.min(low, pose.offset());
                high = Math.max(high, pose.offset());
            }
            require(high - low > .049D, "Every facet moves");
            close(TwinsFacetPose.sample(17, index, false).offset(),
                    TwinsFacetPose.sample(17 + TwinsFacetPose.period(index), index, false).offset());
            require(angleDelta(TwinsFacetPose.sample(17, index, true).twist(),
                    TwinsFacetPose.sample(17 + TwinsFacetPose.period(index), index, true).twist()) < 1e-8D,
                    "Layer returns to its starting orientation");
            for (double time : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e12}) {
                bounded(TwinsFacetPose.sample(time, index, false).offset(), .05D, .10D);
            }
        }
        require(facets.size() == 20, "Twenty separately animated plates");
        long identical = checkTabulatedBases();
        for (int tick = 0; tick <= 1200; tick++) {
            JsonObject frame = new JsonObject();
            JsonArray offsets = new JsonArray(), tilts = new JsonArray(), twists = new JsonArray();
            for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
                var pose = TwinsFacetPose.sample(tick, index, false);
                offsets.add(pose.offset());
                tilts.add(pose.tilt());
                twists.add(pose.twist());
            }
            frame.addProperty("tick", tick);
            frame.add("offsets", offsets);
            frame.add("tilts", tilts);
            frame.add("twists", twists);
            frames.add(frame);
        }
        report.add("facets", facets);
        report.add("frames", frames);
        Path path = Path.of(args[0]);
        Files.createDirectories(path.getParent());
        Files.writeString(path, gson.toJson(report));
        System.out.println("Twins: 20 independent closed armor facets per item, 4801 half-tick samples, unique periods and pivots; "
                + identical + " tabulated poses bit-identical to the per-sample reference");
    }

    /**
     * The tabulated bases and split clocks must give exactly the pose the old per-sample code gave:
     * every double compared by its bits, over both items, all facets and a spread of times
     * (including the non-finite ones the renderer guards against).
     */
    private static long checkTabulatedBases() {
        long compared = 0;
        double[] times = new double[4821];
        for (int k = 0; k < 4801; k++) times[k] = -1200 + k * .5D;
        double[] odd = {0, 17, 1234.5625D, 1e6 + .125D, 1e9, 1e12, -7.75D, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, -Double.MIN_VALUE, 200.0D, 211.0D, 399.99D, 2.5e-3D, 73.25D, 73.375D, 99999.5D, -99999.5D};
        System.arraycopy(odd, 0, times, 4801, odd.length);
        for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
            for (boolean shield : new boolean[] {false, true}) {
                for (double time : times) {
                    var expected = TwinsFacetPoseReference.sample(time, index, shield);
                    var actual = TwinsFacetPose.sample(time, index, shield);
                    var basis = TwinsFacetPose.basis(index);
                    samePoint(expected.normal(), actual.normal());
                    samePoint(expected.tangent(), actual.tangent());
                    samePoint(expected.pivot(), actual.pivot());
                    samePoint(expected.normal(), basis.normal());
                    samePoint(expected.tangent(), basis.tangent());
                    samePoint(expected.pivot(), basis.pivot(shield));
                    same(expected.offset(), actual.offset());
                    same(expected.tilt(), actual.tilt());
                    same(expected.twist(), actual.twist());
                    same(expected.offset(), TwinsFacetPose.offset(time, index, shield));
                    same(expected.tilt(), TwinsFacetPose.tilt(time, index));
                    same(expected.twist(), TwinsFacetPose.twist(time, index, shield));
                    compared++;
                }
            }
        }
        return compared;
    }

    private static void samePoint(TwinsFacetPose.Point expected, TwinsFacetPose.Point actual) {
        same(expected.x(), actual.x());
        same(expected.y(), actual.y());
        same(expected.z(), actual.z());
    }

    private static void same(double expected, double actual) {
        require(Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual), "Pose differs from the reference: " + expected + " vs " + actual);
    }

    private static void close(double a, double b) {
        require(Math.abs(a - b) < 1e-8D, "Expected matching values: " + a + ", " + b);
    }

    private static void bounded(double value, double low, double high) {
        require(Double.isFinite(value) && value >= low - 1e-8D && value <= high + 1e-8D, "Out of bounds: " + value);
    }

    private static double angleDelta(double left, double right) {
        double delta = Math.abs(left - right) % 360.0D;
        return Math.min(delta, 360.0D - delta);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
