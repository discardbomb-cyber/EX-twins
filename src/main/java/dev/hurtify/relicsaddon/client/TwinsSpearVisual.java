package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.relic.TwinsSpearEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The thrown reflection of the Twins spear, drawn with the swarms' glass and light:
 * <ul>
 *   <li>a spear two blocks long, a dark glass shaft with bright edges and an elongated octahedral tip, trailing faceted
 *   sparks in flight;</li>
 *   <li>pinned in a creature: a flash and a ring of glass shards as it strikes, four chains of glass links from the shaft
 *   down to the ground, a ring of runes under the creature, and (with a working Twins hive) six drones circling on a
 *   tilted ring, each in turn striking in;</li>
 *   <li>on its way home, carried by two of the drones.</li>
 * </ul>
 */
public final class TwinsSpearVisual {
    /** Where the weapons stand to be looked over (a model scene), or null. */
    public static Vec3 MODEL;
    private static final int VIOLET = 0xB151FF, BRIGHT = 0xE7C6FF, DEEP = 0x12031F, WHITE = 0xFFF4FF, INK = NoctisFx.INK;
    /** The spear's length, its tip's, and how far the tip sits ahead of the entity's position. */
    private static final double LENGTH = 2.1, TIP = .5, AHEAD = .35;

    static void render(Minecraft minecraft, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, double time, float partial) {
        if (minecraft.level == null) return;
        EclipseScytheVisual.trails(camera, glow, fill, m, time);
        if (MODEL != null) {
            Vec3 at = MODEL.subtract(camera);
            EclipseScytheVisual.draw(glow, fill, m, at, new Vec3(0, 1, 0), new Vec3(-1, 0, 0), 1, 0, 1, time, 1);
            EclipseScytheVisual.draw(glow, fill, m, at.add(2.5, 0, 0), new Vec3(0, 1, 0), new Vec3(-1, 0, 0), 0, 0, 1, time, 1);
            EclipseScytheVisual.draw(glow, fill, m, at.add(-2.5, 0, 0), new Vec3(0, 1, 0), new Vec3(-1, 0, 0), 1, 1, 1, time, 1);
            spear(glow, fill, m, at.add(5, 2.1, 0), new Vec3(0, 1, 0), time, 1, 1, 0);
            spear(glow, fill, m, at.add(-5, 2.1, 0), new Vec3(0, 1, 0), time, 1, .3, .8);
        }
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof TwinsSpearEntity spear) || entity.position().distanceToSqr(camera) > 96 * 96) continue;
            draw(spear, camera, glow, fill, m, time, partial);
        }
    }

    private static void draw(TwinsSpearEntity spear, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, double time, float partial) {
        double yaw = Math.toRadians(Mth.rotLerp(partial, spear.yRotO, spear.getYRot())), pitch = Math.toRadians(Mth.lerp(partial, spear.xRotO, spear.getXRot()));
        Vec3 heading = new Vec3(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 at = spear.getPosition(partial).subtract(camera);
        Vec3 tip = at.add(heading.scale(AHEAD));
        double t = spear.stateTicks() + partial;
        long seed = spear.getId() * 7919L;
        int state = spear.state();
        spear(glow, fill, m, tip, heading, time, 1, .7, 0);
        NoctisFx.fire(tip.add(camera), heading, time, .8);
        if (state == TwinsSpearEntity.ANCHORED || state == TwinsSpearEntity.STUCK) horizon(glow, m, tip, t, time, seed);
        switch (state) {
            case TwinsSpearEntity.FLYING -> trail(glow, m, tip, heading, time, seed);
            case TwinsSpearEntity.ANCHORED -> {
                Entity target = spear.target();
                if (target == null) break;
                double fade = Mth.clamp((TwinsSpearEntity.ANCHOR - t) / 10, 0, 1);
                Vec3 feet = target.getPosition(partial).subtract(camera);
                Vec3 middle = feet.add(0, target.getBbHeight() * .5, 0);
                strike(glow, m, tip, heading, t, seed);
                runes(glow, m, feet, Math.max(1, target.getBbWidth() * 1.6), t, time, fade);
                chains(glow, m, tip.subtract(heading.scale(LENGTH * .45)), feet, Math.max(1.2, target.getBbWidth() * 2), t, time, fade);
                if (spear.swarm() > 0) drones(glow, fill, m, middle, Math.max(1.4, target.getBbWidth() * 2.2), spear.swarm(), t, time, fade);
            }
            case TwinsSpearEntity.STUCK -> {
                strike(glow, m, tip, heading, t, seed);
                GlowBrush.dot(glow, m, tip, .35 + .08 * Math.sin(time * .4), VIOLET, 70);
            }
            case TwinsSpearEntity.HALO -> eclipse(glow, fill, m, at, t, time, seed);
            default -> {
                trail(glow, m, tip, heading, time, seed);
                if (spear.swarm() > 0) {
                    // Two drones carry it home, one under each end.
                    Vec3 up = HiveShapes.axes(heading)[1];
                    HiveProjectiles.icosahedron(glow, fill, m, tip.subtract(heading.scale(.7)).add(up.scale(-.22)), .12, time * .3, 1);
                    HiveProjectiles.icosahedron(glow, fill, m, tip.subtract(heading.scale(LENGTH - .2)).add(up.scale(-.22)), .12, -time * .3, 1);
                }
            }
        }
    }

    /**
     * The spear itself, pointing along {@code heading} with its tip at {@code tip}; its core {@code fullness} full
     * (more and brighter circuit lines), and {@code charge} of the way to firing its beam (its chains overloading).
     */
    static void spear(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 tip, Vec3 heading, double time, double alpha, double fullness, double charge) {
        Vec3[] axes = HiveShapes.axes(heading);
        Vec3 u = axes[1], v = axes[2];
        Vec3 neck = tip.subtract(heading.scale(TIP)), tail = tip.subtract(heading.scale(LENGTH));
        // The shaft: a six-sided prism of dark glass, its edges lit.
        int sides = 6;
        double shaft = .035;
        for (int side = 0; side < sides; side++) {
            double a = Math.PI * 2 * side / sides, b = Math.PI * 2 * (side + 1) / sides;
            Vec3 ra = u.scale(Math.cos(a) * shaft).add(v.scale(Math.sin(a) * shaft)), rb = u.scale(Math.cos(b) * shaft).add(v.scale(Math.sin(b) * shaft));
            face(fill, m, tail.add(ra), tail.add(rb), neck.add(rb), neck.add(ra), alpha);
            GlowBrush.line(glow, m, tail.add(ra), neck.add(ra), .006, BRIGHT, 120 * alpha);
        }
        // Its core: a thin violet light the length of it, brighter towards the tip.
        GlowBrush.line(glow, m, tail, neck, .015, .03, DEEP, VIOLET, 40 * alpha, (120 + 80 * fullness) * alpha);
        // The circuit's chains along the shaft: lit stretches travelling up it, more the fuller the core, and
        // flashing wildly as the beam is charged.
        int chains = 1 + (int) Math.round(fullness * 3);
        long flicker = (long) Math.floor(time * 3);
        for (int chain = 0; chain < chains; chain++) {
            double a = Math.PI * 2 * chain / chains + Math.PI / 6;
            Vec3 off = u.scale(Math.cos(a) * shaft * 1.05).add(v.scale(Math.sin(a) * shaft * 1.05));
            double from = ((time * .03 + chain * .27) % 1) * (LENGTH - TIP), to = Math.min(LENGTH - TIP, from + .3 + .5 * fullness);
            boolean flash = charge > 0 && hash(flicker, chain) < charge;
            GlowBrush.line(glow, m, tail.add(off).add(heading.scale(from)), tail.add(off).add(heading.scale(to)), flash ? .012 : .006,
                    flash ? WHITE : BRIGHT, (100 + 120 * fullness) * alpha);
            if (flash) GlowBrush.line(glow, m, tail.add(off), neck.add(off), .02, VIOLET, 160 * charge * alpha);
        }
        if (charge > 0) {
            // The tip gathers the beam: a white ball swelling at it, and bolts tearing round the shaft.
            GlowBrush.dot(glow, m, tip, .15 + .5 * charge, WHITE, 230 * charge * alpha);
            GlowBrush.dot(glow, m, tip, .4 + 1.2 * charge, VIOLET, 90 * charge * alpha);
            for (int bolt = 0; bolt < 4; bolt++) {
                double b = hash(flicker + bolt, 7) * Math.PI * 2;
                Vec3 start = tail.lerp(neck, hash(flicker, bolt + 3));
                GlowBrush.lightning(glow, m, start, start.add(u.scale(Math.cos(b) * .3 * charge)).add(v.scale(Math.sin(b) * .3 * charge)),
                        flicker * 5 + bolt, 4, .4, .006, BRIGHT, 220 * charge * alpha);
            }
        }
        // A guard where the tip meets the shaft.
        GlowBrush.circle(glow, m, neck, u, v, .09, 6, .012, BRIGHT, 200 * alpha);
        // The tip: an octahedron drawn out along the heading, its widest a third of the way back.
        Vec3 waist = tip.subtract(heading.scale(TIP * .35));
        double width = .1, spin = time * .05;
        Vec3[] ring = new Vec3[4];
        for (int corner = 0; corner < 4; corner++) {
            double a = spin + Math.PI / 2 * corner;
            ring[corner] = waist.add(u.scale(Math.cos(a) * width)).add(v.scale(Math.sin(a) * width));
        }
        for (int corner = 0; corner < 4; corner++) {
            Vec3 a = ring[corner], b = ring[(corner + 1) % 4];
            face(fill, m, tip, a, b, b, alpha);
            face(fill, m, neck, b, a, a, alpha);
            GlowBrush.line(glow, m, a, b, .007, BRIGHT, 220 * alpha);
            GlowBrush.line(glow, m, tip, a, .007, BRIGHT, 240 * alpha);
            GlowBrush.line(glow, m, neck, a, .006, VIOLET, 180 * alpha);
            // Its reflection inside, turned against it.
            Vec3 ia = waist.add(a.subtract(waist).scale(.5)), ib = waist.add(b.subtract(waist).scale(.5));
            GlowBrush.line(glow, m, ia, ib, .004, VIOLET, 150 * alpha);
        }
        GlowBrush.dot(glow, m, tip, .12, WHITE, 140 * alpha);
        GlowBrush.dot(glow, m, waist, .35, VIOLET, 55 * alpha);
    }

    /** A glass face, darker face-on and brighter edge-on, as the swarms' glass is. */
    private static void face(VertexConsumer fill, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, double alpha) {
        Vec3 normal = b.subtract(a).cross(c.subtract(a));
        double edgeOn = normal.lengthSqr() < 1e-14 ? 0 : 1 - Math.abs(normal.normalize().dot(GlowBrush.view(a)));
        int tint = GlowBrush.mix(DEEP, VIOLET, .2 + .6 * edgeOn);
        double body = (120 + 90 * edgeOn) * alpha;
        GlowBrush.quad(fill, m, a, b, c, d, tint, tint, tint, tint, body, body, body, body);
    }

    /** Faceted sparks strung out behind it, fading. */
    private static void trail(VertexConsumer glow, Matrix4f m, Vec3 tip, Vec3 heading, double time, long seed) {
        Vec3 tail = tip.subtract(heading.scale(LENGTH));
        GlowBrush.line(glow, m, tail, tail.subtract(heading.scale(2.5)), .03, .005, VIOLET, VIOLET, 110, 0);
        Vec3[] axes = HiveShapes.axes(heading);
        long flicker = (long) Math.floor(time);
        for (int spark = 0; spark < 8; spark++) {
            double back = .3 + spark * .35, fade = 1 - spark / 8.0;
            Vec3 at = tail.subtract(heading.scale(back)).add(axes[1].scale((hash(seed + flicker, spark) - .5) * .3))
                    .add(axes[2].scale((hash(seed + flicker, spark + 11) - .5) * .3));
            diamond(glow, m, at, axes[1], axes[2], .05 * fade + .02, spark % 3 == 0 ? BRIGHT : VIOLET, 220 * fade);
        }
    }

    /** As it strikes: a flash, a ring flung out across its heading, and glass shards scattering. */
    private static void strike(VertexConsumer glow, Matrix4f m, Vec3 tip, Vec3 heading, double t, long seed) {
        if (t > 12) return;
        double k = t / 12, out = 1 - k;
        Vec3[] axes = HiveShapes.axes(heading);
        GlowBrush.dot(glow, m, tip, .3 + 1.1 * out, WHITE, 255 * out * out);
        GlowBrush.dot(glow, m, tip, .8 + 1.4 * k, VIOLET, 140 * out);
        GlowBrush.circle(glow, m, tip, axes[1], axes[2], .3 + 1.6 * k, 28, .03 * out + .006, BRIGHT, 230 * out);
        for (int shard = 0; shard < 10; shard++) {
            double a = hash(seed, shard) * Math.PI * 2;
            Vec3 dir = axes[1].scale(Math.cos(a)).add(axes[2].scale(Math.sin(a))).add(heading.scale(-.6 * hash(seed, shard + 5))).normalize();
            Vec3 from = tip.add(dir.scale(.2 + 1.8 * k * (.6 + .4 * hash(seed, shard + 9))));
            GlowBrush.line(glow, m, from, from.add(dir.scale(.18 * out + .04)), .012, shard % 2 == 0 ? BRIGHT : VIOLET, 230 * out);
        }
    }

    /** A ring of runes on the ground under the pinned creature, turning slowly, that opens as it is pinned. */
    private static void runes(VertexConsumer glow, Matrix4f m, Vec3 feet, double radius, double t, double time, double fade) {
        double open = Mth.clamp(t / 6, 0, 1), r = radius * (.4 + .6 * open), alpha = fade * open;
        Vec3 centre = feet.add(0, .04, 0), x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        GlowBrush.circle(glow, m, centre, x, z, r, 40, .02, BRIGHT, 200 * alpha);
        GlowBrush.circle(glow, m, centre, x, z, r * .78, 40, .012, VIOLET, 170 * alpha);
        GlowBrush.dot(glow, m, centre, r * 1.1, VIOLET, 40 * alpha);
        for (int mark = 0; mark < 12; mark++) {
            double a = time * .03 + Math.PI * 2 * mark / 12;
            Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a)), side = new Vec3(-Math.sin(a), 0, Math.cos(a));
            Vec3 in = centre.add(dir.scale(r * .8)), out = centre.add(dir.scale(r * .97));
            // A rune: a stroke across the band and a short bar, a different one each mark.
            GlowBrush.line(glow, m, in, out, .012, BRIGHT, 210 * alpha);
            double bar = (mark % 3 - 1) * .3;
            GlowBrush.line(glow, m, in.lerp(out, .5 + bar * .5).add(side.scale(-.05)), in.lerp(out, .5 + bar * .5).add(side.scale(.05)), .01, VIOLET, 200 * alpha);
        }
    }

    /** Four chains of glass links from the shaft down to the ground round the creature, a light running down them. */
    private static void chains(VertexConsumer glow, Matrix4f m, Vec3 from, Vec3 feet, double reach, double t, double time, double fade) {
        double grow = Mth.clamp((t - 2) / 6, 0, 1);
        if (grow <= 0) return;
        for (int chain = 0; chain < 4; chain++) {
            double a = Math.PI / 4 + Math.PI / 2 * chain;
            Vec3 ground = feet.add(Math.cos(a) * reach, .05, Math.sin(a) * reach);
            Vec3 end = from.lerp(ground, grow);
            int links = 9;
            Vec3 along = end.subtract(from);
            Vec3 side = along.cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
            Vec3 across = side.cross(along.normalize());
            for (int link = 0; link < links; link++) {
                double s = (link + .5) / links;
                // A little sag, and the light running down from the spear to the ground.
                Vec3 at = from.lerp(end, s).add(0, -Math.sin(s * Math.PI) * .15 * grow, 0);
                double pulse = .5 + .5 * Math.cos((s - (time * .12 % 1)) * Math.PI * 2);
                Vec3 u = along.normalize(), v = link % 2 == 0 ? side : across;
                diamond(glow, m, at, u, v, .07, pulse > .8 ? WHITE : BRIGHT, (130 + 110 * pulse) * fade);
            }
            GlowBrush.dot(glow, m, ground, .25, VIOLET, 120 * fade * grow);
        }
    }

    /**
     * The hive's drones round the pinned creature: they drop in from above, then circle on a tilted ring, and each in
     * turn strikes in with a short bright line.
     */
    private static void drones(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 middle, double radius, int count, double t, double time, double fade) {
        double arrive = Mth.clamp(t / 8, 0, 1), tilt = Math.toRadians(25);
        int striking = (int) Math.floor((t - 2) / TwinsSpearEntity.BLADE_EVERY);
        double since = (t - 2) - striking * TwinsSpearEntity.BLADE_EVERY;
        for (int drone = 0; drone < count; drone++) {
            double a = time * .12 + Math.PI * 2 * drone / count;
            Vec3 ring = new Vec3(Math.cos(a) * radius, Math.sin(a) * radius * Math.sin(tilt), Math.sin(a) * radius * Math.cos(tilt));
            Vec3 at = middle.add(ring).add(0, 4 * (1 - arrive) * (1 - arrive), 0);
            Vec3 tangent = new Vec3(-Math.sin(a), Math.cos(a) * Math.sin(tilt), Math.cos(a) * Math.cos(tilt)).normalize();
            Vec3 in = middle.subtract(at).normalize();
            Vec3 front = at.add(tangent.scale(.32)), back = at.subtract(tangent.scale(.22)), keel = at.add(in.scale(.14));
            double show = Math.max(.3, fade);
            face(fill, m, back, front, keel, keel, show);
            GlowBrush.line(glow, m, back, front, .009, WHITE, 230 * show);
            GlowBrush.line(glow, m, front, keel, .006, BRIGHT, 200 * show);
            GlowBrush.line(glow, m, keel, back, .006, VIOLET, 160 * show);
            GlowBrush.dot(glow, m, at, .14, VIOLET, 70 * show);
            if (t > 8 && Math.floorMod(striking, count) == drone && since < 2.5) {
                double k = 1 - since / 2.5;
                GlowBrush.line(glow, m, at, middle, .03 * k + .01, BRIGHT, 240 * k * fade);
                GlowBrush.dot(glow, m, middle.add(at.subtract(middle).normalize().scale(.35)), .3 * k + .1, WHITE, 220 * k * fade);
            }
        }
    }

    /** The event horizon round a pinned spear: space pulled in to it, dark, with a slow violet swirl at its rim. */
    private static void horizon(VertexConsumer glow, Matrix4f m, Vec3 tip, double t, double time, long seed) {
        double reach = TwinsSpearEntity.horizon(), open = Mth.clamp(t / 8, 0, 1), fade = Mth.clamp((TwinsSpearEntity.ANCHOR - t) / 8, 0, 1) * open;
        if (fade <= 0) return;
        if (!GlowBrush.flat()) BlackHoleLens.queue(tip, .25 + .15 * open, reach * .5 * open + 1, 1.6 * fade, 1.5);
        GlowBrush.dot(glow, m, tip, .6 + .3 * open, INK, 230 * fade);
        GlowBrush.dot(glow, m, tip, 1.2 * open, VIOLET, 60 * fade);
        // Streams of dark matter drawn in along spirals from the horizon's edge.
        for (int stream = 0; stream < 9; stream++) {
            double a = Math.PI * 2 * stream / 9 - time * .08, r = reach * .45 * open;
            Vec3 previous = null;
            for (int step = 0; step <= 10; step++) {
                double k = step / 10.0, radius = r * (1 - k * .92), angle = a + k * 2.2 + hash(seed, stream) * .5;
                Vec3 p = tip.add(Math.cos(angle) * radius, (hash(seed, stream + 4) - .5) * .8 * (1 - k), Math.sin(angle) * radius);
                if (previous != null) GlowBrush.line(glow, m, previous, p, .03 * (1 - k) + .008, step % 2 == 0 ? VIOLET : INK, (60 + 150 * k) * fade);
                previous = p;
            }
        }
    }

    /** The Black Halo: the spear hanging point down, an eclipse opened round it, a corona of light and runes about the dark. */
    private static void eclipse(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 at, double t, double time, long seed) {
        double open = Mth.clamp((t - TwinsSpearEntity.RISE) / 6, 0, 1), end = TwinsSpearEntity.RISE + TwinsSpearEntity.RAIN;
        double fade = t > end - 8 ? Mth.clamp((end - t) / 8, 0, 1) : 1;
        if (open <= 0) return;
        double radius = 2.6 * open * fade;
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        int segments = 48;
        for (int segment = 0; segment < segments; segment++) {
            double a = Math.PI * 2 * segment / segments, b = Math.PI * 2 * (segment + 1) / segments;
            GlowBrush.quad(fill, m, at, at.add(Math.cos(a) * radius, 0, Math.sin(a) * radius), at.add(Math.cos(b) * radius, 0, Math.sin(b) * radius), at,
                    INK, INK, INK, INK, 250, 250, 250, 250);
        }
        GlowBrush.circle(glow, m, at, x, z, radius * 1.02, segments, .12, WHITE, 240 * fade);
        GlowBrush.circle(glow, m, at, x, z, radius * 1.15, segments, .3, VIOLET, 120 * fade);
        // The corona: rays flickering out from the rim.
        long flicker = (long) Math.floor(time * 2);
        for (int ray = 0; ray < 24; ray++) {
            double a = Math.PI * 2 * ray / 24 + time * .02, length = .4 + 1.2 * hash(seed + flicker, ray);
            Vec3 from = at.add(Math.cos(a) * radius * 1.05, 0, Math.sin(a) * radius * 1.05);
            GlowBrush.line(glow, m, from, from.add(Math.cos(a) * length, 0, Math.sin(a) * length), .03, .005, BRIGHT, VIOLET, 220 * fade, 0);
        }
        // Runes turning under it.
        for (int mark = 0; mark < 16; mark++) {
            double a = -time * .06 + Math.PI * 2 * mark / 16;
            Vec3 in = at.add(Math.cos(a) * radius * .75, -.05, Math.sin(a) * radius * .75), out = at.add(Math.cos(a) * radius * .92, -.05, Math.sin(a) * radius * .92);
            GlowBrush.line(glow, m, in, out, .015, BRIGHT, 200 * fade);
        }
        GlowBrush.dot(glow, m, at, radius * .9, VIOLET, 30 * fade);
    }

    private static void diamond(VertexConsumer glow, Matrix4f m, Vec3 at, Vec3 u, Vec3 v, double size, int color, double alpha) {
        Vec3 a = at.add(u.scale(size)), b = at.add(v.scale(size * .6)), c = at.subtract(u.scale(size)), d = at.subtract(v.scale(size * .6));
        GlowBrush.line(glow, m, a, b, .008, color, alpha);
        GlowBrush.line(glow, m, b, c, .008, color, alpha);
        GlowBrush.line(glow, m, c, d, .008, color, alpha);
        GlowBrush.line(glow, m, d, a, .008, color, alpha);
    }

    private static double hash(long seed, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private TwinsSpearVisual() { }
}
