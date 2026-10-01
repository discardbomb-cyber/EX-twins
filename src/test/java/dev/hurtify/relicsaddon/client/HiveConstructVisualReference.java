package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.domain.hive.HiveConstructs;
import dev.hurtify.relicsaddon.domain.hive.HiveFormation;
import dev.hurtify.relicsaddon.domain.hive.HiveShapes;
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
final class HiveConstructVisualReference {
    private static final int ORANGE = 0xFFB347, GOLD = 0xFFD27A, VOID = 0x05010A;

    /** Constructs seen lately, by the creature they hold: where, whose, how big and when last drawn. */
    private record Held(Vec3 core, dev.hurtify.relicsaddon.domain.hive.HiveType type, double room, double seen) { }
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
                HiveJuiceReference.impact(held.core, new Vec3(0, 1, 0), held.type, HiveJuice.CHARGE, held.seen, Math.max(.8, held.room), held.core.hashCode());
            }
            iterator.remove();
        }
    }

    static void clear() {
        HELD.clear();
    }

    static void render(HiveModeVisual.Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        double room = SwarmMath.enclosure(s.width(), s.height()), age = SwarmMath.constructAge(s.time(), s.cycleStart());
        Vec3 middle = SwarmMath.core(s.target(), s.height());
        HELD.put(s.targets().getFirst().id(), new Held(middle, s.type(), room, s.time()));
        if (!GlowBrushReference.flat()) HiveLoopSounds.hum(s.targets().getFirst().id(), s.type(), middle, s.slots(), s.time());
        // It locks shut with a flash.
        double lock = age - HiveConstructs.CLOSING;
        if (lock >= 0 && lock < 6) {
            Vec3 core = middle.subtract(camera);
            double fade = 1 - lock / 6;
            GlowBrushReference.dot(glow, m, core, room * (.8 + .6 * lock / 6), GlowBrushReference.mix(color, 0xFFFFFF, .5), 160 * fade);
            GlowBrushReference.circle(glow, m, core, new Vec3(1, 0, 0), new Vec3(0, 0, 1), room * (1.1 + .9 * lock / 6), 48, .04, 0xFFFFFF, 200 * fade);
            if (lock < 1) EffectLights.flash(middle, 12, room, 5);
        }
        switch (s.type()) {
            case RF -> cage(s, camera, glow, fill, m, color);
            case MANA -> lotus(s, camera, glow, fill, m, color);
            case TWINS -> rift(s, camera, glow, fill, m, color);
        }
    }

    /** A drone's place relative to the camera, or null if its place is empty. */
    private static Vec3 at(HiveModeVisual.Scene s, int slot, Vec3 camera) {
        Vec3[] drones = s.drones();
        return slot >= 0 && slot < drones.length && drones[slot] != null ? drones[slot].subtract(camera) : null;
    }

    private static double closing(HiveModeVisual.Scene s) {
        return Math.clamp(SwarmMath.constructAge(s.time(), s.cycleStart()) / HiveConstructs.CLOSING, 0, 1);
    }

    // --- RF --------------------------------------------------------------------------------------------

    private static void cage(HiveModeVisual.Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int count = s.slots();
        double time = s.time(), shut = closing(s);
        // Panels: all but clear, a little brighter at their rims where they catch the eye edge-on.
        for (int[] face : HiveConstructs.cageFaces(count)) {
            Vec3 a = at(s, face[0], camera), b = at(s, face[1], camera), c = at(s, face[2], camera);
            if (a == null || b == null || c == null) continue;
            Vec3 middle = a.add(b).add(c).scale(1 / 3.0), normal = b.subtract(a).cross(c.subtract(a));
            double edgeOn = normal.lengthSqr() < 1e-12 ? 0 : 1 - Math.abs(normal.normalize().dot(GlowBrushReference.view(middle)));
            double alpha = (8 + 26 * edgeOn * edgeOn) * shut;
            int tint = GlowBrushReference.mix(0x06222B, color, .25 + .5 * edgeOn);
            GlowBrushReference.quad(fill, m, a, b, c, c, tint, tint, tint, tint, alpha, alpha, alpha, alpha);
        }
        List<int[]> edges = HiveConstructs.cageEdges(count);
        for (int index = 0; index < edges.size(); index++) {
            int[] edge = edges.get(index);
            Vec3 a = at(s, edge[0], camera), b = at(s, edge[1], camera);
            if (a == null || b == null) continue;
            GlowBrushReference.beam(glow, m, a, b, .016, color, 95 + 40 * shut);
            // Currents run down every third conductor, blue one way and orange the other.
            if (index % 3 == 0) {
                double run = (time * (.045 + .03 * hash(index, 1)) + hash(index, 2)) % 1;
                boolean back = index % 2 == 0;
                GlowBrushReference.dot(glow, m, back ? b.lerp(a, run) : a.lerp(b, run), .07, back ? ORANGE : 0xBFF6FF, 190 * Math.sin(Math.PI * run));
            }
        }
        // Sparks at the joints, a few at a time.
        long beat = (long) Math.floor(time / 5);
        for (int slot = 0; slot < count; slot++) {
            Vec3 node = at(s, slot, camera);
            if (node == null) continue;
            GlowBrushReference.dot(glow, m, node, .05, 0xD8FCFF, 140);
            if (hash(slot, (int) beat) < .06) {
                double flash = 1 - (time / 5 - beat);
                GlowBrushReference.dot(glow, m, node, .22, GlowBrushReference.mix(color, 0xFFFFFF, .6), 230 * flash);
            }
        }
    }

    // --- Mana ------------------------------------------------------------------------------------------

    private static void lotus(HiveModeVisual.Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int count = s.slots(), tiers = HiveConstructs.lotusTiers(count);
        double time = s.time(), shut = closing(s);
        Vec3 base = at(s, 0, camera);
        if (base == null) return;
        int rim = GlowBrushReference.mix(color, GOLD, .55);
        for (int tier = 0; tier < tiers; tier++) for (int p = 0; p < HiveConstructs.PETALS; p++) {
            int first = 1 + (tier * HiveConstructs.PETALS + p) * HiveConstructs.PETAL_CORNERS;
            Vec3 left = at(s, first, camera), right = at(s, first + 1, camera), tip = at(s, first + 2, camera);
            if (left == null || right == null || tip == null) continue;
            // Glass: a faint body, teal at the heart and gold towards the tip, brighter edge-on.
            Vec3 normal = left.subtract(base).cross(right.subtract(base));
            double edgeOn = normal.lengthSqr() < 1e-12 ? 0 : 1 - Math.abs(normal.normalize().dot(GlowBrushReference.view(tip)));
            double body = (14 + 40 * edgeOn) * (.5 + .5 * shut);
            int heart = GlowBrushReference.mix(0x0B5E62, color, .4), edge = GlowBrushReference.mix(color, GOLD, .7);
            double shine = 120 + 30 * Math.sin(time * .08 + p + tier);
            GlowBrushReference.beam(glow, m, base, left, .016, color, shine * .7);
            GlowBrushReference.beam(glow, m, base, right, .016, color, shine * .7);
            // Each side from its corner up to the tip bows outwards, so the petal is round, not a triangle.
            for (Vec3 side : new Vec3[]{left, right}) {
                Vec3 middle = side.lerp(tip, .5), axis = new Vec3(base.x, middle.y, base.z), out = middle.subtract(axis);
                Vec3 bow = middle.add(out.lengthSqr() < 1e-8 ? Vec3.ZERO : out.normalize().scale(side.distanceTo(tip) * .22));
                Vec3 previous = side;
                for (int step = 1; step <= 6; step++) {
                    double t = step / 6.0, u = 1 - t;
                    Vec3 point = side.scale(u * u).add(bow.scale(2 * u * t)).add(tip.scale(t * t));
                    GlowBrushReference.quad(fill, m, base, previous, point, point, heart, GlowBrushReference.mix(heart, edge, .5 + .4 * t), edge, edge,
                            body * .6, body, body * 1.3, body * 1.3);
                    GlowBrushReference.beam(glow, m, previous, point, .02, rim, shine);
                    previous = point;
                }
            }
            // Golden motes run up the petal's edges to its drones: the drones charging the ward.
            double run = (time * .03 + hash(tier * 7 + p, 3)) % 1;
            GlowBrushReference.dot(glow, m, base.lerp(left, run).lerp(tip, run * run), .06, GOLD, 200 * Math.sin(Math.PI * run));
            GlowBrushReference.dot(glow, m, tip, .09, GlowBrushReference.mix(GOLD, 0xFFFFFF, .4), 170);
        }
        for (int[] edge : HiveConstructs.lotusEdges(count)) {
            if (edge[0] < 1 + tiers * HiveConstructs.PETALS * HiveConstructs.PETAL_CORNERS) continue;
            Vec3 a = at(s, edge[0], camera), b = at(s, edge[1], camera);
            if (a != null && b != null) GlowBrushReference.line(glow, m, a, b, .012, rim, 80);
        }
        GlowBrushReference.dot(glow, m, base, .2, GOLD, 180);
    }

    // --- Twins -----------------------------------------------------------------------------------------

    private static void rift(HiveModeVisual.Scene s, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        int count = s.slots(), shards = HiveConstructs.shards(count);
        double time = s.time(), open = closing(s);
        Vec3 core = SwarmMath.core(s.target(), s.height()).subtract(camera);
        int edge = GlowBrushReference.mix(color, 0xE7C6FF, .35);
        for (int shard = 0; shard < shards; shard++) {
            Vec3[] corners = new Vec3[HiveConstructs.SHARD_CORNERS];
            boolean whole = true;
            for (int corner = 0; corner < corners.length; corner++) {
                corners[corner] = at(s, shard + corner * shards, camera);
                whole &= corners[corner] != null;
            }
            if (!whole) continue;
            Vec3 middle = Vec3.ZERO;
            for (Vec3 corner : corners) middle = middle.add(corner);
            middle = middle.scale(1.0 / corners.length);
            // The void behind the shard, and its stars.
            for (int corner = 0; corner < corners.length; corner++) {
                Vec3 a = corners[corner], b = corners[(corner + 1) % corners.length];
                GlowBrushReference.quad(fill, m, middle, a, b, b, VOID, 0x12031F, 0x12031F, 0x12031F, 230 * open, 200 * open, 200 * open, 200 * open);
            }
            for (int star = 0; star < 9; star++) {
                double u = hash(shard * 31 + star, 5) * 2 - 1, v = hash(shard * 31 + star, 6) * 2 - 1;
                Vec3 point = middle.add(corners[0].subtract(middle).scale(u * .7)).add(corners[2].subtract(middle).scale(v * .45));
                double twinkle = .6 + .4 * Math.sin(time * .3 + star * 2.1 + shard);
                GlowBrushReference.dot(glow, m, point, .03, star % 3 == 0 ? 0xE7C6FF : 0xFFFFFF, 170 * twinkle * open);
            }
            for (int corner = 0; corner < corners.length; corner++) {
                GlowBrushReference.beam(glow, m, corners[corner], corners[(corner + 1) % corners.length], .02, edge, 150);
            }
            // Circuit traces run in from the shard's edges, bending at right angles to a pad, a pulse racing along each.
            for (int trace = 0; trace < 3; trace++) {
                int corner = (trace * 2 + shard) % corners.length;
                Vec3 from = corners[corner].lerp(corners[(corner + 1) % corners.length], .5);
                Vec3 in = middle.subtract(from).scale(.3 + .15 * hash(shard * 5 + trace, 7));
                Vec3 along = corners[(corner + 1) % corners.length].subtract(corners[corner]).scale(.22 * (hash(shard * 5 + trace, 8) < .5 ? 1 : -1));
                Vec3 bend = from.add(in), pad = bend.add(along);
                GlowBrushReference.line(glow, m, from, bend, .008, 0xFF7BE5, 140 * open);
                GlowBrushReference.line(glow, m, bend, pad, .008, 0xFF7BE5, 140 * open);
                GlowBrushReference.dot(glow, m, pad, .035, 0xFFC6F5, 180 * open);
                double run = (time * .06 + trace * .33 + shard * .17) % 1;
                Vec3 pulse = run < .6 ? from.lerp(bend, run / .6) : bend.lerp(pad, (run - .6) / .4);
                GlowBrushReference.dot(glow, m, pulse, .04, 0xFFFFFF, 200 * open);
            }
            // A crack from the creature out to the shard, re-forking now and then.
            long flicker = (long) Math.floor(time / 3);
            GlowBrushReference.lightning(glow, m, core, middle, flicker * 97 + shard * 13L, 6, .12, .01, 0xE7C6FF, 120 * open);
        }
        GlowBrushReference.dot(glow, m, core, .4 * open, color, 90);
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

    private HiveConstructVisualReference() { }
}
