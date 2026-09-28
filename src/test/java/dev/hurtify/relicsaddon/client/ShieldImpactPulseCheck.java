package dev.hurtify.relicsaddon.client;

public final class ShieldImpactPulseCheck {
    public static void main(String[] args) {
        for (int step = 0; step <= 28; step++) {
            double angle = step * Math.PI / 28;
            require(ShieldImpactPulse.wave(Math.cos(angle), step) > .60, "Wave must reach every spherical latitude");
        }
        require(ShieldImpactPulse.wave(-1, 0) == 0, "Antipode must not flash before the wave arrives");
        require(ShieldImpactPulse.wave(1, -1) == 0 && ShieldImpactPulse.wave(1, 36) == 0, "No idle or stale wave");
        double olderFront = ShieldImpactPulse.wave(Math.cos(10 * Math.PI / 28), 10);
        double newerFront = ShieldImpactPulse.wave(Math.cos(2 * Math.PI / 28), 2);
        require(olderFront > .60 && newerFront > .60,
                "Two independently aged travelling fronts must remain valid together");
        double min = 1, max = 0;
        for (int id = 0; id < 420; id++) {
            double height = ShieldImpactPulse.relief(id);
            require(height >= .018 && height <= .1231, "Panel relief must remain bounded");
            min = Math.min(min, height); max = Math.max(max, height);
        }
        require(max - min > .10, "Independent segment heights required");
        for (int shells : new int[]{4, 6, 12}) for (int index = 0; index < shells; index++) for (int tick = 0; tick < 2400; tick++) {
            var pose = HiveShellPose.sample(shells, index, tick * .5);
            require(Math.abs(pose.x() * pose.x() + pose.y() * pose.y() + pose.z() * pose.z() - 1) < 1e-8, "Unit opening axis");
            require(pose.offset() >= .010 && pose.offset() <= (shells == 12 ? .03001 : .01801)
                    && Math.abs(pose.tilt()) <= 1.501, "Bounded closed-hull hive animation");
        }
        System.out.println("Impact waves: full sphere, overlap preserved, no idle pulse; 420 raised cells; hive joints 4/6/12 bounded");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
