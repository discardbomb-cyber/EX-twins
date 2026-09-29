package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.util.List;

/**
 * Travelling displacement wave that follows an absorbed hit across the Mana and Twins shells.
 * The front moves at the same speed as {@link ShieldImpactPulse#wave}: a crest leads, a trough
 * follows, and a weaker after-ripple trails behind. Heights are unitless in [-1, 1]; callers
 * scale them into a radial offset.
 *
 * <p>Rendering is single-threaded, so the active impacts for the shell being drawn are held in a
 * small static buffer between {@link #begin} and {@link #end} instead of threading them through
 * every vertex helper.
 */
public final class ShieldRipple {
    /** Fraction of the shell radius the crest is pushed outwards at full strength. */
    static final double AMPLITUDE = .055;
    private static final double MAX_OFFSET = .085;
    private static final double SIGMA = .16;
    private static final int MAX_WAVES = 12;
    private static final double[] NX = new double[MAX_WAVES], NY = new double[MAX_WAVES], NZ = new double[MAX_WAVES];
    private static final double[] AGE = new double[MAX_WAVES], STRENGTH = new double[MAX_WAVES];
    private static int count;
    private static double gain = 1;

    /** How strongly each shell bends: Twins keep their layered look with a softer wave than Mana. */
    public static double roleScale(dev.hurtify.relicsaddon.relic.RelicRole role) {
        return role == dev.hurtify.relicsaddon.relic.RelicRole.TWINS_SHIELD ? .4 : 1;
    }

    public static void begin(List<ShieldImpact> impacts, double time) {
        begin(impacts, time, 1);
    }

    public static void begin(List<ShieldImpact> impacts, double time, double scale) {
        count = 0;
        gain = AddonClientConfig.rippleStrength() * scale;
        if (gain <= 0) return;
        for (ShieldImpact impact : impacts) {
            double age = time - impact.gameTime();
            if (impact.absorbed() <= 0 || age < 0 || age >= ShieldResponse.IMPACT_TICKS || count == MAX_WAVES) continue;
            NX[count] = impact.normal().x;
            NY[count] = impact.normal().y;
            NZ[count] = impact.normal().z;
            AGE[count] = age;
            STRENGTH[count] = strength(impact.absorbed());
            count++;
        }
    }

    public static void end() {
        count = 0;
    }

    public static boolean active() {
        return count > 0;
    }

    /** Summed, clamped wave height at a world-oriented unit direction from the shell centre. */
    public static double height(double x, double y, double z) {
        double sum = 0;
        for (int index = 0; index < count; index++) {
            sum += profile(x * NX[index] + y * NY[index] + z * NZ[index], AGE[index]) * STRENGTH[index];
        }
        return Math.clamp(sum * gain, -1, 1);
    }

    /** Multiplier for the shell radius at a direction; exactly 1 when no wave is active. */
    public static double scale(double x, double y, double z) {
        if (count == 0) return 1;
        return 1 + Math.clamp(height(x, y, z) * AMPLITUDE, -MAX_OFFSET, MAX_OFFSET);
    }

    /** Height contribution of one wave, for the refraction band which draws each wave separately. */
    public static double profile(double dot, double age) {
        if (!Double.isFinite(dot) || !Double.isFinite(age) || age < 0 || age >= ShieldResponse.IMPACT_TICKS) return 0;
        double distance = Math.acos(Math.clamp(dot, -1, 1));
        double u = (distance - front(age)) / SIGMA;
        // Derivative-of-Gaussian: crest just ahead of the front, trough just behind it.
        double main = u * Math.exp(-u * u) * 2.3316;
        // Damped after-ripple in the wake of the front.
        double wake = u < -1 ? .38 * Math.sin((u + 1) * 2.4) * Math.exp((u + 1) * .55) : 0;
        return (main + wake) * fade(age);
    }

    public static double front(double age) {
        return age * Math.PI / 28;
    }

    public static double fade(double age) {
        return Math.clamp((ShieldResponse.IMPACT_TICKS - age) / 12, 0, 1) * (1 - age / 60);
    }

    public static double strength(float absorbed) {
        return Math.clamp(.45 + absorbed / 10.0, .45, 1);
    }

    private ShieldRipple() {
    }
}
