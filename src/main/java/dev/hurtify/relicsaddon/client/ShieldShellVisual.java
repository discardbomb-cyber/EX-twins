package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The shield shells, after the user's references:
 * <ul>
 *   <li>RF: a holographic geodesic sphere - glowing cyan edges and nodes, faint crystal facets that
 *       catch a moving glint, a scan line and drifting data dust, with a projector ring on the ground.</li>
 *   <li>Mana: one seamless teal glass dome - bright rim, glowing base ring, rising sparkles, swirling
 *       streams of energy and a wavy band.</li>
 *   <li>Twins: both at once in one violet shell.</li>
 * </ul>
 * Fills go to the translucent shield batch, light to the additive {@link ShieldGlow} batch.
 * Meshes live in the wearer's yaw frame so each face knows its gameplay cell: broken cells are holes.
 */
final class ShieldShellVisual {
    record Palette(int fill, int deep, int bright, int edge, int node) { }

    static final Palette RF = new Palette(0x0C3442, 0x0A4452, 0x5FF6FF, 0x3FE8FF, 0xD2FCFF);
    static final Palette MANA = new Palette(0x0B4E52, 0x0B5E62, 0x4FF2DA, 0x6FF6E4, 0xE0FFF8);
    static final Palette TWINS_DOME = new Palette(0x1E0B33, 0x2A0F45, 0xC07BFF, 0xD7A6FF, 0xF6E8FF);
    static final Palette TWINS_LATTICE = new Palette(0x241040, 0x2A0F45, 0xD7A6FF, 0xC98CFF, 0xF6E8FF);

    /** Everything a layer needs to place itself: shell centre (camera-relative), radius, yaw and eye. */
    record Frame(Matrix4f matrix, double x, double y, double z, double radius, double fx, double fz, Vec3 eye) {
        double[] world(double[] local) {
            return new double[]{-local[0] * fz + local[2] * fx, local[1], local[0] * fx + local[2] * fz};
        }

        double[] point(double[] dir, double lift) {
            double r = radius * (1 + lift) * ShieldRipple.scale(dir[0], dir[1], dir[2]);
            return new double[]{x + dir[0] * r, y + dir[1] * r, z + dir[2] * r};
        }

        double visibility(double[] dir) {
            return ShieldSurfaceLighting.visibility(dir[0], dir[1], dir[2], eye);
        }

        double fresnel(double[] dir) {
            if (ShieldSurfaceLighting.inside(eye)) return .25;
            return Math.pow(1 - Math.abs(dir[0] * eye.x + dir[1] * eye.y + dir[2] * eye.z), 2.2);
        }
    }

    /** Draws the shell for {@code role}; {@code presence} is 0..1 (idle glow up to full combat). */
    static void render(RelicRole role, VertexConsumer fill, Frame frame, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, double presence, boolean low) {
        VertexConsumer glow = ShieldGlow.consumer();
        switch (role) {
            case RF_SHIELD -> {
                lattice(fill, glow, frame, low ? ShieldGeometry.LATTICE_LOW : ShieldGeometry.LATTICE_HIGH, state, impacts, time, presence, RF, 1);
                dust(glow, frame, low ? 24 : 56, time, presence, RF);
                baseRing(fill, glow, frame, time, presence * .7, RF);
            }
            case MANA_SHIELD -> {
                dome(fill, frame, low ? ShieldGeometry.DOME_LOW : ShieldGeometry.DOME_HIGH, state, impacts, threats, time, presence, MANA);
                swirls(glow, frame, low ? 3 : 6, time, presence, MANA);
                waveBand(glow, frame, time, presence, MANA);
                sparkles(glow, frame, low ? 20 : 48, time, presence, MANA);
                baseRing(fill, glow, frame, time, presence, MANA);
            }
            case TWINS_SHIELD -> {
                dome(fill, frame, low ? ShieldGeometry.DOME_LOW : ShieldGeometry.DOME_HIGH, state, impacts, threats, time, presence * .85, TWINS_DOME);
                lattice(fill, glow, frame, low ? ShieldGeometry.LATTICE_LOW : ShieldGeometry.LATTICE_HIGH, state, impacts, time, presence, TWINS_LATTICE, .7);
                swirls(glow, frame, low ? 2 : 4, time, presence * .8, TWINS_DOME);
                sparkles(glow, frame, low ? 14 : 32, time, presence, TWINS_DOME);
                baseRing(fill, glow, frame, time, presence, TWINS_DOME);
            }
            default -> {
            }
        }
    }

    // --- geodesic lattice (RF, Twins) ----------------------------------------------------------

    private static void lattice(VertexConsumer fill, VertexConsumer glow, Frame f, ShieldGeometry.Mesh mesh, ShieldStackState state,
            List<ShieldImpact> impacts, double time, double presence, Palette p, double weight) {
        double[][] vertices = mesh.vertices();
        int n = vertices.length;
        double[][] dir = new double[n][], pos = new double[n][];
        for (int i = 0; i < n; i++) {
            dir[i] = f.world(vertices[i]);
            pos[i] = f.point(dir[i], .002);
        }
        int[][] triangles = mesh.triangles();
        boolean[] alive = new boolean[triangles.length];
        boolean buffer = state.sharedBuffer() > 0;
        for (int t = 0; t < triangles.length; t++) alive[t] = buffer || state.cellHp(mesh.triangleCell()[t]) > 0;

        // Crystal facets: faint flat fills that flash when a slowly orbiting light lines up with them.
        double lx = Math.cos(time * .012), lz = Math.sin(time * .012), ly = .55, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        for (int t = 0; t < triangles.length; t++) {
            if (!alive[t]) continue;
            int[] tri = triangles[t];
            double[] mid = normalize(dir[tri[0]][0] + dir[tri[1]][0] + dir[tri[2]][0], dir[tri[0]][1] + dir[tri[1]][1] + dir[tri[2]][1],
                    dir[tri[0]][2] + dir[tri[1]][2] + dir[tri[2]][2]);
            double glint = Math.pow(Math.max(0, (mid[0] * lx + mid[1] * ly + mid[2] * lz) / ll), 14);
            double alpha = presence * weight * (6 + 34 * glint + 22 * f.fresnel(mid)) * f.visibility(mid);
            int color = mix(p.fill(), p.bright(), glint * .7);
            triangle(fill, f.matrix(), pos[tri[0]], pos[tri[1]], pos[tri[2]], color, alpha);
        }

        // Glowing edges with a soft halo; a scan line sweeps up and down, hits flare nearby edges.
        double scanY = Math.sin(time * .045) * 1.05;
        double core = f.radius() * (mesh == ShieldGeometry.LATTICE_LOW ? .008 : .0055);
        for (int[] edge : mesh.edges()) {
            if (!(alive[edge[2]] || edge[3] >= 0 && alive[edge[3]])) continue;
            double[] a = dir[edge[0]], b = dir[edge[1]];
            double[] mid = normalize(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
            double scan = Math.max(0, 1 - Math.abs(mid[1] - scanY) / .12);
            double hit = hit(mid, impacts, time);
            double ripple = ShieldRipple.active() ? Math.abs(ShieldRipple.height(mid[0], mid[1], mid[2])) : 0;
            double vis = f.visibility(mid);
            double alpha = (presence * weight * (60 + 120 * scan) + hit * 210 + ripple * 90) * vis;
            if (alpha < 1) continue;
            int color = mix(p.edge(), 0xFFFFFF, Math.min(1, hit * .8 + scan * .25));
            ribbon(glow, f.matrix(), pos[edge[0]], pos[edge[1]], mid, core, color, alpha);
            ribbon(glow, f.matrix(), pos[edge[0]], pos[edge[1]], mid, core * 3.2, color, alpha * .28);
        }

        // Nodes: a sparse set of vertices twinkles like data points.
        double size = f.radius() * .011;
        for (int i = 0; i < n; i++) {
            int hash = i * 0x9E3779B1;
            if ((hash >>> 29) > 2) continue;
            double twinkle = .5 + .5 * Math.sin(time * .21 + (hash & 1023) * .0061);
            double alpha = presence * weight * (40 + 150 * twinkle * twinkle) * f.visibility(dir[i]);
            diamond(glow, f.matrix(), pos[i], dir[i], size, p.node(), alpha);
        }
    }

    /** Holographic data dust drifting just outside the RF lattice. */
    private static void dust(VertexConsumer glow, Frame f, int count, double time, double presence, Palette p) {
        for (int k = 0; k < count; k++) {
            double h1 = frac(Math.sin(k * 12.9898) * 43758.5453), h2 = frac(Math.sin(k * 78.233) * 12543.123), h3 = frac(Math.sin(k * 3.17) * 9711.7);
            double theta = Math.acos(1 - 2 * h1), phi = h2 * Math.PI * 2 + time * .004 * (h3 - .5);
            double[] dir = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
            double[] at = f.point(dir, .04 + .1 * h3);
            double alpha = presence * (50 + 90 * (.5 + .5 * Math.sin(time * .17 + h1 * 30))) * f.visibility(dir);
            diamond(glow, f.matrix(), at, dir, f.radius() * .0065, p.node(), alpha);
        }
    }

    // --- glass dome (Mana, Twins) ---------------------------------------------------------------

    private static void dome(VertexConsumer fill, Frame f, ShieldGeometry.Mesh mesh, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, double presence, Palette p) {
        double[][] vertices = mesh.vertices();
        int n = vertices.length;
        double[][] pos = new double[n][];
        int[] color = new int[n];
        double[] alpha = new double[n];
        boolean buffer = state.sharedBuffer() > 0;
        for (int i = 0; i < n; i++) {
            double[] dir = f.world(vertices[i]);
            pos[i] = f.point(dir, 0);
            boolean alive = buffer || state.cellHp(mesh.vertexCell()[i]) > 0;
            var response = ShieldResponse.atMany(new Vec3(dir[0], dir[1], dir[2]), threats, impacts, time, alive ? 1 : 0);
            double ripple = ShieldRipple.active() ? ShieldRipple.height(dir[0], dir[1], dir[2]) : 0;
            double fres = f.fresnel(dir);
            double flare = Math.max(response.absorption(), response.destruction());
            double a = presence * (9 + 80 * fres) * (alive ? 1 : 0) + flare * 95 + Math.abs(ripple) * 45 * presence;
            alpha[i] = Math.clamp(a * f.visibility(dir), 0, 185);
            color[i] = mix(mix(p.deep(), p.bright(), Math.min(1, fres * .85 + Math.max(0, ripple) * .35)), 0xFFFFFF, flare * .55);
        }
        for (int[] tri : mesh.triangles()) {
            if (alpha[tri[0]] + alpha[tri[1]] + alpha[tri[2]] < 1.5) continue;
            vertex(fill, f.matrix(), pos[tri[0]], color[tri[0]], alpha[tri[0]]);
            vertex(fill, f.matrix(), pos[tri[1]], color[tri[1]], alpha[tri[1]]);
            vertex(fill, f.matrix(), pos[tri[2]], color[tri[2]], alpha[tri[2]]);
        }
    }

    /** Streams of energy spiralling up the dome, each with a bright head running along it. */
    private static void swirls(VertexConsumer glow, Frame f, int count, double time, double presence, Palette p) {
        int segments = 30;
        for (int k = 0; k < count; k++) {
            double phase = k * 2.399963 + .7, spin = k % 2 == 0 ? 1 : -1;
            double head = frac(time * .011 + k * .37);
            double[] prev = null;
            for (int s = 0; s <= segments; s++) {
                double u = s / (double) segments;
                double theta = Math.PI * (.92 - .8 * u), phi = phase + spin * (u * 2.5 + time * .014);
                double[] dir = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
                double[] at = f.point(dir, .006);
                if (prev != null) {
                    double body = Math.pow(Math.sin(Math.PI * u), 1.2);
                    double pulse = Math.max(0, 1 - Math.abs(u - head) / .1);
                    double alpha = presence * body * (38 + 150 * pulse) * f.visibility(dir);
                    double width = f.radius() * (.012 + .012 * pulse) * Math.pow(Math.sin(Math.PI * u), .7);
                    ribbon(glow, f.matrix(), prev, at, dir, width, mix(p.bright(), 0xFFFFFF, pulse * .5), alpha);
                }
                prev = at;
            }
        }
    }

    /** A wavy line of light circling the dome just above its equator. */
    private static void waveBand(VertexConsumer glow, Frame f, double time, double presence, Palette p) {
        int segments = 72;
        double[] prev = null;
        for (int s = 0; s <= segments; s++) {
            double phi = Math.PI * 2 * s / segments;
            double theta = Math.PI / 2 - .22 + .08 * Math.sin(3 * phi + time * .08) + .045 * Math.sin(7 * phi - time * .05);
            double[] dir = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
            double[] at = f.point(dir, .008);
            if (prev != null) ribbon(glow, f.matrix(), prev, at, dir, f.radius() * .011, p.bright(), presence * 70 * f.visibility(dir));
            prev = at;
        }
    }

    /** Motes of light rising slowly inside the dome and twinkling. */
    private static void sparkles(VertexConsumer glow, Frame f, int count, double time, double presence, Palette p) {
        for (int k = 0; k < count; k++) {
            double h1 = frac(Math.sin(k * 91.7) * 43758.5453), h2 = frac(Math.sin(k * 17.3) * 24634.634), h3 = frac(Math.sin(k * 5.9) * 9387.1);
            double rise = frac(time * (.0035 + .003 * h3) + h1);
            double y = -.35 + 1.2 * rise;
            if (Math.abs(y) >= .98) continue;
            double ring = Math.sqrt(1 - y * y) * (.35 + .6 * h2), phi = h2 * 40 + time * .004;
            double[] dir = {Math.cos(phi) * ring, y, Math.sin(phi) * ring};
            double[] at = f.point(dir, 0);
            double twinkle = .5 + .5 * Math.sin(time * .3 + h3 * 40);
            double alpha = presence * Math.sin(Math.PI * rise) * (70 + 150 * twinkle) * (ShieldSurfaceLighting.inside(f.eye()) ? ShieldSurfaceLighting.INSIDE : 1);
            double[] outward = normalize(dir[0], dir[1], dir[2]);
            diamond(glow, f.matrix(), at, outward, f.radius() * (.008 + .006 * twinkle), p.node(), alpha);
        }
    }

    /** Glowing ring where the shell meets the ground, with a soft halo and a faint disc inside. */
    private static void baseRing(VertexConsumer fill, VertexConsumer glow, Frame f, double time, double presence, Palette p) {
        double ground = f.y() - ShieldField.CENTER_Y + .03;
        double ring = Math.sqrt(Math.max(.25, f.radius() * f.radius() - ShieldField.CENTER_Y * ShieldField.CENTER_Y));
        double pulse = .8 + .2 * Math.sin(time * .1);
        int segments = 64;
        for (int s = 0; s < segments; s++) {
            double a0 = Math.PI * 2 * s / segments, a1 = Math.PI * 2 * (s + 1) / segments;
            annulus(glow, f, ground, a0, a1, ring * .965, ring * 1.02, p.edge(), presence * 150 * pulse);
            annulus(glow, f, ground, a0, a1, ring * .9, ring * 1.12, p.bright(), presence * 45 * pulse);
            double[] c = {f.x(), ground - .005, f.z()};
            double[] e0 = {f.x() + Math.cos(a0) * ring, ground - .005, f.z() + Math.sin(a0) * ring};
            double[] e1 = {f.x() + Math.cos(a1) * ring, ground - .005, f.z() + Math.sin(a1) * ring};
            vertex(fill, f.matrix(), c, p.deep(), presence * 10);
            vertex(fill, f.matrix(), e1, p.deep(), presence * 26);
            vertex(fill, f.matrix(), e0, p.deep(), presence * 26);
        }
    }

    // --- primitives --------------------------------------------------------------------------------

    private static double hit(double[] dir, List<ShieldImpact> impacts, double time) {
        double hit = 0;
        for (ShieldImpact impact : impacts) {
            double age = time - impact.gameTime();
            if (impact.absorbed() <= 0 || age < 0 || age >= ShieldResponse.IMPACT_TICKS) continue;
            double dot = dir[0] * impact.normal().x + dir[1] * impact.normal().y + dir[2] * impact.normal().z;
            hit = Math.max(hit, ShieldField.focus(dot, .3) * ShieldField.fade(age, 24));
        }
        return hit;
    }

    /** A strip lying on the shell surface between two points (tangent, so it reads the same from anywhere). */
    private static void ribbon(VertexConsumer consumer, Matrix4f m, double[] a, double[] b, double[] normal, double width, int color, double alpha) {
        if (alpha < 1) return;
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double sx = dy * normal[2] - dz * normal[1], sy = dz * normal[0] - dx * normal[2], sz = dx * normal[1] - dy * normal[0];
        double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1e-9) return;
        sx *= width / length;
        sy *= width / length;
        sz *= width / length;
        double[] a0 = {a[0] + sx, a[1] + sy, a[2] + sz}, a1 = {a[0] - sx, a[1] - sy, a[2] - sz};
        double[] b0 = {b[0] + sx, b[1] + sy, b[2] + sz}, b1 = {b[0] - sx, b[1] - sy, b[2] - sz};
        triangle(consumer, m, a0, a1, b1, color, alpha);
        triangle(consumer, m, a0, b1, b0, color, alpha);
    }

    /** A small diamond lying tangent to the shell at {@code at}. */
    private static void diamond(VertexConsumer consumer, Matrix4f m, double[] at, double[] normal, double size, int color, double alpha) {
        if (alpha < 1) return;
        double[] up = Math.abs(normal[1]) > .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] u = normalize(normal[1] * up[2] - normal[2] * up[1], normal[2] * up[0] - normal[0] * up[2], normal[0] * up[1] - normal[1] * up[0]);
        double[] v = {normal[1] * u[2] - normal[2] * u[1], normal[2] * u[0] - normal[0] * u[2], normal[0] * u[1] - normal[1] * u[0]};
        double[] n = {at[0] + v[0] * size, at[1] + v[1] * size, at[2] + v[2] * size}, s = {at[0] - v[0] * size, at[1] - v[1] * size, at[2] - v[2] * size};
        double[] e = {at[0] + u[0] * size, at[1] + u[1] * size, at[2] + u[2] * size}, w = {at[0] - u[0] * size, at[1] - u[1] * size, at[2] - u[2] * size};
        triangle(consumer, m, n, e, s, color, alpha);
        triangle(consumer, m, n, s, w, color, alpha);
    }

    private static void annulus(VertexConsumer consumer, Frame f, double y, double a0, double a1, double inner, double outer, int color, double alpha) {
        double[] i0 = {f.x() + Math.cos(a0) * inner, y, f.z() + Math.sin(a0) * inner}, i1 = {f.x() + Math.cos(a1) * inner, y, f.z() + Math.sin(a1) * inner};
        double[] o0 = {f.x() + Math.cos(a0) * outer, y, f.z() + Math.sin(a0) * outer}, o1 = {f.x() + Math.cos(a1) * outer, y, f.z() + Math.sin(a1) * outer};
        triangle(consumer, f.matrix(), i0, o0, o1, color, alpha);
        triangle(consumer, f.matrix(), i0, o1, i1, color, alpha);
    }

    private static void triangle(VertexConsumer consumer, Matrix4f m, double[] a, double[] b, double[] c, int color, double alpha) {
        vertex(consumer, m, a, color, alpha);
        vertex(consumer, m, b, color, alpha);
        vertex(consumer, m, c, color, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f m, double[] p, int color, double alpha) {
        consumer.addVertex(m, (float) p[0], (float) p[1], (float) p[2])
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, (int) Math.clamp(alpha, 0, 255));
    }

    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-12 ? new double[]{0, 1, 0} : new double[]{x / length, y / length, z / length};
    }

    private static int mix(int a, int b, double t) {
        t = Math.clamp(t, 0, 1);
        int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private ShieldShellVisual() {
    }
}
