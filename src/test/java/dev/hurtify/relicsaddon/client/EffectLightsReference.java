package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.client.EffectLights.Light;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * EffectLights as it was before the scratch buffers (lists of records, nested group lists, a fresh merged
 * list per reach), kept as the reference the equivalence check draws against. The frame/flash bookkeeping
 * is modelled with the same lists the old static state used.
 */
final class EffectLightsReference {
    private record Flash(Light light, long start, int ticks) { }

    private List<Light> drawing = new ArrayList<>(), drawn = new ArrayList<>();
    private long drawnAt = Long.MIN_VALUE;
    private final List<Flash> flashes = new ArrayList<>();

    void glow(double x, double y, double z, double luminance, double radius) {
        if (!valid(x, y, z, luminance) || drawing.size() >= EffectLights.MAX_REPORTS) return;
        drawing.add(light(x, y, z, luminance, radius));
    }

    void flash(double x, double y, double z, double luminance, double radius, long now, int ticks) {
        if (!valid(x, y, z, luminance) || ticks <= 0) return;
        if (flashes.size() >= EffectLights.MAX_FLASHES) flashes.removeFirst();
        flashes.add(new Flash(light(x, y, z, luminance, radius), now, ticks));
    }

    void onFrame(long now) {
        if (drawing.isEmpty() && drawn.isEmpty()) return;
        List<Light> finished = drawing;
        drawing = drawn;
        drawing.clear();
        drawn = finished;
        drawnAt = now;
    }

    void clear() {
        flashes.clear();
        drawing.clear();
    }

    List<Light> lights(long now, Vec3 camera) {
        var raw = new ArrayList<Light>();
        if (drawnAt != Long.MIN_VALUE && now - drawnAt <= 4) raw.addAll(drawn);
        flashes.removeIf(flash -> now - flash.start() >= flash.ticks() || flash.start() > now);
        for (Flash flash : flashes) {
            double left = fade(now - flash.start(), flash.ticks());
            Light light = flash.light();
            if (left > 0) raw.add(new Light(light.x(), light.y(), light.z(), light.luminance() * left, light.radius()));
        }
        raw.removeIf(light -> light.luminance() < 1 || light.distanceSqr(camera.x, camera.y, camera.z) > EffectLights.RANGE * EffectLights.RANGE);
        return merge(raw, camera, EffectLights.MAX_LIGHTS);
    }

    static double fade(long age, int ticks) {
        return age < 0 || age >= ticks ? 0 : 1 - age / (double) ticks;
    }

    static List<Light> merge(List<Light> raw, Vec3 camera, int cap) {
        if (raw.isEmpty() || cap <= 0) return List.of();
        var brightestFirst = new ArrayList<>(raw);
        brightestFirst.sort(Comparator.comparingDouble(Light::luminance).reversed());
        List<Light> merged = cluster(brightestFirst, EffectLights.MERGE_DISTANCE);
        for (double reach = EffectLights.MERGE_DISTANCE * 1.6; merged.size() > cap && reach <= 16; reach *= 1.6) merged = cluster(brightestFirst, reach);
        if (merged.size() > cap) {
            merged.sort(Comparator.comparingDouble(light -> light.distanceSqr(camera.x, camera.y, camera.z)));
            merged = new ArrayList<>(merged.subList(0, cap));
        }
        return merged;
    }

    private static List<Light> cluster(List<Light> brightestFirst, double reach) {
        List<List<Light>> groups = new ArrayList<>();
        for (Light light : brightestFirst) {
            List<Light> home = null;
            for (List<Light> group : groups) {
                Light seed = group.getFirst();
                if (seed.distanceSqr(light.x(), light.y(), light.z()) <= reach * reach) {
                    home = group;
                    break;
                }
            }
            if (home == null) groups.add(home = new ArrayList<>());
            home.add(light);
        }
        List<Light> merged = new ArrayList<>(groups.size());
        for (List<Light> group : groups) {
            if (group.size() == 1) {
                merged.add(group.getFirst());
                continue;
            }
            double weight = 0, x = 0, y = 0, z = 0;
            for (Light light : group) {
                weight += light.luminance();
                x += light.x() * light.luminance();
                y += light.y() * light.luminance();
                z += light.z() * light.luminance();
            }
            x /= weight;
            y /= weight;
            z /= weight;
            double radius = 0;
            for (Light light : group) radius = Math.max(radius, Math.sqrt(light.distanceSqr(x, y, z)) + light.radius());
            merged.add(new Light(x, y, z, group.getFirst().luminance(), Math.min(EffectLights.MAX_RADIUS, radius)));
        }
        return merged;
    }

    private static boolean valid(double x, double y, double z, double luminance) {
        return luminance >= 1 && Double.isFinite(x + y + z);
    }

    private static Light light(double x, double y, double z, double luminance, double radius) {
        return new Light(x, y, z, Math.min(15, luminance), Math.clamp(Double.isFinite(radius) ? radius : 0, 0, EffectLights.MAX_RADIUS));
    }
}
