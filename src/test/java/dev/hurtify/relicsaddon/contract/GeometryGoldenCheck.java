package dev.hurtify.relicsaddon.contract;

import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveFormation;
import dev.hurtify.relicsaddon.domain.hive.HiveSettings;
import dev.hurtify.relicsaddon.domain.hive.HiveShapes;
import dev.hurtify.relicsaddon.domain.hive.HiveSlots;
import dev.hurtify.relicsaddon.domain.hive.HiveStackState;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.domain.math.Vec3d;
import dev.hurtify.relicsaddon.domain.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.domain.shield.ShieldCellMove;
import dev.hurtify.relicsaddon.domain.shield.ShieldField;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Bit-exact golden master of the shared geometry ({@code golden/geometry.txt}): one SHA-256 per
 * function over the raw bits of its outputs ({@code Double.doubleToLongBits}, ints, longs, booleans as
 * 0/1) across a fixed grid of inputs. The formation and shield maths must reproduce these bits through
 * the move to domain types. An exception is part of the output (its class name).
 *
 * <p>Members that stage S13 deletes as dead code are left out (HiveSlots.lane, the three-argument
 * ShieldField.incoming, the four-argument ShieldCellDefense.damage, ShieldTopology.neighborsOf); the
 * overloads they delegate to are covered with the same arguments. {@code --record} rewrites the golden.
 */
public final class GeometryGoldenCheck {
    private static final Vec3d OWNER = new Vec3d(3, 64, -2), TARGET = new Vec3d(-4, 63, 7);
    private static final float[] YAWS = {0, -0.0F, 35, 89.99F, 90, 180, -180, 271.5F, 1e6F, -1e-7F};
    private static final double[] TIMES = {0, .5, 187.25, 5000.5, 3000000.75};
    private static final int[] SLOTS = {1, 2, 12, 16, 17, 100, 250};
    private static final int[] UNITS = {1, 12, 60, 250, 750};
    private static final int[] INTERVALS = {20, 60, 100};
    private static final double[] PROGRESS = {-.5, 0, .2, .45, .5, .9, 1, 1.5};
    private static final double[] DISTANCES = {0, .5, 1, 10, 22, 43.9, 44, 44.1, 45, 100, 153, 153.9, 154, 154.1, 200, 1e9, -3,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, OWNER.distanceTo(TARGET)};
    private static final Vec3d[] FORWARDS = {new Vec3d(0, 0, 1), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, -1, 0), new Vec3d(0, 0, 0),
            TARGET.subtract(OWNER), new Vec3d(.3, .95, .1), new Vec3d(-2.5, -.4, -1)};
    private static final ShieldTopology TOPOLOGY = ShieldTopology.INSTANCE;

    private final Map<String, String> digests = new LinkedHashMap<>();
    /** Calls per function, and how many of them threw (their exception is part of the digest). */
    private final Map<String, long[]> calls = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        Path golden = Path.of(args[0]);
        boolean record = args.length > 1 && args[1].equals("--record");
        GeometryGoldenCheck check = new GeometryGoldenCheck();
        check.formation();
        check.shapes();
        check.slots();
        check.field();
        check.topology();
        check.cellDefense();
        List<String> lines = new ArrayList<>();
        check.digests.forEach((name, digest) -> lines.add(name + "\t" + digest));
        long total = check.calls.values().stream().mapToLong(count -> count[0]).sum();
        long thrown = check.calls.values().stream().mapToLong(count -> count[1]).sum();
        if (record) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, lines, StandardCharsets.UTF_8);
            check.calls.forEach((name, count) -> System.out.println("  " + name + ": " + count[0] + " calls, " + count[1] + " threw"));
            System.out.println("Geometry golden recorded: " + lines.size() + " functions, " + total + " calls (" + thrown + " threw)");
            return;
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(golden, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] columns = line.split("\t");
            expected.put(columns[0], columns[1]);
        }
        List<String> problems = new ArrayList<>();
        expected.forEach((name, digest) -> {
            String now = check.digests.get(name);
            if (now == null) problems.add("function no longer checked: " + name);
            else if (!now.equals(digest)) problems.add("function changed: " + name);
        });
        check.digests.keySet().stream().filter(name -> !expected.containsKey(name)).forEach(name -> problems.add("function not in the golden: " + name));
        problems.forEach(System.out::println);
        if (!problems.isEmpty()) throw new AssertionError(problems.size() + " geometry digests differ; the shared geometry must stay bit-exact");
        System.out.println("Geometry golden: " + lines.size() + " functions bit-exact over " + total + " calls (" + thrown + " threw)");
    }

    private void formation() {
        function("HiveFormation.constants", d -> d.d(HiveFormation.IMPACT).d(HiveFormation.FIRE).i(HiveFormation.RETURN_TICKS));
        function("HiveFormation.idle", d -> {
            for (HiveType type : HiveType.values()) for (float yaw : YAWS) for (int count : withZero(UNITS)) {
                for (int index = -1; index <= count; index++) for (double time : TIMES) {
                    final int i = index;
                    d.v(() -> HiveFormation.idle(OWNER, yaw, i, count, type, time));
                }
            }
        });
        function("HiveFormation.belt", d -> {
            for (HiveType type : HiveType.values()) for (float yaw : YAWS) for (Vec3d owner : new Vec3d[] {OWNER, TARGET}) {
                d.v(() -> HiveFormation.belt(owner, yaw, type));
            }
        });
        function("HiveFormation.healing", d -> {
            for (HiveType type : HiveType.values()) for (float yaw : YAWS) for (int count : withZero(UNITS)) {
                for (int index = -1; index <= count; index++) for (double time : TIMES) for (double progress : PROGRESS) {
                    final int i = index;
                    d.v(() -> HiveFormation.healing(OWNER, yaw, i, count, type, time, progress));
                }
            }
        });
        function("HiveFormation.travelTicks", d -> {
            for (double distance : DISTANCES) d.i(() -> HiveFormation.travelTicks(distance));
        });
        function("HiveFormation.groupPhase", d -> {
            for (double time : sweep(TIMES, 0, 400, .75)) for (double cycleStart : new double[] {0, 100, 187.25}) {
                for (int interval : new int[] {0, 1, 20, 60, 100}) for (int groups = 1; groups <= 16; groups++) {
                    for (int group = -1; group <= groups; group++) {
                        final int g = group, n = groups;
                        d.d(() -> HiveFormation.groupPhase(time, cycleStart, interval, g, n));
                    }
                }
            }
        });
        function("HiveFormation.passes", d -> {
            for (long now = -5; now <= 420; now++) for (long cycleStart : new long[] {0, 100}) {
                for (int interval : new int[] {0, 1, 20, 60, 100}) for (int groups : new int[] {1, 2, 3, 7, 16}) {
                    for (int group = 0; group < groups; group++) {
                        for (double mark : new double[] {0, .5, HiveFormation.IMPACT, HiveFormation.FIRE, 1, 1.3}) {
                            final long t = now;
                            final int g = group;
                            d.z(() -> HiveFormation.passes(t, cycleStart, interval, g, groups, mark));
                        }
                    }
                }
            }
        });
        function("HiveFormation.core", d -> {
            for (Vec3d target : new Vec3d[] {OWNER, TARGET}) {
                for (double height : new double[] {1.9, 1.8, .05, 0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
                    d.v(() -> HiveFormation.core(target, height));
                }
            }
        });
        Vec3d[][] pairs = {{OWNER, TARGET}, {TARGET, OWNER}, {OWNER, OWNER.add(0, 5, 0)}, {OWNER, OWNER}};
        function("HiveFormation.muster", d -> {
            for (Vec3d[] pair : pairs) for (int groups : range(1, 16, 25, 40)) for (int group = -1; group <= groups; group++) {
                for (double time : TIMES) {
                    final int g = group;
                    d.v(() -> HiveFormation.muster(pair[0], pair[1], g, groups, time));
                }
            }
        });
        function("HiveFormation.flightTicks", d -> {
            for (double distance : DISTANCES) for (int interval : new int[] {0, 1, 20, 60, 100}) {
                d.d(() -> HiveFormation.flightTicks(distance, interval));
            }
        });
        double[] sortieTimes = sweep(TIMES, 100, 220, .5);
        function("HiveFormation.sortie", d -> {
            for (Vec3d[] pair : new Vec3d[][] {pairs[0], pairs[2]}) for (int groups : new int[] {1, 2, 5, 16}) {
                for (int group = 0; group < groups; group++) for (double time : sortieTimes) for (int interval : INTERVALS) {
                    final int g = group;
                    d.d(() -> HiveFormation.sortie(pair[0], pair[1], 1.9, g, groups, time, 100, interval));
                }
            }
        });
        function("HiveFormation.dropletCentre", d -> {
            for (Vec3d[] pair : new Vec3d[][] {pairs[0], pairs[2]}) for (int groups : new int[] {1, 2, 5, 16}) {
                for (int group = 0; group < groups; group++) for (double time : sortieTimes) for (int interval : INTERVALS) {
                    final int g = group;
                    d.v(() -> HiveFormation.dropletCentre(pair[0], pair[1], 1.9, g, groups, time, 100, interval));
                }
            }
        });
        function("HiveFormation.shapeSize", d -> {
            for (int members = -1; members <= 300; members++) {
                final int m = members;
                d.d(() -> HiveFormation.shapeSize(m));
            }
        });
        function("HiveFormation.clusterCentre", d -> {
            for (HiveType type : HiveType.values()) for (double[] size : new double[][] {{1.1, 1.9}, {Double.NaN, Double.NaN}}) {
                for (int groups : range(1, 17, 24, 30)) for (int group = -1; group <= groups; group++) for (double time : TIMES) {
                    final int g = group;
                    d.v(() -> HiveFormation.clusterCentre(type, TARGET, size[0], size[1], g, groups, time));
                }
            }
        });
        function("HiveFormation.clusterLinks", d -> {
            for (HiveType type : HiveType.values()) for (int groups = -1; groups <= 40; groups++) {
                final int n = groups;
                d.call(() -> {
                    int[][] links = HiveFormation.clusterLinks(type, n);
                    d.i(links.length);
                    for (int[] link : links) {
                        d.i(link.length);
                        for (int value : link) d.i(value);
                    }
                });
            }
        });
        function("HiveFormation.clumpRadius", d -> {
            for (int members = -1; members <= 300; members++) {
                final int m = members;
                d.d(() -> HiveFormation.clumpRadius(m));
            }
        });
        double[] stationTimes = sweep(TIMES, new double[] {110, 131.5, 150, 163.25, 171, 199.75});
        function("HiveFormation.station", d -> {
            for (AttackMode mode : AttackMode.values()) for (HiveType type : HiveType.values()) for (int slots : SLOTS) {
                for (int slot = -1; slot <= slots; slot++) for (double time : stationTimes) for (int interval : INTERVALS) {
                    final int s = slot;
                    d.v(() -> HiveFormation.station(mode, type, s, slots, OWNER, TARGET, 1.1, 1.9, time, 100, interval));
                }
            }
        });
        Vec3d[] stations = {TARGET.add(1.5, 2.25, -.75), OWNER.add(.1, .9, .2)};
        function("HiveFormation.deployed", d -> {
            for (HiveType type : HiveType.values()) for (float yaw : YAWS) for (int count : new int[] {12, 60, 250}) {
                for (int unit = -1; unit <= count; unit++) for (Vec3d station : stations) for (int travel : new int[] {0, 20, 30, 70}) {
                    for (double time : new double[] {140, 150, 151, 155.5, 165, 172.25, 180, 200}) {
                        final int u = unit;
                        d.v(() -> HiveFormation.deployed(OWNER, yaw, station, u, count, type, time, 150, travel));
                    }
                }
            }
        });
        function("HiveFormation.returning", d -> {
            for (HiveType type : HiveType.values()) for (float yaw : YAWS) for (int count : new int[] {12, 60, 250}) {
                for (int unit = -1; unit <= count; unit++) for (Vec3d struck : stations) {
                    for (double time : new double[] {140, 150, 151, 160.5, 174, 180, 190}) {
                        final int u = unit;
                        d.v(() -> HiveFormation.returning(OWNER, yaw, struck, u, count, type, time, 150));
                    }
                }
            }
        });
        function("HiveFormation.stagger", d -> {
            for (HiveType type : HiveType.values()) for (int index = -1; index <= HiveType.MAX_DRONES; index++) for (double progress : PROGRESS) {
                final int i = index;
                d.d(() -> HiveFormation.stagger(i, type, progress));
            }
        });
    }

    private void shapes() {
        int[] counts = {1, 12, 16, 17, 48, 49, 60, 250, 750};
        function("HiveShapes.tesseract", d -> {
            for (int count : counts) for (int m = -1; m <= count; m++) for (double time : TIMES) for (double size : new double[] {.8, 1.45}) {
                final int member = m;
                d.v(() -> HiveShapes.tesseract(member, count, time, size));
            }
        });
        function("HiveShapes.tesseractCorner", d -> {
            for (int corner = 0; corner < 16; corner++) for (double time : TIMES) for (double size : new double[] {.8, 1.45}) {
                final int c = corner;
                d.v(() -> HiveShapes.tesseractCorner(c, time, size));
            }
        });
        function("HiveShapes.TESSERACT_EDGES", d -> {
            d.i(HiveShapes.TESSERACT_EDGES.length);
            for (int[] edge : HiveShapes.TESSERACT_EDGES) for (int corner : edge) d.i(corner);
        });
        function("HiveShapes.droplet", d -> {
            for (int count : UNITS) for (int m = 0; m < count; m++) for (double time : TIMES) for (Vec3d forward : FORWARDS) {
                final int member = m;
                d.v(() -> HiveShapes.droplet(member, count, time, forward, .8));
            }
        });
        function("HiveShapes.hexagons", d -> {
            for (int count : UNITS) for (int m = 0; m < count; m++) for (double time : TIMES) for (Vec3d forward : FORWARDS) {
                final int member = m;
                d.v(() -> HiveShapes.hexagons(member, count, time, forward, .8));
            }
        });
        function("HiveShapes.hexagonCount", d -> {
            for (int count = -1; count <= 800; count++) {
                final int c = count;
                d.i(() -> HiveShapes.hexagonCount(c));
            }
        });
        function("HiveShapes.hexagonCentre", d -> {
            for (int rings = 1; rings <= 7; rings++) for (int ring = -1; ring <= rings; ring++) for (double time : TIMES) for (Vec3d forward : FORWARDS) {
                final int r = ring, n = rings;
                d.v(() -> HiveShapes.hexagonCentre(r, n, time, forward, .8));
            }
        });
        function("HiveShapes.hexagonPoint", d -> {
            for (double along = -1; along <= 7; along += .125) for (double spin : new double[] {0, .7, -2.5}) for (Vec3d forward : FORWARDS) {
                for (double radius : new double[] {.45, 2}) {
                    final double a = along;
                    d.v(() -> HiveShapes.hexagonPoint(a, spin, forward, radius));
                }
            }
        });
        function("HiveShapes.clump", d -> {
            for (HiveType type : HiveType.values()) for (int count : UNITS) for (int m = 0; m < count; m++) for (double time : TIMES) {
                for (Vec3d facing : FORWARDS) for (double radius : new double[] {HiveFormation.clumpRadius(16), .5}) {
                    final int member = m;
                    d.v(() -> HiveShapes.clump(type, member, count, time, facing, radius));
                }
            }
        });
        function("HiveShapes.rotate", d -> {
            Vec3d[] axes = {new Vec3d(.3, 1, .2).normalize(), new Vec3d(0, 1, 0), new Vec3d(1, 0, 0)};
            for (Vec3d v : FORWARDS) for (Vec3d axis : axes) for (double angle : new double[] {-7, -1, 0, .5, 3.14159, 100}) {
                d.v(() -> HiveShapes.rotate(v, axis, angle));
            }
        });
        int[] structures = {1, 2, 12, 16, 17, 60, 100, 250, 750};
        function("HiveShapes.torus", d -> {
            for (int count : structures) for (int s = -1; s <= count; s++) for (double time : TIMES) {
                for (double[] torus : new double[][] {{1.25, .44}, {2.3, .5}}) {
                    final int slot = s;
                    d.v(() -> HiveShapes.torus(slot, count, time, torus[0], torus[1]));
                }
            }
        });
        function("HiveShapes.ward", d -> {
            for (int count : structures) for (int s = -1; s <= count; s++) for (double time : TIMES) for (double scale : new double[] {1, 1.4}) {
                final int slot = s;
                d.v(() -> HiveShapes.ward(slot, count, time, scale));
            }
        });
        function("HiveShapes.rhombusPoint", d -> {
            for (double t = -1; t <= 2; t += 1 / 64D) for (double angle : new double[] {0, 1, -2.5, 7}) {
                final double at = t;
                d.v(() -> HiveShapes.rhombusPoint(at, angle));
            }
        });
        function("HiveShapes.riftSpheres", d -> {
            for (int count : structures) for (int s = -1; s <= count; s++) for (double time : TIMES) for (double distance : new double[] {1.5, 2.3}) {
                final int slot = s;
                d.v(() -> HiveShapes.riftSpheres(slot, count, time, distance));
            }
        });
        function("HiveShapes.riftCentre", d -> {
            for (int sphere = 0; sphere < 8; sphere++) for (double time : TIMES) for (double distance : new double[] {1.5, 2.3}) {
                final int s = sphere;
                d.v(() -> HiveShapes.riftCentre(s, time, distance));
            }
        });
        function("HiveShapes.riftSpin", d -> {
            for (int sphere = 0; sphere < 8; sphere++) for (double time : TIMES) {
                final int s = sphere;
                d.d(() -> HiveShapes.riftSpin(s, time));
            }
        });
        function("HiveShapes.axes", d -> {
            for (Vec3d forward : FORWARDS) {
                d.call(() -> {
                    Vec3d[] axes = HiveShapes.axes(forward);
                    d.i(axes.length);
                    for (Vec3d axis : axes) d.v(axis);
                });
            }
        });
    }

    private void slots() {
        int[] healers = {0, 1, 12, 249, 250, 251, 700, 750};
        function("HiveSlots.healerSlots", d -> {
            for (int units = -1; units <= 800; units++) for (int count : healers) {
                final int u = units;
                d.i(() -> HiveSlots.healerSlots(u, new HiveSettings(count, AttackMode.BARRAGE)));
            }
        });
        function("HiveSlots.fighterSlots", d -> {
            for (int units = -1; units <= 800; units++) for (int count : healers) {
                final int u = units;
                d.i(() -> HiveSlots.fighterSlots(u, new HiveSettings(count, AttackMode.BARRAGE)));
            }
        });
        Random random = new Random(42);
        List<List<HiveStackState.Unit>> swarms = new ArrayList<>();
        for (int size : UNITS) {
            List<HiveStackState.Unit> units = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                units.add(new HiveStackState.Unit(random.nextInt(4), random.nextInt(3) == 0 ? random.nextInt(2_000) : 0,
                        random.nextInt(3) == 0 ? random.nextInt(2_000) : -1, random.nextInt(2_000)));
            }
            swarms.add(units);
        }
        long[] nows = {0, 500, 1_000, 2_500};
        function("HiveSlots.occupant", d -> {
            for (List<HiveStackState.Unit> units : swarms) for (int slots : SLOTS) for (int slot = -1; slot <= slots; slot++) {
                for (int fighters : new int[] {units.size(), units.size() / 2}) for (long now : nows) {
                    final int s = slot;
                    d.i(() -> HiveSlots.occupant(units, s, slots, fighters, now));
                }
            }
        });
        function("HiveSlots.since", d -> {
            for (List<HiveStackState.Unit> units : swarms) for (int slots : SLOTS) for (int slot = -1; slot <= slots; slot++) {
                for (int fighters : new int[] {units.size(), units.size() / 2}) for (long now : nows) {
                    final int s = slot;
                    d.l(() -> HiveSlots.since(units, s, slots, fighters, HiveSlots.occupant(units, s, slots, fighters, now), now));
                }
            }
        });
        function("HiveSlots.groups", d -> {
            for (int slots = -1; slots <= 800; slots++) {
                final int s = slots;
                d.i(() -> HiveSlots.groups(s));
            }
        });
        function("HiveSlots.group", d -> {
            for (int slot = -1; slot <= 300; slot++) for (int groups = 1; groups <= 16; groups++) {
                final int s = slot, g = groups;
                d.i(() -> HiveSlots.group(s, g));
            }
        });
        function("HiveSlots.member", d -> {
            for (int slot = -1; slot <= 300; slot++) for (int groups = 1; groups <= 16; groups++) {
                final int s = slot, g = groups;
                d.i(() -> HiveSlots.member(s, g));
            }
        });
        function("HiveSlots.groupSize", d -> {
            for (int group = -1; group <= 17; group++) for (int slots : new int[] {0, 1, 2, 3, 12, 16, 17, 31, 32, 33, 100, 250}) {
                for (int groups = 1; groups <= 16; groups++) {
                    final int g = group, n = groups;
                    d.i(() -> HiveSlots.groupSize(g, slots, n));
                }
            }
        });
    }

    private void field() {
        function("ShieldField.constants", d -> d.d(ShieldField.RADIUS).d(ShieldField.CENTER_Y).d(ShieldField.PREVIEW_TICKS));
        double[] coordinates = {-8, -2.5, -1.2, 0, .35, 1.9, 6};
        List<Vec3d> starts = new ArrayList<>();
        for (double x : coordinates) for (double y : coordinates) for (double z : coordinates) starts.add(new Vec3d(x, y, z));
        Vec3d[] velocities = {new Vec3d(0, 0, 0), new Vec3d(1e-6, 0, 0), new Vec3d(.8, 0, 0), new Vec3d(-.5, .1, .2), new Vec3d(0, -2.2, 0),
                new Vec3d(3, 1, -2), new Vec3d(-1.7, -.4, 1.1)};
        double[] maxTicks = {0, 1, ShieldField.PREVIEW_TICKS, 40};
        double[] radii = {0, ShieldField.RADIUS, 5.5, 24, Double.NaN};
        function("ShieldField.intercept", d -> {
            for (Vec3d start : starts) for (Vec3d velocity : velocities) for (double ticks : maxTicks) for (double radius : radii) {
                d.crossing(() -> ShieldField.intercept(start, velocity, ticks, radius));
            }
        });
        function("ShieldField.incoming", d -> {
            for (Vec3d start : starts) for (Vec3d velocity : velocities) for (double ticks : maxTicks) for (double radius : radii) {
                d.crossing(() -> ShieldField.incoming(start, velocity, ticks, radius));
            }
        });
        function("ShieldField.focus", d -> {
            for (double dot = -1.25; dot <= 1.25; dot += 1 / 64D) for (double width : new double[] {.2, .6, 1.2, 3}) {
                final double value = dot;
                d.d(() -> ShieldField.focus(value, width));
            }
        });
        function("ShieldField.fade", d -> {
            for (double age = -3; age <= 60; age += .25) for (double duration : new double[] {0, 1, 12, 36}) {
                final double a = age;
                d.d(() -> ShieldField.fade(a, duration));
            }
        });
    }

    private void topology() {
        function("ShieldTopology.constants", d -> d.i(ShieldTopology.CELL_COUNT).i(ShieldTopology.LEGACY_CELL_COUNT));
        function("ShieldTopology.cells.centres", d -> {
            d.i(TOPOLOGY.cells().length);
            for (ShieldTopology.Cell cell : TOPOLOGY.cells()) {
                d.i(cell.id()).i(cell.center().length);
                for (float value : cell.center()) d.d(value);
            }
        });
        function("ShieldTopology.cells.perimeters", d -> {
            for (ShieldTopology.Cell cell : TOPOLOGY.cells()) {
                d.i(cell.perimeter().length);
                for (float value : cell.perimeter()) d.d(value);
            }
        });
        function("ShieldTopology.cells.panels", d -> {
            for (ShieldTopology.Cell cell : TOPOLOGY.cells()) d.i(cell.panel());
        });
        function("ShieldTopology.nearestTo", d -> {
            for (int cell = 0; cell < ShieldTopology.CELL_COUNT; cell++) {
                int[] order = TOPOLOGY.nearestTo(cell);
                d.i(order.length);
                for (int other : order) d.i(other);
            }
        });
        function("ShieldTopology.neighbors", d -> {
            for (int cell = 0; cell < ShieldTopology.CELL_COUNT; cell++) {
                int[] neighbors = TOPOLOGY.neighbors(cell);
                d.i(neighbors.length);
                for (int other : neighbors) d.i(other);
            }
        });
        function("ShieldTopology.adjacent", d -> {
            for (int first = -1; first <= ShieldTopology.CELL_COUNT; first++) for (int second = -1; second <= ShieldTopology.CELL_COUNT; second++) {
                d.z(TOPOLOGY.adjacent(first, second));
            }
        });
        function("ShieldTopology.migrateLegacyCell", d -> {
            for (int legacy = 0; legacy < ShieldTopology.LEGACY_CELL_COUNT; legacy++) d.i(TOPOLOGY.migrateLegacyCell(legacy));
        });
        List<Vec3d> sphere = sphere(64, 128);
        function("ShieldTopology.nearest", d -> {
            for (Vec3d direction : sphere) for (double scale : new double[] {1, .25, 3}) {
                d.i(TOPOLOGY.nearest(direction.x * scale, direction.y * scale, direction.z * scale));
            }
            d.i(TOPOLOGY.nearest(0, 0, 0));
            d.i(TOPOLOGY.nearest(Double.NaN, 1, 0));
        });
        // ShieldController.selectCell, written out: the incoming direction turned into the wearer's frame.
        List<Vec3d> incoming = sphere(16, 32);
        incoming.add(new Vec3d(0, 0, 0));
        function("CellSelection.select", d -> {
            for (float yaw : sweep(YAWS, -360, 360, 15)) {
                Vec3d forward = Vec3d.directionFromRotation(0, yaw);
                for (Vec3d in : incoming) {
                    d.i(TOPOLOGY.nearest(-in.x * forward.z + in.z * forward.x, in.y, in.x * forward.x + in.z * forward.z));
                }
            }
        });
    }

    /** Damage, gather and repair in a seeded mix of 1000 operations, starting from a fresh shield. */
    private void cellDefense() {
        Digest damage = new Digest(), gather = new Digest(), repair = new Digest();
        Random random = new Random(42);
        ShieldStackState state = ShieldStackState.DEFAULT;
        long now = 0;
        for (int operation = 0; operation < 1000; operation++) {
            now += random.nextInt(25);
            switch (random.nextInt(4)) {
                case 0, 1 -> {
                    int cell = random.nextInt(ShieldTopology.CELL_COUNT);
                    int cost = random.nextInt(random.nextBoolean() ? 40 : 400);
                    double sharing = random.nextDouble() * .7;
                    long at = random.nextInt(8) == 0 ? Long.MAX_VALUE : now;
                    ShieldCellDefense.Damage result = ShieldCellDefense.damage(state, cell, cost, sharing, at);
                    damage.i(result.health().size());
                    for (int hp : result.health()) damage.i(hp);
                    damage.i(result.sharedBuffer()).i(result.spent());
                    state = result.apply(state, cell, result.spent(), now);
                    state(damage, state);
                }
                case 2 -> {
                    state = ShieldCellDefense.gather(state, random.nextInt(ShieldTopology.CELL_COUNT), random.nextInt(5), now);
                    state(gather, state);
                }
                default -> {
                    state = ShieldCellDefense.repair(state, now, random.nextInt(5_600), random.nextInt(50), random.nextInt(5) - 1);
                    state(repair, state);
                }
            }
        }
        digests.put("ShieldCellDefense.damage", damage.hex());
        digests.put("ShieldCellDefense.gather", gather.hex());
        digests.put("ShieldCellDefense.repair", repair.hex());
    }

    private static void state(Digest d, ShieldStackState state) {
        d.z(state.enabled()).i(state.front()).i(state.left()).i(state.right()).i(state.back()).i(state.lastHitPanel())
                .d(state.lastAbsorbed()).l(state.lastActiveGameTime()).i(state.sharedBuffer()).l(state.gatherTime());
        d.i(state.cells().size());
        for (int hp : state.cells()) d.i(hp);
        d.i(state.moves().size());
        for (ShieldCellMove move : state.moves()) d.i(move.from()).i(move.to());
    }

    private void function(String name, Consumer<Digest> body) {
        Digest digest = new Digest();
        body.accept(digest);
        digests.put(name, digest.hex());
        calls.put(name, new long[] {digest.calls, digest.thrown});
    }

    /** A latitude-longitude grid of unit directions. */
    private static List<Vec3d> sphere(int latitudes, int longitudes) {
        List<Vec3d> result = new ArrayList<>();
        for (int lat = 0; lat < latitudes; lat++) for (int lon = 0; lon < longitudes; lon++) {
            double theta = (lat + .5) / latitudes * Math.PI, phi = lon / (double) longitudes * 2 * Math.PI;
            result.add(new Vec3d(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)));
        }
        return result;
    }

    private static int[] withZero(int[] values) {
        int[] result = new int[values.length + 1];
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    private static int[] range(int from, int to, int... more) {
        int[] result = new int[to - from + 1 + more.length];
        for (int value = from; value <= to; value++) result[value - from] = value;
        System.arraycopy(more, 0, result, to - from + 1, more.length);
        return result;
    }

    private static double[] sweep(double[] base, double from, double to, double step) {
        List<Double> values = new ArrayList<>();
        for (double value : base) values.add(value);
        for (int index = 0; from + index * step <= to; index++) values.add(from + index * step);
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] sweep(double[] base, double[] more) {
        double[] result = new double[base.length + more.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(more, 0, result, base.length, more.length);
        return result;
    }

    private static float[] sweep(float[] base, int from, int to, int step) {
        float[] result = new float[base.length + (to - from) / step + 1];
        System.arraycopy(base, 0, result, 0, base.length);
        for (int index = 0; from + index * step <= to; index++) result[base.length + index] = from + index * step;
        return result;
    }

    /** SHA-256 over the raw bits of the outputs; a thrown exception feeds a marker and its class name. */
    private static final class Digest {
        private final MessageDigest sha;
        private final byte[] scratch = new byte[8];
        private long calls, thrown;

        Digest() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        Digest i(int value) {
            for (int index = 0; index < 4; index++) scratch[index] = (byte) (value >>> 24 - 8 * index);
            sha.update(scratch, 0, 4);
            return this;
        }

        Digest l(long value) {
            for (int index = 0; index < 8; index++) scratch[index] = (byte) (value >>> 56 - 8 * index);
            sha.update(scratch, 0, 8);
            return this;
        }

        Digest d(double value) {
            return l(Double.doubleToLongBits(value));
        }

        Digest z(boolean value) {
            return i(value ? 1 : 0);
        }

        Digest v(Vec3d value) {
            return d(value.x).d(value.y).d(value.z);
        }

        void v(java.util.function.Supplier<Vec3d> value) {
            call(() -> v(value.get()));
        }

        void d(java.util.function.DoubleSupplier value) {
            call(() -> d(value.getAsDouble()));
        }

        void i(java.util.function.IntSupplier value) {
            call(() -> i(value.getAsInt()));
        }

        void l(java.util.function.LongSupplier value) {
            call(() -> l(value.getAsLong()));
        }

        void z(java.util.function.BooleanSupplier value) {
            call(() -> z(value.getAsBoolean()));
        }

        void crossing(java.util.function.Supplier<ShieldField.Crossing> value) {
            call(() -> {
                ShieldField.Crossing crossing = value.get();
                if (crossing == null) {
                    i(0);
                } else {
                    i(1).d(crossing.time()).v(crossing.normal());
                }
            });
        }

        void call(Runnable output) {
            calls++;
            try {
                output.run();
            } catch (RuntimeException error) {
                thrown++;
                i(0xE7707).sha.update(error.getClass().getName().getBytes(StandardCharsets.UTF_8));
            }
        }

        String hex() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }

    private GeometryGoldenCheck() { }
}
