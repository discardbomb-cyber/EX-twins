package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.drone.HiveShapes;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The light of Armageddon (see {@link Armageddon}). Over the owner's head: the cannon's core with
 * violet motes spiralling into it, a two-layer barrel of hexagons with charge running up its axis,
 * four double rings lighting one by one and glowing hot after the shot, the gyroscope of hoops round
 * it all, whose drones peel off and gather again as the two-layer focus funnel, the layered beam, and
 * the rings the shot throws off the muzzle; a worn Twins shield sends its charge up to the core along a
 * crackling link. Where the beam lands, the blast plays out in stages for as long as it is heard (see
 * the blast section below), its domes and shock wave bending the world behind them.
 * {@link ArmageddonShake} shakes the ground as the blast sweeps over.
 */
public final class ArmageddonVisual {
    static final int VIOLET = 0xB151FF, PALE = 0xE7C6FF, HOT = 0xFF9A4D, DIM = 0x4B2A78, DEEP = 0x7A2BE0, MAGENTA = 0xE24BFF, PINK = 0xFF5CD6;
    /**
     * How long the blast's light lasts: as long as its sound (24 s), whole while its chord hangs in the air
     * and dying away with it. A blast is dropped a little after.
     */
    static final int LIGHT = Armageddon.GONE + 4, HANGS = Armageddon.GONE - 40, BLAST_LIFE = LIGHT + 40;
    private static final int MOTES = 70;
    /** Blasts this client was told of, oldest first. */
    static final List<Blast> BLASTS = new ArrayList<>();

    /** A blast this client knows of; {@code shockHeard} once its shock wave has reached this client and been heard. */
    static final class Blast {
        final Vec3 centre;
        final long impactAt;
        /** The world it burst in: a blast never follows its viewer into another. */
        final net.minecraft.client.multiplayer.ClientLevel level;
        boolean shockHeard;

        Blast(Vec3 centre, long impactAt, net.minecraft.client.multiplayer.ClientLevel level) {
            this.centre = centre;
            this.impactAt = impactAt;
            this.level = level;
        }

        Vec3 centre() {
            return centre;
        }

        long impactAt() {
            return impactAt;
        }
    }

    /** The server tells of a {@code hive} Armageddon's blast at {@code centre}, fired from {@code from}, bursting at {@code impactAt}. */
    public static void told(dev.hurtify.relicsaddon.drone.HiveType hive, Vec3 centre, Vec3 from, long impactAt) {
        if (hive == dev.hurtify.relicsaddon.drone.HiveType.MANA) ManaArmageddonVisual.blast(centre, from, impactAt);
        else blast(centre, impactAt);
    }

    /** A blast lands: remembered for the frames to come, heard at once, wherever this client is. */
    public static void blast(Vec3 centre, long impactAt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || BLASTS.size() >= 8) return;
        BLASTS.add(new Blast(centre, impactAt, minecraft.level));
        double distance = minecraft.gameRenderer.getMainCamera().getPosition().distanceTo(centre);
        hear(RelicSounds.ARMAGEDDON_BLAST.get(), (float) Math.clamp(1.25 - distance / (Armageddon.RADIUS * 4), .35, 1), impactAt);
        EffectLights.flash(centre, 15, 96, LIGHT);
    }

    private static void hear(SoundEvent sound, float volume, long seed) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound.getLocation(), SoundSource.PLAYERS,
                volume, 1F, RandomSource.create(seed), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    /** The blast's light {@code t} ticks after it lands: whole while its chord hangs in the air, then dying away with the sound. */
    static double sustain(double t) {
        return t < HANGS ? 1 : Math.max(0, 1 - smooth((t - HANGS) / (LIGHT - HANGS)));
    }

    /** The light breathes slowly with the chord's slow beat. */
    static double throb(double t) {
        return .88 + .12 * Math.sin(Math.PI * 2 * .2 * t / 20);
    }

    /** Drops blasts that have faded, and all of them when the world goes. */
    static void prune(double time) {
        var level = Minecraft.getInstance().level;
        BLASTS.removeIf(blast -> blast.level != level || time - blast.impactAt > BLAST_LIFE || time < blast.impactAt - 200);
    }

    // --- the cannon ------------------------------------------------------------------------------

    /** The cannon's light for a shot at {@code time}; {@code chest} is where a shield link leaves the owner. */
    static void cannon(ArmageddonState s, Vec3 chest, double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double age = s.age(time);
        if (age < 0 || age > Armageddon.END) return;
        double formed = smooth(age / Armageddon.ASSEMBLED), gone = age > Armageddon.RECOVER ? smooth((age - Armageddon.RECOVER) / 20) : 0;
        double alpha = formed * (1 - gone);
        if (alpha <= .01 && age < Armageddon.FIRE) return;
        Vec3[] f = Armageddon.frame(s);
        double charge = Armageddon.charge(age), pulse = .5 + .5 * Math.sin(age * (.3 + .9 * charge));
        double heat = age >= Armageddon.FIRE ? Math.exp(-(age - Armageddon.FIRE) / 55) : 0;
        double reformed = Math.clamp((age - Armageddon.CHARGED) / (Armageddon.FIRE - Armageddon.CHARGED), 0, 1);

        // The core: a ball of light that swells and quickens with the charge, white-hot as the shot leaves,
        // and violet motes spiralling into it while it charges.
        Vec3 core = Armageddon.point(s, f, Armageddon.CORE, 0, 0).subtract(camera);
        GlowBrush.dot(glow, m, core, .8 + .9 * charge + .2 * pulse, GlowBrush.mix(VIOLET, 0xFFFFFF, .25 + .6 * charge), 230 * alpha);
        GlowBrush.dot(glow, m, core, 1.6 + 1.2 * charge, VIOLET, (50 + 80 * charge) * alpha);
        if (age > Armageddon.ASSEMBLED * .5 && age < Armageddon.FIRE) {
            for (int mote = 0; mote < MOTES; mote++) {
                double life = (age * .012 * (1 + charge) + hash(mote, 1)) % 1, reach = 7 * (1 - life) + .4;
                double angle = hash(mote, 2) * Math.PI * 2 + life * 7, rise = (hash(mote, 3) - .5) * 5 * (1 - life);
                Vec3 at = Armageddon.point(s, f, Armageddon.CORE + rise, Math.cos(angle) * reach, Math.sin(angle) * reach).subtract(camera);
                GlowBrush.dot(glow, m, at, .09 + .06 * hash(mote, 4), GlowBrush.mix(VIOLET, PALE, hash(mote, 5)), 200 * alpha * Math.sin(Math.PI * life) * (.4 + .6 * charge));
            }
        }

        // The barrel: two layers of hexagons turning opposite ways, charge running up its axis in dashes.
        double from = Armageddon.BARREL_FROM, length = Armageddon.MUZZLE - from;
        int barrel = GlowBrush.mix(GlowBrush.mix(DIM, VIOLET, .45 + .55 * charge), HOT, heat * .6);
        honeycomb(glow, m, camera, s, f, 16, 10, alpha * (60 + 90 * charge), barrel, age * .01, u -> from + u * length, u -> Armageddon.BARREL_OUTER);
        honeycomb(glow, m, camera, s, f, 12, 7, alpha * (50 + 110 * charge), GlowBrush.mix(barrel, PALE, .3), -age * .02, u -> from + u * length, u -> Armageddon.BARREL_INNER);
        Vec3 breech = Armageddon.point(s, f, Armageddon.CORE, 0, 0), muzzle = Armageddon.point(s, f, Armageddon.MUZZLE, 0, 0);
        double run = age * (.12 + .55 * charge);
        for (int dash = 0; dash < 14; dash++) {
            double at = ((dash / 14.0 + run / length) % 1) * length;
            Vec3 a = breech.add(f[0].scale(at)), b = breech.add(f[0].scale(Math.min(length, at + .45)));
            GlowBrush.line(glow, m, a.subtract(camera), b.subtract(camera), .06 + .05 * charge, GlowBrush.mix(VIOLET, 0xFFFFFF, .5), (50 + 190 * charge) * alpha);
        }

        // The double rings: dim until each lights, then bright, the inner and outer turning against each other;
        // after the shot they glow hot and cool.
        for (int ring = 0; ring < Armageddon.RINGS; ring++) {
            double lit = Math.clamp((age - Armageddon.ringLit(ring)) / 8, 0, 1), flash = Math.exp(-Math.max(0, age - Armageddon.ringLit(ring)) / 6) * lit;
            int color = GlowBrush.mix(GlowBrush.mix(DIM, VIOLET, lit), HOT, heat * .85);
            double a = (70 + 170 * lit) * alpha, radius = Armageddon.ringRadius(ring), turn = Armageddon.ringTurn(ring, age);
            Vec3 centre = Armageddon.point(s, f, Armageddon.ringAt(ring), 0, 0).subtract(camera);
            GlowBrush.circle(glow, m, centre, f[1], f[2], radius * 1.06, 56, .07, color, a);
            GlowBrush.circle(glow, m, centre, f[1], f[2], radius * .84, 56, .045, color, a * .8);
            chevrons(glow, m, centre, f, 9, turn, radius * .8, radius * 1.14, .09, GlowBrush.mix(color, 0xFFFFFF, .35), a);
            GlowBrush.circle(glow, m, centre, f[1], f[2], radius * .62, 40, .05, GlowBrush.mix(color, PALE, .3), a * .85);
            chevrons(glow, m, centre, f, 6, -turn * 1.4, radius * .5, radius * .7, .07, GlowBrush.mix(color, PALE, .45), a * .9);
            if (flash > .02) GlowBrush.dot(glow, m, centre, radius * (1.2 + .8 * (1 - flash)), 0xFFFFFF, 200 * flash * alpha);
        }

        // The gyroscope: hoops at different angles sweeping round the cannon, in two layers, fading as their drones leave.
        double hoops = alpha * (1 - reformed);
        if (hoops > .01) {
            for (int hoop = 0; hoop < Armageddon.HOOPS; hoop++) {
                int color = GlowBrush.mix(hoop < 3 ? VIOLET : DEEP, PALE, .25 + .35 * charge);
                Vec3 previous = null;
                for (int step = 0; step <= 96; step++) {
                    Vec3 at = Armageddon.hoopPoint(s, f, hoop, Math.PI * 2 * step / 96, age).subtract(camera);
                    if (previous != null) GlowBrush.line(glow, m, previous, at, hoop < 3 ? .05 : .065, color, (90 + 90 * charge) * hoops);
                    previous = at;
                }
                // Marks along each hoop, running with its drones.
                for (int mark = 0; mark < 16; mark++) {
                    double angle = mark * Math.PI * 2 / 16 + Armageddon.hoopRun(hoop, age) * .5;
                    Vec3 at = Armageddon.hoopPoint(s, f, hoop, angle, age).subtract(camera), next = Armageddon.hoopPoint(s, f, hoop, angle + .06, age).subtract(camera);
                    GlowBrush.line(glow, m, at, next, .14, GlowBrush.mix(color, 0xFFFFFF, .4), (110 + 80 * charge) * hoops);
                }
            }
        }

        // The focus funnel, two layers of hexagons, gathering as the drones land in it and gone as they leave with the shot.
        double focus = reformed * (age < Armageddon.FIRE ? 1 : Math.max(0, 1 - (age - Armageddon.FIRE) / 12));
        if (focus > 0) {
            double funnel = Armageddon.funnelLength(s);
            int color = GlowBrush.mix(VIOLET, PALE, .45);
            honeycomb(glow, m, camera, s, f, 12, 14, focus * alpha * 90, color, age * .03,
                    u -> Armageddon.FUNNEL_START + u * funnel, u -> Armageddon.funnelRadius(u * funnel, age));
            honeycomb(glow, m, camera, s, f, 10, 10, focus * alpha * 70, GlowBrush.mix(color, DEEP, .4), -age * .045,
                    u -> Armageddon.FUNNEL_START + .4 + u * funnel * .92, u -> Armageddon.funnelRadius(u * funnel * .92, age) * .7);
        }

        // The shot: a black hole taking shape in the funnel, fired, flying out, hanging over its target.
        shot(s, f, age, camera, glow, m);

        // A worn Twins shield hands its charge up to the core while the cannon charges.
        if (s.shieldLinked() && chest != null && age > Armageddon.ASSEMBLED && age < Armageddon.FIRE) {
            double link = Math.min(1, (age - Armageddon.ASSEMBLED) / 10) * Math.min(1, (Armageddon.FIRE - age) / 8);
            GlowBrush.lightning(glow, m, chest.subtract(camera), core, (long) (age / 2) * 31, 10, .05, .05, PALE, 170 * link);
            GlowBrush.beam(glow, m, chest.subtract(camera), core, .04, VIOLET, 110 * link);
        }
    }

    private static void chevrons(VertexConsumer glow, Matrix4f m, Vec3 centre, Vec3[] f, int count, double turn, double inner, double outer,
            double width, int color, double alpha) {
        for (int chevron = 0; chevron < count; chevron++) {
            double angle = turn + chevron * Math.PI * 2 / count;
            Vec3 out = f[1].scale(Math.cos(angle)).add(f[2].scale(Math.sin(angle)));
            GlowBrush.line(glow, m, centre.add(out.scale(inner)), centre.add(out.scale(outer)), width, color, alpha);
        }
    }

    /**
     * The shot: a ball of white light taking shape in the focus funnel as its drones gather there, then flying
     * out held in three dense violet tori of the funnel's drones, so that it cannot open before its time. As it
     * touches its target it opens into a black hole 15 blocks across round the point it touched, bending the world
     * round it hard (it casts no shadow); its containment of drones breaks away in a whoosh, and it collapses into
     * that point, devouring the land for 90 blocks round and beating like a pulsar, until it bursts.
     */
    private static void shot(ArmageddonState s, Vec3[] f, double age, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double since = age - Armageddon.FIRE;
        double size = Armageddon.shotSize(age);
        if (size < .05 || age >= Armageddon.IMPACT) return;
        if (age < Armageddon.ARRIVE) {
            Vec3 at = Armageddon.shot(s, age).subtract(camera);
            ArmageddonVolume.queueOrb(at, size * 2.2, Math.min(1, size / Armageddon.SHOT_HORIZON), age, true);
            if (since > 0) escortTori(glow, m, at, age, Math.min(1, since / 10));
            return;
        }
        double opened = smooth((age - Armageddon.ARRIVE) / 3), crushed = smooth((age - Armageddon.HUNGER) / (Armageddon.IMPACT - 4 - Armageddon.HUNGER));
        double horizon = .4 + (Armageddon.HOLE - .4) * opened * (1 - crushed);
        Vec3 touched = s.target().subtract(camera), at = touched;
        giantHole(glow, GlowBrush.flat() ? glow : ShieldGlow.horizonConsumer(), m, at, horizon, age);
        // Bending the world round it hard: its Einstein ring well out from the horizon.
        if (!GlowBrush.flat()) BlackHoleLens.queue(at, horizon, horizon * 4.5 + 6, 0, 1.6);
        // The pulsar beating at the point it collapses into.
        if (age >= Armageddon.HUNGER) ArmageddonVolume.queueOrb(touched.add(0, 1, 0), 1.5 + 5 * (1 - crushed) + 4 * pulsar(age), .4 + .9 * pulsar(age), age, true);
        // The containment of drones breaking away, gone in a whoosh.
        double held = 1 - smooth((age - Armageddon.ARRIVE) / (Armageddon.BROKEN - Armageddon.ARRIVE));
        if (held > .01) escortTori(glow, m, touched.add(0, 2, 0), age, held);
    }

    /** The black hole the ball opens into: a horizon of utter black, its photon ring, and a thin disk of light at its waist. */
    private static void giantHole(VertexConsumer glow, VertexConsumer horizonFill, Matrix4f m, Vec3 core, double horizon, double time) {
        GlowBrush.sphere(horizonFill, m, core, horizon, 0x000000, 0x0A0214, 255, 255, 32);
        Vec3 eye = GlowBrush.view(core);
        Vec3 right = eye.cross(Math.abs(eye.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), up = right.cross(eye);
        GlowBrush.circle(glow, m, core, right, up, horizon * 1.02, 160, Math.max(.3, horizon * .015), 0xF2DDFF, 235);
        GlowBrush.circle(glow, m, core, right, up, horizon * 1.12, 160, Math.max(.6, horizon * .05), 0xA24BFF, 90);
        Vec3 u = new Vec3(1, 0, 0), v = new Vec3(0, Math.sin(.1), Math.cos(.1));
        for (int band = 0; band < 6; band++) {
            double r = horizon * (1.25 + .1 * band), turn = time * (.04 - .004 * band);
            GlowBrush.circle(glow, m, core, u.scale(Math.cos(turn)).add(v.scale(Math.sin(turn))), v.scale(Math.cos(turn)).subtract(u.scale(Math.sin(turn))),
                    r, 180, Math.max(.25, horizon * .02), GlowBrush.mix(0xFFF0FF, 0x7A22D6, band / 5.0), 200 - 25 * band);
        }
    }

    /**
     * The pulsar's beat as the black hole devours: a flash at each beat, the beats coming faster every second, on
     * the same beats the sound clicks (five a second at first, nine times as fast by the burst).
     */
    private static double pulsar(double age) {
        double hunger = (Armageddon.HUNGER - Armageddon.ARRIVE) / 20.0, span = (Armageddon.IMPACT - Armageddon.ARRIVE) / 20.0 - hunger;
        double u = Math.clamp(((age - Armageddon.ARRIVE) / 20.0 - hunger) / span, 0, 1);
        double beats = 5 * span * (Math.pow(9, u) - 1) / Math.log(9);
        return Math.exp(-(beats - Math.floor(beats)) * 6);
    }

    /** The three dense violet tori of drones flying round the black hole, like the Twins containment's. */
    private static void escortTori(VertexConsumer glow, Matrix4f m, Vec3 at, double time, double alpha) {
        double radius = Armageddon.SHOT_RINGS;
        int rows = HiveShapes.ringRows(true), line = GlowBrush.mix(VIOLET, PALE, .3);
        for (int ring = 0; ring < 3; ring++) {
            Vec3[] frame = HiveShapes.ringFrame(ring, time);
            int columns = HiveShapes.ringColumns(ring, radius, true);
            for (int column = 0; column < columns; column++) for (int row = 0; row < rows; row++) {
                Vec3 previous = null;
                for (int corner = 0; corner <= 6; corner++) {
                    Vec3 point = at.add(HiveShapes.ringHexCorner(frame, ring, column, row, corner % 6, time, radius, true));
                    if (previous != null) GlowBrush.line(glow, m, previous, point, .018, line, 115 * alpha);
                    previous = point;
                }
            }
        }
    }

    /**
     * A honeycomb over a surface of revolution round the cannon's axis: {@code along} maps u (0..1) to a
     * distance along the axis and {@code radius} to the surface's radius there; {@code columns} hexagons
     * along it, {@code rows} round it.
     */
    private static void honeycomb(VertexConsumer glow, Matrix4f m, Vec3 camera, ArmageddonState s, Vec3[] f, int columns, int rows, double alpha,
            int color, double turn, DoubleUnaryOperator along, DoubleUnaryOperator radius) {
        if (alpha < 1) return;
        for (int column = 0; column < columns; column++) for (int row = 0; row < rows; row++) {
            double u = (column + (row % 2) * .5 + .25) / (columns + .5), v = (row + .5) / rows * Math.PI * 2 + turn;
            Vec3 previous = null;
            for (int corner = 0; corner <= 6; corner++) {
                double angle = Math.PI / 6 + corner * Math.PI / 3;
                double cu = u + Math.cos(angle) / (Math.sqrt(3) * (columns + .5)) * .88, cv = v + Math.PI * 2 / rows * Math.sin(angle) / 1.5 * .88;
                double r = radius.applyAsDouble(Math.clamp(cu, 0, 1));
                Vec3 at = Armageddon.point(s, f, along.applyAsDouble(Math.clamp(cu, 0, 1)), Math.cos(cv) * r, Math.sin(cv) * r).subtract(camera);
                if (previous != null) GlowBrush.line(glow, m, previous, at, .03, color, alpha);
                previous = at;
            }
        }
    }

    // --- the blast -------------------------------------------------------------------------------
    //
    // The supernova, in ticks after the black hole bursts (see Armageddon for the stages; its sounds are timed
    // to the same ticks): a white blast that fills the air, the blinded, stunned moments after it with everything
    // near standing dark red against the white and rocks flying; the ball of light forming, swelling out over the
    // land (striking all it sweeps over), holding, and crushed back in; then the eruption, a beam of light to the
    // zenith in a crown of flames of light with a black pillar writhing inside it and a black sphere flickering up
    // through it at each deep pop, narrowing to a thread and gone in an orange dusk. ArmageddonVolume draws all of
    // it in the world; here are its stages, its rocks and its sound. Nothing in it is placed by where the camera is.

    /** How much of the blast a viewer {@code distance} away gets: all of it within twice its reach, fading out beyond. */
    static double near(double distance) {
        double radius = Armageddon.RADIUS;
        return distance <= 2 * radius ? 1 : Math.clamp(1 - (distance - 2 * radius) / (2 * radius), 0, 1);
    }

    /**
     * How thick the flash's white light hangs in the air: so thick at first that everything goes white, then
     * thinner, so that what is near stands dark red against the white, until the ball of light forms.
     */
    static double flash(double t) {
        if (t < 0) return 0;
        if (t < Armageddon.FLASH) return 1;
        if (t < Armageddon.BALL) return .45 - .15 * (t - Armageddon.FLASH) / (Armageddon.BALL - Armageddon.FLASH);
        return Math.max(0, .3 * (1 - (t - Armageddon.BALL) / 4));
    }

    static double ballAlpha(double t) {
        return t < Armageddon.BALL || t >= Armageddon.CRUSHED ? 0 : smooth((t - Armageddon.BALL) / 3);
    }

    /** The beam's radius: bursting up out of the crushed ball and widening to 128 blocks across, narrowing to a thread at the end. */
    static double beamRadius(double t) {
        return Armageddon.beam(t);
    }

    static double beamGlow(double t) {
        return t < Armageddon.ERUPT || t > Armageddon.GONE ? 0 : smooth((t - Armageddon.ERUPT) / 4) * (1 - smooth((t - (Armageddon.GONE - 10)) / 10));
    }

    /** The black pillar inside the beam. */
    static double core(double t) {
        return beamRadius(t) * .36 * smooth((t - Armageddon.ERUPT - 10) / 20);
    }

    /** When the eruption's k-th deep pop comes, in ticks after the burst (the blast's sound pops on the same ticks). */
    static double pop(int k) {
        return Armageddon.ERUPT + 8 + 11 * k + 5 * Math.sin(2.3 * k);
    }

    /** How many pops of the eruption have come, with the time since the latest as the fraction of the gap to the next; -1 before the first (a black sphere flickers up at each). */
    static double spheres(double t) {
        if (t < pop(0)) return -1;
        int k = 0;
        while (k < 40 && pop(k + 1) <= t) k++;
        return k + (t - pop(k)) / (pop(k + 1) - pop(k));
    }

    /** How thick the light hangs in the air inside the ball of light. */
    static double glare(double t) {
        return t < Armageddon.BALL ? 0 : smooth((t - Armageddon.BALL) / 10) * (1 - smooth((t - Armageddon.HOLD) / 60));
    }

    /** The violet haze the eruption lights. */
    static double magenta(double t) {
        return smooth((t - Armageddon.ERUPT) / 30) * (1 - smooth((t - Armageddon.NARROW) / 50));
    }

    /**
     * The blast's light on the world {@code t} ticks in, for a viewer {@code near} it: what the land, the sky and
     * the land inside the ball of light are multiplied by, and a light of its own near the blast and how far it
     * reaches. Dark red in the flash, dark before the ball of light and bright inside it, night round the
     * eruption lit violet by the beam, an orange dusk as it goes out.
     */
    static double[][] lightOnWorld(double t, double near) {
        double[] one = {1, 1, 1};
        double[] burnt = {.34, .06, .03}, before = {.4, .1, .16}, beforeSky = {.5, .18, .42}, inside = {1.05, .95, 1.25};
        double[] night = {.3, .07, .2}, nightSky = {.14, .03, .12}, dusk = {.42, .14, .08}, duskSky = {.75, .24, .08};
        double[] world = one, sky = one, within = one, glow = {0, 0, 0};
        double reach = 90;
        if (t >= 0) {
            world = burnt;
            within = burnt;
        }
        if (t >= Armageddon.BALL) {
            double up = smooth((t - Armageddon.BALL) / 4);
            world = mix(burnt, before, up);
            sky = mix(one, beforeSky, up);
            within = mix(burnt, inside, up);
        }
        if (t >= Armageddon.ERUPT - 5) {
            double dark = smooth((t - (Armageddon.ERUPT - 5)) / 10);
            world = mix(world, night, dark);
            within = mix(within, night, dark);
            sky = mix(sky, nightSky, dark);
            double violet = beamGlow(t);
            glow = new double[]{.9 * violet, .5 * violet, 1.3 * violet};
        }
        if (t >= Armageddon.NARROW) {
            double evening = smooth((t - Armageddon.NARROW) / 30);
            world = mix(world, dusk, evening);
            within = world;
            sky = mix(sky, duskSky, evening);
        }
        if (t >= Armageddon.GONE) {
            double back = smooth((t - Armageddon.GONE) / 40);
            world = mix(world, one, back);
            within = world;
            sky = mix(sky, one, back);
            glow = new double[]{0, 0, 0};
        }
        return new double[][]{mix(one, world, near), mix(one, sky, near), mix(one, within, near), {glow[0] * near, glow[1] * near, glow[2] * near}, {reach}};
    }

    private static double[] mix(double[] a, double[] b, double u) {
        return new double[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
    }

    /** Every blast this client knows of: its shock heard as the ball of light reaches the camera. */
    static void blasts(double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        prune(time);
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt;
            if (t < 0) continue;
            double distance = blast.centre.distanceTo(camera);
            if (!blast.shockHeard && distance <= Armageddon.RADIUS * 1.2 && t >= Armageddon.reaches(Math.min(distance, Armageddon.RADIUS))) {
                blast.shockHeard = true;
                hear(RelicSounds.ARMAGEDDON_SHOCK.get(), (float) Math.clamp(1.1 - distance / Armageddon.RADIUS * .6, .45, 1), blast.impactAt + 1);
            }
        }
    }

    private static final int FLASH_ROCKS = 90, HIT_ROCKS = 70;
    /** Rocks are drawn from a buffer of their own before the blast's light and haze go over the world, so those cover them too. */
    private static final net.minecraft.client.renderer.MultiBufferSource.BufferSource ROCKS =
            net.minecraft.client.renderer.MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 18));

    /** Draws the rocks queued this frame. */
    static void flushDebris() {
        ROCKS.endBatch();
    }

    /**
     * Rocks, real blocks: flung out of the burst in the white of the flash, thrown up as the ball of light forms,
     * and swept past the eye as it goes by.
     */
    static void debris(Minecraft minecraft, double time, Vec3 camera, PoseStack poses) {
        if (BLASTS.isEmpty()) return;
        var buffers = ROCKS;
        var blocks = minecraft.getBlockRenderer();
        BlockState[] earth = {Blocks.ORANGE_TERRACOTTA.defaultBlockState(), Blocks.BROWN_TERRACOTTA.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState(),
                Blocks.COARSE_DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
        int bright = LightTexture.FULL_BRIGHT;
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt;
            long seed = blast.impactAt;
            Vec3 c = blast.centre.subtract(camera);
            double flung = t - Armageddon.FLASH;
            if (flung > 0 && t < Armageddon.BALL + 20) {
                for (int rock = 0; rock < FLASH_ROCKS; rock++) {
                    double a = hash(rock, seed + 21) * Math.PI * 2, out = 2 + 4 * hash(rock, seed + 22), rise = .5 + 3 * hash(rock, seed + 23);
                    double s = flung * (.7 + .5 * hash(rock, seed + 24));
                    Vec3 at = c.add(Math.cos(a) * (6 + out * s), 1 + rise * s - .04 * s * s, Math.sin(a) * (6 + out * s));
                    rock(blocks, buffers, poses, earth[rock % earth.length], at, 1 + 2.5 * hash(rock, seed + 25), s * .2, rock, bright);
                }
            }
            double thrown = t - (Armageddon.BALL - 4);
            if (thrown > 0 && thrown < 100) {
                for (int rock = 0; rock < HIT_ROCKS; rock++) {
                    double a = hash(rock, seed + 41) * Math.PI * 2, out = .6 + 1.4 * hash(rock, seed + 42), rise = 1.5 + 2.5 * hash(rock, seed + 43);
                    double s = thrown * (.7 + .5 * hash(rock, seed + 44)), reach = 8 + out * s;
                    Vec3 at = c.add(Math.cos(a) * reach, rise * s - .05 * s * s, Math.sin(a) * reach);
                    if (at.y < c.y - 3) continue;
                    rock(blocks, buffers, poses, earth[rock % earth.length], at, 1.2 + 2.6 * hash(rock, seed + 45), s * .15, rock, bright);
                }
            }
            // Swept outwards by the ball of light as it passes: each 12-block patch of ground round the eye has
            // rocks of its own, in their own places in the world, each set flying as the ball reaches it.
            int cell = 12, span = 4, cx = (int) Math.floor(camera.x / cell), cz = (int) Math.floor(camera.z / cell);
            for (int gx = cx - span; gx <= cx + span; gx++) for (int gz = cz - span; gz <= cz + span; gz++) {
                for (int k = 0; k < 2; k++) {
                    long patchSeed = seed ^ gx * 73856093L ^ gz * 19349663L ^ k * 83492791L;
                    Vec3 start = new Vec3((gx + hash(k, patchSeed)) * cell, blast.centre.y + 3 * hash(k, patchSeed + 1), (gz + hash(k, patchSeed + 2)) * cell);
                    double dx = start.x - blast.centre.x, dz = start.z - blast.centre.z, d = Math.hypot(dx, dz);
                    if (d < 10 || d > Armageddon.RADIUS) continue;
                    double age = t - Armageddon.reaches(d);
                    if (age < 0 || age > 30) continue;
                    double speed = 1.5 + 2.5 * hash(k, patchSeed + 3), lift = .4 + .8 * hash(k, patchSeed + 4);
                    Vec3 at = start.add(dx / d * speed * age, lift * age - .03 * age * age, dz / d * speed * age);
                    rock(blocks, buffers, poses, earth[(int) (hash(k, patchSeed + 5) * earth.length) % earth.length], at.subtract(camera),
                            .3 + 1.2 * hash(k, patchSeed + 6), age * .35, k, bright);
                }
            }
        }
    }

    /** One tumbling rock, {@code size} blocks across, at a camera-relative place. */
    private static void rock(net.minecraft.client.renderer.block.BlockRenderDispatcher blocks, net.minecraft.client.renderer.MultiBufferSource buffers,
            PoseStack poses, BlockState state, Vec3 at, double size, double spin, int index, int light) {
        poses.pushPose();
        poses.translate(at.x, at.y, at.z);
        poses.mulPose(Axis.YP.rotation((float) (spin + index * 1.7)));
        poses.mulPose(Axis.XP.rotation((float) (spin * 1.3 + index)));
        poses.scale((float) size, (float) (size * .7), (float) (size * .85));
        poses.translate(-.5, -.5, -.5);
        blocks.renderSingleBlock(state, poses, buffers, light, OverlayTexture.NO_OVERLAY);
        poses.popPose();
    }

    /**
     * Queues what bends the world round every blast before the renderer's lens pass (the shock at the ball of
     * light's front, swelling out and crushed back in), and the blast's volumes.
     */
    static void lenses(double time, Vec3 camera) {
        for (Blast blast : BLASTS) {
            double t = time - blast.impactAt;
            if (t < 0 || t > BLAST_LIFE) continue;
            Vec3 c = blast.centre.subtract(camera);
            double ball = Armageddon.ball(t), shock = ballAlpha(t) * (t < Armageddon.HOLD ? 1 : .8);
            if (shock > .01 && ball > 4 && c.length() > ball * 1.02) BlackHoleLens.queueShock(c, ball, Math.max(3, ball * .06), 1.5 * shock);
            ArmageddonVolume.queue(c, t);
        }
    }

    private static double hash(int index, long salt) {
        long h = index * 0x9E3779B97F4A7C15L ^ (salt + 1) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double x) {
        double t = Math.clamp(x, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private ArmageddonVisual() {
    }
}
