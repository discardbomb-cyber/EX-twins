package dev.hurtify.relicsaddon.client;

/** Pure guard: the Ex-Twins hive is a set of independently moving volumes. */
public final class HiveLayerAnimationCheck {
    public static void main(String[] args) {
        double first = 17.25, next = first + .125;
        HiveShellPose.LayerPose[] layers = new HiveShellPose.LayerPose[3];
        for (int layer = 0; layer < layers.length; layer++) {
            layers[layer] = HiveShellPose.twinsLayer(layer, first);
            HiveShellPose.LayerPose later = HiveShellPose.twinsLayer(layer, next);
            requireFinite(layers[layer]);
            require(Math.abs(later.degrees() - layers[layer].degrees()) > .01F,
                    "Twins hive layer must rotate continuously");
        }
        for (int left = 0; left < layers.length; left++) for (int right = left + 1; right < layers.length; right++) {
            HiveShellPose.LayerPose a = layers[left], b = layers[right];
            require(Math.abs(a.axisX() - b.axisX()) + Math.abs(a.axisY() - b.axisY()) + Math.abs(a.axisZ() - b.axisZ()) > .2F,
                    "Twins hive layers need distinct axes");
        }
        for (int index = 0; index < 12; index++) {
            HiveShellPose.Pose now = HiveShellPose.sample(12, index, first);
            HiveShellPose.Pose later = HiveShellPose.sample(12, index, next);
            require(Double.isFinite(now.x()) && Double.isFinite(now.y()) && Double.isFinite(now.z()), "shell position must be finite");
            require(Math.abs(later.spin() - now.spin()) > .01F, "every outer plate must rotate");
            for (int other = 0; other < index; other++) {
                HiveShellPose.Pose prior = HiveShellPose.sample(12, other, first);
                require(Math.abs(prior.spin() - now.spin()) > .01F, "outer plates cannot share a locked phase");
            }
        }
        for (int shells : new int[]{4, 6, 12}) for (int index = 0; index < shells; index++) {
            for (int tick = 0; tick <= 2400; tick++) {
                HiveShellPose.Pose pose = HiveShellPose.sample(shells, index, tick * .5);
                require(pose.offset() >= .01 && pose.offset() <= (shells == 12 ? .030001 : .018001),
                        "closed hive shells must preserve narrow articulated joints");
                require(Math.abs(pose.tilt()) <= 1.5001, "hive doors cannot expose huge cut-out gaps");
            }
        }
        long identical = checkTabulatedJoints();
        System.out.println("Hive animation: 3 independent layers and 12 independent Twins plates rotate continuously; "
                + identical + " tabulated poses bit-identical to the reference.");
    }

    /** The tabulated axes and split clocks give exactly the pose the old per-sample code gave, bit for bit. */
    private static long checkTabulatedJoints() {
        long compared = 0;
        double[] times = new double[4821];
        for (int k = 0; k < 4801; k++) times[k] = -1200 + k * .5D;
        double[] odd = {0, 17.25, 17.375, 1234.5625D, 1e6 + .125D, 1e9, 1e12, -7.75D, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, -Double.MIN_VALUE, 360.0D, 720.5D, 2.5e-3D, 73.25D, 99999.5D, -99999.5D, 1e15};
        System.arraycopy(odd, 0, times, 4801, odd.length);
        for (double time : times) {
            for (int shells : new int[] {4, 6, 12}) for (int index = 0; index < shells; index++) {
                HiveShellPose.Pose expected = HiveShellPoseReference.sample(shells, index, time);
                HiveShellPose.Pose actual = HiveShellPose.sample(shells, index, time);
                double[] axis = HiveShellPose.axis(shells, index);
                same(expected.x(), actual.x());
                same(expected.y(), actual.y());
                same(expected.z(), actual.z());
                same(expected.x(), axis[0]);
                same(expected.y(), axis[1]);
                same(expected.z(), axis[2]);
                same(expected.offset(), actual.offset());
                same(expected.offset(), HiveShellPose.opening(shells, index, time));
                same(expected.tilt(), actual.tilt());
                same(expected.tilt(), HiveShellPose.tilt(shells, index, time));
                same(expected.spin(), actual.spin());
                same(expected.spin(), HiveShellPose.spin(shells, index, time));
                compared++;
            }
            for (int layer = 0; layer < 3; layer++) {
                HiveShellPose.LayerPose expected = HiveShellPoseReference.twinsLayer(layer, time);
                HiveShellPose.LayerPose actual = HiveShellPose.twinsLayer(layer, time);
                float[] axis = HiveShellPose.twinsLayerAxis(layer);
                require(expected.equals(actual), "Twins hive layer differs from the reference: " + expected + " vs " + actual);
                same(expected.axisX(), axis[0]);
                same(expected.axisY(), axis[1]);
                same(expected.axisZ(), axis[2]);
                same(expected.degrees(), HiveShellPose.twinsLayerDegrees(layer, time));
                compared++;
            }
        }
        return compared;
    }

    private static void same(double expected, double actual) {
        require(Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual), "Pose differs from the reference: " + expected + " vs " + actual);
    }

    private static void same(float expected, float actual) {
        require(Float.floatToIntBits(expected) == Float.floatToIntBits(actual), "Pose differs from the reference: " + expected + " vs " + actual);
    }

    private static void requireFinite(HiveShellPose.LayerPose pose) {
        require(Float.isFinite(pose.axisX()) && Float.isFinite(pose.axisY()) && Float.isFinite(pose.axisZ()) && Float.isFinite(pose.degrees()),
                "layer pose must be finite");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private HiveLayerAnimationCheck() { }
}
