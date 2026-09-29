package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The shield shells, after the user's references:
 * <ul>
 *   <li>RF: a holographic sphere of hexagonal cells - raised translucent panels with glowing rims and
 *       twinkling nodes, a glint sweeping the panels, a scan line and drifting data dust.</li>
 *   <li>Mana: one seamless teal glass dome - bright rim, rising sparkles, swirling streams of energy
 *       and a wavy band.</li>
 *   <li>Twins: the glass dome with violet hexagonal cells raised over it.</li>
 * </ul>
 * Fills go to the translucent shield batch, light to the additive {@link ShieldGlow} batch.
 * Meshes live in the wearer's yaw frame so each face knows its gameplay cell: broken cells are holes.
 *
 * <p>Every line and point of light is drawn soft: bright along its centre and fading to nothing at its
 * edges, facing the camera, and never thinner than about a pixel (a thinner one is widened and dimmed
 * instead). Without multisampling this is what keeps the wireframe smooth instead of jagged and
 * shimmering at a distance.
 */
final class ShieldShellVisual {
    record Palette(int fill, int deep, int bright, int edge, int node) { }

    static final Palette RF = new Palette(0x0C3442, 0x0A4452, 0x5FF6FF, 0x3FE8FF, 0xD2FCFF);
    static final Palette MANA = new Palette(0x0B4E52, 0x0B5E62, 0x4FF2DA, 0x6FF6E4, 0xE0FFF8);
    static final Palette TWINS_DOME = new Palette(0x1E0B33, 0x2A0F45, 0xC07BFF, 0xD7A6FF, 0xF6E8FF);
    static final Palette TWINS_CELLS = new Palette(0x241040, 0x2A0F45, 0xD7A6FF, 0xB55CFF, 0xF6E8FF);

    /** Angular size of one screen pixel, refreshed each frame from the field of view and window height. */
    private static double pixelAngle = .0011;

    static void setPixelAngle(double radians) {
        if (Double.isFinite(radians) && radians > 0) pixelAngle = radians;
    }

    /**
     * Everything a layer needs to place itself: shell centre, radius, yaw and eye (from the centre towards
     * the viewer). In the world the camera sits at the origin of these coordinates ({@code perspective});
     * the flat galleries look along {@code eye} instead.
     */
    record Frame(Matrix4f matrix, double x, double y, double z, double radius, double fx, double fz, Vec3 eye, boolean perspective) {
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

        /** Unit vector from {@code p} towards the viewer. */
        double[] view(double[] p) {
            if (!perspective) return eye.lengthSqr() > 1e-8 ? new double[]{eye.x, eye.y, eye.z} : new double[]{0, 0, 1};
            return normalize(-p[0], -p[1], -p[2]);
        }

        /** World size of one screen pixel at {@code p}; zero in the flat galleries, where shells are drawn large. */
        double pixel(double[] p) {
            return perspective ? Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) * pixelAngle : 0;
        }
    }

    /** Draws the shell for {@code role}; {@code presence} is 0..1 (idle glow up to full combat). */
    static void render(RelicRole role, VertexConsumer fill, Frame frame, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, double presence, boolean low) {
        VertexConsumer glow = ShieldGlow.consumer();
        switch (role) {
            case RF_SHIELD -> {
                hexCells(fill, glow, frame, state, impacts, threats, time, presence, RF, false, low);
                dust(glow, frame, low ? 24 : 64, time, presence, RF);
            }
            case MANA_SHIELD -> {
                dome(fill, frame, low ? ShieldGeometry.DOME_LOW : ShieldGeometry.DOME_HIGH, state, impacts, threats, time, presence, MANA);
                swirls(glow, frame, low ? 3 : 6, low ? 24 : 56, time, presence, MANA);
                waveBand(glow, frame, low ? 64 : 144, time, presence, MANA);
                sparkles(glow, frame, low ? 20 : 56, time, presence, MANA);
            }
            case TWINS_SHIELD -> {
                dome(fill, frame, low ? ShieldGeometry.DOME_LOW : ShieldGeometry.DOME_HIGH, state, impacts, threats, time, presence * .85, TWINS_DOME);
                hexCells(fill, glow, frame, state, impacts, threats, time, presence, TWINS_CELLS, true, low);
                swirls(glow, frame, low ? 2 : 4, low ? 24 : 56, time, presence * .8, TWINS_DOME);
                sparkles(glow, frame, low ? 14 : 40, time, presence, TWINS_DOME);
            }
            default -> {
            }
        }
    }

    // --- hexagonal cells (RF, Twins) ------------------------------------------------------------

    /**
     * A honeycomb of glass: even hexagonal cells ({@link ShieldHoneycomb}) over the gameplay cells, so a
     * broken cell is a hole. Each is a flat pane tinted dark in the middle and lit towards its edge, joined to its
     * neighbours by one bright seam. A slowly circling light leaves a glassy sheen where it reflects
     * towards the viewer. Damaged RF cells warm through yellow and orange to red, damaged Twins cells
     * dim. A hit lights the cells around it and sends a wave of light across the honeycomb; while the
     * shell is awake every cell glows faintly.
     */
    private static void hexCells(VertexConsumer fill, VertexConsumer glow, Frame f, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, double presence, Palette p, boolean twins, boolean low) {
        int averageHp = (int) Math.ceil(state.totalIntegrity() / (double) ShieldTopology.CELL_COUNT);
        double[] sun = normalize(Math.cos(time * .011), .62, Math.sin(time * .011));
        double seam = f.radius() * (low ? .010 : .0068);
        // Twins panes sit a hair above their glass dome.
        double lift = twins ? .004 : 0;
        for (ShieldHoneycomb.Cell cell : ShieldHoneycomb.CELLS) {
            // Drawn cells are even hexagons; health, holes and motion come from the gameplay cell beneath.
            int id = cell.gameplay();
            int integrity = state.cellHp(id);
            boolean moving = state.moving(id, time);
            Quaternionf rotation = moving ? ShieldCellVisual.relocation(state, id, time) : null;
            float[] center = moving ? ShieldCellVisual.transform(cell.center(), rotation) : cell.center();
            float[] perimeter = moving ? ShieldCellVisual.transform(cell.perimeter(), rotation) : cell.perimeter();
            double[] normal = f.world(new double[]{center[0], center[1], center[2]});
            int visual = integrity == 0 && state.sharedBuffer() > 0 ? 1 : integrity;
            var response = ShieldResponse.atMany(new Vec3(normal[0], normal[1], normal[2]), threats, impacts, time, visual);
            if (visual == 0 && (response.destruction() < .008 || !ShieldCellVisual.justBroken(impacts, id, f.fx(), f.fz()))) continue;
            double light = Math.max(response.presence(), presence * (twins ? .38 : .42));
            if (integrity > 0 && moving) light = Math.max(light, .65);
            double vis = f.visibility(normal);
            if (light * vis < .008) continue;

            int hp = Math.min(averageHp, visual);
            int healthy = twins ? ShieldCellVisual.dim(p.edge(), hp) : ShieldCellVisual.warm(p.edge(), hp);
            double flare = Math.max(response.absorption() * .78, response.destruction());
            int seamColor = mix(healthy, twins || response.destruction() < response.absorption() ? 0xFFFFFF : 0xFFE08A, flare);
            int tint = mix(p.fill(), healthy, .35);

            int corners = perimeter.length / 3;
            double[][] corner = new double[corners][], cornerDir = new double[corners][];
            for (int k = 0; k < corners; k++) {
                cornerDir[k] = f.world(new double[]{perimeter[k * 3], perimeter[k * 3 + 1], perimeter[k * 3 + 2]});
                corner[k] = f.point(cornerDir[k], lift);
            }
            double[] hub = f.point(normal, lift);

            // Pane: darker in the middle, brighter at the rim, with the sheen of the circling light.
            double base = light * (1 + .6 * f.fresnel(normal)) + response.absorption() * .9 + response.destruction();
            double hubSheen = sheen(normal, sun, f.view(hub));
            double hubAlpha = (base * 22 + hubSheen * 150 * light) * vis;
            int hubColor = mix(tint, 0xFFFFFF, hubSheen * .7);
            for (int k = 0; k < corners; k++) {
                int next = (k + 1) % corners;
                double sheenA = sheen(cornerDir[k], sun, f.view(corner[k])), sheenB = sheen(cornerDir[next], sun, f.view(corner[next]));
                double rimA = (base * 58 + sheenA * 150 * light) * vis, rimB = (base * 58 + sheenB * 150 * light) * vis;
                if (hubAlpha + rimA + rimB < 1.5) continue;
                vertex(fill, f.matrix(), hub, hubColor, hubAlpha);
                vertex(fill, f.matrix(), corner[k], mix(mix(tint, healthy, .3), 0xFFFFFF, sheenA * .7), rimA);
                vertex(fill, f.matrix(), corner[next], mix(mix(tint, healthy, .3), 0xFFFFFF, sheenB * .7), rimB);
            }
            // Seam: every cell traces its own edge and its neighbour traces the same line, so together
            // they read as one bright seam; the rim of a hole stays half as bright.
            double seamAlpha = (light * 130 + response.absorption() * 90 + response.destruction() * 90) * vis;
            if (seamAlpha >= 1) for (int k = 0; k < corners; k++) {
                double[] a = corner[k], b = corner[(k + 1) % corners];
                line(glow, f, a, b, seam, mix(seamColor, 0xFFFFFF, .45), seamAlpha * .75);
                if (!low) line(glow, f, a, b, seam * 3.6, seamColor, seamAlpha * .22);
            }
        }
    }

    /** Glassy highlight: how closely the light, mirrored in a pane facing {@code normal}, points at the viewer. */
    private static double sheen(double[] normal, double[] light, double[] view) {
        double d = 2 * (normal[0] * light[0] + normal[1] * light[1] + normal[2] * light[2]);
        double rx = normal[0] * d - light[0], ry = normal[1] * d - light[1], rz = normal[2] * d - light[2];
        double facing = rx * view[0] + ry * view[1] + rz * view[2];
        return facing <= 0 ? 0 : Math.pow(facing, 18);
    }

    /** Holographic data dust drifting just outside the RF cells. */
    private static void dust(VertexConsumer glow, Frame f, int count, double time, double presence, Palette p) {
        for (int k = 0; k < count; k++) {
            double h1 = frac(Math.sin(k * 12.9898) * 43758.5453), h2 = frac(Math.sin(k * 78.233) * 12543.123), h3 = frac(Math.sin(k * 3.17) * 9711.7);
            double theta = Math.acos(1 - 2 * h1), phi = h2 * Math.PI * 2 + time * .004 * (h3 - .5);
            double[] dir = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
            double[] at = f.point(dir, .04 + .1 * h3);
            double alpha = presence * (50 + 90 * (.5 + .5 * Math.sin(time * .17 + h1 * 30))) * f.visibility(dir);
            dot(glow, f, at, f.radius() * .009, p.node(), alpha * 1.2);
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

    /** Streams of energy spiralling up the dome, tapered at both ends, each with a bright head running along it. */
    private static void swirls(VertexConsumer glow, Frame f, int count, int segments, double time, double presence, Palette p) {
        for (int k = 0; k < count; k++) {
            double phase = k * 2.399963 + .7, spin = k % 2 == 0 ? 1 : -1;
            double head = frac(time * .011 + k * .37);
            double[] prev = null;
            double prevWidth = 0, prevAlpha = 0;
            int prevColor = p.bright();
            for (int s = 0; s <= segments; s++) {
                double u = s / (double) segments;
                double theta = Math.PI * (.92 - .8 * u), phi = phase + spin * (u * 2.5 + time * .014);
                double[] dir = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
                double[] at = f.point(dir, .006);
                double body = Math.pow(Math.sin(Math.PI * u), 1.2);
                double pulse = Math.max(0, 1 - Math.abs(u - head) / .1);
                double alpha = presence * body * (38 + 150 * pulse) * f.visibility(dir) * 1.4;
                double width = f.radius() * (.017 + .017 * pulse) * Math.pow(Math.sin(Math.PI * u), .7);
                int color = mix(p.bright(), 0xFFFFFF, pulse * .5);
                if (prev != null) line(glow, f, prev, at, prevWidth, width, prevColor, color, prevAlpha, alpha);
                prev = at;
                prevWidth = width;
                prevAlpha = alpha;
                prevColor = color;
            }
        }
    }

    /** A wavy line of light circling the dome just above its equator. */
    private static void waveBand(VertexConsumer glow, Frame f, int segments, double time, double presence, Palette p) {
        double[] prev = null;
        for (int s = 0; s <= segments; s++) {
            double phi = Math.PI * 2 * s / segments;
            double theta = Math.PI / 2 - .22 + .08 * Math.sin(3 * phi + time * .08) + .045 * Math.sin(7 * phi - time * .05);
            double[] dir = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
            double[] at = f.point(dir, .008);
            if (prev != null) {
                double alpha = presence * 70 * f.visibility(dir);
                line(glow, f, prev, at, f.radius() * .016, mix(p.bright(), 0xFFFFFF, .2), alpha * 1.4);
                line(glow, f, prev, at, f.radius() * .045, p.bright(), alpha * .35);
            }
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
            dot(glow, f, at, f.radius() * (.011 + .009 * twinkle), p.node(), alpha * 1.25);
        }
    }

    // --- primitives --------------------------------------------------------------------------------

    private static void line(VertexConsumer consumer, Frame f, double[] a, double[] b, double width, int color, double alpha) {
        line(consumer, f, a, b, width, width, color, color, alpha, alpha);
    }

    /**
     * A soft line of light facing the viewer: full brightness along a→b fading to nothing at a
     * half-width of {@code widthA}/{@code widthB} on either side. A line narrower than a pixel is drawn
     * a pixel wide at proportionally lower brightness, so it neither breaks up nor shimmers.
     */
    private static void line(VertexConsumer consumer, Frame f, double[] a, double[] b, double widthA, double widthB,
            int colorA, int colorB, double alphaA, double alphaB) {
        if (alphaA < 1 && alphaB < 1) return;
        double pixelA = f.pixel(a) * 1.2, pixelB = f.pixel(b) * 1.2;
        if (widthA < pixelA) {
            alphaA *= widthA / pixelA;
            widthA = pixelA;
        }
        if (widthB < pixelB) {
            alphaB *= widthB / pixelB;
            widthB = pixelB;
        }
        if (alphaA < 1 && alphaB < 1) return;
        double[] mid = {(a[0] + b[0]) * .5, (a[1] + b[1]) * .5, (a[2] + b[2]) * .5};
        double[] view = f.view(mid);
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double sx = dy * view[2] - dz * view[1], sy = dz * view[0] - dx * view[2], sz = dx * view[1] - dy * view[0];
        double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1e-12) return;
        sx /= length;
        sy /= length;
        sz /= length;
        double[] al = {a[0] + sx * widthA, a[1] + sy * widthA, a[2] + sz * widthA}, ar = {a[0] - sx * widthA, a[1] - sy * widthA, a[2] - sz * widthA};
        double[] bl = {b[0] + sx * widthB, b[1] + sy * widthB, b[2] + sz * widthB}, br = {b[0] - sx * widthB, b[1] - sy * widthB, b[2] - sz * widthB};
        Matrix4f m = f.matrix();
        vertex(consumer, m, al, colorA, 0);
        vertex(consumer, m, a, colorA, alphaA);
        vertex(consumer, m, b, colorB, alphaB);
        vertex(consumer, m, al, colorA, 0);
        vertex(consumer, m, b, colorB, alphaB);
        vertex(consumer, m, bl, colorB, 0);
        vertex(consumer, m, a, colorA, alphaA);
        vertex(consumer, m, ar, colorA, 0);
        vertex(consumer, m, br, colorB, 0);
        vertex(consumer, m, a, colorA, alphaA);
        vertex(consumer, m, br, colorB, 0);
        vertex(consumer, m, b, colorB, alphaB);
    }

    /** A round point of light facing the viewer: a hot white-ish centre fading out to its rim. */
    private static void dot(VertexConsumer consumer, Frame f, double[] at, double size, int color, double alpha) {
        if (alpha < 1) return;
        double least = f.pixel(at) * 1.6;
        if (size < least) {
            alpha *= size / least;
            size = least;
            if (alpha < 1) return;
        }
        double[] view = f.view(at);
        double[] up = Math.abs(view[1]) > .95 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] right = normalize(view[1] * up[2] - view[2] * up[1], view[2] * up[0] - view[0] * up[2], view[0] * up[1] - view[1] * up[0]);
        double[] top = {right[1] * view[2] - right[2] * view[1], right[2] * view[0] - right[0] * view[2], right[0] * view[1] - right[1] * view[0]};
        int hot = mix(color, 0xFFFFFF, .45);
        Matrix4f m = f.matrix();
        double[] previous = null;
        for (int k = 0; k <= DOT_SIDES; k++) {
            double c = COS[k % DOT_SIDES] * size, s = SIN[k % DOT_SIDES] * size;
            double[] rim = {at[0] + right[0] * c + top[0] * s, at[1] + right[1] * c + top[1] * s, at[2] + right[2] * c + top[2] * s};
            if (previous != null) {
                vertex(consumer, m, at, hot, alpha);
                vertex(consumer, m, previous, color, 0);
                vertex(consumer, m, rim, color, 0);
            }
            previous = rim;
        }
    }

    private static final int DOT_SIDES = 10;
    private static final double[] COS = new double[DOT_SIDES], SIN = new double[DOT_SIDES];

    static {
        for (int k = 0; k < DOT_SIDES; k++) {
            COS[k] = Math.cos(Math.PI * 2 * k / DOT_SIDES);
            SIN[k] = Math.sin(Math.PI * 2 * k / DOT_SIDES);
        }
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
