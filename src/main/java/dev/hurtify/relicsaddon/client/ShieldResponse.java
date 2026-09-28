package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
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
        double anticipation = 0, absorption = 0, destruction = 0;
        if (integrity > 0) {
            for (Threat threat : threats) {
                double strength = .25D + .75D * Math.clamp(1 - threat.ticks() / ShieldField.PREVIEW_TICKS, 0, 1);
                anticipation = Math.max(anticipation, ShieldField.focus(normal.dot(threat.normal()), .72D) * strength);
            }
        }
        for (ShieldImpact impact : impacts) {
            double age = time - impact.gameTime();
            if (age < 0 || age >= IMPACT_TICKS) continue;
            double dot = normal.dot(impact.normal());
            double fade = ShieldField.fade(age, 16);
            if (impact.absorbed() > 0 && fade > 0) {
                absorption = Math.max(absorption, fade * ShieldField.focus(dot, .28D));
            }
            if (impact.absorbed() > 0) absorption = Math.max(absorption, ShieldImpactPulse.wave(dot, age));
            if (impact.broken()) destruction = Math.max(destruction, ShieldField.focus(dot, .34D) * ShieldField.fade(age, 20));
        }
        return new Light(Math.max(anticipation, Math.max(absorption, destruction)), absorption, destruction);
    }

    private ShieldResponse() {
    }
}
