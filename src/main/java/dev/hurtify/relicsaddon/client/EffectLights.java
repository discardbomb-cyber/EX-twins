package dev.hurtify.relicsaddon.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Light the mod's effects cast on the world. Renderers report glows every frame they draw and flashes
 * once; a lighting engine reads a merged, bounded set once a tick. The only engine is the optional
 * LambDynamicLights bridge in {@code client.light}, which LambDynamicLights itself loads: until it
 * attaches every report returns at once, so without it the renderers pay nothing.
 */
public final class EffectLights {
    /** Full {@code luminance} (0..15) within {@code radius} blocks of the centre, falling off beyond. */
    public record Light(double x, double y, double z, double luminance, double radius) {
        double distanceSqr(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Each light relights every chunk section it touches whenever it moves, so a few merged ones beat many small ones. */
    public static final int MAX_LIGHTS = 24;
    static final double MERGE_DISTANCE = 2.5, MAX_RADIUS = 4, RANGE = 128;
    private static final int MAX_REPORTS = 256, MAX_FLASHES = 64;

    private record Flash(Light light, long start, int ticks) { }

    private static List<Light> drawing = new ArrayList<>(), drawn = new ArrayList<>();
    private static long drawnAt = Long.MIN_VALUE;
    private static final List<Flash> FLASHES = new ArrayList<>();
    private static Object level;
    private static volatile boolean attached;

    /** Called by the engine bridge once it is ready to read lights. */
    public static void attach() {
        attached = true;
    }

    /** Whether reports are wanted at all: an engine is attached and the player has not turned lights off. */
    public static boolean enabled() {
        return attached && AddonClientConfig.dynamicLights();
    }

    /** A light that shines while it is reported; call it every frame the effect is drawn. */
    public static void glow(Vec3 at, double luminance, double radius) {
        if (!enabled() || !valid(at, luminance) || drawing.size() >= MAX_REPORTS) return;
        drawing.add(light(at, luminance, radius));
    }

    /** A light that fades out over {@code ticks} from now; call it once, when the event's effect starts playing. */
    public static void flash(Vec3 at, double luminance, double radius, int ticks) {
        var current = Minecraft.getInstance().level;
        if (!enabled() || current == null || !valid(at, luminance) || ticks <= 0) return;
        if (FLASHES.size() >= MAX_FLASHES) FLASHES.removeFirst();
        FLASHES.add(new Flash(light(at, luminance, radius), current.getGameTime(), ticks));
    }

    /** The frame just finished becomes what the next tick sees; a frame without effects clears it. */
    public static void onFrame(RenderFrameEvent.Pre event) {
        var current = Minecraft.getInstance().level;
        if (current != level) {
            level = current;
            FLASHES.clear();
            drawing.clear();
        }
        if (drawing.isEmpty() && drawn.isEmpty()) return;
        List<Light> finished = drawing;
        drawing = drawn;
        drawing.clear();
        drawn = finished;
        drawnAt = current == null ? Long.MIN_VALUE : current.getGameTime();
    }

    /** What should be lit at game time {@code now}: the last frame's glows and the live flashes, merged and capped. */
    public static List<Light> lights(long now, Vec3 camera) {
        var raw = new ArrayList<Light>();
        // Frames stop while the window is minimised; their last glows must not hang on.
        if (drawnAt != Long.MIN_VALUE && now - drawnAt <= 4) raw.addAll(drawn);
        FLASHES.removeIf(flash -> now - flash.start() >= flash.ticks() || flash.start() > now);
        for (Flash flash : FLASHES) {
            double left = fade(now - flash.start(), flash.ticks());
            Light light = flash.light();
            if (left > 0) raw.add(new Light(light.x(), light.y(), light.z(), light.luminance() * left, light.radius()));
        }
        raw.removeIf(light -> light.luminance() < 1 || light.distanceSqr(camera.x, camera.y, camera.z) > RANGE * RANGE);
        return merge(raw, camera, MAX_LIGHTS);
    }

    /** Share of a flash left {@code age} ticks after it starts. */
    static double fade(long age, int ticks) {
        return age < 0 || age >= ticks ? 0 : 1 - age / (double) ticks;
    }

    /**
     * Folds every light within reach of a brighter one into it, widening the reach until at most
     * {@code cap} remain; anything still over the cap is dropped farthest from the camera first.
     */
    static List<Light> merge(List<Light> raw, Vec3 camera, int cap) {
        if (raw.isEmpty() || cap <= 0) return List.of();
        var brightestFirst = new ArrayList<>(raw);
        brightestFirst.sort(Comparator.comparingDouble(Light::luminance).reversed());
        List<Light> merged = cluster(brightestFirst, MERGE_DISTANCE);
        for (double reach = MERGE_DISTANCE * 1.6; merged.size() > cap && reach <= 16; reach *= 1.6) merged = cluster(brightestFirst, reach);
        if (merged.size() > cap) {
            merged.sort(Comparator.comparingDouble(light -> light.distanceSqr(camera.x, camera.y, camera.z)));
            merged = new ArrayList<>(merged.subList(0, cap));
        }
        return merged;
    }

    /** Groups lights around the brightest one within {@code reach}, so a group never grows wider than twice the reach. */
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
            merged.add(new Light(x, y, z, group.getFirst().luminance(), Math.min(MAX_RADIUS, radius)));
        }
        return merged;
    }

    private static boolean valid(Vec3 at, double luminance) {
        return at != null && luminance >= 1 && Double.isFinite(at.x + at.y + at.z);
    }

    private static Light light(Vec3 at, double luminance, double radius) {
        return new Light(at.x, at.y, at.z, Math.min(15, luminance), Math.clamp(Double.isFinite(radius) ? radius : 0, 0, MAX_RADIUS));
    }

    private EffectLights() {
    }
}
