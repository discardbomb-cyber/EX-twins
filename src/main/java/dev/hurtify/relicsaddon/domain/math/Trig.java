package dev.hurtify.relicsaddon.domain.math;

/**
 * Minecraft's {@code Mth} functions the domain needs, reproduced exactly: the same 65536-entry sine
 * table, the same index arithmetic and the same truncating ceil and floor. MathKernelCheck compares
 * every result with {@code Mth} bit for bit.
 */
public final class Trig {
    private static final float[] SIN = table();

    public static float sin(float value) {
        return SIN[(int) (value * 10430.378F) & 65535];
    }

    public static float cos(float value) {
        return SIN[(int) (value * 10430.378F + 16384.0F) & 65535];
    }

    public static int ceil(float value) {
        int truncated = (int) value;
        return value > (float) truncated ? truncated + 1 : truncated;
    }

    public static int ceil(double value) {
        int truncated = (int) value;
        return value > (double) truncated ? truncated + 1 : truncated;
    }

    public static int floor(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static float[] table() {
        float[] table = new float[65536];
        for (int i = 0; i < table.length; i++) table[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
        return table;
    }

    private Trig() { }
}
