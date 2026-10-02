package dev.hurtify.relicsaddon.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Exact sequence comparison against the previous stable List<long[]> implementation. */
public final class ArmageddonColumnsCheck {
    public static void main(String[] args) {
        int checked = 0;
        double[][] centres = {{0, 0}, {.5, .5}, {-1, -1}, {-1.5, -2.5},
                {-.01, -.99}, {12.375, -63.8125}, {-4096.5, 8192.5}};
        for (double[] centre : centres) {
            for (double radius : new double[]{0, .25, .5, 1, Math.sqrt(.5), 2.5, 6, 16, 32, 64}) {
                for (double inner : new double[]{-1, 0, radius / 2, radius, radius + .25}) {
                    compare(centre[0], centre[1], radius, inner);
                    checked++;
                }
            }
        }
        Random random = new Random(0xA11A6EDDL);
        for (int i = 0; i < 200; i++) {
            double x = random.nextDouble() * 512 - 256, z = random.nextDouble() * 512 - 256;
            double radius = random.nextDouble() * 48;
            compare(x, z, radius, i % 2 == 0 ? -1 : radius * random.nextDouble());
            checked++;
        }
        // Four equal distances must retain x-major/z-major order, including signed coordinates.
        long[] tied = ArmageddonController.Columns.generate(0, 0, 1, -1);
        long[] expected = {pack(-1, -1), pack(-1, 0), pack(0, -1), pack(0, 0)};
        if (!Arrays.equals(tied, expected)) throw new AssertionError("Equal-distance order changed");
        System.out.println("ArmageddonColumnsCheck: " + checked + " exact sequences and tie order passed");
        allocationCheck();
    }

    private static volatile long[] sink;

    private static void allocationCheck() {
        var standard = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(standard instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            System.out.println("Allocation check unavailable: ThreadMXBean does not support allocated bytes");
            return;
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (double inner : new double[]{-1, 80}) {
            for (int warm = 0; warm < 8; warm++) {
                sink = reference(-.375, -128.625, 90, inner);
                sink = ArmageddonController.Columns.generate(-.375, -128.625, 90, inner);
            }
            long start = bean.getThreadAllocatedBytes(thread);
            for (int run = 0; run < 8; run++) sink = reference(-.375, -128.625, 90, inner);
            long oldBytes = bean.getThreadAllocatedBytes(thread) - start;
            start = bean.getThreadAllocatedBytes(thread);
            for (int run = 0; run < 8; run++) sink = ArmageddonController.Columns.generate(-.375, -128.625, 90, inner);
            long newBytes = bean.getThreadAllocatedBytes(thread) - start;
            if (newBytes >= oldBytes) throw new AssertionError("Allocation did not decrease: old=" + oldBytes + ", new=" + newBytes);
            System.out.println("Columns radius90 inner=" + inner + ": allocated old=" + oldBytes / 8 + ", new=" + newBytes / 8 + " bytes/run");
        }
    }

    private static void compare(double x, double z, double radius, double inner) {
        long[] expected = reference(x, z, radius, inner);
        long[] actual = ArmageddonController.Columns.generate(x, z, radius, inner);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Column sequence differs: x=" + x + ", z=" + z
                    + ", radius=" + radius + ", inner=" + inner + ", mismatch=" + Arrays.mismatch(expected, actual));
        }
    }

    private static long[] reference(double centreX, double centreZ, double radius, double inner) {
        int reach = (int) Math.ceil(radius), cx = (int) Math.floor(centreX), cz = (int) Math.floor(centreZ);
        List<long[]> columns = new ArrayList<>();
        for (int x = cx - reach; x <= cx + reach; x++) for (int z = cz - reach; z <= cz + reach; z++) {
            double dx = x + .5 - centreX, dz = z + .5 - centreZ, d = dx * dx + dz * dz;
            if (d <= radius * radius && (inner < 0 || d >= inner * inner)) {
                columns.add(new long[]{pack(x, z), Double.doubleToLongBits(d)});
            }
        }
        columns.sort(Comparator.comparingDouble(column -> Double.longBitsToDouble(column[1])));
        long[] result = new long[columns.size()];
        for (int i = 0; i < result.length; i++) result[i] = columns.get(i)[0];
        return result;
    }

    private static long pack(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }
}
