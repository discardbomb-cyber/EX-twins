package dev.hurtify.relicsaddon.client;

/** Pure guard for the independent body/core/fx/arcs motion of the Twins shield. */
public final class TwinsShieldLayerAnimationCheck {
    public static void main(String[] args) {
        double time = 73.25D, later = time + .125D;
        TwinsShieldLayerPose.Layer[] layers = new TwinsShieldLayerPose.Layer[3];
        for (int index = 0; index < layers.length; index++) {
            layers[index] = TwinsShieldLayerPose.layer(index, time);
            TwinsShieldLayerPose.Layer next = TwinsShieldLayerPose.layer(index, later);
            require(Float.isFinite(layers[index].axisX()) && Float.isFinite(layers[index].axisY())
                    && Float.isFinite(layers[index].axisZ()) && Float.isFinite(layers[index].degrees()), "layer must be finite");
            require(Math.abs(next.degrees() - layers[index].degrees()) > .01F, "layer must rotate continuously");
        }
        for (int left = 0; left < layers.length; left++) for (int right = left + 1; right < layers.length; right++) {
            TwinsShieldLayerPose.Layer a = layers[left], b = layers[right];
            require(Math.abs(a.axisX() - b.axisX()) + Math.abs(a.axisY() - b.axisY()) + Math.abs(a.axisZ() - b.axisZ()) > .2F,
                    "shield layers require distinct axes");
        }
        for (int index = 0; index < TwinsFacetPose.COUNT; index++) {
            float now = TwinsShieldLayerPose.shellSpin(index, time);
            float motion = 0.0F;
            for (int sample = 1; sample <= 12; sample++) {
                motion += Math.abs(TwinsShieldLayerPose.shellSpin(index, time + sample * .25D) - now);
            }
            require(Float.isFinite(now) && motion > .01F, "every arc must move over a short interval");
            for (int other = 0; other < index; other++) {
                float otherNow = TwinsShieldLayerPose.shellSpin(other, time);
                float laterNow = TwinsShieldLayerPose.shellSpin(index, time + 5.0D);
                float otherLater = TwinsShieldLayerPose.shellSpin(other, time + 5.0D);
                require(Math.abs(now - otherNow) + Math.abs(laterNow - otherLater) > .01F,
                        "arcs need distinct motion signatures");
            }
        }
        long identical = 0;
        for (double tick = -1200; tick <= 1200; tick += .5D) {
            for (double sample : new double[] {tick, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e12}) {
                for (int index = 0; index < 3; index++) {
                    TwinsShieldLayerPose.Layer expected = HiveShellPoseReference.shieldLayer(index, sample);
                    require(expected.equals(TwinsShieldLayerPose.layer(index, sample)), "Shield layer differs from the reference at " + sample);
                    float[] axis = TwinsShieldLayerPose.axis(index);
                    require(Float.floatToIntBits(expected.degrees()) == Float.floatToIntBits(TwinsShieldLayerPose.degrees(index, sample))
                            && expected.axisX() == axis[0] && expected.axisY() == axis[1] && expected.axisZ() == axis[2],
                            "Shield layer axis or angle differs from the reference at " + sample);
                    identical++;
                }
            }
        }
        System.out.println("Twins shield animation: body/core/fx rotate and all arc plates move independently; "
                + identical + " tabulated layers bit-identical to the reference.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private TwinsShieldLayerAnimationCheck() { }
}
