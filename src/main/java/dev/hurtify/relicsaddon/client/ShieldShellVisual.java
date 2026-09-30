package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.relic.RelicRole;
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
 *
 * <p>Rendering is single-threaded, so the per-vertex work of a shell goes through static primitive
 * scratch buffers (dome vertices, cell corners, the response sampler) instead of arrays per vertex.
 */
final class ShieldShellVisual {
    record Palette(int fill, int deep, int bright, int edge, int node) { }

    static final Palette RF = new Palette(0x0C3442, 0x0A4452, 0x5FF6FF, 0x3FE8FF, 0xD2FCFF);
    static final Palette MANA = new Palette(0x0B4E52, 0x0B5E62, 0x4FF2DA, 0x6FF6E4, 0xE0FFF8);
    static final Palette TWINS_DOME = new Palette(0x1E0B33, 0x2A0F45, 0xC07BFF, 0xD7A6FF, 0xF6E8FF);
    static final Palette TWINS_CELLS = new Palette(0x241040, 0x2A0F45, 0xD7A6FF, 0xB55CFF, 0xF6E8FF);

    /** Angular size of one screen pixel, refreshed each frame from the field of view and window height. */
    private static double pixelAngle = .0011;

    /** Threat and impact light of the shell being drawn, prepared once per shell. */
    private static final ShieldResponse.Sampler RESPONSE = new ShieldResponse.Sampler();
    /** Dome vertices of the shell being drawn: positions (x, y, z triples), colours and alphas by mesh vertex. */
    private static double[] domePos = new double[3 * ShieldGeometry.DOME_HIGH.vertices().length];
    private static int[] domeColor = new int[ShieldGeometry.DOME_HIGH.vertices().length];
    private static double[] domeAlpha = new double[ShieldGeometry.DOME_HIGH.vertices().length];
    /** Corners of the honeycomb cell being drawn: unit directions and shell points (x, y, z triples). */
    private static final int MAX_CORNERS = 8;
    private static final double[] CORNER_DIR = new double[3 * MAX_CORNERS], CORNER = new double[3 * MAX_CORNERS];
    private static final double[] CORNER_SHEEN = new double[MAX_CORNERS];
    /** The unit vector towards the viewer last asked of {@link #view}. */
    private static final double[] VIEW = new double[3];

    static void setPixelAngle(double radians) {
        if (Double.isFinite(radians) && radians > 0) pixelAngle = radians;
    }

    /**
     * Everything a layer needs to place itself: shell centre, radius, yaw and eye (from the centre towards
     * the viewer). In the world the camera sits at the origin of these coordinates ({@code perspective});
     * the flat galleries look along {@code eye} instead.
     */
    record Frame(Matrix4f matrix, double x, double y, double z, double radius, double fx, double fz, Vec3 eye, boolean perspective) {
        double worldX(double lx, double lz) {
            return -lx * fz + lz * fx;
        }

        double worldZ(double lx, double lz) {
            return lx * fx + lz * fz;
        }

        /** Distance from the centre to the shell at a unit direction, {@code lift} above the (rippling) surface. */
        double reach(double dx, double dy, double dz, double lift) {
            return radius * (1 + lift) * ShieldRipple.scale(dx, dy, dz);
        }

        double visibility(double dx, double dy, double dz) {
            return ShieldSurfaceLighting.visibility(dx, dy, dz, eye);
        }

        double fresnel(double dx, double dy, double dz) {
            if (ShieldSurfaceLighting.inside(eye)) return .25;
            return Math.pow(1 - Math.abs(dx * eye.x + dy * eye.y + dz * eye.z), 2.2);
        }

        /** World size of one screen pixel at a point; zero in the flat galleries, where shells are drawn large. */
        double pixel(double px, double py, double pz) {
            return perspective ? Math.sqrt(px * px + py * py + pz * pz) * pixelAngle : 0;
        }
    }

    /** Unit vector from a point towards the viewer, left in {@link #VIEW}. */
    private static void view(Frame f, double px, double py, double pz) {
        if (!f.perspective()) {
            Vec3 eye = f.eye();
            if (eye.lengthSqr() > 1e-8) {
                VIEW[0] = eye.x;
                VIEW[1] = eye.y;
                VIEW[2] = eye.z;
            } else {
                VIEW[0] = 0;
                VIEW[1] = 0;
                VIEW[2] = 1;
            }
            return;
        }
        normalize(VIEW, -px, -py, -pz);
    }

    /** Draws the shell for {@code role} into the translucent {@code fill} and additive {@code glow}; {@code presence} is 0..1 (idle glow up to full combat). */
    static void render(RelicRole role, VertexConsumer fill, VertexConsumer glow, Frame frame, ShieldStackState state, List<ShieldImpact> impacts,
            List<ShieldResponse.Threat> threats, double time, double presence, boolean low) {
        RESPONSE.reset(threats, impacts, time);
        switch (role) {
            case RF_SHIELD -> {
                hexCells(fill, glow, frame, state, impacts, time, presence, RF, false, low);
                dust(glow, frame, low ? 24 : 64, time, presence, RF);
            }
            case MANA_SHIELD -> {
                dome(fill, frame, low ? ShieldGeometry.DOME_LOW : ShieldGeometry.DOME_HIGH, state, presence, MANA);
                swirls(glow, frame, low ? 3 : 6, low ? 24 : 56, time, presence, MANA);
                waveBand(glow, frame, low ? 64 : 144, time, presence, MANA);
                sparkles(glow, frame, low ? 20 : 56, time, presence, MANA);
            }
            case TWINS_SHIELD -> {
                dome(fill, frame, low ? ShieldGeometry.DOME_LOW : ShieldGeometry.DOME_HIGH, state, presence * .85, TWINS_DOME);
                hexCells(fill, glow, frame, state, impacts, time, presence, TWINS_CELLS, true, low);
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
            double time, double presence, Palette p, boolean twins, boolean low) {
        int averageHp = (int) Math.ceil(state.totalIntegrity() / (double) ShieldTopology.CELL_COUNT);
        double sunX, sunY, sunZ;
        {
            double[] sun = VIEW;
            normalize(sun, Math.cos(time * .011), .62, Math.sin(time * .011));
            sunX = sun[0];
            sunY = sun[1];
            sunZ = sun[2];
        }
        double seam = f.radius() * (low ? .010 : .0068);
        // Twins panes sit a hair above their glass dome.
        double lift = twins ? .004 : 0;
        Matrix4f m = f.matrix();
        for (ShieldHoneycomb.Cell cell : ShieldHoneycomb.CELLS) {
            // Drawn cells are even hexagons; health, holes and motion come from the gameplay cell beneath.
            int id = cell.gameplay();
            int integrity = state.cellHp(id);
            boolean moving = state.moving(id, time);
            Quaternionf rotation = moving ? ShieldCellVisual.relocation(state, id, time) : null;
            float[] center = moving ? ShieldCellVisual.transform(cell.center(), rotation) : cell.center();
            float[] perimeter = moving ? ShieldCellVisual.transform(cell.perimeter(), rotation) : cell.perimeter();
            double nx = f.worldX(center[0], center[2]), ny = center[1], nz = f.worldZ(center[0], center[2]);
            int visual = integrity == 0 && state.sharedBuffer() > 0 ? 1 : integrity;
            RESPONSE.sample(nx, ny, nz, visual);
            double absorption = RESPONSE.absorption, destruction = RESPONSE.destruction;
            if (visual == 0 && (destruction < .008 || !ShieldCellVisual.justBroken(impacts, id, f.fx(), f.fz()))) continue;
            double light = Math.max(RESPONSE.presence, presence * (twins ? .38 : .42));
            if (integrity > 0 && moving) light = Math.max(light, .65);
            double vis = f.visibility(nx, ny, nz);
            if (light * vis < .008) continue;

            int hp = Math.min(averageHp, visual);
            int healthy = twins ? ShieldCellVisual.dim(p.edge(), hp) : ShieldCellVisual.warm(p.edge(), hp);
            double flare = Math.max(absorption * .78, destruction);
            int seamColor = mix(healthy, twins || destruction < absorption ? 0xFFFFFF : 0xFFE08A, flare);
            int tint = mix(p.fill(), healthy, .35);

            int corners = perimeter.length / 3;
            for (int k = 0; k < corners; k++) {
                double dx = f.worldX(perimeter[k * 3], perimeter[k * 3 + 2]), dy = perimeter[k * 3 + 1], dz = f.worldZ(perimeter[k * 3], perimeter[k * 3 + 2]);
                CORNER_DIR[k * 3] = dx;
                CORNER_DIR[k * 3 + 1] = dy;
                CORNER_DIR[k * 3 + 2] = dz;
                double r = f.reach(dx, dy, dz, lift);
                CORNER[k * 3] = f.x() + dx * r;
                CORNER[k * 3 + 1] = f.y() + dy * r;
                CORNER[k * 3 + 2] = f.z() + dz * r;
            }
            double hubReach = f.reach(nx, ny, nz, lift);
            double hubX = f.x() + nx * hubReach, hubY = f.y() + ny * hubReach, hubZ = f.z() + nz * hubReach;

            // Pane: darker in the middle, brighter at the rim, with the sheen of the circling light.
            double base = light * (1 + .6 * f.fresnel(nx, ny, nz)) + absorption * .9 + destruction;
            view(f, hubX, hubY, hubZ);
            double hubSheen = sheen(nx, ny, nz, sunX, sunY, sunZ, VIEW[0], VIEW[1], VIEW[2]);
            double hubAlpha = (base * 22 + hubSheen * 150 * light) * vis;
            int hubColor = mix(tint, 0xFFFFFF, hubSheen * .7);
            for (int k = 0; k < corners; k++) {
                view(f, CORNER[k * 3], CORNER[k * 3 + 1], CORNER[k * 3 + 2]);
                CORNER_SHEEN[k] = sheen(CORNER_DIR[k * 3], CORNER_DIR[k * 3 + 1], CORNER_DIR[k * 3 + 2], sunX, sunY, sunZ, VIEW[0], VIEW[1], VIEW[2]);
            }
            int rimTint = mix(tint, healthy, .3);
            for (int k = 0; k < corners; k++) {
                int next = (k + 1) % corners;
                double sheenA = CORNER_SHEEN[k], sheenB = CORNER_SHEEN[next];
                double rimA = (base * 58 + sheenA * 150 * light) * vis, rimB = (base * 58 + sheenB * 150 * light) * vis;
                if (hubAlpha + rimA + rimB < 1.5) continue;
                vertex(fill, m, hubX, hubY, hubZ, hubColor, hubAlpha);
                vertex(fill, m, CORNER[k * 3], CORNER[k * 3 + 1], CORNER[k * 3 + 2], mix(rimTint, 0xFFFFFF, sheenA * .7), rimA);
                vertex(fill, m, CORNER[next * 3], CORNER[next * 3 + 1], CORNER[next * 3 + 2], mix(rimTint, 0xFFFFFF, sheenB * .7), rimB);
            }
            // Seam: every cell traces its own edge and its neighbour traces the same line, so together
            // they read as one bright seam; the rim of a hole stays half as bright.
            double seamAlpha = (light * 130 + absorption * 90 + destruction * 90) * vis;
            if (seamAlpha >= 1) for (int k = 0; k < corners; k++) {
                int next = (k + 1) % corners;
                double ax = CORNER[k * 3], ay = CORNER[k * 3 + 1], az = CORNER[k * 3 + 2];
                double bx = CORNER[next * 3], by = CORNER[next * 3 + 1], bz = CORNER[next * 3 + 2];
                line(glow, f, ax, ay, az, bx, by, bz, seam, mix(seamColor, 0xFFFFFF, .45), seamAlpha * .75);
                if (!low) line(glow, f, ax, ay, az, bx, by, bz, seam * 3.6, seamColor, seamAlpha * .22);
            }
        }
    }

    /** Glassy highlight: how closely the light, mirrored in a pane facing the normal, points at the viewer. */
    private static double sheen(double nx, double ny, double nz, double lx, double ly, double lz, double vx, double vy, double vz) {
        double d = 2 * (nx * lx + ny * ly + nz * lz);
        double rx = nx * d - lx, ry = ny * d - ly, rz = nz * d - lz;
        double facing = rx * vx + ry * vy + rz * vz;
        return facing <= 0 ? 0 : Math.pow(facing, 18);
    }

    /** Holographic data dust drifting just outside the RF cells. */
    private static void dust(VertexConsumer glow, Frame f, int count, double time, double presence, Palette p) {
        for (int k = 0; k < count; k++) {
            double h1 = frac(Math.sin(k * 12.9898) * 43758.5453), h2 = frac(Math.sin(k * 78.233) * 12543.123), h3 = frac(Math.sin(k * 3.17) * 9711.7);
            double theta = Math.acos(1 - 2 * h1), phi = h2 * Math.PI * 2 + time * .004 * (h3 - .5);
            double dx = Math.sin(theta) * Math.cos(phi), dy = Math.cos(theta), dz = Math.sin(theta) * Math.sin(phi);
            double r = f.reach(dx, dy, dz, .04 + .1 * h3);
            double alpha = presence * (50 + 90 * (.5 + .5 * Math.sin(time * .17 + h1 * 30))) * f.visibility(dx, dy, dz);
            dot(glow, f, f.x() + dx * r, f.y() + dy * r, f.z() + dz * r, f.radius() * .009, p.node(), alpha * 1.2);
        }
    }

    // --- glass dome (Mana, Twins) ---------------------------------------------------------------

    private static void dome(VertexConsumer fill, Frame f, ShieldGeometry.Mesh mesh, ShieldStackState state, double presence, Palette p) {
        double[][] vertices = mesh.vertices();
        int[] vertexCell = mesh.vertexCell();
        int n = vertices.length;
        if (domeColor.length < n) {
            domePos = new double[3 * n];
            domeColor = new int[n];
            domeAlpha = new double[n];
        }
        double[] pos = domePos;
        int[] color = domeColor;
        double[] alpha = domeAlpha;
        boolean buffer = state.sharedBuffer() > 0;
        boolean rippling = ShieldRipple.active();
        for (int i = 0; i < n; i++) {
            double[] local = vertices[i];
            double dx = f.worldX(local[0], local[2]), dy = local[1], dz = f.worldZ(local[0], local[2]);
            // The waves are summed once per vertex: they bend the surface and light the crest.
            double ripple = rippling ? ShieldRipple.height(dx, dy, dz) : 0;
            double r = f.radius() * (rippling ? ShieldRipple.scaleOf(ripple) : 1);
            pos[i * 3] = f.x() + dx * r;
            pos[i * 3 + 1] = f.y() + dy * r;
            pos[i * 3 + 2] = f.z() + dz * r;
            boolean alive = buffer || state.cellHp(vertexCell[i]) > 0;
            RESPONSE.sample(dx, dy, dz, alive ? 1 : 0);
            double fres = f.fresnel(dx, dy, dz);
            double flare = Math.max(RESPONSE.absorption, RESPONSE.destruction);
            double a = presence * (9 + 80 * fres) * (alive ? 1 : 0) + flare * 95 + Math.abs(ripple) * 45 * presence;
            alpha[i] = Math.clamp(a * f.visibility(dx, dy, dz), 0, 185);
            color[i] = mix(mix(p.deep(), p.bright(), Math.min(1, fres * .85 + Math.max(0, ripple) * .35)), 0xFFFFFF, flare * .55);
        }
        Matrix4f m = f.matrix();
        for (int[] tri : mesh.triangles()) {
            int a = tri[0], b = tri[1], c = tri[2];
            if (alpha[a] + alpha[b] + alpha[c] < 1.5) continue;
            vertex(fill, m, pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2], color[a], alpha[a]);
            vertex(fill, m, pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2], color[b], alpha[b]);
            vertex(fill, m, pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2], color[c], alpha[c]);
        }
    }

    /** Streams of energy spiralling up the dome, tapered at both ends, each with a bright head running along it. */
    private static void swirls(VertexConsumer glow, Frame f, int count, int segments, double time, double presence, Palette p) {
        for (int k = 0; k < count; k++) {
            double phase = k * 2.399963 + .7, spin = k % 2 == 0 ? 1 : -1;
            double head = frac(time * .011 + k * .37);
            boolean started = false;
            double prevX = 0, prevY = 0, prevZ = 0;
            double prevWidth = 0, prevAlpha = 0;
            int prevColor = p.bright();
            for (int s = 0; s <= segments; s++) {
                double u = s / (double) segments;
                double theta = Math.PI * (.92 - .8 * u), phi = phase + spin * (u * 2.5 + time * .014);
                double dx = Math.sin(theta) * Math.cos(phi), dy = Math.cos(theta), dz = Math.sin(theta) * Math.sin(phi);
                double r = f.reach(dx, dy, dz, .006);
                double atX = f.x() + dx * r, atY = f.y() + dy * r, atZ = f.z() + dz * r;
                double body = Math.pow(Math.sin(Math.PI * u), 1.2);
                double pulse = Math.max(0, 1 - Math.abs(u - head) / .1);
                double alpha = presence * body * (38 + 150 * pulse) * f.visibility(dx, dy, dz) * 1.4;
                double width = f.radius() * (.017 + .017 * pulse) * Math.pow(Math.sin(Math.PI * u), .7);
                int color = mix(p.bright(), 0xFFFFFF, pulse * .5);
                if (started) line(glow, f, prevX, prevY, prevZ, atX, atY, atZ, prevWidth, width, prevColor, color, prevAlpha, alpha);
                started = true;
                prevX = atX;
                prevY = atY;
                prevZ = atZ;
                prevWidth = width;
                prevAlpha = alpha;
                prevColor = color;
            }
        }
    }

    /** A wavy line of light circling the dome just above its equator. */
    private static void waveBand(VertexConsumer glow, Frame f, int segments, double time, double presence, Palette p) {
        boolean started = false;
        double prevX = 0, prevY = 0, prevZ = 0;
        for (int s = 0; s <= segments; s++) {
            double phi = Math.PI * 2 * s / segments;
            double theta = Math.PI / 2 - .22 + .08 * Math.sin(3 * phi + time * .08) + .045 * Math.sin(7 * phi - time * .05);
            double dx = Math.sin(theta) * Math.cos(phi), dy = Math.cos(theta), dz = Math.sin(theta) * Math.sin(phi);
            double r = f.reach(dx, dy, dz, .008);
            double atX = f.x() + dx * r, atY = f.y() + dy * r, atZ = f.z() + dz * r;
            if (started) {
                double alpha = presence * 70 * f.visibility(dx, dy, dz);
                line(glow, f, prevX, prevY, prevZ, atX, atY, atZ, f.radius() * .016, mix(p.bright(), 0xFFFFFF, .2), alpha * 1.4);
                line(glow, f, prevX, prevY, prevZ, atX, atY, atZ, f.radius() * .045, p.bright(), alpha * .35);
            }
            started = true;
            prevX = atX;
            prevY = atY;
            prevZ = atZ;
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
            double dx = Math.cos(phi) * ring, dy = y, dz = Math.sin(phi) * ring;
            double r = f.reach(dx, dy, dz, 0);
            double twinkle = .5 + .5 * Math.sin(time * .3 + h3 * 40);
            double alpha = presence * Math.sin(Math.PI * rise) * (70 + 150 * twinkle) * (ShieldSurfaceLighting.inside(f.eye()) ? ShieldSurfaceLighting.INSIDE : 1);
            dot(glow, f, f.x() + dx * r, f.y() + dy * r, f.z() + dz * r, f.radius() * (.011 + .009 * twinkle), p.node(), alpha * 1.25);
        }
    }

    // --- primitives --------------------------------------------------------------------------------

    private static void line(VertexConsumer consumer, Frame f, double ax, double ay, double az, double bx, double by, double bz,
            double width, int color, double alpha) {
        line(consumer, f, ax, ay, az, bx, by, bz, width, width, color, color, alpha, alpha);
    }

    /**
     * A soft line of light facing the viewer: full brightness along a→b fading to nothing at a
     * half-width of {@code widthA}/{@code widthB} on either side. A line narrower than a pixel is drawn
     * a pixel wide at proportionally lower brightness, so it neither breaks up nor shimmers.
     */
    private static void line(VertexConsumer consumer, Frame f, double ax, double ay, double az, double bx, double by, double bz,
            double widthA, double widthB, int colorA, int colorB, double alphaA, double alphaB) {
        if (alphaA < 1 && alphaB < 1) return;
        double pixelA = f.pixel(ax, ay, az) * 1.2, pixelB = f.pixel(bx, by, bz) * 1.2;
        if (widthA < pixelA) {
            alphaA *= widthA / pixelA;
            widthA = pixelA;
        }
        if (widthB < pixelB) {
            alphaB *= widthB / pixelB;
            widthB = pixelB;
        }
        if (alphaA < 1 && alphaB < 1) return;
        view(f, (ax + bx) * .5, (ay + by) * .5, (az + bz) * .5);
        double vx = VIEW[0], vy = VIEW[1], vz = VIEW[2];
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
        double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1e-12) return;
        sx /= length;
        sy /= length;
        sz /= length;
        double alx = ax + sx * widthA, aly = ay + sy * widthA, alz = az + sz * widthA;
        double arx = ax - sx * widthA, ary = ay - sy * widthA, arz = az - sz * widthA;
        double blx = bx + sx * widthB, bly = by + sy * widthB, blz = bz + sz * widthB;
        double brx = bx - sx * widthB, bry = by - sy * widthB, brz = bz - sz * widthB;
        Matrix4f m = f.matrix();
        vertex(consumer, m, alx, aly, alz, colorA, 0);
        vertex(consumer, m, ax, ay, az, colorA, alphaA);
        vertex(consumer, m, bx, by, bz, colorB, alphaB);
        vertex(consumer, m, alx, aly, alz, colorA, 0);
        vertex(consumer, m, bx, by, bz, colorB, alphaB);
        vertex(consumer, m, blx, bly, blz, colorB, 0);
        vertex(consumer, m, ax, ay, az, colorA, alphaA);
        vertex(consumer, m, arx, ary, arz, colorA, 0);
        vertex(consumer, m, brx, bry, brz, colorB, 0);
        vertex(consumer, m, ax, ay, az, colorA, alphaA);
        vertex(consumer, m, brx, bry, brz, colorB, 0);
        vertex(consumer, m, bx, by, bz, colorB, alphaB);
    }

    /** A round point of light facing the viewer: a hot white-ish centre fading out to its rim. */
    private static void dot(VertexConsumer consumer, Frame f, double atX, double atY, double atZ, double size, int color, double alpha) {
        if (alpha < 1) return;
        double least = f.pixel(atX, atY, atZ) * 1.6;
        if (size < least) {
            alpha *= size / least;
            size = least;
            if (alpha < 1) return;
        }
        view(f, atX, atY, atZ);
        double vx = VIEW[0], vy = VIEW[1], vz = VIEW[2];
        double ux, uy, uz;
        if (Math.abs(vy) > .95) {
            ux = 1;
            uy = 0;
            uz = 0;
        } else {
            ux = 0;
            uy = 1;
            uz = 0;
        }
        double[] right = VIEW;
        normalize(right, vy * uz - vz * uy, vz * ux - vx * uz, vx * uy - vy * ux);
        double rx = right[0], ry = right[1], rz = right[2];
        double tx = ry * vz - rz * vy, ty = rz * vx - rx * vz, tz = rx * vy - ry * vx;
        int hot = mix(color, 0xFFFFFF, .45);
        Matrix4f m = f.matrix();
        boolean started = false;
        double prevX = 0, prevY = 0, prevZ = 0;
        for (int k = 0; k <= DOT_SIDES; k++) {
            double c = COS[k % DOT_SIDES] * size, s = SIN[k % DOT_SIDES] * size;
            double rimX = atX + rx * c + tx * s, rimY = atY + ry * c + ty * s, rimZ = atZ + rz * c + tz * s;
            if (started) {
                vertex(consumer, m, atX, atY, atZ, hot, alpha);
                vertex(consumer, m, prevX, prevY, prevZ, color, 0);
                vertex(consumer, m, rimX, rimY, rimZ, color, 0);
            }
            started = true;
            prevX = rimX;
            prevY = rimY;
            prevZ = rimZ;
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

    private static void vertex(VertexConsumer consumer, Matrix4f m, double x, double y, double z, int color, double alpha) {
        consumer.addVertex(m, (float) x, (float) y, (float) z)
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, (int) Math.clamp(alpha, 0, 255));
    }

    /** Normalises (x, y, z) into {@code out}; straight up when the vector is too short. */
    private static void normalize(double[] out, double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-12) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
        } else {
            out[0] = x / length;
            out[1] = y / length;
            out[2] = z / length;
        }
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
