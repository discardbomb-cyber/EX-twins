package dev.hurtify.relicsaddon.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Light the mod's effects cast on the world. Renderers report glows every frame they draw and flashes
 * once; a lighting engine reads a merged, bounded set once a tick. The only engine is the optional
 * LambDynamicLights bridge in {@code client.light}, which LambDynamicLights itself loads: until it
 * attaches every report returns at once, so without it the renderers pay nothing.
 *
 * <p>Reports, flashes and the merge live in fixed primitive buffers sized by the caps, so a tick
 * allocates only the handful of {@link Light}s it hands out. Everything here runs on the render thread.
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
    static final int MAX_REPORTS = 256, MAX_FLASHES = 64;
    private static final int RAW = MAX_REPORTS + MAX_FLASHES;

    /** One frame's glow reports, column by column. */
    private static final class Reports {
        final double[] x = new double[MAX_REPORTS], y = new double[MAX_REPORTS], z = new double[MAX_REPORTS];
        final double[] luminance = new double[MAX_REPORTS], radius = new double[MAX_REPORTS];
        int count;
    }

    private static Reports drawing = new Reports(), drawn = new Reports();
    private static long drawnAt = Long.MIN_VALUE;

    // Live flashes, oldest first.
    private static final double[] FLASH_X = new double[MAX_FLASHES], FLASH_Y = new double[MAX_FLASHES], FLASH_Z = new double[MAX_FLASHES];
    private static final double[] FLASH_LUMINANCE = new double[MAX_FLASHES], FLASH_RADIUS = new double[MAX_FLASHES];
    private static final long[] FLASH_START = new long[MAX_FLASHES];
    private static final int[] FLASH_TICKS = new int[MAX_FLASHES];
    private static int flashes;

    // The tick's candidates (last frame's glows, then the flashes as they are now), in report order.
    private static final double[] RAW_X = new double[RAW], RAW_Y = new double[RAW], RAW_Z = new double[RAW];
    private static final double[] RAW_LUMINANCE = new double[RAW], RAW_RADIUS = new double[RAW];
    private static int raw;

    // Clustering scratch: candidates brightest first, their groups, and each group's merged light.
    private static final int[] ORDER = new int[RAW], SORT_SCRATCH = new int[RAW], GROUP_OF = new int[RAW];
    private static final int[] GROUP_SEED = new int[RAW], GROUP_SIZE = new int[RAW], GROUP_ORDER = new int[RAW];
    private static final double[] GROUP_WEIGHT = new double[RAW], GROUP_REACH = new double[RAW], GROUP_DISTANCE = new double[RAW];
    private static final double[] MERGED_X = new double[RAW], MERGED_Y = new double[RAW], MERGED_Z = new double[RAW];
    private static final double[] MERGED_LUMINANCE = new double[RAW], MERGED_RADIUS = new double[RAW];
    private static int groups;

    private static final List<Light> OUT = new ArrayList<>(MAX_LIGHTS);
    private static final List<Light> OUT_VIEW = Collections.unmodifiableList(OUT);

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
        if (!enabled() || at == null) return;
        report(at.x, at.y, at.z, luminance, radius);
    }

    /** A light that fades out over {@code ticks} from now; call it once, when the event's effect starts playing. */
    public static void flash(Vec3 at, double luminance, double radius, int ticks) {
        var current = Minecraft.getInstance().level;
        if (!enabled() || current == null || at == null) return;
        flash(at.x, at.y, at.z, luminance, radius, current.getGameTime(), ticks);
    }

    /** The frame just finished becomes what the next tick sees; a frame without effects clears it. */
    public static void onFrame(RenderFrameEvent.Pre event) {
        var current = Minecraft.getInstance().level;
        if (current != level) {
            level = current;
            reset();
        }
        endFrame(current == null ? Long.MIN_VALUE : current.getGameTime());
    }

    /**
     * What should be lit at game time {@code now}: the last frame's glows and the live flashes, merged
     * and capped. The list is reused by the next call.
     */
    public static List<Light> lights(long now, Vec3 camera) {
        raw = 0;
        // Frames stop while the window is minimised; their last glows must not hang on.
        if (drawnAt != Long.MIN_VALUE && now - drawnAt <= 4) {
            Reports frame = drawn;
            for (int k = 0; k < frame.count; k++) candidate(frame.x[k], frame.y[k], frame.z[k], frame.luminance[k], frame.radius[k], camera);
        }
        int live = 0;
        for (int k = 0; k < flashes; k++) {
            long age = now - FLASH_START[k];
            if (age >= FLASH_TICKS[k] || age < 0) continue;
            if (live != k) {
                FLASH_X[live] = FLASH_X[k];
                FLASH_Y[live] = FLASH_Y[k];
                FLASH_Z[live] = FLASH_Z[k];
                FLASH_LUMINANCE[live] = FLASH_LUMINANCE[k];
                FLASH_RADIUS[live] = FLASH_RADIUS[k];
                FLASH_START[live] = FLASH_START[k];
                FLASH_TICKS[live] = FLASH_TICKS[k];
            }
            double left = fade(age, FLASH_TICKS[live]);
            if (left > 0) candidate(FLASH_X[live], FLASH_Y[live], FLASH_Z[live], FLASH_LUMINANCE[live] * left, FLASH_RADIUS[live], camera);
            live++;
        }
        flashes = live;
        return reduce(camera, MAX_LIGHTS);
    }

    /** Share of a flash left {@code age} ticks after it starts. */
    static double fade(long age, int ticks) {
        return age < 0 || age >= ticks ? 0 : 1 - age / (double) ticks;
    }

    /**
     * Folds every light within reach of a brighter one into it, widening the reach until at most
     * {@code cap} remain; anything still over the cap is dropped farthest from the camera first.
     * The returned list is reused by the next merge.
     */
    static List<Light> merge(List<Light> lights, Vec3 camera, int cap) {
        raw = 0;
        for (Light light : lights) {
            if (raw >= RAW) break;
            RAW_X[raw] = light.x();
            RAW_Y[raw] = light.y();
            RAW_Z[raw] = light.z();
            RAW_LUMINANCE[raw] = light.luminance();
            RAW_RADIUS[raw] = light.radius();
            raw++;
        }
        return reduce(camera, cap);
    }

    // --- what the public entry points do once past the Minecraft checks; the checks drive these directly ---

    static void report(double x, double y, double z, double luminance, double radius) {
        if (!valid(x, y, z, luminance) || drawing.count >= MAX_REPORTS) return;
        Reports frame = drawing;
        int k = frame.count++;
        frame.x[k] = x;
        frame.y[k] = y;
        frame.z[k] = z;
        frame.luminance[k] = Math.min(15, luminance);
        frame.radius[k] = radius(radius);
    }

    static void flash(double x, double y, double z, double luminance, double radius, long now, int ticks) {
        if (!valid(x, y, z, luminance) || ticks <= 0) return;
        if (flashes >= MAX_FLASHES) {
            System.arraycopy(FLASH_X, 1, FLASH_X, 0, MAX_FLASHES - 1);
            System.arraycopy(FLASH_Y, 1, FLASH_Y, 0, MAX_FLASHES - 1);
            System.arraycopy(FLASH_Z, 1, FLASH_Z, 0, MAX_FLASHES - 1);
            System.arraycopy(FLASH_LUMINANCE, 1, FLASH_LUMINANCE, 0, MAX_FLASHES - 1);
            System.arraycopy(FLASH_RADIUS, 1, FLASH_RADIUS, 0, MAX_FLASHES - 1);
            System.arraycopy(FLASH_START, 1, FLASH_START, 0, MAX_FLASHES - 1);
            System.arraycopy(FLASH_TICKS, 1, FLASH_TICKS, 0, MAX_FLASHES - 1);
            flashes--;
        }
        int k = flashes++;
        FLASH_X[k] = x;
        FLASH_Y[k] = y;
        FLASH_Z[k] = z;
        FLASH_LUMINANCE[k] = Math.min(15, luminance);
        FLASH_RADIUS[k] = radius(radius);
        FLASH_START[k] = now;
        FLASH_TICKS[k] = ticks;
    }

    static void endFrame(long now) {
        if (drawing.count == 0 && drawn.count == 0) return;
        Reports finished = drawing;
        drawing = drawn;
        drawing.count = 0;
        drawn = finished;
        drawnAt = now;
    }

    static void reset() {
        flashes = 0;
        drawing.count = 0;
    }

    // --- the merge ----------------------------------------------------------------------------------

    private static void candidate(double x, double y, double z, double luminance, double radius, Vec3 camera) {
        if (luminance < 1) return;
        double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
        if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) return;
        RAW_X[raw] = x;
        RAW_Y[raw] = y;
        RAW_Z[raw] = z;
        RAW_LUMINANCE[raw] = luminance;
        RAW_RADIUS[raw] = radius;
        raw++;
    }

    private static List<Light> reduce(Vec3 camera, int cap) {
        OUT.clear();
        if (raw == 0 || cap <= 0) return OUT_VIEW;
        for (int k = 0; k < raw; k++) ORDER[k] = k;
        sortStable(ORDER, raw, RAW_LUMINANCE, true);
        cluster(MERGE_DISTANCE);
        for (double reach = MERGE_DISTANCE * 1.6; groups > cap && reach <= 16; reach *= 1.6) cluster(reach);
        int kept = groups;
        for (int g = 0; g < groups; g++) GROUP_ORDER[g] = g;
        if (groups > cap) {
            for (int g = 0; g < groups; g++) {
                double dx = MERGED_X[g] - camera.x, dy = MERGED_Y[g] - camera.y, dz = MERGED_Z[g] - camera.z;
                GROUP_DISTANCE[g] = dx * dx + dy * dy + dz * dz;
            }
            sortStable(GROUP_ORDER, groups, GROUP_DISTANCE, false);
            kept = cap;
        }
        for (int k = 0; k < kept; k++) {
            int g = GROUP_ORDER[k];
            OUT.add(new Light(MERGED_X[g], MERGED_Y[g], MERGED_Z[g], MERGED_LUMINANCE[g], MERGED_RADIUS[g]));
        }
        return OUT_VIEW;
    }

    /** Groups lights around the brightest one within {@code reach}, so a group never grows wider than twice the reach. */
    private static void cluster(double reach) {
        double reachSqr = reach * reach;
        groups = 0;
        for (int k = 0; k < raw; k++) {
            int light = ORDER[k];
            double x = RAW_X[light], y = RAW_Y[light], z = RAW_Z[light], luminance = RAW_LUMINANCE[light];
            int home = -1;
            for (int g = 0; g < groups; g++) {
                int seed = GROUP_SEED[g];
                double dx = RAW_X[seed] - x, dy = RAW_Y[seed] - y, dz = RAW_Z[seed] - z;
                if (dx * dx + dy * dy + dz * dz <= reachSqr) {
                    home = g;
                    break;
                }
            }
            if (home < 0) {
                home = groups++;
                GROUP_SEED[home] = light;
                GROUP_SIZE[home] = 0;
                GROUP_WEIGHT[home] = 0;
                MERGED_X[home] = 0;
                MERGED_Y[home] = 0;
                MERGED_Z[home] = 0;
                GROUP_REACH[home] = 0;
            }
            GROUP_OF[k] = home;
            GROUP_SIZE[home]++;
            GROUP_WEIGHT[home] += luminance;
            MERGED_X[home] += x * luminance;
            MERGED_Y[home] += y * luminance;
            MERGED_Z[home] += z * luminance;
        }
        for (int g = 0; g < groups; g++) {
            int seed = GROUP_SEED[g];
            MERGED_LUMINANCE[g] = RAW_LUMINANCE[seed];
            if (GROUP_SIZE[g] == 1) {
                MERGED_X[g] = RAW_X[seed];
                MERGED_Y[g] = RAW_Y[seed];
                MERGED_Z[g] = RAW_Z[seed];
                MERGED_RADIUS[g] = RAW_RADIUS[seed];
            } else {
                MERGED_X[g] /= GROUP_WEIGHT[g];
                MERGED_Y[g] /= GROUP_WEIGHT[g];
                MERGED_Z[g] /= GROUP_WEIGHT[g];
            }
        }
        for (int k = 0; k < raw; k++) {
            int g = GROUP_OF[k];
            if (GROUP_SIZE[g] == 1) continue;
            int light = ORDER[k];
            double dx = RAW_X[light] - MERGED_X[g], dy = RAW_Y[light] - MERGED_Y[g], dz = RAW_Z[light] - MERGED_Z[g];
            GROUP_REACH[g] = Math.max(GROUP_REACH[g], Math.sqrt(dx * dx + dy * dy + dz * dz) + RAW_RADIUS[light]);
        }
        for (int g = 0; g < groups; g++) if (GROUP_SIZE[g] > 1) MERGED_RADIUS[g] = Math.min(MAX_RADIUS, GROUP_REACH[g]);
    }

    /**
     * Stable merge sort of {@code index[0..n)} by {@code key[index]}, ascending or descending, with
     * {@link Double#compare} ties kept in their order: what {@code List.sort} with a comparing-double
     * comparator (reversed for descending) produces.
     */
    private static void sortStable(int[] index, int n, double[] key, boolean descending) {
        for (int width = 1; width < n; width *= 2) {
            for (int low = 0; low < n - width; low += 2 * width) {
                int middle = low + width, high = Math.min(low + 2 * width, n);
                int a = low, b = middle, out = low;
                while (a < middle && b < high) {
                    int compared = Double.compare(key[index[a]], key[index[b]]);
                    boolean left = descending ? compared >= 0 : compared <= 0;
                    SORT_SCRATCH[out++] = left ? index[a++] : index[b++];
                }
                while (a < middle) SORT_SCRATCH[out++] = index[a++];
                while (b < high) SORT_SCRATCH[out++] = index[b++];
                System.arraycopy(SORT_SCRATCH, low, index, low, high - low);
            }
        }
    }

    private static boolean valid(double x, double y, double z, double luminance) {
        return luminance >= 1 && Double.isFinite(x + y + z);
    }

    private static double radius(double radius) {
        return Math.clamp(Double.isFinite(radius) ? radius : 0, 0, MAX_RADIUS);
    }

    private EffectLights() {
    }
}
