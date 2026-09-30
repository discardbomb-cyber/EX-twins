package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.drone.ManaArmageddon;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The light of Mana Armageddon (see {@link ManaArmageddon}), all of it in the world. Over its owner's shoulders:
 * the two flowers, their petals full of twinkling sparks round glowing hearts that swell with the charge; behind
 * each a seal (a double ring round a belt of runes, a seven-pointed star, a ring at the core) and round it a
 * gyroscope of rune rings; between them, over the owner's head, the central seal, turquoise on the left and gold
 * on the right; a worn Mana shield handing its charge up to the hearts; the runes written one by one, clockwise,
 * a spark at the pen. Then the streams: the turquoise one dense and writhing, scaled at its edge; the gold one a
 * dazzling beam glinting in every colour. Where they meet (told to every client near, so it is seen from afar):
 * the clash spraying sparks and flakes, the vortex's spirals and the stones it tears up, the rune shards and
 * stones flung out as the sphere shatters, and the sparks falling from the white sky after. The sphere, the seal
 * on the ground, the dome and the column are drawn by {@link ArmageddonVolume}; here are their stages, sounds
 * and light on the world.
 */
public final class ManaArmageddonVisual {
    /** Blasts this client was told of, oldest first. */
    static final List<Blast> BLASTS = new ArrayList<>();
    /** How long after the burst a blast is kept: through the white and the silence, until the world is itself again. */
    static final int BLAST_LIFE = ManaArmageddon.QUIET + 60;
    private static final int SPARKS = 150, SPRAY = 170, SHARDS = 150, GROUND_SHARDS = 70, STONES = 460;
    /** The crescent moon over the white sky: its radius, and how high over the blast it hangs. */
    static final double MOON_RADIUS = 26, MOON_HEIGHT = 130;
    private static final int[] SIDES = {-1, 1};

    /** A blast this client knows of: told as the streams meet, it bursts at {@code impactAt}. */
    static final class Blast {
        final Vec3 centre, from;
        final long impactAt;
        /** The world it bursts in: a blast never follows its viewer into another. */
        final ClientLevel level;
        boolean sphereHeard, blastHeard, shockHeard;
        /** The stones the vortex tears up, noted before the land goes. */
        List<Stone> stones;

        Blast(Vec3 centre, Vec3 from, long impactAt, ClientLevel level) {
            this.centre = centre;
            this.from = from;
            this.impactAt = impactAt;
            this.level = level;
        }

        /** The level way from where the shot came to where it landed, and across it. */
        Vec3[] frame() {
            Vec3 forward = new Vec3(centre.x - from.x, 0, centre.z - from.z);
            forward = forward.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : forward.normalize();
            return new Vec3[]{forward, new Vec3(-forward.z, 0, forward.x), new Vec3(0, 1, 0)};
        }
    }

    /** A block the vortex tears up: where it was, what it was, when it goes, and where it swirls. */
    private record Stone(BlockPos pos, BlockState state, double takenAt, double radius, double height, double spin, int index) { }

    /** The server tells of a blast as the streams meet: remembered for the frames to come. */
    public static void blast(Vec3 centre, Vec3 from, long impactAt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || BLASTS.size() >= 8) return;
        BLASTS.add(new Blast(centre, from, impactAt, minecraft.level));
    }

    /** Drops blasts that have faded, and all of them when the world goes. */
    static void prune(double time) {
        var level = Minecraft.getInstance().level;
        BLASTS.removeIf(blast -> blast.level != level || time - blast.impactAt > BLAST_LIFE || time < blast.impactAt - ManaArmageddon.IMPACT);
    }

    private static void hear(SoundEvent sound, float volume, long seed) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound.getLocation(), SoundSource.PLAYERS,
                volume, 1F, RandomSource.create(seed), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    // --- the flowers ------------------------------------------------------------------------------

    /** The flowers' light for a shot at {@code time}; {@code chest} is where a shield link leaves the owner. */
    static void construct(ArmageddonState s, Vec3 chest, double time, Vec3 camera, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        double age = s.age(time);
        if (age < 0 || age > ManaArmageddon.END) return;
        double formed = smooth(age / ManaArmageddon.ASSEMBLED), closing = smooth((age - ManaArmageddon.IGNITE) / ManaArmageddon.COLLAPSE);
        double gone = smooth((age - ManaArmageddon.IGNITE - ManaArmageddon.COLLAPSE) / 30), alpha = formed * (1 - gone);
        double charge = ManaArmageddon.charge(age);
        if (alpha > .01) {
            for (int side : SIDES) {
                petals(s, side, age, time, charge, alpha * (1 - closing), camera, glow, m);
                seal(s, side, age, charge, alpha, camera, glow, runes, m);
                for (int ring = 1; ring < ManaArmageddon.RINGS; ring++) {
                    runeRing(ManaArmageddon.ringFrame(s, side, ring, age), camera, side, ring, ManaArmageddon.ringRadius(ring), .21,
                            ManaArmageddon.written(ring, age), age, alpha, true, glow, runes, m);
                }
            }
            central(s, age, charge, alpha, camera, glow, runes, m);
        }
        for (int side : SIDES) heart(s, side, age, charge, formed, camera, glow, m);
        // A worn Mana shield hands its charge up to both hearts while they charge, in a ribbon of beads.
        if (s.shieldLinked() && chest != null && age > ManaArmageddon.ASSEMBLED && age < ManaArmageddon.FIRE) {
            double link = Math.min(1, (age - ManaArmageddon.ASSEMBLED) / 10) * Math.min(1, (ManaArmageddon.FIRE - age) / 8);
            for (int side : SIDES) ribbon(chest.subtract(camera), ManaArmageddon.heart(s, side).subtract(camera), age, side, link, glow, m);
        }
        streams(s, age, camera, glow, m);
    }

    /** A petal drone's twinkle, over its model. */
    static void twinkle(VertexConsumer glow, Matrix4f m, Vec3 at, int slot, double time, int side) {
        double spark = Math.pow(.5 + .5 * Math.sin(time * (.25 + .3 * hash(slot, 11)) + hash(slot, 12) * 6.283), 4);
        if (spark > .05) GlowBrush.dot(glow, m, at, .07 + .05 * spark, GlowBrush.mix(ManaPalette.side(side, true), 0xFFFFFF, .5), 200 * spark);
    }

    /** Each petal: a soft rounded outline, filled from the heart out with sparks that twinkle as they drift. */
    private static void petals(ArmageddonState s, int side, double age, double time, double charge, double alpha, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        if (alpha < .01) return;
        Vec3[] face = ManaArmageddon.face(s, side);
        int color = ManaPalette.side(side, false), bright = ManaPalette.side(side, true);
        double middle = (ManaArmageddon.PETAL_BASE + ManaArmageddon.PETAL) / 2, half = (ManaArmageddon.PETAL - ManaArmageddon.PETAL_BASE) / 2;
        for (int petal = 0; petal < ManaArmageddon.PETALS; petal++) {
            double angle = ManaArmageddon.petalAngle(petal), cos = Math.cos(angle), sin = Math.sin(angle);
            double filled = Math.clamp((age - ManaArmageddon.filledBy(0)) / (ManaArmageddon.filledBy(1) - ManaArmageddon.filledBy(0)), 0, 1);
            Vec3 previous = null;
            for (int k = 0; k <= 48; k++) {
                double phi = Math.PI * 2 * k / 48, along = middle + half * Math.cos(phi), across = Math.signum(Math.sin(phi)) * ManaArmageddon.petalHalfWidth(along)
                        * Math.min(1, Math.abs(Math.sin(phi)) * 3);
                double out = -.12 * along / ManaArmageddon.PETAL;
                Vec3 at = ManaArmageddon.onFace(s, side, face, cos * along - sin * across, sin * along + cos * across, out).subtract(camera);
                if (previous != null) GlowBrush.line(glow, m, previous, at, .016, bright, (40 + 70 * charge) * alpha * filled);
                previous = at;
            }
            for (int k = 0; k < SPARKS; k++) {
                double[] place = ManaArmageddon.petalPlace(petal, k, SPARKS);
                double out = Math.hypot(place[0], place[1]) / ManaArmageddon.PETAL;
                double landed = Math.clamp((age - ManaArmageddon.filledBy(out)) / 6, 0, 1);
                if (landed <= 0) continue;
                int id = petal * 131 + k + (side > 0 ? 7000 : 0);
                double twinkle = Math.pow(.5 + .5 * Math.sin(time * (.2 + .35 * hash(id, 1)) + hash(id, 2) * 6.283), 3);
                double right = place[0] + .05 * Math.sin(time * .03 + hash(id, 3) * 6.283), up = place[1] + .05 * Math.cos(time * .027 + hash(id, 4) * 6.283);
                Vec3 at = ManaArmageddon.onFace(s, side, face, right, up, -.12 * out + .08 * (hash(id, 5) - .5)).subtract(camera);
                GlowBrush.dot(glow, m, at, .03 + .035 * hash(id, 6) + .03 * twinkle, GlowBrush.mix(color, 0xFFFFFF, .25 + .55 * twinkle),
                        (50 + 190 * twinkle) * alpha * landed * (.55 + .45 * charge));
            }
        }
    }

    /** The heart of a flower: a glowing core that swells and quickens with the charge, flares as the flower closes, then goes out. */
    private static void heart(ArmageddonState s, int side, double age, double charge, double formed, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double close = ManaArmageddon.IGNITE + ManaArmageddon.COLLAPSE;
        double lit = formed * (age < close ? 1 : Math.max(0, 1 - (age - close) / 24));
        if (lit < .01) return;
        Vec3 heart = ManaArmageddon.heart(s, side).subtract(camera);
        double pulse = .5 + .5 * Math.sin(age * (.18 + .6 * charge) + side), flare = age >= ManaArmageddon.IGNITE ? Math.exp(-(age - ManaArmageddon.IGNITE) / 7) : 0;
        double loosed = age >= ManaArmageddon.FIRE && age < ManaArmageddon.IGNITE ? 1 : 0;
        double core = .15 + .32 * charge + .1 * loosed;
        int bright = ManaPalette.side(side, true), color = ManaPalette.side(side, false);
        GlowBrush.dot(glow, m, heart, core * (1 + .15 * pulse) + 1.1 * flare, GlowBrush.mix(bright, 0xFFFFFF, .45 + .4 * charge), 240 * lit);
        GlowBrush.dot(glow, m, heart, core * 2.8 + 2.4 * flare, color, (55 + 95 * charge) * lit);
        // Motes spiralling into the heart while it charges.
        if (age > ManaArmageddon.ASSEMBLED * .6 && age < ManaArmageddon.FIRE) {
            Vec3[] face = ManaArmageddon.face(s, side);
            for (int mote = 0; mote < 36; mote++) {
                int id = mote + (side > 0 ? 500 : 0);
                double life = (age * .014 * (1 + charge) + hash(id, 21)) % 1, reach = 2.3 * (1 - life) + .15;
                double angle = hash(id, 22) * Math.PI * 2 - side * life * 5;
                Vec3 at = ManaArmageddon.onFace(s, side, face, Math.cos(angle) * reach, Math.sin(angle) * reach, (hash(id, 23) - .5) * .8 * (1 - life)).subtract(camera);
                GlowBrush.dot(glow, m, at, .04 + .03 * hash(id, 24), GlowBrush.mix(color, 0xFFFFFF, hash(id, 25) * .6), 190 * Math.sin(Math.PI * life) * (.4 + .6 * charge) * lit);
            }
        }
    }

    /** The seal behind a flower: a double ring round its belt of runes (the first ring written), a seven-pointed star, and a ring at the core. */
    private static void seal(ArmageddonState s, int side, double age, double charge, double alpha, Vec3 camera, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        Vec3[] f = ManaArmageddon.ringFrame(s, side, 0, age);
        Vec3 c = f[0].subtract(camera);
        double r = ManaArmageddon.SEAL_RADIUS, a = alpha * (95 + 115 * charge);
        int color = ManaPalette.side(side, false), bright = ManaPalette.side(side, true);
        GlowBrush.circle(glow, m, c, f[1], f[2], r * 1.07, 112, .02, bright, a);
        GlowBrush.circle(glow, m, c, f[1], f[2], r * .87, 112, .014, color, a * .85);
        double star = r * .84;
        for (int k = 0; k < 7; k++) {
            Vec3 from = point(c, f, star, Math.PI / 2 + k * Math.PI * 2 / 7), to = point(c, f, star, Math.PI / 2 + (k + 3) * Math.PI * 2 / 7);
            GlowBrush.line(glow, m, from, to, .012, color, a * .75);
            GlowBrush.dot(glow, m, from, .045, GlowBrush.mix(bright, 0xFFFFFF, .3), a);
        }
        GlowBrush.circle(glow, m, c, f[1], f[2], r * .6, 84, .011, color, a * .55);
        GlowBrush.circle(glow, m, c, f[1], f[2], r * .22, 44, .016, bright, a);
        GlowBrush.circle(glow, m, c, f[1], f[2], r * .15, 36, .01, color, a * .7);
        runeRing(f, camera, side, 0, r * .97, r * .17, ManaArmageddon.written(0, age), age, alpha, false, glow, runes, m);
    }

    /**
     * A ring of runes round {@code f}'s centre, {@code radius} out and {@code width} wide, written as far as
     * {@code written} (0..1): the runes one by one from the top, clockwise, each as the pen reaches it, the pen
     * a spark; with {@code borders}, a thin line either side, drawn as the runes are and faint ahead of them.
     */
    private static void runeRing(Vec3[] f, Vec3 camera, int side, int ring, double radius, double width, double written, double age, double alpha,
            boolean borders, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        Vec3 c = f[0].subtract(camera);
        double n = written * ManaArmageddon.RUNES;
        int color = ManaPalette.side(side, false), bright = ManaPalette.side(side, true);
        if (borders) {
            GlowBrush.circle(glow, m, c, f[1], f[2], radius + width * .55, 96, .008, color, 30 * alpha);
            GlowBrush.circle(glow, m, c, f[1], f[2], radius - width * .55, 96, .008, color, 30 * alpha);
            if (n > 0) for (double edge : new double[]{radius + width * .55, radius - width * .55}) {
                int steps = Math.max(2, (int) Math.ceil(96 * written));
                Vec3 previous = null;
                for (int k = 0; k <= steps; k++) {
                    Vec3 at = point(c, f, edge, ManaArmageddon.runeAngle(n * k / steps));
                    if (previous != null) GlowBrush.line(glow, m, previous, at, .012, bright, 150 * alpha);
                    previous = at;
                }
            }
        }
        for (int rune = 0; rune < ManaArmageddon.RUNES; rune++) {
            double part = Math.clamp(n - rune, 0, 1);
            if (part <= 0) break;
            double angle = ManaArmageddon.runeAngle(rune + .5);
            Vec3 out = f[1].scale(Math.cos(angle)).add(f[2].scale(Math.sin(angle))), along = f[1].scale(Math.sin(angle)).subtract(f[2].scale(Math.cos(angle)));
            double fresh = part < 1 ? 1 : Math.exp(-(n - rune - 1) / 1.5);
            ManaRunes.glyph(runes, m, c.add(out.scale(radius)), along, out, width * .92, ManaRunes.pick(ring * 2 + (side > 0 ? 1 : 0), rune),
                    iris(side, rune / (double) ManaArmageddon.RUNES + ring * .23 + age * .0008), (150 + 100 * fresh) * alpha, part);
        }
        if (written > 0 && written < 1) {
            Vec3 pen = point(c, f, radius, ManaArmageddon.runeAngle(n));
            GlowBrush.dot(glow, m, pen, .07, 0xFFFFFF, 235 * alpha);
            GlowBrush.dot(glow, m, pen, .22, bright, 110 * alpha);
        }
    }

    /**
     * The central seal over the owner's head: a double ring, its left half turquoise and its right half gold, round
     * a belt of runes; a rhombus in the middle; a line of small rhombi down the seam lighting one by one as the
     * charge grows; the sun on the turquoise half and a crescent moon on the gold one.
     */
    private static void central(ArmageddonState s, double age, double charge, double alpha, Vec3 camera, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        Vec3[] f = ManaArmageddon.centralFrame(s);
        Vec3 c = f[0].subtract(camera);
        Vec3[] plane = {f[0], f[1], f[2]};
        double r = ManaArmageddon.CENTRAL, a = alpha * (90 + 120 * charge);
        int turquoise = ManaPalette.BRIGHT_TURQUOISE, gold = ManaPalette.BRIGHT_GOLD;
        for (double ring : new double[]{1.0, .86}) {
            arc(glow, m, c, plane, r * ring, Math.PI / 2, Math.PI * 1.5, ring == 1 ? .018 : .012, turquoise, a);
            arc(glow, m, c, plane, r * ring, -Math.PI / 2, Math.PI / 2, ring == 1 ? .018 : .012, gold, a);
        }
        // The belt's runes: the left half turquoise, the right half gold, written as the charge grows.
        int belt = 20;
        double n = charge * belt;
        for (int rune = 0; rune < belt; rune++) {
            double part = Math.clamp(n - rune, 0, 1);
            if (part <= 0) break;
            double angle = Math.PI / 2 - (rune + .5) * Math.PI * 2 / belt;
            Vec3 out = f[1].scale(Math.cos(angle)).add(f[2].scale(Math.sin(angle))), along = f[1].scale(Math.sin(angle)).subtract(f[2].scale(Math.cos(angle)));
            ManaRunes.glyph(runes, m, c.add(out.scale(r * .93)), along, out, r * .13, ManaRunes.pick(9, rune), Math.cos(angle) < 0 ? turquoise : gold, 190 * alpha, part);
        }
        // The rhombus, split down the seam.
        double tall = r * .46, wide = r * .32;
        Vec3 top = at(c, f, 0, tall), bottom = at(c, f, 0, -tall), left = at(c, f, -wide, 0), right = at(c, f, wide, 0);
        GlowBrush.line(glow, m, top, left, .018, turquoise, a);
        GlowBrush.line(glow, m, left, bottom, .018, turquoise, a);
        GlowBrush.line(glow, m, top, right, .018, gold, a);
        GlowBrush.line(glow, m, right, bottom, .018, gold, a);
        // Small rhombi down the seam, lit one by one.
        double[] seam = {.58, .7, .8, -.58, -.7, -.8};
        for (int k = 0; k < seam.length; k++) {
            double lit = Math.clamp(charge * seam.length - k, 0, 1), y = seam[k] * r, d = r * .04;
            if (lit <= 0) continue;
            Vec3 t = at(c, f, 0, y + d * 1.5), b = at(c, f, 0, y - d * 1.5), l = at(c, f, -d, y), rr = at(c, f, d, y);
            int hue = GlowBrush.mix(turquoise, gold, .5);
            GlowBrush.line(glow, m, t, l, .01, hue, a * lit);
            GlowBrush.line(glow, m, l, b, .01, hue, a * lit);
            GlowBrush.line(glow, m, b, rr, .01, hue, a * lit);
            GlowBrush.line(glow, m, rr, t, .01, hue, a * lit);
        }
        GlowBrush.line(glow, m, at(c, f, 0, r * .86), at(c, f, 0, r * .5), .008, 0xFFFFFF, a * .6);
        GlowBrush.line(glow, m, at(c, f, 0, -r * .86), at(c, f, 0, -r * .5), .008, 0xFFFFFF, a * .6);
        // The sun on the turquoise half: a ring and its rays.
        Vec3 sun = at(c, f, -r * .62, 0);
        GlowBrush.circle(glow, m, sun, f[1], f[2], r * .1, 28, .012, turquoise, a);
        for (int ray = 0; ray < 8; ray++) {
            double angle = ray * Math.PI / 4 + age * .004;
            Vec3 out = f[1].scale(Math.cos(angle)).add(f[2].scale(Math.sin(angle)));
            GlowBrush.line(glow, m, sun.add(out.scale(r * .14)), sun.add(out.scale(r * (ray % 2 == 0 ? .22 : .18))), .01, turquoise, a);
        }
        // The crescent moon on the gold half.
        Vec3 moon = at(c, f, r * .62, 0);
        arc(glow, m, moon, plane, r * .13, -Math.PI * .8, Math.PI * .8, .014, gold, a);
        arc(glow, m, moon.add(f[1].scale(-r * .06)), plane, r * .1, -Math.PI * .62, Math.PI * .62, .01, gold, a * .8);
    }

    /** A flowing ribbon from the owner's chest up to a flower's heart, beads of light running along it. */
    private static void ribbon(Vec3 from, Vec3 to, double age, int side, double link, VertexConsumer glow, Matrix4f m) {
        Vec3 span = to.subtract(from), across = span.cross(new Vec3(0, 1, 0));
        across = across.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : across.normalize();
        int color = ManaPalette.side(side, false);
        Vec3 previous = null;
        for (int k = 0; k <= 24; k++) {
            double t = k / 24.0;
            Vec3 at = from.add(span.scale(t)).add(across.scale(Math.sin(t * Math.PI * 2 + age * .3 * side) * .18 * Math.sin(Math.PI * t))).add(0, Math.sin(Math.PI * t) * .6, 0);
            if (previous != null) GlowBrush.line(glow, m, previous, at, .025, color, 120 * link);
            previous = at;
            if ((k + (int) (age * .5)) % 6 == 0) GlowBrush.dot(glow, m, at, .06, GlowBrush.mix(color, 0xFFFFFF, .5), 200 * link);
        }
    }

    // --- the streams ------------------------------------------------------------------------------

    /**
     * The streams in flight and pouring into the collision until the flowers close: the turquoise one a dense,
     * writhing body with scales at its edge, the gold one a dazzling beam glinting in every colour.
     */
    private static void streams(ArmageddonState s, double age, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        if (age < ManaArmageddon.FIRE || age > ManaArmageddon.IGNITE + 16) return;
        double head = ManaArmageddon.streamHead(age), tail = ManaArmageddon.streamTail(age);
        if (head <= tail + 1e-4) return;
        for (int side : SIDES) {
            double length = ManaArmageddon.stream(s, side, 0).distanceTo(ManaArmageddon.stream(s, side, 1)) * 1.35;
            int samples = (int) Math.clamp(length * (head - tail) / 1.1, 12, 220);
            Vec3[] points = new Vec3[samples + 1];
            Vec3[][] axes = new Vec3[samples + 1][];
            for (int k = 0; k <= samples; k++) {
                double u = tail + (head - tail) * k / samples;
                points[k] = ManaArmageddon.stream(s, side, u).subtract(camera);
                axes[k] = ManaArmageddon.streamAxes(s, side, u);
            }
            double pour = Math.min(1, (age - ManaArmageddon.FIRE) / 3);
            if (side < 0) serpent(points, axes, length * (head - tail), age, pour, glow, m);
            else beam(points, axes, age, pour, glow, m);
            if (head < 1) {
                GlowBrush.dot(glow, m, points[samples], 1.1, GlowBrush.mix(ManaPalette.side(side, true), 0xFFFFFF, .6), 235);
                GlowBrush.dot(glow, m, points[samples], 2.6, ManaPalette.side(side, false), 90);
            }
        }
    }

    /** The turquoise stream: a dense core, three strands writhing round it, and leaf-like scales at its edge, pointing back. */
    private static void serpent(Vec3[] points, Vec3[][] axes, double length, double age, double alpha, VertexConsumer glow, Matrix4f m) {
        int last = points.length - 1;
        for (int k = 0; k < last; k++) {
            GlowBrush.line(glow, m, points[k], points[k + 1], .62, ManaPalette.DEEP_TURQUOISE, 150 * alpha);
            GlowBrush.line(glow, m, points[k], points[k + 1], .3, ManaPalette.TURQUOISE, 170 * alpha);
            GlowBrush.line(glow, m, points[k], points[k + 1], .09, ManaPalette.MIST, 200 * alpha);
        }
        for (int strand = 0; strand < 3; strand++) {
            Vec3 previous = null;
            for (int k = 0; k <= last; k++) {
                double along = length * k / Math.max(1, last), angle = strand * Math.PI * 2 / 3 + along * .45 - age * .3;
                double radius = .55 + .18 * Math.sin(along * .22 + age * .12 + strand);
                Vec3 at = points[k].add(axes[k][1].scale(Math.cos(angle) * radius)).add(axes[k][2].scale(Math.sin(angle) * radius));
                if (previous != null) GlowBrush.line(glow, m, previous, at, .1, strand == 0 ? ManaPalette.PALE_TURQUOISE : ManaPalette.BRIGHT_TURQUOISE, 160 * alpha);
                previous = at;
            }
        }
        // Scales: every so often a leaf at the edge, its tip trailing back along the stream.
        int step = Math.max(1, (int) Math.round(last / Math.max(1.0, length / .9)));
        for (int k = step; k < last; k += step) {
            double angle = k * 2.39996 + age * .05, lift = .75 + .15 * hash(k, 31);
            Vec3 out = axes[k][1].scale(Math.cos(angle)).add(axes[k][2].scale(Math.sin(angle)));
            Vec3 base = points[k].add(out.scale(.45)), tip = points[k].add(out.scale(lift)).subtract(axes[k][0].scale(.7));
            Vec3 bulge = axes[k][0].cross(out).scale(.14);
            Vec3 middle = base.lerp(tip, .5);
            GlowBrush.line(glow, m, base, middle.add(bulge), .03, ManaPalette.PALE_TURQUOISE, 150 * alpha);
            GlowBrush.line(glow, m, middle.add(bulge), tip, .025, ManaPalette.BRIGHT_TURQUOISE, 150 * alpha);
            GlowBrush.line(glow, m, base, middle.subtract(bulge), .03, ManaPalette.PALE_TURQUOISE, 150 * alpha);
            GlowBrush.line(glow, m, middle.subtract(bulge), tip, .025, ManaPalette.BRIGHT_TURQUOISE, 150 * alpha);
        }
    }

    /** The gold stream: a white-hot core in a golden glow, and glints of every colour flashing along it. */
    private static void beam(Vec3[] points, Vec3[][] axes, double age, double alpha, VertexConsumer glow, Matrix4f m) {
        int last = points.length - 1;
        for (int k = 0; k < last; k++) {
            GlowBrush.line(glow, m, points[k], points[k + 1], 1.4, ManaPalette.GOLD, 55 * alpha);
            GlowBrush.line(glow, m, points[k], points[k + 1], .45, ManaPalette.BRIGHT_GOLD, 160 * alpha);
            GlowBrush.line(glow, m, points[k], points[k + 1], .15, 0xFFFFFF, 240 * alpha);
        }
        for (int k = 1; k < last; k += 2) {
            double flash = Math.pow(.5 + .5 * Math.sin(age * (.5 + .4 * hash(k, 41)) + hash(k, 42) * 6.283), 6);
            if (flash < .05) continue;
            int colour = rainbow(hash(k, 43) + age * .01);
            double angle = hash(k, 44) * 6.283, reach = .3 + .5 * hash(k, 45);
            Vec3 at = points[k].add(axes[k][1].scale(Math.cos(angle) * reach)).add(axes[k][2].scale(Math.sin(angle) * reach));
            GlowBrush.dot(glow, m, at, .08 + .12 * flash, colour, 230 * flash * alpha);
            if (flash > .6) {
                GlowBrush.line(glow, m, at.subtract(axes[k][1].scale(.5 * flash)), at.add(axes[k][1].scale(.5 * flash)), .025, colour, 180 * flash * alpha);
                GlowBrush.line(glow, m, at.subtract(axes[k][2].scale(.5 * flash)), at.add(axes[k][2].scale(.5 * flash)), .025, colour, 180 * flash * alpha);
            }
        }
    }

    // --- where the streams meet ------------------------------------------------------------------

    /** Every blast this client knows of: its sounds as their moments come, and its light at the target. */
    static void blasts(double time, Vec3 camera, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        prune(time);
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt, distance = blast.centre.distanceTo(camera);
            float volume = (float) Math.clamp(1.25 - distance / (ManaArmageddon.RADIUS * 4), .35, 1);
            double sphereAt = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT;
            // Played only if this client is in time for them: a blast heard late would be out of step with its light.
            if (!blast.sphereHeard && t >= sphereAt) {
                blast.sphereHeard = true;
                if (t < sphereAt + 20) hear(RelicSounds.MANA_ARMAGEDDON_SPHERE.get(), volume, blast.impactAt + 2);
            }
            if (!blast.blastHeard && t >= 0) {
                blast.blastHeard = true;
                if (t < 20) hear(RelicSounds.MANA_ARMAGEDDON_BLAST.get(), volume, blast.impactAt);
                EffectLights.flash(blast.centre, 15, 110, ManaArmageddon.BLAST + 30);
            }
            if (!blast.shockHeard && distance <= ManaArmageddon.RADIUS * 1.2 && t >= ManaArmageddon.domeReaches(Math.min(distance, ManaArmageddon.RADIUS))) {
                blast.shockHeard = true;
                hear(RelicSounds.MANA_ARMAGEDDON_SHOCK.get(), (float) Math.clamp(1.1 - distance / ManaArmageddon.RADIUS * .6, .45, 1), blast.impactAt + 1);
            }
            Vec3 c = blast.centre.subtract(camera);
            Vec3[] f = blast.frame();
            flash(t, c, glow, m);
            clash(blast, t, c, f, glow, runes, m);
            vortex(t, c, glow, m);
            shards(blast, t, c, f, runes, glow, m);
            falling(blast, t, camera, glow, m);
        }
    }

    /**
     * The streams meeting head-on: a gold-white core throbbing in a turquoise corona, sparks flung out across the
     * streams' way, flakes torn off the turquoise stream tumbling away on its side, and glints on the gold one's.
     */
    private static void clash(Blast blast, double t, Vec3 c, Vec3[] f, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        double since = t - (ManaArmageddon.ARRIVE - ManaArmageddon.IMPACT), end = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT;
        double strength = Math.min(1, since / 3) * (1 - smooth((t - end) / 10));
        if (since < 0 || strength < .01) return;
        Vec3 core = c.add(0, 1.2, 0);
        double throb = .5 + .5 * Math.sin(since * 1.3);
        GlowBrush.dot(glow, m, core, 2 + .7 * throb, ManaPalette.IVORY, 245 * strength);
        GlowBrush.dot(glow, m, core, 5 + 1.5 * throb, ManaPalette.BRIGHT_GOLD, 120 * strength);
        GlowBrush.dot(glow, m, core, 9, ManaPalette.TURQUOISE, 60 * strength);
        for (int k = 0; k < SPRAY; k++) {
            double life = 14 + 8 * hash(k, 51), phase = ((since + hash(k, 52) * life) % life) / life;
            double angle = hash(k, 53) * Math.PI * 2, speed = 1.2 + 2.2 * hash(k, 54);
            // Out across the streams' way: in the plane of forward and up, with a little sideways scatter.
            Vec3 dir = f[0].scale(Math.cos(angle)).add(f[2].scale(Math.abs(Math.sin(angle)) * .9 + .1)).add(f[1].scale((hash(k, 55) - .5) * .5)).normalize();
            double run = speed * phase * life;
            Vec3 at = core.add(dir.scale(run)).add(0, -.02 * run * run * .3, 0);
            double fade = (1 - phase) * strength;
            if (k % 3 != 0) {
                int colour = k % 3 == 1 ? GlowBrush.mix(ManaPalette.BRIGHT_GOLD, 0xFFFFFF, hash(k, 56)) : rainbow(hash(k, 57));
                GlowBrush.line(glow, m, at.subtract(dir.scale(.9 + .6 * speed * .3)), at, .05, colour, 230 * fade);
            } else {
                // A flake of the turquoise stream, tumbling away on its own side.
                Vec3 side = f[1].scale(-1).add(dir.scale(.6)).normalize();
                Vec3 flake = core.add(side.scale(run * .8)).add(0, run * .15 - .01 * run * run, 0);
                double spin = since * .4 + k;
                Vec3 a = f[0].scale(Math.cos(spin)).add(f[2].scale(Math.sin(spin))), b = side.cross(a).normalize();
                GlowBrush.line(glow, m, flake.subtract(a.scale(.35)), flake.add(b.scale(.12)), .04, ManaPalette.BRIGHT_TURQUOISE, 210 * fade);
                GlowBrush.line(glow, m, flake.add(b.scale(.12)), flake.add(a.scale(.35)), .04, ManaPalette.PALE_TURQUOISE, 210 * fade);
                GlowBrush.line(glow, m, flake.add(a.scale(.35)), flake.subtract(b.scale(.12)), .04, ManaPalette.BRIGHT_TURQUOISE, 210 * fade);
                GlowBrush.line(glow, m, flake.subtract(b.scale(.12)), flake.subtract(a.scale(.35)), .04, ManaPalette.PALE_TURQUOISE, 210 * fade);
            }
        }
    }

    /**
     * The thin horizontal flash as the sphere shatters: a white-hot core and long thin rays shooting out level along the
     * land, a line of light across the world seen from the side and a star of rays from above.
     */
    private static void flash(double t, Vec3 c, VertexConsumer glow, Matrix4f m) {
        if (t < 0 || t > ManaArmageddon.FLASH + 14) return;
        double burst = t < ManaArmageddon.FLASH ? 1 : Math.exp(-(t - ManaArmageddon.FLASH) / 4), reach = 40 + 220 * smooth(t / 5);
        Vec3 core = c.add(0, 2.5, 0);
        GlowBrush.dot(glow, m, core, 6 + 10 * burst, 0xFFFFFF, 255 * burst);
        GlowBrush.dot(glow, m, core, 22, ManaPalette.PALE_GOLD, 150 * burst);
        for (int ray = 0; ray < 40; ray++) {
            double angle = ray * Math.PI * 2 / 40 + hash(ray, 101) * .1, length = reach * (.35 + .65 * hash(ray, 102));
            Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            GlowBrush.line(glow, m, core, core.add(out.scale(length)).add(0, (hash(ray, 103) - .5) * 1.2, 0), 1.6 - .8 * hash(ray, 104), .15,
                    0xFFFFFF, ray % 3 == 0 ? ManaPalette.BRIGHT_GOLD : ManaPalette.PALE_TURQUOISE, 250 * burst, 0);
        }
        // The level line of light the rays make, seen edge on: a broad, flat band through the core.
        GlowBrush.line(glow, m, core.add(-reach, 0, 0), core.add(reach, 0, 0), .8, 0xFFFFFF, 180 * burst);
        GlowBrush.line(glow, m, core.add(0, 0, -reach), core.add(0, 0, reach), .8, 0xFFFFFF, 180 * burst);
    }

    /** The vortex: spirals of light winding up round the column of torn-up land, turning ever faster until the sun ignites. */
    private static void vortex(double t, Vec3 c, VertexConsumer glow, Matrix4f m) {
        double from = ManaArmageddon.TEAR - ManaArmageddon.IMPACT, ignite = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT;
        double strength = smooth((t - from) / 20) * (1 - smooth((t - ignite) / 20));
        if (strength < .01) return;
        double since = t - from;
        for (int spiral = 0; spiral < 4; spiral++) {
            int colour = spiral % 2 == 0 ? ManaPalette.BRIGHT_TURQUOISE : ManaPalette.BRIGHT_GOLD;
            Vec3 previous = null;
            for (double h = 0; h <= 52; h += 1.3) {
                double radius = 13 - 8.5 * h / 52 + 1.6 * Math.sin(h * .21 + spiral), angle = spiral * Math.PI / 2 + h * .17 - since * (.12 + .002 * since);
                Vec3 at = c.add(Math.cos(angle) * radius, h, Math.sin(angle) * radius);
                if (previous != null) GlowBrush.line(glow, m, previous, at, .22, colour, 130 * strength * (1 - h / 64));
                previous = at;
            }
        }
    }

    /**
     * Rune shards as the sphere shatters: glyphs torn off its skin, flung out spinning and burning from white through
     * gold to orange as they fall; and burning runes skimming out with the ring of stones along the ground.
     */
    private static void shards(Blast blast, double t, Vec3 c, Vec3[] f, VertexConsumer runes, VertexConsumer glow, Matrix4f m) {
        if (t < 0 || t > 90) return;
        double radius = ManaArmageddon.SPHERE;
        for (int k = 0; k < SHARDS; k++) {
            double y = .06 + .92 * (k + .5) / SHARDS, around = k * 2.39996, r = Math.sqrt(1 - y * y);
            Vec3 dir = new Vec3(Math.cos(around) * r, y, Math.sin(around) * r);
            double speed = 1.1 + 1.4 * hash(k, 61), life = 60 + 30 * hash(k, 62);
            if (t > life) continue;
            Vec3 at = c.add(dir.scale(radius + speed * t)).add(0, -.028 * t * t, 0);
            Vec3 axis = new Vec3(hash(k, 63) - .5, hash(k, 64) - .5, hash(k, 65) - .5).normalize();
            double spin = t * (.08 + .15 * hash(k, 66)) + k;
            Vec3 right = rotate(dir.cross(new Vec3(0, 1, 0)).lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : dir.cross(new Vec3(0, 1, 0)).normalize(), axis, spin);
            Vec3 up = rotate(dir, axis, spin);
            double u = t / life;
            ManaRunes.glyph(runes, m, at, right, up, 2.2 - 1.2 * u, ManaRunes.pick(21, k), burning(u), 250 * (1 - u * u));
        }
        // Burning runes skimming out with the ring along the ground.
        for (int k = 0; k < GROUND_SHARDS; k++) {
            double angle = k * Math.PI * 2 / GROUND_SHARDS + hash(k, 71) * .3, run = ManaArmageddon.shock(t) * (.72 + .28 * hash(k, 72));
            double u = t / ManaArmageddon.SHOCK;
            if (u > 1) continue;
            Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            Vec3 at = c.add(out.scale(radius * .8 + run)).add(0, 1 + 2.5 * hash(k, 73) + 3 * Math.sin(Math.PI * u), 0);
            double flicker = .7 + .3 * Math.sin(t * 1.7 + k);
            ManaRunes.glyph(runes, m, at, out.cross(new Vec3(0, 1, 0)), new Vec3(0, 1, 0), 2.6, ManaRunes.pick(22, k), burning(u), 240 * (1 - u) * flicker);
            GlowBrush.dot(glow, m, at, 1.4, 0xFF8A3D, 90 * (1 - u) * flicker);
        }
    }

    /** Sparks falling slowly from the white sky, turquoise and gold, each patch of air round the blast with its own. */
    private static void falling(Blast blast, double t, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double fall = smooth((t - ManaArmageddon.BLAST) / 30) * (1 - smooth((t - ManaArmageddon.QUIET) / 50));
        if (fall < .01 || blast.centre.distanceTo(camera) > ManaArmageddon.RADIUS * 1.5) return;
        int cell = 9, span = 6, cx = (int) Math.floor(camera.x / cell), cz = (int) Math.floor(camera.z / cell);
        for (int gx = cx - span; gx <= cx + span; gx++) for (int gz = cz - span; gz <= cz + span; gz++) {
            for (int k = 0; k < 3; k++) {
                long seed = blast.impactAt ^ gx * 73856093L ^ gz * 19349663L ^ k * 83492791L;
                double x = (gx + hash(k, seed)) * cell, z = (gz + hash(k, seed + 1)) * cell;
                double y = blast.centre.y + 46 - ((t * (.05 + .03 * hash(k, seed + 2)) + hash(k, seed + 3) * 56) % 56);
                Vec3 at = new Vec3(x + Math.sin(t * .03 + k) * 1.5, y, z + Math.cos(t * .027 + k) * 1.5).subtract(camera);
                double twinkle = .5 + .5 * Math.sin(t * .2 + hash(k, seed + 4) * 6.283);
                GlowBrush.dot(glow, m, at, .06 + .06 * twinkle, k % 2 == 0 ? ManaPalette.BRIGHT_TURQUOISE : ManaPalette.BRIGHT_GOLD, (90 + 150 * twinkle) * fall);
            }
        }
    }

    // --- stones ----------------------------------------------------------------------------------

    /** Stones are drawn from a buffer of their own before the blast's light and haze go over the world, so those cover them too. */
    private static final MultiBufferSource.BufferSource ROCKS = MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 18));

    static void flushDebris() {
        ROCKS.endBatch();
    }

    /**
     * The stones the vortex tears up: each rises out of the land as the vortex reaches it and swirls round the
     * collision, slowing once the sun ignites and hanging round the sphere; as the sphere shatters they are flung out
     * along the ground with the ring.
     */
    static void debris(Minecraft minecraft, double time, Vec3 camera, PoseStack poses) {
        if (BLASTS.isEmpty()) return;
        var blocks = minecraft.getBlockRenderer();
        double ignite = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT, tear = ManaArmageddon.TEAR - ManaArmageddon.IMPACT;
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt;
            if (t < tear - 2 || t > 60 || dev.hurtify.relicsaddon.server.ArmageddonController.safeClient()) continue;
            if (blast.stones == null) blast.stones = noteStones(minecraft, blast.centre, blast.impactAt);
            for (Stone stone : blast.stones) {
                double since = t - stone.takenAt;
                if (since < 0) continue;
                Vec3 at = swirl(blast.centre, stone, Math.min(t, 0));
                if (t > 0) {
                    // Flung out along the ground with the ring, from wherever it swirled.
                    Vec3 out = new Vec3(at.x - blast.centre.x, 0, at.z - blast.centre.z);
                    out = out.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : out.normalize();
                    double speed = 1.6 + 1.8 * hash(stone.index, 81), lift = .35 + .5 * hash(stone.index, 82);
                    at = at.add(out.scale(speed * t)).add(0, lift * t - .035 * t * t, 0);
                    if (at.y < blast.centre.y - 40) continue;
                }
                double size = (.55 + .6 * hash(stone.index, 83)) * (1 - .25 * smooth(since / 20));
                rock(blocks, poses, stone.state, at.subtract(camera), size, since * .12 + stone.index, stone.index);
            }
        }
    }

    /** Where a torn-up stone swirls {@code t} ticks from the burst (t no later than the burst). */
    private static Vec3 swirl(Vec3 centre, Stone stone, double t) {
        double since = t - stone.takenAt, rise = smooth(since / 26);
        double x0 = stone.pos.getX() + .5 - centre.x, z0 = stone.pos.getZ() + .5 - centre.z, y0 = stone.pos.getY() + .5 - centre.y;
        double d0 = Math.hypot(x0, z0), a0 = Math.atan2(z0, x0);
        double ignite = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT;
        double turned = stone.spin * (Math.min(t, ignite) - stone.takenAt) + stone.spin * .3 * Math.max(0, t - ignite);
        double radius = d0 + (stone.radius - d0) * rise, height = y0 + (stone.height - y0) * rise;
        double angle = a0 + turned * rise;
        return centre.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    /**
     * A few hundred of the blocks round the collision, spread evenly: the top block of every column in the vortex's
     * reach, each taken as its reach passes that column, and each given its own orbit in the swirl.
     */
    private static List<Stone> noteStones(Minecraft minecraft, Vec3 centre, long seed) {
        List<Stone> all = new ArrayList<>();
        double radius = ManaArmageddon.CARVE_RADIUS, most = radius * radius;
        int reach = (int) Math.ceil(radius), cx = (int) Math.floor(centre.x), cz = (int) Math.floor(centre.z);
        for (int x = cx - reach; x <= cx + reach; x++) for (int z = cz - reach; z <= cz + reach; z++) {
            double dx = x + .5 - centre.x, dz = z + .5 - centre.z, flat = dx * dx + dz * dz;
            if (flat > most) continue;
            int y = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (Math.abs(y + .5 - centre.y) > Math.sqrt(most - flat)) continue;
            BlockPos at = new BlockPos(x, y, z);
            BlockState state = minecraft.level.getBlockState(at);
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
            all.add(new Stone(at, state, ManaArmageddon.tornAt(Math.sqrt(flat)) - ManaArmageddon.IMPACT, 0, 0, 0, 0));
        }
        int step = Math.max(1, all.size() / STONES);
        List<Stone> sample = new ArrayList<>();
        for (int index = 0; index < all.size(); index += step) {
            Stone stone = all.get(index);
            int n = sample.size();
            double orbit = 5 + 24 * Math.sqrt(hash(n, seed + 91)), height = 3 + 38 * hash(n, seed + 92);
            sample.add(new Stone(stone.pos, stone.state, stone.takenAt, orbit, height, .05 + .5 / (1 + orbit / 3), n));
        }
        return sample;
    }

    private static void rock(net.minecraft.client.renderer.block.BlockRenderDispatcher blocks, PoseStack poses, BlockState state, Vec3 at, double size, double spin, int index) {
        poses.pushPose();
        poses.translate(at.x, at.y, at.z);
        poses.mulPose(Axis.YP.rotation((float) (spin + index * 1.7)));
        poses.mulPose(Axis.XP.rotation((float) (spin * 1.3 + index)));
        poses.scale((float) size, (float) size, (float) size);
        poses.translate(-.5, -.5, -.5);
        blocks.renderSingleBlock(state, poses, ROCKS, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poses.popPose();
    }

    // --- the sphere, the seal, the blast and the column: their stages for ArmageddonVolume -----------

    /** Queues every blast's surfaces and haze for the renderer's volume passes, or draws the plainer stand-ins where a shader pack is in use. */
    static void volumes(double time, Vec3 camera, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt;
            if (t < ManaArmageddon.ARRIVE - ManaArmageddon.IMPACT || t > BLAST_LIFE) continue;
            ArmageddonVolume.ManaStage stage = stage(blast, t, camera);
            if (ShieldRefraction.shaderPackActive()) plain(blast, stage, camera, glow, runes, m);
            else ArmageddonVolume.queueMana(blast.centre.subtract(camera), stage);
        }
    }

    /**
     * The blast {@code t} ticks from its burst: the sphere of runes rising round the igniting sun, its runes written in a
     * running wave, the sun pressing until it shatters; the seal on the ground flashing up and living until the column
     * is gone; the thin flash, the ring of dust and its dark core; the dome of light sweeping out, holding and fading;
     * the column growing while the blast is heard and dissolving into white air; the moon in the white sky.
     */
    static ArmageddonVolume.ManaStage stage(Blast blast, double t, Vec3 camera) {
        double ignite = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT, tear = ManaArmageddon.TEAR - ManaArmageddon.IMPACT;
        Vec3 back = new Vec3(blast.from.x - blast.centre.x, 0, blast.from.z - blast.centre.z);
        back = back.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : back.normalize();
        boolean rising = t >= ignite && t < 0;
        double pressure = smooth((t - ignite - ManaArmageddon.SPHERE_WRITE * .6) / (-ignite - ManaArmageddon.SPHERE_WRITE * .6));
        double sphere = rising ? ManaArmageddon.SPHERE * smooth((t - ignite) / ManaArmageddon.SPHERE_RISE) * (1 + .025 * pressure * Math.sin(t * 1.7)) : 0;
        double written = Math.clamp((t - ignite - ManaArmageddon.SPHERE_RISE) / ManaArmageddon.SPHERE_WRITE, 0, 1);
        double sphereGlow = rising ? smooth((t - ignite) / 3) : 0;
        double grown = Math.pow(smooth((t - ignite) / -ignite), 1.6);
        double sun = rising ? .7 + (ManaArmageddon.SPHERE * .42 - .7) * grown : t >= 0 && t < 10 ? ManaArmageddon.SPHERE * .42 * (1 + t * .9) : 0;
        double sunGlow = rising ? smooth((t - ignite) / 2) * (1 + .5 * pressure) : t >= 0 && t < 10 ? 2 * Math.exp(-t / 3) : 0;
        double flash = t < 0 ? 0 : t < ManaArmageddon.FLASH ? 1 : Math.exp(-(t - ManaArmageddon.FLASH) / 4);
        double sealGlow = t < ignite ? 0 : (1.8 * Math.exp(-(t - ignite) / 6) + .75 * smooth((t - ignite) / 10)) * (1 - smooth((t - ManaArmageddon.BLAST) / 30))
                * (1 + .8 * flash);
        double column = ManaArmageddon.column(t);
        double columnGlow = t < ManaArmageddon.COLUMN ? 0 : smooth((t - ManaArmageddon.COLUMN) / 12)
                * (t < ManaArmageddon.BLAST ? 1 : 1 - smooth((t - ManaArmageddon.BLAST) / (ManaArmageddon.WHITE - ManaArmageddon.BLAST)));
        double shock = t < 0 ? 0 : smooth(t / 2) * (1 - smooth(t / (ManaArmageddon.SHOCK + 20)));
        double darkCore = t < 2 ? 0 : smooth((t - 2) / 4) * (1 - smooth((t - 30) / 20));
        double wave = t < ManaArmageddon.DOME ? 0 : smooth((t - ManaArmageddon.DOME) / 3)
                * (1 - smooth((t - ManaArmageddon.DOME_HOLD) / (ManaArmageddon.DOME_GONE - ManaArmageddon.DOME_HOLD)));
        double white = t < ManaArmageddon.BLAST ? 0 : t < ManaArmageddon.WHITE ? smooth((t - ManaArmageddon.BLAST) / (ManaArmageddon.WHITE - ManaArmageddon.BLAST))
                : (1 - .6 * smooth((t - ManaArmageddon.WHITE) / 30)) * (1 - smooth((t - ManaArmageddon.QUIET) / 50));
        double vortex = smooth((t - tear) / 15) * (1 - smooth((t - ignite) / 20));
        double moon = smooth((t - (ManaArmageddon.WHITE - 10)) / 30) * (1 - smooth((t - ManaArmageddon.QUIET) / 50));
        Vec3 moonAt = blast.centre.add(0, MOON_HEIGHT, 0).add(back.scale(20)).subtract(camera);
        // Lit from above and to one side, so that seen from the ground below and round it, it is a crescent.
        Vec3 right = new Vec3(0, 1, 0).cross(back).normalize();
        Vec3 moonLight = right.scale(.45).add(0, .9, 0).normalize();
        double near = ArmageddonVisual.near(blast.centre.distanceTo(camera));
        return new ArmageddonVolume.ManaStage(t, back, sphere, written, pressure, sphereGlow, t * .012, sun, sunGlow,
                t >= ignite && t < ManaArmageddon.WHITE ? ManaArmageddon.SEAL : 0, sealGlow, ((t - ignite) * 1.4) % (ManaArmageddon.SEAL * 1.4), (t - ignite) * .004,
                column, columnGlow, t * .06, columnGlow, sphereGlow * (.7 + .6 * pressure), sealGlow * .5,
                flash, ManaArmageddon.shock(t), shock, darkCore, ManaArmageddon.dome(t), wave, wave * .7, white, vortex, moon, moonAt, moonLight,
                lightOnWorld(t, near, pressure, column));
    }

    /**
     * The blast's light on the world {@code t} ticks from its burst, for a viewer {@code near} it (as
     * {@link ArmageddonVisual#lightOnWorld} gives the Twins blast's): a cold turquoise dimming as the streams collide,
     * dusk round the sphere lit gold by its sun, cold silhouettes in the flash, bright gold inside the dome of light and
     * dim turquoise outside it, a cool dusk round the column lit gold and turquoise by it, then white, and back.
     */
    static double[][] lightOnWorld(double t, double near, double pressure, double column) {
        double[] one = {1, 1, 1}, silhouettes = {.25, .42, .48};
        double[] world = one, sky = one, inside, glow = {0, 0, 0};
        double reach = 60, arrive = ManaArmageddon.ARRIVE - ManaArmageddon.IMPACT, ignite = ManaArmageddon.IGNITE - ManaArmageddon.IMPACT;
        if (t >= arrive) {
            double u = smooth((t - arrive) / 10), throb = .8 + .2 * Math.sin(t * 1.3);
            world = mix(one, new double[]{.78, .86, .9}, u);
            sky = mix(one, new double[]{.72, .84, .95}, u);
            glow = new double[]{.55 * throb * u, .5 * throb * u, .38 * throb * u};
        }
        if (t >= ignite) {
            double u = smooth((t - ignite) / 20), sun = .4 + pressure;
            world = mix(world, new double[]{.5, .62, .7}, u);
            sky = mix(sky, new double[]{.42, .58, .72}, u);
            glow = new double[]{1.0 * sun, .75 * sun, .4 * sun};
            reach = 30 + 40 * pressure;
        }
        inside = world;
        if (t >= 0) {
            double u = smooth((t - ManaArmageddon.FLASH) / 12);
            world = mix(silhouettes, new double[]{.45, .58, .66}, u);
            inside = mix(silhouettes, new double[]{1.25, 1.15, 1.0}, u);
            sky = mix(new double[]{1.35, 1.35, 1.3}, new double[]{.8, .92, 1.05}, u);
            glow = new double[]{1.2 * (1 - u), 1.1 * (1 - u), .9 * (1 - u)};
            reach = 120;
        }
        if (t >= ManaArmageddon.DOME_HOLD) {
            double u = smooth((t - ManaArmageddon.DOME_HOLD) / (ManaArmageddon.DOME_GONE - ManaArmageddon.DOME_HOLD));
            world = mix(world, new double[]{.55, .7, .76}, u);
            inside = mix(inside, new double[]{.55, .7, .76}, u);
            sky = mix(sky, new double[]{.5, .68, .8}, u);
        }
        if (t >= ManaArmageddon.COLUMN) {
            double grown = column / ManaArmageddon.COLUMN_RADIUS;
            glow = new double[]{glow[0] + .75 * grown, glow[1] + .68 * grown, glow[2] + .48 * grown};
            reach = 40 + 2.5 * column;
        }
        if (t >= ManaArmageddon.BLAST) {
            double u = smooth((t - ManaArmageddon.BLAST) / (ManaArmageddon.WHITE - ManaArmageddon.BLAST));
            world = mix(world, new double[]{1.6, 1.6, 1.55}, u);
            inside = world;
            sky = mix(sky, new double[]{2, 2, 2}, u);
            glow = new double[]{glow[0] * (1 - u), glow[1] * (1 - u), glow[2] * (1 - u)};
        }
        if (t >= ManaArmageddon.WHITE) {
            world = mix(new double[]{1.6, 1.6, 1.55}, new double[]{1.2, 1.2, 1.18}, smooth((t - ManaArmageddon.WHITE) / 30));
            inside = world;
            sky = new double[]{2, 2, 2};
        }
        if (t >= ManaArmageddon.QUIET) {
            double u = smooth((t - ManaArmageddon.QUIET) / 50);
            world = mix(world, one, u);
            inside = world;
            sky = mix(sky, one, u);
            glow = new double[]{0, 0, 0};
        }
        return new double[][]{mix(one, world, near), mix(one, sky, near), mix(one, inside, near), {glow[0] * near, glow[1] * near, glow[2] * near}, {reach}};
    }

    private static double[] mix(double[] a, double[] b, double u) {
        return new double[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
    }

    /**
     * Where a shader pack is in use and the screen passes cannot run: plainer stand-ins drawn as light in the world
     * instead, as {@link ShieldRefraction} falls back: the sphere a glassy shell ringed with runes round a bright sun, the
     * seal a few rings on the ground, the dome a sweeping ring of light, the column a tube of lines and rising runes.
     */
    private static void plain(Blast blast, ArmageddonVolume.ManaStage s, Vec3 camera, VertexConsumer glow, VertexConsumer runes, Matrix4f m) {
        Vec3 c = blast.centre.subtract(camera);
        Vec3 east = new Vec3(1, 0, 0), north = new Vec3(0, 0, 1);
        if (s.sphere() > .1) {
            GlowBrush.sphere(glow, m, c, s.sphere(), ManaPalette.DEEP_TURQUOISE, ManaPalette.BRIGHT_TURQUOISE, 12 * s.sphereGlow(), 90 * s.sphereGlow(), 20);
            for (int k = 0; k < 36; k++) {
                double angle = k * Math.PI * 2 / 36 + s.sphereTurn();
                Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle));
                if (k < 36 * s.written()) ManaRunes.glyph(runes, m, c.add(out.scale(s.sphere())).add(0, s.sphere() * .2, 0), out.cross(new Vec3(0, 1, 0)), new Vec3(0, 1, 0),
                        2.4, ManaRunes.pick(30, k), GlowBrush.mix(k < 18 ? ManaPalette.BRIGHT_TURQUOISE : ManaPalette.BRIGHT_GOLD, 0xFFFFFF, s.pressure()), 220 * s.sphereGlow());
            }
        }
        if (s.sun() > .1 && s.sunGlow() > .01) {
            Vec3 sun = c.add(0, ManaArmageddon.SUN_LIFT, 0);
            GlowBrush.dot(glow, m, sun, s.sun(), 0xFFF4D8, Math.min(255, 240 * s.sunGlow()));
            GlowBrush.dot(glow, m, sun, s.sun() * 2.4, ManaPalette.BRIGHT_GOLD, Math.min(255, 110 * s.sunGlow()));
        }
        if (s.seal() > .1 && s.sealGlow() > .01) {
            Vec3 floor = c.add(0, .3, 0);
            for (double share : new double[]{.1, .24, .38, .5, .63, .82, 1.0}) {
                GlowBrush.circle(glow, m, floor, east, north, s.seal() * share, 128, .18, ManaPalette.SEAL_BLUE, Math.min(255, 160 * s.sealGlow()));
            }
        }
        if (s.wave() > .01 && s.front() > 1) GlowBrush.circle(glow, m, c.add(0, 2, 0), east, north, s.front(), 160, 3, ManaPalette.IVORY, 150 * s.wave());
        if (s.column() > .1 && s.columnGlow() > .01) {
            for (double h = 0; h < 260; h += 20) GlowBrush.circle(glow, m, c.add(0, h, 0), east, north, s.column(), 96, .5, h % 40 == 0 ? ManaPalette.BRIGHT_TURQUOISE : ManaPalette.BRIGHT_GOLD,
                    150 * s.columnGlow() * (1 - h / 300));
            for (int k = 0; k < 24; k++) {
                double angle = k * Math.PI * 2 / 24;
                Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(s.column());
                GlowBrush.line(glow, m, c.add(out), c.add(out).add(0, 260, 0), .4, ManaPalette.PALE_GOLD, 90 * s.columnGlow());
            }
        }
    }

    // --- the ground shaking ------------------------------------------------------------------------

    /** How hard the ground shakes at {@code camera}: the collision's rumble, the sphere's pressure, the kick of the ring and dome passing, the column's hum. */
    static double shake(Vec3 camera, double time) {
        double shake = 0;
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt, distance = camera.distanceTo(blast.centre);
            if (distance > ManaArmageddon.RADIUS * 1.3) continue;
            double near = 1 - distance / (ManaArmageddon.RADIUS * 1.3);
            double collide = t >= ManaArmageddon.ARRIVE - ManaArmageddon.IMPACT && t < ManaArmageddon.IGNITE - ManaArmageddon.IMPACT ? .35 : 0;
            double press = t >= ManaArmageddon.IGNITE - ManaArmageddon.IMPACT && t < 0 ? .15 + .45 * (1 + t / (ManaArmageddon.IMPACT - ManaArmageddon.IGNITE)) : 0;
            double ring = distance < ManaArmageddon.SHOCK_RADIUS && t >= 0 ? shockPasses(distance, t) : 0;
            double dome = t >= ManaArmageddon.domeReaches(Math.min(distance, ManaArmageddon.RADIUS)) ? Math.exp(-(t - ManaArmageddon.domeReaches(Math.min(distance, ManaArmageddon.RADIUS))) / 18) : 0;
            double hum = t > 60 && t < ManaArmageddon.BLAST ? .12 : 0;
            shake = Math.max(shake, (collide + press + 1.6 * ring + 1.6 * dome + hum) * near);
        }
        return shake;
    }

    /** How hard the ring of stones kicks the ground {@code distance} blocks out as it passes, {@code t} ticks after the burst. */
    private static double shockPasses(double distance, double t) {
        double passed = t;
        for (double u = 0; u <= ManaArmageddon.SHOCK; u += 1) if (ManaArmageddon.shock(u) >= distance) { passed = t - u; break; }
        return passed >= 0 ? Math.exp(-passed / 12) : 0;
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** A flower colour shimmering along a ring, as a film of oil does: turquoise through aquamarine and sky, or gold through amber and ivory. */
    private static int iris(int side, double phase) {
        double p = phase - Math.floor(phase);
        int[] colours = side < 0 ? new int[]{ManaPalette.BRIGHT_TURQUOISE, 0x7FFFD4, 0x9AD8FF, ManaPalette.PALE_TURQUOISE}
                : new int[]{ManaPalette.BRIGHT_GOLD, 0xFFC061, 0xFFE9A8, ManaPalette.IVORY};
        double at = p * colours.length;
        int k = (int) Math.floor(at);
        return GlowBrush.mix(colours[k % colours.length], colours[(k + 1) % colours.length], at - k);
    }

    /** A bright colour of the rainbow at {@code phase} round it. */
    private static int rainbow(double phase) {
        double h = (phase - Math.floor(phase)) * 6;
        int k = (int) h;
        double f = h - k;
        double[] rgb = switch (k) {
            case 0 -> new double[]{1, f, 0};
            case 1 -> new double[]{1 - f, 1, 0};
            case 2 -> new double[]{0, 1, f};
            case 3 -> new double[]{0, 1 - f, 1};
            case 4 -> new double[]{f, 0, 1};
            default -> new double[]{1, 0, 1 - f};
        };
        return (int) (155 + 100 * rgb[0]) << 16 | (int) (155 + 100 * rgb[1]) << 8 | (int) (155 + 100 * rgb[2]);
    }

    /** A burning rune's colour as it cools: white-hot, gold, then orange. */
    private static int burning(double u) {
        return u < .35 ? GlowBrush.mix(0xFFFFFF, ManaPalette.BRIGHT_GOLD, u / .35) : GlowBrush.mix(ManaPalette.BRIGHT_GOLD, 0xFF7A2E, (u - .35) / .65);
    }

    private static Vec3 point(Vec3 centre, Vec3[] f, double radius, double angle) {
        return centre.add(f[1].scale(Math.cos(angle) * radius)).add(f[2].scale(Math.sin(angle) * radius));
    }

    private static Vec3 at(Vec3 centre, Vec3[] f, double right, double up) {
        return centre.add(f[1].scale(right)).add(f[2].scale(up));
    }

    private static void arc(VertexConsumer glow, Matrix4f m, Vec3 centre, Vec3[] f, double radius, double from, double to, double width, int colour, double alpha) {
        int steps = Math.max(4, (int) Math.ceil(Math.abs(to - from) / (Math.PI * 2) * 96));
        Vec3 previous = null;
        for (int k = 0; k <= steps; k++) {
            Vec3 at = point(centre, f, radius, from + (to - from) * k / steps);
            if (previous != null) GlowBrush.line(glow, m, previous, at, width, colour, alpha);
            previous = at;
        }
    }

    private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        return dev.hurtify.relicsaddon.drone.HiveShapes.rotate(v, axis, angle);
    }

    static double hash(int index, long salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ (salt + 1) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    static double smooth(double x) {
        double t = Math.clamp(x, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private ManaArmageddonVisual() {
    }
}
