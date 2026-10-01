package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.network.NoctisPayloads;
import dev.hurtify.relicsaddon.network.NoctisPayloads.Kind;
import dev.hurtify.relicsaddon.server.NoctisWormhole;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The moments of the Noctis weapons the server tells of, drawn with the swarms' light; and the liquid violet fire
 * that runs from the spear's tip wherever one is drawn: drops of dark ink with a bright rim, falling slowly, with
 * sparks and a little smoke.
 */
public final class NoctisFx {
    static final int VIOLET = 0xB151FF, BRIGHT = 0xE7C6FF, DEEP = 0x12031F, WHITE = 0xFFF4FF, INK = 0x07010C;

    private record Moment(Kind kind, Vec3 at, Vec3 along, double size, double since, long seed) { }

    /** A drop of the liquid fire: where it is, how it moves, when it fell and how long it lasts; sparks and smoke too. */
    private static final class Drop {
        Vec3 at, motion;
        final double born, life, size;
        final int kind;

        Drop(Vec3 at, Vec3 motion, double born, double life, double size, int kind) {
            this.at = at;
            this.motion = motion;
            this.born = born;
            this.life = life;
            this.size = size;
            this.kind = kind;
        }
    }

    private static final List<Moment> MOMENTS = new ArrayList<>();
    private static final List<Drop> DROPS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static final int MAX_DROPS = 480;
    /** Game time as of the last frame, so the drops move by real time and no more. */
    private static double lastTime;

    public static void told(NoctisPayloads.Moment payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (MOMENTS.size() >= 64) MOMENTS.removeFirst();
        MOMENTS.add(new Moment(payload.kind(), payload.at(), payload.along(), payload.size(), minecraft.level.getGameTime(), RANDOM.nextLong()));
    }

    public static void clear() {
        MOMENTS.clear();
        DROPS.clear();
    }

    /**
     * The spear's tip runs with liquid fire: called wherever a tip is drawn ({@code tip} and {@code heading} in the
     * world), it sheds a drop or two a frame, and a spark now and then.
     */
    static void fire(Vec3 tip, Vec3 heading, double time, double strength) {
        if (DROPS.size() >= MAX_DROPS || HiveJuice.detail() == HiveJuice.Detail.LOW && RANDOM.nextInt(3) != 0) return;
        int drops = RANDOM.nextDouble() < .6 * strength ? 2 : 1;
        for (int drop = 0; drop < drops; drop++) {
            Vec3 jitter = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian(), RANDOM.nextGaussian()).scale(.025);
            double roll = RANDOM.nextDouble();
            // Mostly ink, falling slowly back along the spear; now and then a spark flung out, or a wisp of smoke rising.
            int kind = roll < .72 ? 0 : roll < .9 ? 1 : 2;
            Vec3 motion = kind == 1 ? jitter.scale(6).add(heading.scale(.05)) : kind == 2 ? jitter.add(0, .025, 0) : jitter.add(heading.scale(-.012)).add(0, -.01, 0);
            DROPS.add(new Drop(tip.add(jitter.scale(2)).add(heading.scale(-.1 * RANDOM.nextDouble())), motion, time,
                    kind == 1 ? 6 + RANDOM.nextDouble() * 6 : kind == 2 ? 18 + RANDOM.nextDouble() * 10 : 10 + RANDOM.nextDouble() * 10,
                    (kind == 2 ? .12 : kind == 1 ? .02 : .05) * (.7 + .6 * RANDOM.nextDouble()) * (.6 + .5 * strength), kind));
        }
    }

    static void render(Minecraft minecraft, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, double time, float partial) {
        double step = lastTime == 0 ? 0 : Math.clamp(time - lastTime, 0, 1);
        lastTime = time;
        drops(camera, glow, m, time, step);
        MOMENTS.removeIf(moment -> time - moment.since > span(moment.kind));
        for (Moment moment : MOMENTS) {
            double t = time - moment.since;
            Vec3 at = moment.at.subtract(camera);
            switch (moment.kind) {
                case COLLAPSE -> collapse(glow, m, at, moment.along, t);
                case LIGHT_CUT -> cut(glow, fill, m, at, moment.size, t);
                case BEAM -> beam(glow, fill, m, at, moment.along, moment.size, t, moment.seed);
                case HALO_SPEAR -> haloSpear(glow, m, at, moment.along.subtract(camera), t);
                case SWEEP -> sweep(glow, fill, m, at, moment.along, moment.size, t);
                case WORMHOLE_SCAN -> scan(minecraft, glow, m, camera, moment.at, moment.size, t, time);
                case WORMHOLE_BURST -> burst(glow, fill, m, at, moment.size, t);
                case WORMHOLE_THROW -> thrown(glow, m, at, moment.along.subtract(camera), t);
            }
        }
    }

    /** How long each kind of moment is shown, in ticks. */
    private static double span(Kind kind) {
        return switch (kind) {
            case COLLAPSE -> 8;
            case LIGHT_CUT -> 14;
            case BEAM -> 40;
            case HALO_SPEAR -> 7;
            case SWEEP -> 10;
            case WORMHOLE_SCAN -> NoctisWormhole.SCAN;
            case WORMHOLE_BURST -> 30;
            case WORMHOLE_THROW -> 12;
        };
    }

    /** The drops of liquid fire, carried on, drawn and let go as they die. */
    private static void drops(Vec3 camera, VertexConsumer glow, Matrix4f m, double time, double step) {
        DROPS.removeIf(drop -> time - drop.born > drop.life);
        for (Drop drop : DROPS) {
            double age = (time - drop.born) / drop.life;
            if (step > 0) {
                drop.at = drop.at.add(drop.motion.scale(step));
                // Ink thickens and slows as it cools; sparks fall; smoke drifts.
                drop.motion = drop.kind == 1 ? drop.motion.scale(.9).add(0, -.012 * step, 0) : drop.kind == 2 ? drop.motion.scale(.97) : drop.motion.scale(.93);
            }
            Vec3 at = drop.at.subtract(camera);
            if (at.lengthSqr() > 64 * 64) continue;
            double size = drop.size * (drop.kind == 2 ? .6 + 1.4 * age : drop.kind == 1 ? 1 - age * .5 : .6 + .9 * Math.sin(age * Math.PI));
            double fade = drop.kind == 2 ? (1 - age) * .35 : 1 - age * age;
            if (drop.kind == 1) {
                GlowBrush.dot(glow, m, at, size, WHITE, 230 * fade);
                GlowBrush.line(glow, m, at, at.subtract(drop.motion.scale(2)), .01, BRIGHT, 160 * fade);
            } else if (drop.kind == 2) {
                GlowBrush.dot(glow, m, at, size, INK, 120 * fade);
            } else {
                GlowBrush.dot(glow, m, at, size * 1.7, VIOLET, 90 * fade);
                GlowBrush.dot(glow, m, at, size, INK, 240 * fade);
                GlowBrush.dot(glow, m, at.add(0, size * .3, 0), size * .35, BRIGHT, 150 * fade);
            }
        }
    }

    /** A thrust landing: a ring rushing in to the point, then a dark flash where the air closed. */
    private static void collapse(VertexConsumer glow, Matrix4f m, Vec3 at, Vec3 along, double t) {
        Vec3[] axes = HiveShapes.axes(along);
        double k = Math.min(1, t / 5);
        if (k < 1) {
            double radius = 1.3 * (1 - k) * (1 - k) + .05;
            GlowBrush.circle(glow, m, at, axes[1], axes[2], radius, 28, .03, VIOLET, 220);
            GlowBrush.circle(glow, m, at, axes[1], axes[2], radius * .7, 28, .015, BRIGHT, 160);
            for (int streak = 0; streak < 8; streak++) {
                double a = Math.PI * 2 * streak / 8 + t * .3;
                Vec3 dir = axes[1].scale(Math.cos(a)).add(axes[2].scale(Math.sin(a)));
                GlowBrush.line(glow, m, at.add(dir.scale(radius * 1.6)), at.add(dir.scale(radius * .9)), .012, BRIGHT, 200 * (1 - k));
            }
        }
        double flash = t < 4 ? t / 4 : Math.max(0, 1 - (t - 4) / 4);
        GlowBrush.dot(glow, m, at, .25 + .35 * flash, INK, 230 * flash);
        GlowBrush.dot(glow, m, at, .15 * flash, WHITE, 255 * flash);
    }

    /** The light cut: a disc of thin light spun out level, with the shadow of the blade under it. */
    private static void cut(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 at, double reach, double t) {
        double k = Math.min(1, t / 6), radius = reach * (1 - (1 - k) * (1 - k)), fade = t < 8 ? 1 : Math.max(0, 1 - (t - 8) / 6);
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        GlowBrush.circle(glow, m, at, x, z, radius, 48, .04, WHITE, 240 * fade);
        GlowBrush.circle(glow, m, at, x, z, radius * .96, 48, .08, VIOLET, 140 * fade);
        // The disc itself: dark glass, thin as a thought, trailing the rim by a little.
        int segments = 48;
        double inner = Math.max(0, radius - 1.2);
        for (int segment = 0; segment < segments; segment++) {
            double a = Math.PI * 2 * segment / segments, b = Math.PI * 2 * (segment + 1) / segments;
            Vec3 pa = at.add(Math.cos(a) * radius, 0, Math.sin(a) * radius), pb = at.add(Math.cos(b) * radius, 0, Math.sin(b) * radius);
            Vec3 ia = at.add(Math.cos(a) * inner, 0, Math.sin(a) * inner), ib = at.add(Math.cos(b) * inner, 0, Math.sin(b) * inner);
            GlowBrush.quad(fill, m, ia, ib, pb, pa, DEEP, DEEP, VIOLET, VIOLET, 0, 0, 150 * fade, 150 * fade);
        }
    }

    /**
     * The quantum beam: a white core in a violet shaft, dark round it, rings of its circuit racing out along it, and
     * the shaft thinning to a thread of light as it dies.
     */
    private static void beam(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 from, Vec3 heading, double length, double t, long seed) {
        double life = t < 6 ? 1 : Math.max(0, 1 - (t - 6) / 34), swell = t < 2 ? t / 2 : 1;
        Vec3 to = from.add(heading.scale(length));
        double width = .55 * life * swell + .03;
        GlowBrush.line(glow, m, from, to, width * 2.2, INK, 90 * life);
        GlowBrush.line(glow, m, from, to, width * 1.4, VIOLET, 150 * life);
        GlowBrush.line(glow, m, from, to, width * .6, WHITE, 230 * life);
        GlowBrush.line(glow, m, from, to, width * .25, WHITE, 255 * life);
        Vec3[] axes = HiveShapes.axes(heading);
        // The circuit's rings, flung out along it.
        for (int ring = 0; ring < 10; ring++) {
            double along = ((t * 2.2 + ring * length / 10) % length);
            Vec3 centre = from.add(heading.scale(along));
            GlowBrush.circle(glow, m, centre, axes[1], axes[2], width * 1.6 + .3, 24, .03, BRIGHT, 200 * life);
        }
        // Bolts tearing round the mouth of it.
        long flicker = (long) Math.floor(t * 2);
        for (int bolt = 0; bolt < 6; bolt++) {
            double a = (seed + flicker * 7 + bolt * 131) % 360 / 180.0 * Math.PI;
            Vec3 start = from.add(heading.scale(1 + bolt * .5)), out = axes[1].scale(Math.cos(a)).add(axes[2].scale(Math.sin(a)));
            GlowBrush.lightning(glow, m, start.add(out.scale(width)), start.add(out.scale(width + 1.5 * life)), seed + flicker + bolt, 5, .35, .01, BRIGHT, 220 * life);
        }
        GlowBrush.dot(glow, m, from.add(heading.scale(.5)), .9 * life + .2, WHITE, 200 * life);
    }

    /** A light spear of the Black Halo falling on a creature: a line of light, then a burst where it lands. */
    private static void haloSpear(VertexConsumer glow, Matrix4f m, Vec3 from, Vec3 to, double t) {
        double k = Math.min(1, t / 3), fade = t < 4 ? 1 : Math.max(0, 1 - (t - 4) / 3);
        Vec3 head = from.lerp(to, k), tail = from.lerp(to, Math.max(0, k - .35));
        GlowBrush.line(glow, m, tail, head, .02, .08, VIOLET, WHITE, 120 * fade, 255 * fade);
        if (k >= 1) {
            double burst = (t - 3) / 4;
            GlowBrush.dot(glow, m, to, .4 + .8 * burst, WHITE, 230 * fade);
            GlowBrush.circle(glow, m, to, new Vec3(1, 0, 0), new Vec3(0, 0, 1), .3 + 1.2 * burst, 20, .03, BRIGHT, 200 * fade);
        }
    }

    /** The scythe's swing: its jet-black aura flung out across the arc, a violet rim on it. */
    private static void sweep(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 eye, Vec3 look, double reach, double t) {
        double k = Math.min(1, t / 4), fade = Math.max(0, 1 - t / 10);
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize(), side = flat.cross(new Vec3(0, 1, 0));
        double from = -Math.toRadians(60), to = Math.toRadians(60), sweep = from + (to - from) * k;
        int segments = 16;
        Vec3 centre = eye.subtract(0, .4, 0);
        for (int segment = 0; segment < segments; segment++) {
            double a = from + (sweep - from) * segment / segments, b = from + (sweep - from) * (segment + 1) / segments;
            Vec3 da = flat.scale(Math.cos(a)).add(side.scale(Math.sin(a))), db = flat.scale(Math.cos(b)).add(side.scale(Math.sin(b)));
            Vec3 oa = centre.add(da.scale(reach)), ob = centre.add(db.scale(reach)), ia = centre.add(da.scale(reach * .45)), ib = centre.add(db.scale(reach * .45));
            GlowBrush.quad(fill, m, ia, ib, ob.add(0, .25, 0), oa.add(0, .25, 0), INK, INK, INK, INK, 60 * fade, 60 * fade, 200 * fade, 200 * fade);
            GlowBrush.line(glow, m, oa.add(0, .25, 0), ob.add(0, .25, 0), .04, VIOLET, 220 * fade);
        }
    }

    /**
     * The wormhole's scan: rings of its circuit running out from the planted scythe over the land, each following
     * the ground, with a thread of light rising where each crosses a ring of its grid.
     */
    private static void scan(Minecraft minecraft, VertexConsumer glow, Matrix4f m, Vec3 camera, Vec3 at, double radius, double t, double time) {
        if (minecraft.level == null) return;
        double k = t / NoctisWormhole.SCAN;
        int rings = 6, segments = 48;
        for (int ring = 1; ring <= rings; ring++) {
            double r = radius * ring / rings;
            if (r > radius * k * 1.4) continue;
            double fade = Math.min(1, (radius * k * 1.4 - r) / (radius * .3));
            Vec3 previous = null;
            for (int segment = 0; segment <= segments; segment++) {
                double a = Math.PI * 2 * segment / segments;
                double x = at.x + Math.cos(a) * r, z = at.z + Math.sin(a) * r;
                double y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + .08;
                Vec3 point = new Vec3(x, y, z).subtract(camera);
                if (previous != null) GlowBrush.line(glow, m, previous, point, .025, ring % 2 == 0 ? VIOLET : BRIGHT, 200 * fade);
                if (segment % 8 == 0) GlowBrush.line(glow, m, point, point.add(0, .6 + .4 * Math.sin(time * .5 + segment), 0), .015, BRIGHT, 160 * fade);
                previous = point;
            }
        }
        // The circuit's reading, written in the air over the scythe: a ring of runes turning faster as it finishes.
        Vec3 centre = at.subtract(camera).add(0, 2.2, 0);
        GlowBrush.circle(glow, m, centre, new Vec3(1, 0, 0), new Vec3(0, 0, 1), .8, 32, .02, BRIGHT, 220);
        for (int mark = 0; mark < 10; mark++) {
            double a = time * (.1 + .4 * k) + Math.PI * 2 * mark / 10;
            Vec3 p = centre.add(Math.cos(a) * .8, 0, Math.sin(a) * .8);
            GlowBrush.line(glow, m, p, p.add(0, .25, 0), .015, VIOLET, 220);
        }
    }

    /** The dome bursting out, then falling in on itself to a point. */
    private static void burst(VertexConsumer glow, VertexConsumer fill, Matrix4f m, Vec3 at, double radius, double t) {
        double k = t < 8 ? 1 - Math.pow(1 - t / 8, 2) : Math.max(0, 1 - (t - 8) / 18);
        double r = radius * k;
        if (r < .1) return;
        if (t >= 8) BlackHoleLens.queue(at, Math.max(.3, r * .15), r * 2 + 4, 1.4, 1.3);
        GlowBrush.sphere(fill, m, at, r, DEEP, VIOLET, 120 * (t < 8 ? 1 : k), 180, 12);
        GlowBrush.circle(glow, m, at.add(0, .05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, 64, .08, BRIGHT, 240);
        GlowBrush.dot(glow, m, at, 1 + 2 * (1 - k), WHITE, 220 * (t < 8 ? t / 8 : 1));
    }

    /** A creature thrown through the wormhole: a streak out of the dome, and a flash where it comes out. */
    private static void thrown(VertexConsumer glow, Matrix4f m, Vec3 from, Vec3 to, double t) {
        double k = Math.min(1, t / 6), fade = Math.max(0, 1 - t / 12);
        Vec3 head = from.lerp(to, k);
        GlowBrush.line(glow, m, from.lerp(to, Math.max(0, k - .2)), head, .06, .15, VIOLET, WHITE, 100 * fade, 230 * fade);
        if (k >= 1) GlowBrush.dot(glow, m, to, 1.2, VIOLET, 200 * fade);
        GlowBrush.dot(glow, m, from, .8, INK, 200 * fade);
    }

    private NoctisFx() { }
}
