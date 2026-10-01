package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.shield.ShieldField;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Bounded, deterministic visual envelope; no idle shell and no fabricated absorption hit. */
public final class ShieldResponse {
    public static final int IMPACT_TICKS = 36;
    public record Threat(Vec3 normal, double ticks) {
    }

    public record Light(double presence, double absorption, double destruction) {
    }

    public static Light at(Vec3 normal, List<Threat> threats, ShieldImpact impact, double time, int integrity) {
        return atMany(normal, threats, impact == null ? List.of() : List.of(impact), time, integrity);
    }

    /**
     * Combines concurrent wave fronts by their strongest local contribution. The renderer can therefore draw one
     * shell, even while several independent impacts are still travelling across it.
     */
    public static Light atMany(Vec3 normal, List<Threat> threats, List<ShieldImpact> impacts, double time, int integrity) {
        Sampler sampler = new Sampler();
        sampler.reset(threats, impacts, time);
        sampler.sample(normal.x, normal.y, normal.z, integrity);
        return new Light(sampler.presence, sampler.absorption, sampler.destruction);
    }

    /**
     * {@link #atMany} for thousands of directions on one shell: the threats' strengths and the impacts'
     * ages and fades are worked out once in {@link #reset}, then {@link #sample} leaves each direction's
     * light in {@link #presence}, {@link #absorption} and {@link #destruction} without allocating.
     */
    public static final class Sampler {
        private double[] threatX = new double[8], threatY = new double[8], threatZ = new double[8], threatStrength = new double[8];
        private int threatCount;
        private double[] hitX = new double[12], hitY = new double[12], hitZ = new double[12];
        private double[] hitAge = new double[12], hitFade = new double[12], hitBreakFade = new double[12];
        private boolean[] hitAbsorbed = new boolean[12], hitBroken = new boolean[12];
        private int hitCount;
        public double presence, absorption, destruction;

        public void reset(List<Threat> threats, List<ShieldImpact> impacts, double time) {
            threatCount = 0;
            for (Threat threat : threats) {
                if (threatCount == threatX.length) growThreats();
                threatX[threatCount] = threat.normal().x;
                threatY[threatCount] = threat.normal().y;
                threatZ[threatCount] = threat.normal().z;
                threatStrength[threatCount] = .25D + .75D * Math.clamp(1 - threat.ticks() / ShieldField.PREVIEW_TICKS, 0, 1);
                threatCount++;
            }
            hitCount = 0;
            for (ShieldImpact impact : impacts) {
                double age = time - impact.gameTime();
                if (age < 0 || age >= IMPACT_TICKS) continue;
                if (hitCount == hitX.length) growHits();
                hitX[hitCount] = impact.normal().x;
                hitY[hitCount] = impact.normal().y;
                hitZ[hitCount] = impact.normal().z;
                hitAge[hitCount] = age;
                hitFade[hitCount] = ShieldField.fade(age, 16);
                hitBreakFade[hitCount] = ShieldField.fade(age, 20);
                hitAbsorbed[hitCount] = impact.absorbed() > 0;
                hitBroken[hitCount] = impact.broken();
                hitCount++;
            }
        }

        /** Light at the world-oriented unit direction (x, y, z); {@code integrity} 0 leaves a cell without anticipation. */
        public void sample(double x, double y, double z, int integrity) {
            double anticipation = 0, absorbed = 0, destroyed = 0;
            if (integrity > 0) {
                for (int k = 0; k < threatCount; k++) {
                    anticipation = Math.max(anticipation, ShieldField.focus(x * threatX[k] + y * threatY[k] + z * threatZ[k], .72D) * threatStrength[k]);
                }
            }
            for (int k = 0; k < hitCount; k++) {
                double dot = x * hitX[k] + y * hitY[k] + z * hitZ[k];
                double fade = hitFade[k];
                if (hitAbsorbed[k] && fade > 0) {
                    absorbed = Math.max(absorbed, fade * ShieldField.focus(dot, .28D));
                }
                if (hitAbsorbed[k]) absorbed = Math.max(absorbed, ShieldImpactPulse.wave(dot, hitAge[k]));
                if (hitBroken[k]) destroyed = Math.max(destroyed, ShieldField.focus(dot, .34D) * hitBreakFade[k]);
            }
            presence = Math.max(anticipation, Math.max(absorbed, destroyed));
            absorption = absorbed;
            destruction = destroyed;
        }

        private void growThreats() {
            int size = threatX.length * 2;
            threatX = Arrays.copyOf(threatX, size);
            threatY = Arrays.copyOf(threatY, size);
            threatZ = Arrays.copyOf(threatZ, size);
            threatStrength = Arrays.copyOf(threatStrength, size);
        }

        private void growHits() {
            int size = hitX.length * 2;
            hitX = Arrays.copyOf(hitX, size);
            hitY = Arrays.copyOf(hitY, size);
            hitZ = Arrays.copyOf(hitZ, size);
            hitAge = Arrays.copyOf(hitAge, size);
            hitFade = Arrays.copyOf(hitFade, size);
            hitBreakFade = Arrays.copyOf(hitBreakFade, size);
            hitAbsorbed = Arrays.copyOf(hitAbsorbed, size);
            hitBroken = Arrays.copyOf(hitBroken, size);
        }
    }

    private ShieldResponse() {
    }
}
