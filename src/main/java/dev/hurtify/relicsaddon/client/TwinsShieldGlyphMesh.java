package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.ArrayList;
import java.util.List;

/** Cached spherical sigils, clipped to the same Voronoi cells used by damage selection. */
final class TwinsShieldGlyphMesh {
    static final int MEMBRANE = 0, GLOW = 1, GLYPH = 2, ORBIT = 3, CIRCUIT = 4;
    private static final double TAU = Math.PI * 2;
    private static final Point[] CENTERS = java.util.Arrays.stream(ShieldTopology.INSTANCE.cells())
            .map(cell -> new Point(cell.center()[0], cell.center()[1], cell.center()[2])).toArray(Point[]::new);
    private static final double[] CELL_COS_RADIUS = cellRadii();
    private static final TwinsShieldGlyphMesh LOW = create(true);
    private static final TwinsShieldGlyphMesh HIGH = create(false);

    record Point(double x, double y, double z) {
        Point add(Point p) { return new Point(x + p.x, y + p.y, z + p.z); }
        Point scale(double k) { return new Point(x * k, y * k, z * k); }
        double dot(Point p) { return x * p.x + y * p.y + z * p.z; }
        Point cross(Point p) { return new Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x); }
        Point unit() { return scale(1 / Math.sqrt(dot(this))); }
    }
    record Triangle(Point a, Point b, Point c, int cell, int material, double phase) { }
    private record XY(double x, double y) {
        XY add(XY p) { return new XY(x + p.x, y + p.y); }
        XY scale(double k) { return new XY(x * k, y * k); }
    }
    private record Basis(Point n, Point u, Point v) {
        Point project(XY p) { return n.add(u.scale(p.x)).add(v.scale(p.y)).unit(); }
    }

    private final Triangle[][] cells;
    private final Triangle[][] circuits;

    private TwinsShieldGlyphMesh(List<Triangle> triangles) {
        cells = new Triangle[ShieldTopology.CELL_COUNT][];
        circuits = new Triangle[ShieldTopology.CELL_COUNT][];
        for (int id = 0; id < cells.length; id++) {
            int cell = id;
            cells[id] = triangles.stream().filter(t -> t.cell == cell).toArray(Triangle[]::new);
            circuits[id] = triangles.stream().filter(t -> t.cell == cell && t.material == CIRCUIT).toArray(Triangle[]::new);
        }
    }

    static TwinsShieldGlyphMesh forQuality(boolean low) { return low ? LOW : HIGH; }
    Triangle[] cell(int id) { return cells[id]; }
    Triangle[] circuits(int id) { return circuits[id]; }

    private static TwinsShieldGlyphMesh create(boolean low) {
        Builder b = new Builder(low);
        int rows = low ? 12 : 20, columns = rows * 2;
        for (int row = 0; row < rows; row++) for (int col = 0; col < columns; col++) {
            Point a = sphere(Math.PI * row / rows, TAU * col / columns);
            Point c = sphere(Math.PI * (row + 1) / rows, TAU * (col + 1) / columns);
            b.triangle(a, sphere(Math.PI * (row + 1) / rows, TAU * col / columns), c, MEMBRANE, 0);
            b.triangle(a, c, sphere(Math.PI * row / rows, TAU * (col + 1) / columns), MEMBRANE, 0);
        }
        Point[] axes = {new Point(0, 0, 1), new Point(0, 0, -1), new Point(1, 0, 0),
                new Point(-1, 0, 0), new Point(0, 1, 0), new Point(0, -1, 0)};
        for (int face = 0; face < axes.length; face++) {
            Point normal = axes[face];
            Point u = (Math.abs(normal.y) > .9 ? new Point(0, 0, 1) : new Point(0, 1, 0)).cross(normal).unit();
            Basis basis = new Basis(normal, u, normal.cross(u).unit());
            b.sigil(basis, face * .47);
            b.circuit(basis, face * 1.8);
        }
        for (int orbit = 0; orbit < 2; orbit++) {
            double azimuth = orbit * TAU / 3 + .3;
            Point normal = new Point(Math.cos(azimuth), .55, Math.sin(azimuth)).unit();
            Point u = new Point(0, 1, 0).cross(normal).unit(), v = normal.cross(u).unit();
            int steps = low ? 96 : 160;
            for (int step = 0; step < steps; step++) {
                double t = TAU * step / steps, next = TAU * (step + 1) / steps;
                Point a = u.scale(Math.cos(t)).add(v.scale(Math.sin(t)));
                Point c = u.scale(Math.cos(next)).add(v.scale(Math.sin(next)));
                b.stroke(a, c, .0035, ORBIT, t * 2 + orbit);
            }
            for (int node = 0; node < 6; node++) {
                double angle = TAU * node / 6;
                Point n = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
                Basis basis = new Basis(n, normal, n.cross(normal).unit());
                b.arc(basis, new XY(0, 0), .031, 0, TAU, .004, ORBIT, node + orbit);
            }
        }
        return new TwinsShieldGlyphMesh(b.triangles);
    }

    private static final class Builder {
        final List<Triangle> triangles = new ArrayList<>();
        final boolean low;
        Builder(boolean low) { this.low = low; }

        void circuit(Basis basis, double phase) {
            for (int arm = 0; arm < 3; arm++) {
                double angle = arm * TAU / 3;
                XY[] path = {new XY(.24, -.40), new XY(.45, -.40), new XY(.60, -.25),
                        new XY(.60, .15), new XY(.76, .31), new XY(.96, .31)};
                for (int index = 0; index < path.length; index++) {
                    XY point = path[index];
                    path[index] = new XY(point.x * Math.cos(angle) - point.y * Math.sin(angle),
                            point.x * Math.sin(angle) + point.y * Math.cos(angle));
                }
                for (int segment = 0; segment + 1 < path.length; segment++) {
                    // Subdivide pulse phase along straight tracks, while keeping the path angular.
                    for (int step = 0; step < 3; step++) {
                        XY a = path[segment].scale(1 - step / 3.0).add(path[segment + 1].scale(step / 3.0));
                        XY b = path[segment].scale(1 - (step + 1) / 3.0).add(path[segment + 1].scale((step + 1) / 3.0));
                        stroke(basis.project(a), basis.project(b), .014, CIRCUIT, phase + arm * 2 + (segment + step / 3.0) * .55);
                    }
                }
                arc(basis, path[0], .032, 0, TAU, .012, CIRCUIT, phase + arm * 2);
            }
        }

        void sigil(Basis basis, double phase) {
            arc(basis, new XY(0, 0), .195, 0, TAU, .018, GLYPH, phase);
            for (int arm = 0; arm < 3; arm++) {
                double a = arm * TAU / 3 + .18;
                arc(basis, new XY(0, 0), .278, a + .10, a + 1.76, .024, GLYPH, phase);
                XY terminal = polar(.57, a + .52);
                arc(basis, terminal, .106, 0, TAU, .016, GLYPH, phase + arm);
                curve(basis, polar(.30, a + .13), polar(.45, a - .25), polar(.69, a - .13), polar(.96, a + .35), .016, phase);
                curve(basis, polar(.96, a + .35), polar(.73, a + .33), polar(.76, a + .72), polar(.40, a + 1.12), .013, phase + .7);
                curve(basis, polar(.35, a + .83), polar(.38, a + .83), polar(.43, a + .76), terminal, .009, phase + 1.4);
                for (int rune = 0; rune < (low ? 1 : 2); rune++) {
                    double angle = a + .72 + rune * .28;
                    XY center = polar(.76, angle);
                    XY tangent = new XY(-Math.sin(angle), Math.cos(angle));
                    XY radial = polar(1, angle);
                    XY p0 = center.add(radial.scale(-.027)).add(tangent.scale(-.019));
                    XY p1 = center.add(radial.scale(.025)).add(tangent.scale(-.019));
                    XY p2 = center.add(radial.scale(.007)).add(tangent.scale(.019));
                    XY p3 = center.add(radial.scale(-.025)).add(tangent.scale(.019));
                    line(basis, p0, p1, .007, phase + rune);
                    line(basis, p1, p2, .007, phase + rune);
                    line(basis, p2, p3, .007, phase + rune);
                }
            }
        }

        void line(Basis basis, XY a, XY b, double width, double phase) {
            stroke(basis.project(a), basis.project(b), width, GLYPH, phase);
        }

        void curve(Basis basis, XY a, XY b, XY c, XY d, double width, double phase) {
            int steps = low ? 10 : 18;
            XY previous = a;
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps, s = 1 - t;
                XY next = a.scale(s * s * s).add(b.scale(3 * s * s * t)).add(c.scale(3 * s * t * t)).add(d.scale(t * t * t));
                line(basis, previous, next, width, phase + t * 3);
                previous = next;
            }
        }

        void arc(Basis basis, XY center, double radius, double start, double end, double width, int material, double phase) {
            int steps = Math.max(low ? 10 : 16, (int) Math.ceil((end - start) * radius * (low ? 24 : 40)));
            for (int i = 0; i < steps; i++) {
                double t = start + (end - start) * i / steps, next = start + (end - start) * (i + 1) / steps;
                stroke(basis.project(center.add(polar(radius, t))), basis.project(center.add(polar(radius, next))),
                        width, material, phase + t);
            }
        }

        void stroke(Point a, Point b, double width, int material, double phase) {
            Point side = a.cross(b).unit();
            for (int layer = low || material == CIRCUIT ? 0 : 1; layer >= 0; layer--) {
                double w = width * (layer == 0 ? 1 : 2.7);
                Point p = a.add(side.scale(w)).unit(), q = b.add(side.scale(w)).unit();
                Point r = b.add(side.scale(-w)).unit(), s = a.add(side.scale(-w)).unit();
                int kind = layer == 0 ? material : GLOW;
                triangle(p, q, r, kind, phase);
                triangle(p, r, s, kind, phase);
            }
        }

        void triangle(Point a, Point b, Point c, int material, double phase) {
            if (area(a, b, c) < 1e-12) return;
            int ia = nearest(a), ib = nearest(b), ic = nearest(c);
            if (ia == ib && ib == ic) {
                triangles.add(new Triangle(a, b, c, ia, material, phase));
                return;
            }
            Point middle = a.add(b).add(c).unit();
            double triangleCos = Math.min(middle.dot(a), Math.min(middle.dot(b), middle.dot(c)));
            double triangleSin = Math.sqrt(Math.max(0, 1 - triangleCos * triangleCos));
            // Reject disjoint spherical caps before clipping against cached Voronoi neighbors.
            for (int cell = 0; cell < CENTERS.length; cell++) {
                double cellCos = CELL_COS_RADIUS[cell];
                double capCos = triangleCos * cellCos - triangleSin * Math.sqrt(Math.max(0, 1 - cellCos * cellCos));
                if (middle.dot(CENTERS[cell]) < capCos - 1e-6) continue;
                List<Point> polygon = List.of(a, b, c);
                for (int other : ShieldTopology.INSTANCE.neighborsOf(cell)) {
                    if (polygon.size() < 3) break;
                    Point plane = CENTERS[cell].add(CENTERS[other].scale(-1));
                    polygon = clip(polygon, plane);
                }
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    Point p = polygon.getFirst().unit(), q = polygon.get(i).unit(), r = polygon.get(i + 1).unit();
                    if (area(p, q, r) > 1e-12) triangles.add(new Triangle(p, q, r, cell, material, phase));
                }
            }
        }
    }

    private static double[] cellRadii() {
        double[] radii = new double[CENTERS.length];
        for (var cell : ShieldTopology.INSTANCE.cells()) {
            double minimum = 1;
            float[] perimeter = cell.perimeter();
            for (int i = 0; i < perimeter.length; i += 3) {
                minimum = Math.min(minimum, CENTERS[cell.id()].dot(new Point(perimeter[i], perimeter[i + 1], perimeter[i + 2])));
            }
            // Render perimeters are slightly inset; pad the cap to contain the exact clipping cell.
            radii[cell.id()] = Math.cos(Math.acos(Math.clamp(minimum, -1, 1)) * 1.08 + .002);
        }
        return radii;
    }

    private static List<Point> clip(List<Point> polygon, Point normal) {
        var output = new ArrayList<Point>(5);
        Point previous = polygon.getLast();
        double before = previous.dot(normal);
        for (Point current : polygon) {
            double now = current.dot(normal);
            if ((before >= 0) != (now >= 0)) {
                double t = before / (before - now);
                output.add(previous.scale(1 - t).add(current.scale(t)));
            }
            if (now >= 0) output.add(current);
            previous = current;
            before = now;
        }
        return output;
    }

    private static int nearest(Point p) { return ShieldTopology.INSTANCE.nearest(p.x, p.y, p.z); }
    private static double area(Point a, Point b, Point c) {
        Point cross = b.add(a.scale(-1)).cross(c.add(a.scale(-1)));
        return cross.dot(cross);
    }
    private static XY polar(double radius, double angle) { return new XY(Math.cos(angle) * radius, Math.sin(angle) * radius); }
    private static Point sphere(double theta, double phi) {
        return new Point(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
    }
}
