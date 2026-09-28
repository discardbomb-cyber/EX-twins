package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Union of incoming-facing hemispheres, without drawing overlapping glass twice. */
final class ManaDomeSurface {
    record Cap(Vec3 normal, double strength) { }

    static List<Cap> caps(List<ShieldResponse.Threat> threats, List<ShieldImpact> impacts, double time) {
        var caps = new ArrayList<Cap>();
        for (var threat : threats) {
            add(caps, threat.normal(), .35 + .65 * Math.clamp(1 - threat.ticks() / ShieldField.PREVIEW_TICKS, 0, 1));
        }
        for (var impact : impacts) {
            double fade = ShieldField.fade(time - impact.gameTime(), ShieldResponse.IMPACT_TICKS);
            if (fade > 0 && impact.absorbed() > 0) add(caps, impact.normal(), fade);
        }
        return caps;
    }

    private static void add(List<Cap> caps, Vec3 normal, double strength) {
        for (int i = 0; i < caps.size(); i++) {
            if (caps.get(i).normal().dot(normal) > .9999) {
                if (strength > caps.get(i).strength()) caps.set(i, new Cap(normal, strength));
                return;
            }
        }
        caps.add(new Cap(normal, strength));
    }

    static double presence(Vec3 point, List<Cap> caps) {
        double strength = 0;
        for (var cap : caps) if (point.dot(cap.normal()) >= -1e-7) strength = Math.max(strength, cap.strength());
        return strength;
    }

    static double rim(Vec3 point, List<Cap> caps) {
        double interior = -1;
        for (var cap : caps) interior = Math.max(interior, point.dot(cap.normal()));
        return Math.pow(Math.clamp(1 - interior / .075, 0, 1), 2);
    }

    static List<List<Vec3>> clip(Vec3 a, Vec3 b, Vec3 c, List<Cap> caps) {
        var result = new ArrayList<List<Vec3>>(2);
        List<Vec3> remaining = List.of(a, b, c);
        for (var cap : caps) {
            if (remaining.size() < 3) break;
            List<Vec3> covered = half(remaining, cap.normal());
            if (covered.size() >= 3) result.add(covered);
            remaining = half(remaining, cap.normal().scale(-1));
        }
        return result;
    }

    private static List<Vec3> half(List<Vec3> polygon, Vec3 normal) {
        var next = new ArrayList<Vec3>(5);
        Vec3 previous = polygon.getLast();
        double before = previous.dot(normal);
        for (Vec3 current : polygon) {
            double now = current.dot(normal);
            if ((before >= 0) != (now >= 0)) next.add(previous.lerp(current, before / (before - now)).normalize());
            if (now >= 0) next.add(current);
            previous = current;
            before = now;
        }
        return next;
    }

    private ManaDomeSurface() { }
}
