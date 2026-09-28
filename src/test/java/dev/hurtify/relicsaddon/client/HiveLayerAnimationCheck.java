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
        System.out.println("Hive animation: 3 independent layers and 12 independent Twins plates rotate continuously.");
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
