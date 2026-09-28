package dev.hurtify.relicsaddon.client;

/** A spherical wave reaches the antipode before fading; no repeated idle pulses. */
public final class ShieldImpactPulse {
    public static double wave(double dot, double age) {
        if (!Double.isFinite(dot) || !Double.isFinite(age) || age < 0 || age >= 36) return 0;
        double distance = Math.acos(Math.clamp(dot, -1, 1));
        double front = age * Math.PI / 28;
        double band = Math.max(0, 1 - Math.abs(distance - front) / .18);
        double fade = Math.min(1, (36 - age) / 10);
        return band * fade * .88;
    }

    public static double relief(int cell) {
        int mixed = cell * 0x45d9f3b;
        mixed = (mixed ^ mixed >>> 16) * 0x45d9f3b;
        return .018 + ((mixed ^ mixed >>> 16) & 255) / 255.0 * .105;
    }

    private ShieldImpactPulse() { }
}
