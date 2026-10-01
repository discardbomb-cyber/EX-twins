package dev.hurtify.relicsaddon.domain.math;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Differential check of the domain math kernel against the game's own classes in the same JVM: Trig
 * against Mth, Vec3d against Vec3 and Box against AABB, bit for bit. Doubles and floats are compared
 * through doubleToLongBits and floatToIntBits, so +0 and -0 differ while every NaN counts as the one
 * canonical NaN (Java leaves NaN payloads unspecified).
 *
 * <p>Inputs: every sine table index for sin and cos, 10^6 random floats and 10^6 random doubles from
 * {@code new Random(0x5EED)} mixed with edge values (±0, NaN, ±Infinity, 1e±30, the int limits), float
 * grids of pitch and yaw, every pair of 729 edge vectors, and 10^6 random vector pairs (tiny, huge and
 * NaN components included).
 */
public final class MathKernelCheck {
    private static final int RANDOM_SCALARS = 1_000_000, RANDOM_ROTATIONS = 1_000_000, RANDOM_VECTORS = 1_000_000;
    private static final int TABLE_SIZE = 65536;
    private static final float[] EDGE_FLOATS = {0F, -0F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.intBitsToFloat(0x7F800001), Float.intBitsToFloat(0xFFC00000), 1e30F, -1e30F, 1e-30F, -1e-30F,
            Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE, -Float.MAX_VALUE, 1F, -1F, .5F, -.5F, 1e-7F, -1e-7F,
            35F, 89.99F, 90F, -90F, 180F, -180F, 271.5F, 360F, -360F, 720F, 1e6F, (float) Math.PI, -(float) Math.PI,
            (float) (Math.PI * 2), 2.14748365E9F, -2.14748365E9F, 2.1474835E9F, -2.1474835E9F, 16777216F, -16777216F,
            8388607.5F, -8388607.5F, 6.2831855F, 1.5707964F, -1.5707964F, 3.4028235E38F / 10430.378F};
    private static final double[] EDGE_DOUBLES = {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.longBitsToDouble(0x7FF0000000000001L), Double.longBitsToDouble(0xFFF8000000000000L),
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
            1e-300, -1e-300, 1e300, -1e300, 1e-30, -1e-30, 1e30, -1e30, 1e-4, Math.nextDown(1e-4), Math.nextUp(1e-4),
            5.773502691896258e-5, -5.773502691896258e-5, 1e-8, 1.0, -1.0, .5, -.5, .1, 2147483647.0, 2147483648.0, -2147483648.0,
            -2147483649.0, 2147483646.5, -2147483647.5, 1e10, -1e10, 4503599627370496.5, 9007199254740993.0, 64.0, -64.0, .92};
    /** Components of the edge vectors: every combination of three of them is one vector. */
    private static final double[] EDGE_COMPONENTS = {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1e-300, 1e300, 5e-5, 1.0};

    private final Random random = new Random(0x5EED);
    /** Per operation: comparisons and failures, in first-use order. */
    private final Map<String, long[]> tallies = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private Supplier<String> inputs = () -> "";

    public static void main(String[] args) {
        MathKernelCheck check = new MathKernelCheck();
        check.table();
        check.scalars();
        check.rotations();
        check.vectors();
        long compared = check.tallies.values().stream().mapToLong(tally -> tally[0]).sum();
        long failed = check.tallies.values().stream().mapToLong(tally -> tally[1]).sum();
        check.tallies.forEach((operation, tally) -> System.out.println("  " + operation + ": " + tally[0] + " comparisons"
                + (tally[1] > 0 ? ", " + tally[1] + " DIFFERENT" : "")));
        check.failures.forEach(System.out::println);
        if (failed > 0) throw new AssertionError(failed + " of " + compared + " math kernel results differ from Mth, Vec3 or AABB");
        System.out.println("Math kernel: " + compared + " results of " + check.tallies.size()
                + " operations bit-exact against Mth, Vec3 and AABB");
    }

    /** Every table index, through sin and cos, at the float that lands on it and its two neighbours. */
    private void table() {
        boolean[] sinIndexes = new boolean[TABLE_SIZE], cosIndexes = new boolean[TABLE_SIZE];
        for (int index = 0; index < TABLE_SIZE; index++) {
            float sinInput = landing(index, 0F), cosInput = landing(index, 16384F);
            sinIndexes[sinIndex(sinInput)] = true;
            cosIndexes[cosIndex(cosInput)] = true;
            for (float value : new float[] {sinInput, Math.nextDown(sinInput), Math.nextUp(sinInput)}) {
                inputs = () -> "sin(" + value + ")";
                same("Trig.sin", Mth.sin(value), Trig.sin(value));
            }
            for (float value : new float[] {cosInput, Math.nextDown(cosInput), Math.nextUp(cosInput)}) {
                inputs = () -> "cos(" + value + ")";
                same("Trig.cos", Mth.cos(value), Trig.cos(value));
            }
        }
        for (int index = 0; index < TABLE_SIZE; index++) {
            if (!sinIndexes[index] || !cosIndexes[index]) throw new AssertionError("Table index " + index + " was never reached");
        }
    }

    /** A float whose table index, with the given offset, is exactly {@code index}. */
    private static float landing(int index, float offset) {
        int target = offset == 0F ? index : index - (int) offset;
        float value = target / 10430.378F;
        for (int step = 0; step < 4096; step++) {
            int reached = (int) (value * 10430.378F + offset);
            if (reached == target + (int) offset) return value;
            value = reached < target + (int) offset ? Math.nextUp(value) : Math.nextDown(value);
        }
        throw new AssertionError("No float lands on table index " + index);
    }

    private static int sinIndex(float value) {
        return (int) (value * 10430.378F) & 65535;
    }

    private static int cosIndex(float value) {
        return (int) (value * 10430.378F + 16384.0F) & 65535;
    }

    /** sin, cos, ceil and floor over edge and random floats and doubles, and lerp over random triples. */
    private void scalars() {
        for (float value : EDGE_FLOATS) floatFunctions(value);
        for (int count = 0; count < RANDOM_SCALARS; count++) floatFunctions(nextFloat());
        for (double value : EDGE_DOUBLES) doubleFunctions(value);
        for (int count = 0; count < RANDOM_SCALARS; count++) doubleFunctions(nextDouble());
        for (double delta : EDGE_DOUBLES) for (double start : EDGE_DOUBLES) for (double end : new double[] {0, 1, -3.5, Double.NaN, 1e300}) {
            lerp(delta, start, end);
        }
        for (int count = 0; count < RANDOM_SCALARS; count++) {
            lerp(random.nextBoolean() ? random.nextDouble() : nextDouble(), nextDouble(), nextDouble());
        }
    }

    private void floatFunctions(float value) {
        inputs = () -> Float.toString(value) + " (bits " + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
        same("Trig.sin", Mth.sin(value), Trig.sin(value));
        same("Trig.cos", Mth.cos(value), Trig.cos(value));
        same("Trig.ceil(float)", Mth.ceil(value), Trig.ceil(value));
        same("Trig.ceil(double)", Mth.ceil((double) value), Trig.ceil((double) value));
        same("Trig.floor(double)", Mth.floor((double) value), Trig.floor((double) value));
    }

    private void doubleFunctions(double value) {
        inputs = () -> Double.toString(value) + " (bits " + Long.toHexString(Double.doubleToRawLongBits(value)) + ")";
        same("Trig.ceil(double)", Mth.ceil(value), Trig.ceil(value));
        same("Trig.floor(double)", Mth.floor(value), Trig.floor(value));
    }

    private void lerp(double delta, double start, double end) {
        inputs = () -> "lerp(" + delta + ", " + start + ", " + end + ")";
        same("Trig.lerp", Mth.lerp(delta, start, end), Trig.lerp(delta, start, end));
    }

    /** directionFromRotation over pitch and yaw grids, edge pairs and random pairs. */
    private void rotations() {
        for (int pitch = -360; pitch <= 360; pitch++) for (int yaw = -1440; yaw <= 1440; yaw++) rotation(pitch * .25F, yaw * .25F);
        for (int pitch = -270; pitch <= 270; pitch++) for (int yaw = -270; yaw <= 270; yaw++) rotation(pitch * 37.1F, yaw * 37.1F);
        for (float pitch : EDGE_FLOATS) for (float yaw : EDGE_FLOATS) rotation(pitch, yaw);
        for (int count = 0; count < RANDOM_ROTATIONS; count++) rotation(nextFloat(), nextFloat());
    }

    private void rotation(float pitch, float yaw) {
        inputs = () -> "directionFromRotation(" + pitch + ", " + yaw + ")";
        same("Vec3d.directionFromRotation", Vec3.directionFromRotation(pitch, yaw), Vec3d.directionFromRotation(pitch, yaw));
    }

    /** Every vector and box operation over all pairs of edge vectors, then over random pairs. */
    private void vectors() {
        List<double[]> edges = new ArrayList<>();
        for (double x : EDGE_COMPONENTS) for (double y : EDGE_COMPONENTS) for (double z : EDGE_COMPONENTS) edges.add(new double[] {x, y, z});
        for (int first = 0; first < edges.size(); first++) {
            for (int second = 0; second < edges.size(); second++) {
                double[] a = edges.get(first), b = edges.get(second);
                double scalar = EDGE_COMPONENTS[(first + second) % EDGE_COMPONENTS.length];
                double[] factors = edges.get((first * 31 + second) % edges.size());
                pair(a, b, scalar, factors, edges.get((first + 7 * second) % edges.size()));
            }
        }
        for (int count = 0; count < RANDOM_VECTORS; count++) {
            double[] a = nextVector(), b = nextVector(), factors = nextVector(), direction = nextVector();
            double scalar = random.nextBoolean() ? random.nextDouble() * 2 - .5 : nextDouble();
            pair(a, b, scalar, factors, direction);
        }
        same("Vec3d.ZERO", Vec3.ZERO, Vec3d.ZERO);
    }

    private void pair(double[] a, double[] b, double scalar, double[] factors, double[] direction) {
        inputs = () -> "a=" + text(a) + " b=" + text(b) + " scalar=" + scalar + " factors=" + text(factors) + " direction=" + text(direction);
        Vec3 va = new Vec3(a[0], a[1], a[2]), vb = new Vec3(b[0], b[1], b[2]);
        Vec3d da = new Vec3d(a[0], a[1], a[2]), db = new Vec3d(b[0], b[1], b[2]);
        same("Vec3d.new", va, da);
        same("Vec3d.x()", va.x(), da.x());
        same("Vec3d.y()", va.y(), da.y());
        same("Vec3d.z()", va.z(), da.z());
        same("Vec3d.add(Vec3d)", va.add(vb), da.add(db));
        same("Vec3d.add(x,y,z)", va.add(factors[0], factors[1], factors[2]), da.add(factors[0], factors[1], factors[2]));
        same("Vec3d.subtract(Vec3d)", va.subtract(vb), da.subtract(db));
        same("Vec3d.subtract(x,y,z)", va.subtract(factors[0], factors[1], factors[2]), da.subtract(factors[0], factors[1], factors[2]));
        same("Vec3d.multiply", va.multiply(factors[0], factors[1], factors[2]), da.multiply(factors[0], factors[1], factors[2]));
        same("Vec3d.scale", va.scale(scalar), da.scale(scalar));
        same("Vec3d.reverse", va.reverse(), da.reverse());
        Vec3 normal = va.normalize();
        Vec3d domainNormal = da.normalize();
        same("Vec3d.normalize", normal, domainNormal);
        same("Vec3d.normalize is ZERO", normal == Vec3.ZERO, domainNormal == Vec3d.ZERO);
        same("Vec3d.dot", va.dot(vb), da.dot(db));
        same("Vec3d.cross", va.cross(vb), da.cross(db));
        same("Vec3d.length", va.length(), da.length());
        same("Vec3d.lengthSqr", va.lengthSqr(), da.lengthSqr());
        same("Vec3d.horizontalDistance", va.horizontalDistance(), da.horizontalDistance());
        same("Vec3d.horizontalDistanceSqr", va.horizontalDistanceSqr(), da.horizontalDistanceSqr());
        same("Vec3d.distanceTo", va.distanceTo(vb), da.distanceTo(db));
        same("Vec3d.distanceToSqr(Vec3d)", va.distanceToSqr(vb), da.distanceToSqr(db));
        same("Vec3d.distanceToSqr(x,y,z)", va.distanceToSqr(b[0], b[1], b[2]), da.distanceToSqr(b[0], b[1], b[2]));
        same("Vec3d.lerp", va.lerp(vb, scalar), da.lerp(db, scalar));
        equality(a, b, va, vb, da, db);
        same("Vec3d.hashCode", va.hashCode(), da.hashCode());
        same("Vec3d.toString", va.toString(), da.toString());

        AABB box = new AABB(a[0], a[1], a[2], b[0], b[1], b[2]);
        Box domainBox = new Box(a[0], a[1], a[2], b[0], b[1], b[2]);
        same("Box.new", box, domainBox);
        same("Box.new(Vec3d,Vec3d)", new AABB(va, vb), new Box(da, db));
        same("Box.inflate", box.inflate(scalar), domainBox.inflate(scalar));
        same("Box.inflate(x,y,z)", box.inflate(factors[0], factors[1], factors[2]), domainBox.inflate(factors[0], factors[1], factors[2]));
        Vec3 towards = new Vec3(direction[0], direction[1], direction[2]);
        same("Box.expandTowards", box.expandTowards(towards), domainBox.expandTowards(new Vec3d(direction[0], direction[1], direction[2])));
        same("Box.center", box.getCenter(), domainBox.center());
        double[][] points = {a, b, direction, factors, {box.minX, box.minY, box.minZ}, {box.maxX, box.maxY, box.maxZ},
                {Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)},
                {Math.nextDown(box.minX), box.minY, box.minZ}, {box.minX, box.maxY, Math.nextDown(box.maxZ)},
                {Mth.lerp(scalar, box.minX, box.maxX), Mth.lerp(scalar, box.minY, box.maxY), Mth.lerp(scalar, box.minZ, box.maxZ)},
                {box.getCenter().x, box.getCenter().y, box.getCenter().z}};
        for (double[] point : points) {
            same("Box.contains(Vec3d)", box.contains(new Vec3(point[0], point[1], point[2])), domainBox.contains(new Vec3d(point[0], point[1], point[2])));
            same("Box.contains(x,y,z)", box.contains(point[0], point[1], point[2]), domainBox.contains(point[0], point[1], point[2]));
        }
    }

    /** equals against itself, a copy, the other vector, copies with one flipped zero or another NaN, null and a foreign object. */
    private void equality(double[] a, double[] b, Vec3 va, Vec3 vb, Vec3d da, Vec3d db) {
        same("Vec3d.equals", va.equals(va), da.equals(da));
        same("Vec3d.equals", va.equals(new Vec3(a[0], a[1], a[2])), da.equals(new Vec3d(a[0], a[1], a[2])));
        same("Vec3d.equals", va.equals(vb), da.equals(db));
        same("Vec3d.equals", vb.equals(va), db.equals(da));
        for (int axis = 0; axis < 3; axis++) {
            double[] changed = a.clone();
            changed[axis] = a[axis] == 0 ? -a[axis] : Double.isNaN(a[axis]) ? Double.longBitsToDouble(0x7FF0000000000ABCL) : Math.nextUp(a[axis]);
            same("Vec3d.equals", va.equals(new Vec3(changed[0], changed[1], changed[2])), da.equals(new Vec3d(changed[0], changed[1], changed[2])));
        }
        same("Vec3d.equals", va.equals(null), da.equals(null));
        same("Vec3d.equals", va.equals(text(a)), da.equals(text(a)));
        same("Vec3d.equals", false, da.equals(va));
    }

    private float nextFloat() {
        return switch (random.nextInt(10)) {
            case 0 -> EDGE_FLOATS[random.nextInt(EDGE_FLOATS.length)];
            case 1 -> Float.intBitsToFloat(random.nextInt());
            case 2 -> (random.nextFloat() - .5F) * 1440F;
            case 3 -> (random.nextFloat() - .5F) * 32F;
            case 4 -> random.nextInt(4001) - 2000;
            case 5 -> {
                float whole = random.nextInt(4001) - 2000;
                yield random.nextBoolean() ? Math.nextUp(whole) : Math.nextDown(whole);
            }
            case 6 -> (float) (random.nextGaussian() * Math.pow(10, random.nextInt(61) - 30));
            case 7 -> (float) (random.nextGaussian() * 3e9);
            default -> (random.nextFloat() - .5F) * 200F;
        };
    }

    private double nextDouble() {
        return switch (random.nextInt(11)) {
            case 0 -> EDGE_DOUBLES[random.nextInt(EDGE_DOUBLES.length)];
            case 1 -> Double.longBitsToDouble(random.nextLong());
            case 2 -> random.nextGaussian();
            case 3 -> random.nextGaussian() * 64;
            case 4 -> random.nextGaussian() * 1e-4;
            case 5 -> random.nextGaussian() * 1e6;
            case 6 -> random.nextGaussian() * Math.pow(10, random.nextInt(601) - 300);
            case 7 -> random.nextInt(2001) - 1000;
            case 8 -> {
                double whole = random.nextInt(2001) - 1000;
                yield random.nextBoolean() ? Math.nextUp(whole) : Math.nextDown(whole);
            }
            case 9 -> random.nextDouble() * 3e10 - 1.5e10;
            default -> random.nextFloat() - .5;
        };
    }

    private double[] nextVector() {
        return switch (random.nextInt(6)) {
            case 0 -> new double[] {nextDouble(), nextDouble(), nextDouble()};
            case 1 -> new double[] {random.nextGaussian() * 1e-4, random.nextGaussian() * 1e-4, random.nextGaussian() * 1e-4};
            case 2 -> new double[] {EDGE_COMPONENTS[random.nextInt(EDGE_COMPONENTS.length)], nextDouble(), random.nextGaussian()};
            default -> new double[] {random.nextGaussian() * 64, random.nextGaussian() * 64, random.nextGaussian() * 64};
        };
    }

    private static String text(double[] vector) {
        return "(" + vector[0] + ", " + vector[1] + ", " + vector[2] + ")";
    }

    private void same(String operation, float expected, float actual) {
        if (tally(operation, Float.floatToIntBits(expected) == Float.floatToIntBits(actual))) return;
        fail(operation, Float.toString(expected), Float.toString(actual));
    }

    private void same(String operation, double expected, double actual) {
        if (tally(operation, Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual))) return;
        fail(operation, Double.toString(expected), Double.toString(actual));
    }

    private void same(String operation, int expected, int actual) {
        if (tally(operation, expected == actual)) return;
        fail(operation, Integer.toString(expected), Integer.toString(actual));
    }

    private void same(String operation, boolean expected, boolean actual) {
        if (tally(operation, expected == actual)) return;
        fail(operation, Boolean.toString(expected), Boolean.toString(actual));
    }

    private void same(String operation, String expected, String actual) {
        if (tally(operation, expected.equals(actual))) return;
        fail(operation, expected, actual);
    }

    private void same(String operation, Vec3 expected, Vec3d actual) {
        boolean equal = bits(expected.x) == bits(actual.x) && bits(expected.y) == bits(actual.y) && bits(expected.z) == bits(actual.z);
        if (tally(operation, equal)) return;
        fail(operation, expected.toString(), actual.toString());
    }

    private void same(String operation, AABB expected, Box actual) {
        boolean equal = bits(expected.minX) == bits(actual.minX) && bits(expected.minY) == bits(actual.minY)
                && bits(expected.minZ) == bits(actual.minZ) && bits(expected.maxX) == bits(actual.maxX)
                && bits(expected.maxY) == bits(actual.maxY) && bits(expected.maxZ) == bits(actual.maxZ);
        if (tally(operation, equal)) return;
        fail(operation, expected.toString(), "Box[" + actual.minX + ", " + actual.minY + ", " + actual.minZ + "] -> ["
                + actual.maxX + ", " + actual.maxY + ", " + actual.maxZ + "]");
    }

    private static long bits(double value) {
        return Double.doubleToLongBits(value);
    }

    /** Counts one comparison; true when it matched. */
    private boolean tally(String operation, boolean equal) {
        long[] tally = tallies.computeIfAbsent(operation, name -> new long[2]);
        tally[0]++;
        if (!equal) tally[1]++;
        return equal;
    }

    private void fail(String operation, String expected, String actual) {
        if (failures.size() < 50) failures.add(operation + ": the game gives " + expected + ", the kernel " + actual + " for " + inputs.get());
    }

    private MathKernelCheck() { }
}
