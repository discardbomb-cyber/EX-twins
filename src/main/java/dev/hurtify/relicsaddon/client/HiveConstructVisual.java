package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.HiveConstructs;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.drone.HiveType;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The light of the containment constructs, drawn from drone to drone: every line joins two drones and goes
 * out when either is away, so the drones are the constructs' corners. A scene here is the part of a swarm
 * round one creature ({@link HiveModeVisual#part}), its drones numbered as {@link HiveConstructs} lays them out.
 * <ul>
 *   <li>RF, the Faraday cage: nearly clear panels, conductors along the edges with blue and orange currents
 *   running down them, and sparks at the joints.</li>
 *   <li>Mana, the lotus: glass petals, teal at the heart and gold at the rim, with golden motes running up
 *   their edges to the drones as the drones charge the ward.</li>
 *   <li>Twins, the rift: hexagonal shards of space with the void and its stars showing through them, cracks
 *   running out from the creature to them, and the world round it bent and darkened.</li>
 * </ul>
 */
final class HiveConstructVisual {
    private static final int ORANGE = 0xFFB347, GOLD = 0xFFD27A, VOID = 0x05010A;

    /** Constructs seen lately, by the creature they hold: where, whose, how big and when last drawn. */
    private record Held(Vec3 core, dev.hurtify.relicsaddon.drone.HiveType type, double room, double seen) { }
    private static final java.util.Map<Integer, Held> HELD = new java.util.HashMap<>();

    /**
     * Constructs that were drawn a moment ago and are not now came apart: each bursts into its drones with a
     * spray of sparks. Called once a frame, after the scenes.
     */
    static void sweep(double time) {
        for (var iterator = HELD.entrySet().iterator(); iterator.hasNext(); ) {
            Held held = iterator.next().getValue();
            if (time - held.seen < 1.5 && time >= held.seen) continue;
            if (time - held.seen < 20 && time >= held.seen) {
                HiveJuice.impact(held.core, new Vec3(0, 1, 0), held.type, HiveJuice.CHARGE, held.seen, Math.max(.8, held.room), held.core.hashCode());
            }
            iterator.remove();
        }
    }

    static void clear() {
        HELD.clear();
    }

    static void render(HiveModeVisual.Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        double room = HiveFormation.enclosure(s.width(), s.height()), age = HiveFormation.constructAge(s.time(), s.cycleStart());
        Vec3 middle = HiveFormation.core(s.target(), s.height());
        HELD.put(s.targets().getFirst().id(), new Held(middle, s.type(), room, s.time()));
        if (!GlowBrush.flat()) HiveLoopSounds.hum(s.targets().getFirst().id(), s.type(), middle, s.slots(), s.time());
        // It locks shut with a flash.
        double lock = age - HiveConstructs.CLOSING;
        if (lock >= 0 && lock < 6) {
            Vec3 core = middle.subtract(camera);
            double fade = 1 - lock / 6;
            GlowBrush.dot(glow, m, core, room * (.8 + .6 * lock / 6), GlowBrush.mix(color, 0xFFFFFF, .5), 160 * fade);
            GlowBrush.circle(glow, m, core, new Vec3(1, 0, 0), new Vec3(0, 0, 1), room * (1.1 + .9 * lock / 6), 48, .04, 0xFFFFFF, 200 * fade);
            if (lock < 1) EffectLights.flash(middle, 12, room, 5);
        }
        Vec3[] drones = HiveModeVisual.relative(s, camera);
        switch (s.type()) {
            case RF -> cage(s, drones, camera, glow, fill, m, color);
            case MANA -> lotus(s, drones, camera, glow, fill, m, color);
            case TWINS -> rift(s, drones, camera, glow, fill, m, color);
        }
    }

    /** A drone's place relative to the camera, or null if its place is empty. */
    private static Vec3 at(Vec3[] drones, int slot) {
        return slot >= 0 && slot < drones.length ? drones[slot] : null;
    }

    /** The cage's panels and conductors by its drone count, worked out once ({@link HiveConstructs#cageFaces}, {@link HiveConstructs#cageEdges}). */
    private static final List<int[]>[] CAGE_FACES = new List[HiveType.MAX_DEPLOYED + 1], CAGE_EDGES = new List[HiveType.MAX_DEPLOYED + 1];

    private static List<int[]> cageFaces(int count) {
        if (count < 0 || count > HiveType.MAX_DEPLOYED) return HiveConstructs.cageFaces(count);
        List<int[]> faces = CAGE_FACES[count];
        if (faces == null) CAGE_FACES[count] = faces = HiveConstructs.cageFaces(count);
        return faces;
    }

    private static List<int[]> cageEdges(int count) {
        if (count < 0 || count > HiveType.MAX_DEPLOYED) return HiveConstructs.cageEdges(count);
        List<int[]> edges = CAGE_EDGES[count];
        if (edges == null) CAGE_EDGES[count] = edges = HiveConstructs.cageEdges(count);
        return edges;
    }

    private static double closing(HiveModeVisual.Scene s) {
        return Math.clamp(HiveFormation.constructAge(s.time(), s.cycleStart()) / HiveConstructs.CLOSING, 0, 1);
    }

    // --- RF --------------------------------------------------------------------------------------------

    private static void cage(HiveModeVisual.Scene s, Vec3[] drones, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int count = s.slots();
        double time = s.time(), shut = closing(s);
        // Panels: all but clear, a little brighter at their rims where they catch the eye edge-on.
        for (int[] face : cageFaces(count)) {
            Vec3 a = at(drones, face[0]), b = at(drones, face[1]), c = at(drones, face[2]);
            if (a == null || b == null || c == null) continue;
            double mx = (a.x + b.x + c.x) * (1 / 3.0), my = (a.y + b.y + c.y) * (1 / 3.0), mz = (a.z + b.z + c.z) * (1 / 3.0);
            double px = b.x - a.x, py = b.y - a.y, pz = b.z - a.z, qx = c.x - a.x, qy = c.y - a.y, qz = c.z - a.z;
            double nx = py * qz - pz * qy, ny = pz * qx - px * qz, nz = px * qy - py * qx;
            double edgeOn = nx * nx + ny * ny + nz * nz < 1e-12 ? 0 : 1 - Math.abs(GlowBrush.facing(nx, ny, nz, mx, my, mz));
            double alpha = (8 + 26 * edgeOn * edgeOn) * shut;
            int tint = GlowBrush.mix(0x06222B, color, .25 + .5 * edgeOn);
            GlowBrush.quad(fill, m, a, b, c, c, tint, tint, tint, tint, alpha, alpha, alpha, alpha);
        }
        List<int[]> edges = cageEdges(count);
        for (int index = 0; index < edges.size(); index++) {
            int[] edge = edges.get(index);
            Vec3 a = at(drones, edge[0]), b = at(drones, edge[1]);
            if (a == null || b == null) continue;
            GlowBrush.beam(glow, m, a, b, .016, color, 95 + 40 * shut);
            // Currents run down every third conductor, blue one way and orange the other.
            if (index % 3 == 0) {
                double run = (time * (.045 + .03 * hash(index, 1)) + hash(index, 2)) % 1;
                boolean back = index % 2 == 0;
                GlowBrush.dot(glow, m, back ? b.lerp(a, run) : a.lerp(b, run), .07, back ? ORANGE : 0xBFF6FF, 190 * Math.sin(Math.PI * run));
            }
        }
        // Sparks at the joints, a few at a time.
        long beat = (long) Math.floor(time / 5);
        for (int slot = 0; slot < count; slot++) {
            Vec3 node = at(drones, slot);
            if (node == null) continue;
            GlowBrush.dot(glow, m, node, .05, 0xD8FCFF, 140);
            if (hash(slot, (int) beat) < .06) {
                double flash = 1 - (time / 5 - beat);
                GlowBrush.dot(glow, m, node, .22, GlowBrush.mix(color, 0xFFFFFF, .6), 230 * flash);
            }
        }
    }

    // --- Mana ------------------------------------------------------------------------------------------

    private static void lotus(HiveModeVisual.Scene s, Vec3[] drones, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int count = s.slots(), tiers = HiveConstructs.lotusTiers(count);
        double time = s.time(), shut = closing(s);
        Vec3 base = at(drones, 0);
        if (base == null) return;
        int rim = GlowBrush.mix(color, GOLD, .55);
        for (int tier = 0; tier < tiers; tier++) for (int p = 0; p < HiveConstructs.PETALS; p++) {
            int first = 1 + (tier * HiveConstructs.PETALS + p) * HiveConstructs.PETAL_CORNERS;
            Vec3 left = at(drones, first), right = at(drones, first + 1), tip = at(drones, first + 2);
            if (left == null || right == null || tip == null) continue;
            // Glass: a faint body, teal at the heart and gold towards the tip, brighter edge-on.
            double lx = left.x - base.x, ly = left.y - base.y, lz = left.z - base.z, rx = right.x - base.x, ry = right.y - base.y, rz = right.z - base.z;
            double nx = ly * rz - lz * ry, ny = lz * rx - lx * rz, nz = lx * ry - ly * rx;
            double edgeOn = nx * nx + ny * ny + nz * nz < 1e-12 ? 0 : 1 - Math.abs(GlowBrush.facing(nx, ny, nz, tip.x, tip.y, tip.z));
            double body = (14 + 40 * edgeOn) * (.5 + .5 * shut);
            int heart = GlowBrush.mix(0x0B5E62, color, .4), edge = GlowBrush.mix(color, GOLD, .7);
            double shine = 120 + 30 * Math.sin(time * .08 + p + tier);
            GlowBrush.beam(glow, m, base, left, .016, color, shine * .7);
            GlowBrush.beam(glow, m, base, right, .016, color, shine * .7);
            // Each side from its corner up to the tip bows outwards, so the petal is round, not a triangle.
            for (int which = 0; which < 2; which++) {
                Vec3 side = which == 0 ? left : right;
                double mx = side.x + .5 * (tip.x - side.x), my = side.y + .5 * (tip.y - side.y), mz = side.z + .5 * (tip.z - side.z);
                double ox = mx - base.x, oy = my - my, oz = mz - base.z;
                double bowX = mx, bowY = my, bowZ = mz;
                if (!(ox * ox + oy * oy + oz * oz < 1e-8)) {
                    double length = Math.sqrt(ox * ox + oy * oy + oz * oz);
                    if (length < 1.0E-4) { ox = 0; oy = 0; oz = 0; } else { ox = ox / length; oy = oy / length; oz = oz / length; }
                    double reach = side.distanceTo(tip) * .22;
                    bowX = mx + ox * reach; bowY = my + oy * reach; bowZ = mz + oz * reach;
                } else {
                    bowX = mx + 0.0; bowY = my + 0.0; bowZ = mz + 0.0;
                }
                double px = side.x, py = side.y, pz = side.z;
                for (int step = 1; step <= 6; step++) {
                    double t = step / 6.0, u = 1 - t, near = u * u, between = 2 * u * t, far = t * t;
                    double qx = side.x * near + bowX * between + tip.x * far, qy = side.y * near + bowY * between + tip.y * far, qz = side.z * near + bowZ * between + tip.z * far;
                    GlowBrush.quad(fill, m, base.x, base.y, base.z, px, py, pz, qx, qy, qz, qx, qy, qz, heart, GlowBrush.mix(heart, edge, .5 + .4 * t), edge, edge,
                            body * .6, body, body * 1.3, body * 1.3);
                    GlowBrush.beam(glow, m, px, py, pz, qx, qy, qz, .02, rim, shine);
                    px = qx; py = qy; pz = qz;
                }
            }
            // Golden motes run up the petal's edges to its drones: the drones charging the ward.
            double run = (time * .03 + hash(tier * 7 + p, 3)) % 1, climb = run * run;
            double ex = base.x + run * (left.x - base.x), ey = base.y + run * (left.y - base.y), ez = base.z + run * (left.z - base.z);
            GlowBrush.dot(glow, m, ex + climb * (tip.x - ex), ey + climb * (tip.y - ey), ez + climb * (tip.z - ez), .06, GOLD, 200 * Math.sin(Math.PI * run));
            GlowBrush.dot(glow, m, tip, .09, GlowBrush.mix(GOLD, 0xFFFFFF, .4), 170);
        }
        for (int[] edge : HiveConstructs.lotusEdges(count)) {
            if (edge[0] < 1 + tiers * HiveConstructs.PETALS * HiveConstructs.PETAL_CORNERS) continue;
            Vec3 a = at(drones, edge[0]), b = at(drones, edge[1]);
            if (a != null && b != null) GlowBrush.line(glow, m, a, b, .012, rim, 80);
        }
        GlowBrush.dot(glow, m, base, .2, GOLD, 180);
    }

    // --- Twins -----------------------------------------------------------------------------------------

    /** A shard's corners, relative to the camera, while it is drawn. */
    private static final Vec3[] SHARD = new Vec3[HiveConstructs.SHARD_CORNERS];

    private static void rift(HiveModeVisual.Scene s, Vec3[] drones, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int count = s.slots(), shards = HiveConstructs.shards(count);
        double time = s.time(), open = closing(s);
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        int edge = GlowBrush.mix(color, 0xE7C6FF, .35);
        Vec3[] corners = SHARD;
        for (int shard = 0; shard < shards; shard++) {
            boolean whole = true;
            for (int corner = 0; corner < corners.length; corner++) {
                corners[corner] = at(drones, shard + corner * shards);
                whole &= corners[corner] != null;
            }
            if (!whole) continue;
            double mx = 0, my = 0, mz = 0;
            for (Vec3 corner : corners) { mx = mx + corner.x; my = my + corner.y; mz = mz + corner.z; }
            double share = 1.0 / corners.length;
            mx = mx * share; my = my * share; mz = mz * share;
            // The void behind the shard, and its stars.
            for (int corner = 0; corner < corners.length; corner++) {
                Vec3 a = corners[corner], b = corners[(corner + 1) % corners.length];
                GlowBrush.quad(fill, m, mx, my, mz, a.x, a.y, a.z, b.x, b.y, b.z, b.x, b.y, b.z, VOID, 0x12031F, 0x12031F, 0x12031F, 230 * open, 200 * open, 200 * open, 200 * open);
            }
            Vec3 c0 = corners[0], c2 = corners[2];
            for (int star = 0; star < 9; star++) {
                double u = hash(shard * 31 + star, 5) * 2 - 1, v = hash(shard * 31 + star, 6) * 2 - 1, out = u * .7, across = v * .45;
                double twinkle = .6 + .4 * Math.sin(time * .3 + star * 2.1 + shard);
                GlowBrush.dot(glow, m, mx + (c0.x - mx) * out + (c2.x - mx) * across, my + (c0.y - my) * out + (c2.y - my) * across,
                        mz + (c0.z - mz) * out + (c2.z - mz) * across, .03, star % 3 == 0 ? 0xE7C6FF : 0xFFFFFF, 170 * twinkle * open);
            }
            for (int corner = 0; corner < corners.length; corner++) {
                GlowBrush.beam(glow, m, corners[corner], corners[(corner + 1) % corners.length], .02, edge, 150);
            }
            // Circuit traces run in from the shard's edges, bending at right angles to a pad, a pulse racing along each.
            for (int trace = 0; trace < 3; trace++) {
                int corner = (trace * 2 + shard) % corners.length;
                Vec3 a = corners[corner], b = corners[(corner + 1) % corners.length];
                double fx = a.x + .5 * (b.x - a.x), fy = a.y + .5 * (b.y - a.y), fz = a.z + .5 * (b.z - a.z);
                double in = .3 + .15 * hash(shard * 5 + trace, 7), along = .22 * (hash(shard * 5 + trace, 8) < .5 ? 1 : -1);
                double bx = fx + (mx - fx) * in, by = fy + (my - fy) * in, bz = fz + (mz - fz) * in;
                double px = bx + (b.x - a.x) * along, py = by + (b.y - a.y) * along, pz = bz + (b.z - a.z) * along;
                GlowBrush.line(glow, m, fx, fy, fz, bx, by, bz, .008, 0xFF7BE5, 140 * open);
                GlowBrush.line(glow, m, bx, by, bz, px, py, pz, .008, 0xFF7BE5, 140 * open);
                GlowBrush.dot(glow, m, px, py, pz, .035, 0xFFC6F5, 180 * open);
                double run = (time * .06 + trace * .33 + shard * .17) % 1;
                if (run < .6) {
                    double t = run / .6;
                    GlowBrush.dot(glow, m, fx + t * (bx - fx), fy + t * (by - fy), fz + t * (bz - fz), .04, 0xFFFFFF, 200 * open);
                } else {
                    double t = (run - .6) / .4;
                    GlowBrush.dot(glow, m, bx + t * (px - bx), by + t * (py - by), bz + t * (pz - bz), .04, 0xFFFFFF, 200 * open);
                }
            }
            // A crack from the creature out to the shard, re-forking now and then.
            long flicker = (long) Math.floor(time / 3);
            GlowBrush.lightning(glow, m, core.x, core.y, core.z, mx, my, mz, flicker * 97 + shard * 13L, 6, .12, .01, 0xE7C6FF, 120 * open);
        }
        GlowBrush.dot(glow, m, core, .4 * open, color, 90);
    }

    /** Queues the rift's pull on the world round it: bent and darkened, the nearer the darker. */
    static void lens(HiveModeVisual.Scene s, Vec3 camera) {
        double room = HiveFormation.enclosure(s.width(), s.height()), open = closing(s);
        if (open <= 0) return;
        Vec3 core = HiveFormation.core(s.target(), s.height()).subtract(camera);
        double horizon = room * .55 * open;
        BlackHoleLens.queue(core, horizon, room * (HiveConstructs.RIFT_DISTANCE + HiveConstructs.SHARD_SIZE) * 2.6);
    }

    private static double hash(int index, int salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private HiveConstructVisual() { }
}
