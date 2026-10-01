package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.client.EffectLights.Light;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

public final class EffectLightsCheck {
    public static void main(String[] args) {
        Vec3 camera = new Vec3(0, 64, 0);
        require(EffectLights.merge(List.of(), camera, EffectLights.MAX_LIGHTS).isEmpty(), "No effects, no light sources");

        List<Light> pair = List.copyOf(EffectLights.merge(List.of(new Light(0, 64, 0, 8, .5), new Light(1, 64, 0, 12, .5)), camera, EffectLights.MAX_LIGHTS));
        require(pair.size() == 1, "Lights a block apart become one source");
        Light merged = pair.getFirst();
        require(merged.luminance() == 12, "A merged light keeps its brightest member's level");
        require(merged.x() > .5 && merged.x() < 1, "A merged light leans towards its brighter member");
        require(merged.radius() + 1e-9 >= Math.max(merged.x(), 1 - merged.x()) + .5, "A merged light still covers both members");

        List<Light> apart = List.copyOf(EffectLights.merge(List.of(new Light(0, 64, 0, 10, .5), new Light(12, 64, 0, 10, .5)), camera, EffectLights.MAX_LIGHTS));
        require(apart.size() == 2, "Separate effects stay separate while under the cap");

        // A deployed swarm: 16 groups, their charges and a string of blasts, far more reports than sources.
        List<Light> swarm = new ArrayList<>();
        for (int report = 0; report < 400; report++) {
            double angle = report * 2.399963;
            swarm.add(new Light(Math.cos(angle) * (3 + report % 40), 64 + report % 7, Math.sin(angle) * (3 + report % 40), 4 + report % 12, .2 + report % 5 * .2));
        }
        List<Light> capped = List.copyOf(EffectLights.merge(swarm, camera, EffectLights.MAX_LIGHTS));
        require(!capped.isEmpty() && capped.size() <= EffectLights.MAX_LIGHTS, "Hundreds of reports merge down to the source cap");
        double brightest = swarm.stream().mapToDouble(Light::luminance).max().orElse(0);
        require(capped.stream().anyMatch(light -> light.luminance() == brightest), "Merging never dims the brightest effect");
        for (Light light : capped) {
            require(light.luminance() <= 15 && light.radius() <= EffectLights.MAX_RADIUS, "Merged sources stay within LambDynamicLights' range");
        }

        List<Light> tight = List.copyOf(EffectLights.merge(List.of(new Light(0, 64, 0, 9, .5), new Light(40, 64, 0, 9, .5), new Light(80, 64, 0, 9, .5)), camera, 2));
        require(tight.size() == 2 && tight.stream().noneMatch(light -> light.x() > 60), "Over the cap, the farthest source goes first");

        require(EffectLights.fade(0, 8) == 1 && EffectLights.fade(4, 8) == .5 && EffectLights.fade(8, 8) == 0,
                "Flashes fade out linearly over their ticks");
        require(EffectLights.fade(-2, 8) == 0, "A flash waits dark until its event happens");

        mergeMatchesReference(camera);
        ticksMatchReference();
        allocations();

        System.out.println("Effect lights: nearby lights merge, 400 swarm reports fit " + capped.size() + "/" + EffectLights.MAX_LIGHTS
                + " sources, flashes fade, the scratch-buffer merge matches the list merge bit for bit");
    }

    // --- equivalence with the reference merge -------------------------------------------------------------

    /** Every merge result must equal the reference's, light for light, in order, with the same bits. */
    private static void mergeMatchesReference(Vec3 camera) {
        var random = new Random(0x11CE5);
        same(List.of(), camera, "0 reports");
        same(List.of(new Light(3, 65, -2, 11, 1.5)), camera, "1 report");
        same(ring(24, 10, random), camera, "24 reports, each its own source");
        same(scatter(EffectLights.MAX_REPORTS + EffectLights.MAX_FLASHES, 60, random), camera, "256 + 64 scattered reports");
        same(scatter(EffectLights.MAX_REPORTS + EffectLights.MAX_FLASHES, 1.25, random), camera, "320 reports within 2.5 blocks of each other");
        same(scatter(EffectLights.MAX_REPORTS + EffectLights.MAX_FLASHES, 120, random), camera, "320 sparse reports, some beyond the cap's reach");
        List<Light> ties = new ArrayList<>();
        for (int k = 0; k < 100; k++) ties.add(new Light(k * 3.1, 64, k % 3 * 7, 5 + k % 4, 1));
        same(ties, camera, "Ties in brightness keep their report order");
        List<Light> columns = new ArrayList<>();
        for (int k = 0; k < 64; k++) columns.add(new Light(k % 8 * 20, 64 + k / 8 * 20, 0, 15 - k % 8, k % 3));
        same(columns, camera, "Equal distances from the camera keep their order over the cap");
        for (int frame = 0; frame < 1200; frame++) {
            int count = random.nextInt(EffectLights.MAX_REPORTS + EffectLights.MAX_FLASHES + 1);
            double spread = switch (random.nextInt(4)) {
                case 0 -> 1.2;
                case 1 -> 6;
                case 2 -> 30;
                default -> 110;
            };
            Vec3 eye = new Vec3(random.nextGaussian() * 20, 64 + random.nextGaussian() * 5, random.nextGaussian() * 20);
            same(scatter(count, spread, random), eye, "random frame " + frame);
        }
    }

    private static void same(List<Light> reports, Vec3 camera, String scenario) {
        for (int cap : new int[]{EffectLights.MAX_LIGHTS, 1, 7}) {
            List<Light> expected = EffectLightsReference.merge(reports, camera, cap);
            List<Light> actual = List.copyOf(EffectLights.merge(reports, camera, cap));
            require(expected.equals(actual), scenario + " (cap " + cap + "): expected " + expected + " but got " + actual);
        }
    }

    private static List<Light> ring(int count, double radius, Random random) {
        List<Light> lights = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            double angle = k * Math.PI * 2 / count;
            lights.add(new Light(Math.cos(angle) * radius, 64 + random.nextInt(3), Math.sin(angle) * radius, 1 + random.nextInt(15), random.nextDouble() * 4));
        }
        return lights;
    }

    private static List<Light> scatter(int count, double spread, Random random) {
        List<Light> lights = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            lights.add(new Light(random.nextGaussian() * spread, 64 + random.nextGaussian() * spread * .3, random.nextGaussian() * spread,
                    Math.min(15, 1 + random.nextDouble() * 14), random.nextDouble() * 4));
        }
        return lights;
    }

    // --- equivalence of the frame/flash bookkeeping over ticks ------------------------------------------

    /** The whole pipeline (glow reports, flashes, frame hand-over, the tick's merge) against the reference, tick for tick. */
    private static void ticksMatchReference() {
        var random = new Random(0x5EED);
        var reference = new EffectLightsReference();
        EffectLights.reset();
        int sources = 40;
        double[] x = new double[sources], y = new double[sources], z = new double[sources], vx = new double[sources], vz = new double[sources];
        for (int s = 0; s < sources; s++) {
            x[s] = random.nextGaussian() * 15;
            y[s] = 64 + random.nextInt(6);
            z[s] = random.nextGaussian() * 15;
            vx[s] = random.nextGaussian() * .08;
            vz[s] = random.nextGaussian() * .08;
        }
        long now = 1000;
        int ticks = 0, lit = 0;
        for (int frame = 0; frame < 3000; frame++) {
            Vec3 camera = new Vec3(Math.sin(frame * .01) * 10, 64, Math.cos(frame * .013) * 10);
            boolean quiet = frame % 700 > 650;
            int reports = quiet ? 0 : random.nextInt(sources + 1) + (frame % 400 < 20 ? 300 : 0);
            for (int k = 0; k < reports; k++) {
                int s = k % sources;
                double luminance = 2 + random.nextDouble() * 14, radius = random.nextDouble() * 5 - .5;
                if (random.nextInt(300) == 0) luminance = .5;
                if (random.nextInt(500) == 0) radius = Double.NaN;
                double px = x[s] + k / sources * 1.5, py = y[s], pz = z[s];
                if (random.nextInt(400) == 0) px = Double.POSITIVE_INFINITY;
                reference.glow(px, py, pz, luminance, radius);
                EffectLights.report(px, py, pz, luminance, radius);
            }
            if (!quiet && random.nextInt(3) == 0) {
                int bursts = random.nextInt(10) == 0 ? 70 : 1;
                for (int b = 0; b < bursts; b++) {
                    int s = random.nextInt(sources);
                    double luminance = 1 + random.nextDouble() * 15, radius = random.nextDouble() * 6;
                    int life = random.nextInt(12) - 1;
                    reference.flash(x[s], y[s], z[s], luminance, radius, now, life);
                    EffectLights.flash(x[s], y[s], z[s], luminance, radius, now, life);
                }
            }
            long stamp = frame % 900 > 880 ? Long.MIN_VALUE : now;
            reference.onFrame(stamp);
            EffectLights.endFrame(stamp);
            if (frame % 3 == 0 || random.nextInt(4) == 0) {
                if (random.nextInt(200) == 0) now += 7;
                List<Light> expected = reference.lights(now, camera);
                List<Light> actual = List.copyOf(EffectLights.lights(now, camera));
                require(expected.equals(actual), "tick " + ticks + " (frame " + frame + "): expected " + expected + " but got " + actual);
                lit += actual.size();
                ticks++;
                now++;
            }
            if (frame % 1000 == 999) {
                reference.clear();
                EffectLights.reset();
            }
            for (int s = 0; s < sources; s++) {
                x[s] += vx[s];
                z[s] += vz[s];
            }
        }
        require(ticks >= 1000 && lit > ticks, "The tick scenario must cover at least 1000 ticks with lights in them");
    }

    // --- allocations -----------------------------------------------------------------------------------

    /** One full tick (256 glow reports, 64 live flashes, the merge) must allocate at least half less than it did. */
    private static void allocations() {
        var random = new Random(0xA110C);
        List<Light> reports = scatter(EffectLights.MAX_REPORTS, 25, random);
        List<Light> bursts = scatter(EffectLights.MAX_FLASHES, 25, random);
        Vec3 camera = new Vec3(0, 64, 0);
        var reference = new EffectLightsReference();
        EffectLights.reset();
        long[] now = {5000};
        Runnable before = () -> {
            for (Light light : reports) reference.glow(light.x(), light.y(), light.z(), light.luminance(), light.radius());
            for (Light light : bursts) reference.flash(light.x(), light.y(), light.z(), light.luminance(), light.radius(), now[0], 40);
            reference.onFrame(now[0]);
            require(reference.lights(now[0], camera).size() == EffectLights.MAX_LIGHTS, "The reference tick fills the cap");
        };
        Runnable after = () -> {
            for (Light light : reports) EffectLights.report(light.x(), light.y(), light.z(), light.luminance(), light.radius());
            for (Light light : bursts) EffectLights.flash(light.x(), light.y(), light.z(), light.luminance(), light.radius(), now[0], 40);
            EffectLights.endFrame(now[0]);
            require(EffectLights.lights(now[0], camera).size() == EffectLights.MAX_LIGHTS, "The tick fills the cap");
        };
        long was = measure(before, now), is = measure(after, now);
        System.out.printf(Locale.ROOT, "A tick of 256 glows and 64 flashes: allocated bytes %,d -> %,d%n", was, is);
        require(is * 2 <= was, "A tick's allocations must at least halve");
    }

    /** Runs {@code tick} warmed up and returns the least bytes one run allocated. */
    private static long measure(Runnable tick, long[] now) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        for (int k = 0; k < 200; k++) {
            tick.run();
            now[0]++;
        }
        long bytes = Long.MAX_VALUE;
        for (int k = 0; k < 10; k++) {
            long start = threads.getThreadAllocatedBytes(id);
            tick.run();
            bytes = Math.min(bytes, threads.getThreadAllocatedBytes(id) - start);
            now[0]++;
        }
        return bytes;
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
