package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.RfArmageddon;
import dev.hurtify.relicsaddon.server.ArmageddonController;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
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
import org.joml.Vector3f;

/**
 * The light of RF Armageddon (see {@link RfArmageddon}), all of it in the world. Over its owner: the hologram of the
 * relay drone, blue lines of light between its drones, a scanline sweeping along it and a flicker running through,
 * copper belts round its body, needle antennas at nose and stern with redstone beacons, and its four panels unfolding
 * click by click, their cells lighting row by row from the body out while blue sparks run in along the grid; a worn
 * RF shield hands its charge up to the body along a crackling link. Before the nose, the ball: a dark core (drawn by
 * {@link ArmageddonVolume}) in a rim of crawling lightning, ringed by atomic orbits whose electrons quicken with the
 * charge, and short discharges leaping to it from the nose's needles. Then, for every client near (told as the ball
 * leaves): its heavy flight, the bolts it strikes the ground with and the scorch they leave, its hover while the world
 * goes grey, the dome of glass it becomes with lightning inside, sparks spraying where its edge cuts the ground, the
 * orbits flashing out, and after it all the crater, its blue glow fading and small discharges running over its floor;
 * the blocks the dome lifts, the flash flings and the sky drops; and where the server keeps its land, a rim that is
 * only light and goes with it.
 */
public final class RfArmageddonVisual {
    /** Blasts this client was told of, oldest first. */
    static final List<Blast> BLASTS = new ArrayList<>();
    /** Shots whose ball is still growing before the nose, noted as their drones are drawn, for the ball to be drawn once the world is done. */
    private static final List<ArmageddonState> CHARGING = new ArrayList<>();
    /** How long after the ball meets the ground a blast is kept: until the blast has fallen silent, and a little after. */
    static final int BLAST_LIFE = RfArmageddon.FLASH + RfArmageddon.BLAST + 40;
    private static final int STONES = 320, RIM_BLOCKS = 900, SPRAY = 56;
    /** The atomic orbits: how far each reaches along its long and short axes (shares of the ball's radius), how far it leans and where round it leans. */
    private static final double[] ORBIT_LONG = {2.05, 1.9, 2.2, 1.8, 2.35}, ORBIT_SHORT = {.62, .7, .55, .75, .5},
            ORBIT_TILT = {.35, 1.2, 2.05, .8, 2.6}, ORBIT_TURN = {0, 2.1, 4.2, 1.05, 3.15};
    private static final int ORBITS = 5;

    /** A blast this client knows of: told as the ball leaves, it meets the ground at {@code impactAt}. */
    static final class Blast {
        /** The shot as it was fired, rebuilt from what the server told: every stage of it follows from this. */
        final ArmageddonState shot;
        final long impactAt;
        /** The world it bursts in: a blast never follows its viewer into another. */
        final ClientLevel level;
        boolean flightHeard, domeHeard, blastHeard, shockHeard;
        BallSound flight;
        /** Where each bolt struck the ground, found as it struck, and whether its light has flashed. */
        final Vec3[] strikes = new Vec3[RfArmageddon.bolts()];
        final boolean[] flashed = new boolean[RfArmageddon.bolts()];
        /** The blocks the dome lifts, noted before the land goes; and the rim of light where the server keeps its land. */
        List<Stone> stones;
        List<RimBlock> rim;

        Blast(Vec3 centre, Vec3 from, long impactAt, ClientLevel level) {
            this.shot = new ArmageddonState(HiveType.RF, impactAt - RfArmageddon.IMPACT, from, centre, false);
            this.impactAt = impactAt;
            this.level = level;
        }

        double age(double time) {
            return time - shot.startedAt();
        }

        Vec3 centre() {
            return shot.target();
        }
    }

    /** A block the dome lifts: where it was, what it was, when the dome's edge reaches it, how high it floats and how far in it drifts. */
    private record Stone(BlockPos pos, BlockState state, double liftAt, double height, double drift, int index) { }

    /** A column of the rim of light: its ground, its top block and the one under it, how high it stands, and which way is out. */
    private record RimBlock(BlockPos pos, BlockState top, BlockState fill, double rise, double outX, double outZ, double width) { }

    /** The server tells of a blast as the ball leaves (and the owner's own client notes it then too): remembered for the frames to come. */
    public static void blast(Vec3 centre, Vec3 from, long impactAt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || BLASTS.size() >= 8) return;
        for (Blast known : BLASTS) if (known.impactAt == impactAt && known.centre().distanceToSqr(centre) < 1e-4) return;
        BLASTS.add(new Blast(centre, from, impactAt, minecraft.level));
    }

    /** Drops blasts that have faded, and all of them when the world goes, silencing the ball's flight if it is still heard. */
    static void prune(double time) {
        var level = Minecraft.getInstance().level;
        BLASTS.removeIf(blast -> {
            boolean gone = blast.level != level || time - blast.impactAt > BLAST_LIFE || time < blast.shot.startedAt();
            if (gone && blast.flight != null) blast.flight.finish();
            return gone;
        });
    }

    /** Forgets every blast at once, as the world goes. */
    static void clear() {
        for (Blast blast : BLASTS) if (blast.flight != null) blast.flight.finish();
        BLASTS.clear();
        CHARGING.clear();
    }

    /** A new frame: the shots charging are noted afresh as their drones are drawn. */
    static void startFrame() {
        CHARGING.clear();
    }

    private static void hear(SoundEvent sound, float volume, long seed) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound.getLocation(), SoundSource.PLAYERS,
                volume, 1F, RandomSource.create(seed), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    // --- the hologram ----------------------------------------------------------------------------

    /**
     * The hologram's light for a shot at {@code time}, its ball noted for later; {@code chest} is where a shield link
     * leaves the owner. Once the ball has left, the owner's own client knows of the blast at once.
     */
    static void construct(ArmageddonState s, Vec3 chest, double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double age = s.age(time);
        if (age < 0 || age > RfArmageddon.END) return;
        hologram(s, age, time, camera, glow, m);
        if (age < RfArmageddon.FIRE) {
            if (RfArmageddon.ballRadius(age) > .02) CHARGING.add(s);
        } else blast(s.target(), s.origin(), s.startedAt() + RfArmageddon.IMPACT);
        // A worn RF shield hands its charge up to the body while it charges, along a crackling link.
        if (s.shieldLinked() && chest != null && age > RfArmageddon.ASSEMBLED && age < RfArmageddon.FIRE) {
            double link = Math.min(1, (age - RfArmageddon.ASSEMBLED) / 10) * Math.min(1, (RfArmageddon.FIRE - age) / 6);
            link(s, chest, age, time, link, camera, glow, m);
        }
    }

    /** How brightly a part of the hologram built by {@code builtAt} shows {@code age} ticks in: as its drones land. */
    private static double appear(double builtAt, double age) {
        return smooth((age - builtAt + 3) / 8);
    }

    /** Where the scanline is along the axis at {@code time}: sweeping from the stern's needles to the nose's, over and over. */
    private static double scanline(double time) {
        double first = RfArmageddon.STERN_CAP - RfArmageddon.STERN_NEEDLES - 2, last = RfArmageddon.NOSE_TIP + RfArmageddon.NOSE_NEEDLES + 2;
        return first + (last - first) * ((time / 46) % 1);
    }

    /** How brightly the hologram's line {@code id} shows {@code along} the axis: a fine raster, the scanline passing, and now and then a dropout. */
    private static double holo(double along, double scan, double time, int id) {
        double raster = .8 + .2 * Math.sin(time * 2.3 + along * 7.1), passing = Math.exp(-(along - scan) * (along - scan) / .5);
        double dropout = hash(id, (long) Math.floor(time / 2) * 7919) < .035 ? .3 : 1;
        return (raster + 1.1 * passing) * dropout;
    }

    private static void hologram(ArmageddonState s, double age, double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double base = 1 - smooth((age - RfArmageddon.SCATTER) / 12);
        if (base <= .01) return;
        Vec3[] f = RfArmageddon.frame(s);
        double unfold = RfArmageddon.unfold(age), charge = RfArmageddon.charge(age), scan = scanline(time);
        body(s, f, age, time, scan, base, camera, glow, m);
        for (int panel = 0; panel < RfArmageddon.PANELS; panel++) panel(s, f, panel, unfold, charge, age, time, base, camera, glow, m);
    }

    /** A point of the body, relative to the camera. */
    private static Vec3 body(ArmageddonState s, Vec3[] f, double along, double angle, double radius, Vec3 camera) {
        return RfArmageddon.body(s, f, along, angle, radius).subtract(camera);
    }

    /** A ring round the axis, {@code along} it and {@code radius} out. */
    private static void ring(ArmageddonState s, Vec3[] f, double along, double radius, int segments, double width, int colour, double alpha, Vec3 camera,
            VertexConsumer glow, Matrix4f m) {
        if (alpha < 1) return;
        Vec3 previous = null;
        for (int k = 0; k <= segments; k++) {
            Vec3 at = body(s, f, along, Math.PI * 2 * k / segments, radius, camera);
            if (previous != null) GlowBrush.line(glow, m, previous, at, width, colour, alpha);
            previous = at;
        }
    }

    /**
     * The body: rings of light round the axis and lines along it, built from the stern to the nose; three copper belts;
     * the cap behind and the cone in front; the arms the panels are hinged on; and the needle antennas, the nose's tipped
     * with pale light and the stern's with blinking redstone beacons.
     */
    private static void body(ArmageddonState s, Vec3[] f, double age, double time, double scan, double base, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double r = RfArmageddon.BODY_RADIUS;
        int rings = RfArmageddon.bodyRings(), nodes = RfArmageddon.RING_NODES;
        for (int k = 0; k < rings; k++) {
            double along = RfArmageddon.bodyRing(k), a = base * appear(RfArmageddon.builtAt(along), age) * holo(along, scan, time, k);
            ring(s, f, along, r, 40, .022, RfPalette.HOLO, 115 * a, camera, glow, m);
        }
        for (int n = 0; n < nodes; n++) {
            double angle = RfArmageddon.ringAngle(n);
            for (int k = 0; k + 1 < rings; k++) {
                double from = RfArmageddon.bodyRing(k), to = RfArmageddon.bodyRing(k + 1);
                double a = base * appear(RfArmageddon.builtAt(to), age) * holo((from + to) / 2, scan, time, 20 + n * 8 + k);
                GlowBrush.line(glow, m, body(s, f, from, angle, r, camera), body(s, f, to, angle, r, camera), .016, RfPalette.HOLO, 80 * a);
            }
            // The cap and the cone, closing the body behind and before.
            double cap = base * appear(RfArmageddon.builtAt(RfArmageddon.STERN_CAP), age) * holo(RfArmageddon.STERN_CAP, scan, time, 90 + n);
            GlowBrush.line(glow, m, body(s, f, RfArmageddon.STERN, angle, r, camera), body(s, f, RfArmageddon.CAP_RING, angle, RfArmageddon.CAP_RADIUS, camera),
                    .016, RfPalette.STEEL, 110 * cap);
            GlowBrush.line(glow, m, body(s, f, RfArmageddon.CAP_RING, angle, RfArmageddon.CAP_RADIUS, camera), body(s, f, RfArmageddon.STERN_CAP, 0, 0, camera),
                    .014, RfPalette.STEEL, 90 * cap);
            double cone = base * appear(RfArmageddon.builtAt(RfArmageddon.NOSE_TIP), age) * holo(RfArmageddon.NOSE_TIP, scan, time, 100 + n);
            GlowBrush.line(glow, m, body(s, f, RfArmageddon.NOSE, angle, r, camera), body(s, f, RfArmageddon.CONE_RING, angle, RfArmageddon.CONE_RADIUS, camera),
                    .016, RfPalette.SILVER, 110 * cone);
            GlowBrush.line(glow, m, body(s, f, RfArmageddon.CONE_RING, angle, RfArmageddon.CONE_RADIUS, camera), body(s, f, RfArmageddon.NOSE_TIP, 0, 0, camera),
                    .014, RfPalette.SILVER, 90 * cone);
        }
        ring(s, f, RfArmageddon.CAP_RING, RfArmageddon.CAP_RADIUS, 28, .016, RfPalette.STEEL, 100 * base * appear(RfArmageddon.builtAt(RfArmageddon.CAP_RING), age), camera, glow, m);
        ring(s, f, RfArmageddon.CONE_RING, RfArmageddon.CONE_RADIUS, 28, .016, RfPalette.SILVER, 100 * base * appear(RfArmageddon.builtAt(RfArmageddon.CONE_RING), age), camera, glow, m);
        // The copper belts: a double ring round the body, the band between them glowing warm.
        for (int belt = 0; belt < RfArmageddon.belts(); belt++) {
            double along = RfArmageddon.belt(belt), a = base * appear(RfArmageddon.builtAt(along), age);
            if (a < .01) continue;
            ring(s, f, along - .14, r * 1.06, 40, .03, RfPalette.COPPER, 175 * a, camera, glow, m);
            ring(s, f, along + .14, r * 1.06, 40, .03, RfPalette.COPPER, 175 * a, camera, glow, m);
            for (int k = 0; k < 40; k++) {
                double from = Math.PI * 2 * k / 40, to = Math.PI * 2 * (k + 1) / 40;
                GlowBrush.quad(glow, m, body(s, f, along - .14, from, r * 1.05, camera), body(s, f, along + .14, from, r * 1.05, camera),
                        body(s, f, along + .14, to, r * 1.05, camera), body(s, f, along - .14, to, r * 1.05, camera),
                        RfPalette.COPPER_PALE, RfPalette.COPPER_PALE, RfPalette.COPPER_PALE, RfPalette.COPPER_PALE, 26 * a, 26 * a, 26 * a, 26 * a);
            }
        }
        // The arms the panels are hinged on, copper, and a copper stud at each hinge.
        double arms = base * appear(RfArmageddon.panelBuiltAt(0), age);
        for (int panel = 0; panel < RfArmageddon.PANELS; panel++) {
            double angle = RfArmageddon.panelAngle(panel);
            Vec3 hinge = body(s, f, RfArmageddon.HINGE, angle, RfArmageddon.HINGE_RADIUS, camera);
            GlowBrush.line(glow, m, body(s, f, RfArmageddon.HINGE, angle, r, camera), hinge, .045, RfPalette.COPPER, 170 * arms);
            GlowBrush.dot(glow, m, hinge, .11, RfPalette.COPPER_PALE, 200 * arms);
        }
        // The needles.
        for (boolean nose : new boolean[]{true, false}) {
            int bundle = nose ? RfArmageddon.NOSE_BUNDLE : RfArmageddon.STERN_BUNDLE;
            double a = base * appear(RfArmageddon.builtAt(nose ? RfArmageddon.NOSE_TIP + RfArmageddon.NOSE_NEEDLES : RfArmageddon.STERN_CAP - RfArmageddon.STERN_NEEDLES), age);
            if (a < .01) continue;
            for (int needle = 0; needle < bundle; needle++) {
                double angle = RfArmageddon.needleAngle(nose, needle);
                Vec3 from = body(s, f, RfArmageddon.needleBaseAlong(nose), angle, RfArmageddon.needleBaseRadius(nose, needle), camera);
                Vec3 tip = body(s, f, RfArmageddon.needleTipAlong(nose, needle), angle, RfArmageddon.needleTipRadius(nose, needle), camera);
                GlowBrush.line(glow, m, from, tip, .014, .006, RfPalette.SILVER, RfPalette.HOLO_PALE, 170 * a, 150 * a);
                if (nose) GlowBrush.dot(glow, m, tip, .07, RfPalette.HOLO_PALE, 220 * a);
                else {
                    double blink = Math.pow(.5 + .5 * Math.sin(time * .35 + needle * 1.7), 6);
                    GlowBrush.dot(glow, m, tip, .08 + .08 * blink, RfPalette.REDSTONE, (60 + 190 * blink) * a);
                }
            }
        }
    }

    /**
     * A panel: its frame of silver light and its grid of deep blue, laid out from its hinge; its cells dark until the
     * charge lights them row after row from the body out, the newest row flaring; a scan sweeping out along it; and
     * blue sparks running in along the grid from its lit edge to the body.
     */
    private static void panel(ArmageddonState s, Vec3[] f, int panel, double unfold, double charge, double age, double time, double base, Vec3 camera,
            VertexConsumer glow, Matrix4f m) {
        Vec3[] p = RfArmageddon.panel(s, f, panel, unfold);
        double length = RfArmageddon.PANEL_LENGTH, width = RfArmageddon.PANEL_WIDTH;
        int rows = RfArmageddon.ROWS, columns = RfArmageddon.COLUMNS;
        double sweep = length * ((time / 60 + panel * .25) % 1) * 1.3 - length * .15;
        for (int row = 0; row < rows; row++) {
            double from = length * row / rows, to = length * (row + 1) / rows, middle = (from + to) / 2;
            double a = base * appear(RfArmageddon.panelBuiltAt(middle), age);
            if (a < .01) continue;
            double lit = RfArmageddon.lit(row, age), passing = Math.exp(-(middle - sweep) * (middle - sweep) / .6);
            for (int column = 0; column < columns; column++) {
                double left = -width / 2 + width * column / columns, right = left + width / columns, inset = .06;
                int colour = GlowBrush.mix(RfPalette.HOLO_DEEP, RfPalette.HOLO, lit);
                double fill = (7 + 44 * lit + 20 * passing) * a * (hash(panel * 97 + row * 7 + column, (long) Math.floor(time / 2) * 131) < .02 ? .3 : 1);
                GlowBrush.quad(glow, m, cell(p, from + inset, left + inset, camera), cell(p, to - inset, left + inset, camera), cell(p, to - inset, right - inset, camera),
                        cell(p, from + inset, right - inset, camera), colour, colour, colour, colour, fill, fill, fill, fill);
                // The row lighting now flares white-blue as it comes on.
                if (lit > 0 && lit < 1) {
                    double flare = 70 * Math.sin(Math.PI * lit) * a;
                    GlowBrush.quad(glow, m, cell(p, from + inset, left + inset, camera), cell(p, to - inset, left + inset, camera), cell(p, to - inset, right - inset, camera),
                            cell(p, from + inset, right - inset, camera), RfPalette.SPARK, RfPalette.SPARK, RfPalette.SPARK, RfPalette.SPARK, flare, flare, flare, flare);
                }
            }
            // The grid across the panel at this row's outer edge, and along it between this row's edges.
            boolean outer = row + 1 == rows;
            GlowBrush.line(glow, m, cell(p, to, -width / 2, camera), cell(p, to, width / 2, camera), outer ? .03 : .014,
                    outer ? RfPalette.SILVER : RfPalette.HOLO_DEEP, (outer ? 150 : 90) * a * (1 + .8 * passing));
            for (int column = 0; column <= columns; column++) {
                boolean edge = column == 0 || column == columns;
                double across = -width / 2 + width * column / columns;
                GlowBrush.line(glow, m, cell(p, from, across, camera), cell(p, to, across, camera), edge ? .03 : .014,
                        edge ? RfPalette.SILVER : RfPalette.HOLO_DEEP, (edge ? 150 : 90) * a * (1 + .8 * passing));
            }
        }
        double hinge = base * appear(RfArmageddon.panelBuiltAt(0), age);
        GlowBrush.line(glow, m, cell(p, 0, -width / 2, camera), cell(p, 0, width / 2, camera), .03, RfPalette.SILVER, 150 * hinge);
        // Sparks running in along the grid, from the lit edge to the body, more and more of them as the charge grows.
        double litLength = length * Math.clamp(charge, 0, 1);
        if (litLength < .3 || base < .01) return;
        int sparks = (int) Math.round(3 + 10 * charge);
        for (int k = 0; k < sparks; k++) {
            int id = panel * 131 + k;
            double life = 14 + 10 * hash(id, 1), phase = ((time + hash(id, 2) * life) % life) / life;
            double across = -width / 2 + width * (k % (columns + 1)) / columns, along = litLength * (1 - phase);
            Vec3 head = cell(p, along, across, camera), tail = cell(p, Math.min(litLength, along + .7), across, camera);
            double fade = Math.sin(Math.PI * phase) * base;
            GlowBrush.line(glow, m, tail, head, .012, .03, RfPalette.HOLO, RfPalette.SPARK, 0, 220 * fade);
            GlowBrush.dot(glow, m, head, .07, RfPalette.SPARK, 230 * fade);
        }
    }

    private static Vec3 cell(Vec3[] panel, double length, double width, Vec3 camera) {
        return RfArmageddon.onPanel(panel, length, width).subtract(camera);
    }

    /** The RF shield's link: a crackling bolt from the owner's chest up to the underside of the body's stern, copper beads running up beside it. */
    private static void link(ArmageddonState s, Vec3 chest, double age, double time, double link, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        Vec3[] f = RfArmageddon.frame(s);
        Vec3 from = chest.subtract(camera), to = body(s, f, RfArmageddon.STERN + .4, -Math.PI / 2, RfArmageddon.BODY_RADIUS, camera);
        long seed = (long) Math.floor(time / 2) * 7717;
        GlowBrush.lightning(glow, m, from, to, seed, 10, .07, .035, RfPalette.ELECTRIC, 170 * link);
        GlowBrush.line(glow, m, from, to, .012, RfPalette.HOLO_PALE, 90 * link);
        for (int bead = 0; bead < 6; bead++) {
            double u = ((age * .035 + bead / 6.0) % 1);
            GlowBrush.dot(glow, m, from.lerp(to, u), .07, RfPalette.COPPER_PALE, 210 * link * Math.sin(Math.PI * u));
        }
    }

    // --- the ball ----------------------------------------------------------------------------------

    /**
     * The ball's light, once the world and its dark core are drawn: every ball still growing before a nose, and every
     * blast this client knows of, with its sounds as their moments come.
     */
    static void late(double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        prune(time);
        boolean plain = ShieldRefraction.shaderPackActive();
        for (ArmageddonState s : CHARGING) {
            double age = s.age(time), radius = RfArmageddon.ballRadius(age), charge = RfArmageddon.charge(age);
            Vec3 c = RfArmageddon.nose(s).subtract(camera);
            if (plain) plainBall(c, radius, glow, m);
            ball(c, radius, age, time, charge, 1, .03 + .22 * charge, s.startedAt(), camera, glow, m);
            discharges(s, c, radius, age, time, camera, glow, m);
        }
        CHARGING.clear();
        for (Blast blast : BLASTS) {
            sounds(blast, time, camera);
            double age = blast.age(time), t = age - RfArmageddon.IMPACT;
            if (age >= RfArmageddon.FIRE && age < RfArmageddon.IMPACT + 1) {
                double hover = smooth((age - RfArmageddon.ARRIVE) / RfArmageddon.HOVER), radius = RfArmageddon.ballRadius(age);
                Vec3 c = RfArmageddon.ball(blast.shot, age).subtract(camera);
                if (plain) plainBall(c, radius, glow, m);
                ball(c, radius, age, time, 1, 1 - .45 * hover, .28 + .25 * hover, blast.impactAt, camera, glow, m);
            }
            bolts(blast, age, camera, glow, m);
            if (t >= 0 && t < RfArmageddon.FLASH + 30) dome(blast, t, time, plain, camera, glow, m);
            if (t >= RfArmageddon.FLASH + 20) aftermath(blast, t, time, camera, glow, m);
        }
    }

    /**
     * The ball's crackle and its atom: jagged arcs round its rim, lightning crawling over the side facing the eye, and
     * the orbits round it ({@code contract} of their full reach), three at first and five as the charge fills, each
     * with a bright electron running round it {@code spin} radians a tick, trailing light.
     */
    private static void ball(Vec3 c, double radius, double age, double time, double charge, double contract, double spin, long seed, Vec3 camera,
            VertexConsumer glow, Matrix4f m) {
        if (radius < .02) return;
        Vec3 toEye = c.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : c.scale(-1).normalize();
        Vec3 e1 = toEye.cross(Math.abs(toEye.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), e2 = toEye.cross(e1);
        long bucket = (long) Math.floor(time / 2);
        // Arcs crackling round the rim.
        int arcs = 6 + (int) Math.round(8 * charge);
        for (int k = 0; k < arcs; k++) {
            double start = hash(k, bucket * 31 + seed) * Math.PI * 2, span = .2 + .3 * hash(k, bucket * 31 + seed + 1);
            Vec3 previous = null;
            for (int step = 0; step <= 6; step++) {
                double angle = start + span * step / 6, out = radius * (1.01 + .05 * hash(k * 7 + step, bucket * 17 + seed));
                Vec3 at = c.add(e1.scale(Math.cos(angle) * out)).add(e2.scale(Math.sin(angle) * out));
                if (previous != null) GlowBrush.line(glow, m, previous, at, Math.max(.02, radius * .018), RfPalette.ELECTRIC, 190);
                previous = at;
            }
        }
        // Lightning crawling over the side facing the eye.
        int crawling = 3 + (int) Math.round(5 * charge);
        for (int k = 0; k < crawling; k++) {
            Vec3 from = toEye.add(e1.scale((hash(k, bucket * 13 + seed) - .5) * 1.8)).add(e2.scale((hash(k, bucket * 13 + seed + 1) - .5) * 1.8)).normalize();
            Vec3 bend = from.cross(toEye).lengthSqr() < 1e-6 ? e1 : from.cross(toEye).normalize();
            double reach = .6 + .6 * hash(k, bucket * 13 + seed + 2);
            Vec3 previous = null;
            for (int step = 0; step <= 8; step++) {
                Vec3 dir = dev.hurtify.relicsaddon.drone.HiveShapes.rotate(from, bend, reach * step / 8);
                double out = radius * (1.008 + .035 * hash(k * 11 + step, bucket * 19 + seed));
                Vec3 at = c.add(dir.scale(out));
                if (dir.dot(toEye) > .05 && previous != null) {
                    GlowBrush.line(glow, m, previous, at, Math.max(.02, radius * .014), RfPalette.SPARK, 200);
                    GlowBrush.line(glow, m, previous, at, Math.max(.05, radius * .04), RfPalette.ELECTRIC, 60);
                }
                previous = at;
            }
        }
        // The atom's orbits: three, then a fourth and a fifth as the charge passes a half and three quarters.
        for (int orbit = 0; orbit < ORBITS; orbit++) {
            double shown = orbit < 3 ? 1 : smooth((charge - (orbit == 3 ? .45 : .75)) / .1);
            if (shown < .01) continue;
            Vec3[] axes = orbitAxes(orbit, time);
            double along = radius * ORBIT_LONG[orbit] * contract, across = radius * ORBIT_SHORT[orbit] * contract;
            orbit(c, radius, axes, along, across, Math.max(.02, radius * .016), RfPalette.HOLO_PALE, 115 * shown, glow, m);
            // The electron, and the light it trails.
            double at = hash(orbit, 5) * Math.PI * 2 + time * spin * (1 + .15 * orbit) * (orbit % 2 == 0 ? 1 : -1);
            double direction = orbit % 2 == 0 ? 1 : -1;
            Vec3 previous = null;
            for (int step = 10; step >= 0; step--) {
                double angle = at - direction * .07 * step;
                Vec3 point = c.add(axes[0].scale(Math.cos(angle) * along)).add(axes[1].scale(Math.sin(angle) * across));
                if (previous != null && !behind(c, radius, previous.add(point).scale(.5))) {
                    GlowBrush.line(glow, m, previous, point, Math.max(.02, radius * .02 * (1 - step / 11.0)), RfPalette.SPARK, 200 * shown * (1 - step / 11.0));
                }
                previous = point;
            }
            if (!behind(c, radius, previous)) {
                GlowBrush.dot(glow, m, previous, Math.max(.08, radius * .075), RfPalette.SPARK, 245 * shown);
                GlowBrush.dot(glow, m, previous, Math.max(.2, radius * .2), RfPalette.ELECTRIC, 110 * shown);
            }
        }
    }

    /** Orbit {@code orbit}'s long and short axes: leaning its own way, and slowly turning round the world's up, alternate orbits opposite ways. */
    private static Vec3[] orbitAxes(int orbit, double time) {
        double turn = ORBIT_TURN[orbit] + time * .004 * (orbit % 2 == 0 ? 1 : -1), tilt = ORBIT_TILT[orbit];
        Vec3 lean = new Vec3(Math.cos(turn), 0, Math.sin(turn)), side = new Vec3(-Math.sin(turn), 0, Math.cos(turn));
        Vec3 normal = new Vec3(0, 1, 0).scale(Math.cos(tilt)).add(lean.scale(Math.sin(tilt)));
        Vec3 first = side, second = normal.cross(first).normalize();
        return new Vec3[]{first, second};
    }

    /** An orbit round {@code c}: an ellipse {@code along} by {@code across}, the part of it behind the ball hidden by it. */
    private static void orbit(Vec3 c, double radius, Vec3[] axes, double along, double across, double width, int colour, double alpha, VertexConsumer glow, Matrix4f m) {
        Vec3 previous = null;
        for (int k = 0; k <= 72; k++) {
            double angle = Math.PI * 2 * k / 72;
            Vec3 at = c.add(axes[0].scale(Math.cos(angle) * along)).add(axes[1].scale(Math.sin(angle) * across));
            if (previous != null && !behind(c, radius, previous.add(at).scale(.5))) GlowBrush.line(glow, m, previous, at, width, colour, alpha);
            previous = at;
        }
    }

    /** Whether a camera-relative point outside a ball round {@code c} is hidden behind it. */
    private static boolean behind(Vec3 c, double radius, Vec3 point) {
        double length = point.lengthSqr();
        if (length < 1e-9) return false;
        double s = Math.clamp(c.dot(point) / length, 0, 1);
        return s < 1 && c.subtract(point.scale(s)).lengthSqr() < radius * radius && point.subtract(c).lengthSqr() > radius * radius * .98;
    }

    /** Short discharges leaping from the nose's needles to the growing ball, a pair every few ticks. */
    private static void discharges(ArmageddonState s, Vec3 c, double radius, double age, double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        if (radius < .1) return;
        Vec3[] f = RfArmageddon.frame(s);
        long bucket = (long) Math.floor(time / 3);
        double flicker = new double[]{1, .45, .8}[(int) Math.floor(time) % 3];
        for (int k = 0; k < 2; k++) {
            int needle = (int) (hash(k, bucket * 23 + s.startedAt()) * RfArmageddon.NOSE_BUNDLE);
            double angle = RfArmageddon.needleAngle(true, needle);
            Vec3 tip = body(s, f, RfArmageddon.needleTipAlong(true, needle), angle, RfArmageddon.needleTipRadius(true, needle), camera);
            Vec3 to = tip.subtract(c).normalize().scale(radius).add(c);
            GlowBrush.lightning(glow, m, tip, to, bucket * 97 + k, 5, .16, .025, RfPalette.SPARK, 220 * flicker);
            GlowBrush.dot(glow, m, tip, .12, RfPalette.ELECTRIC, 180 * flicker);
        }
    }

    /** Where a shader pack is in use and the ball's dark core cannot be drawn: its rim as a shell of light instead. */
    private static void plainBall(Vec3 c, double radius, VertexConsumer glow, Matrix4f m) {
        if (radius > .05) GlowBrush.sphere(glow, m, c, radius, 0x000000, RfPalette.ELECTRIC, 0, 170, 16);
    }

    // --- the bolts ------------------------------------------------------------------------------

    /** Where bolt {@code k} of a blast struck the ground: under and round the ball as it was then, on the ground as this client has it. */
    private static Vec3 strike(Blast blast, int k) {
        if (blast.strikes[k] != null) return blast.strikes[k];
        double at = RfArmageddon.boltAt(k);
        Vec3 ball = RfArmageddon.ball(blast.shot, at);
        double radius = RfArmageddon.ballRadius(Math.min(at, RfArmageddon.DESCEND)), angle = hash(k, 301) * Math.PI * 2, out = radius * (.35 + 1.3 * hash(k, 302));
        int x = (int) Math.floor(ball.x + Math.cos(angle) * out), z = (int) Math.floor(ball.z + Math.sin(angle) * out);
        int y = blast.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        // Unloaded here, or over the ball: the target's own height will do.
        if (y <= blast.level.getMinBuildHeight() + 1 || y > ball.y - radius * .5) y = (int) Math.floor(blast.centre().y);
        return blast.strikes[k] = new Vec3(x + .5, y, z + .5);
    }

    /**
     * The bolts the ball strikes the ground with, thick and jagged from its rim, each a few ticks of flicker, flashing
     * where it lands and spraying sparks: one every second or so in flight, more and more often over the target.
     */
    private static void bolts(Blast blast, double age, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        for (int k = 0; k < RfArmageddon.bolts(); k++) {
            double at = RfArmageddon.boltAt(k), life = age - at;
            if (life < 0) break;
            if (life >= 7) continue;
            Vec3 ground = strike(blast, k);
            if (!blast.flashed[k]) {
                blast.flashed[k] = true;
                EffectLights.flash(ground.add(0, 1, 0), 12, 14, 6);
            }
            Vec3 centre = RfArmageddon.ball(blast.shot, at);
            double radius = RfArmageddon.ballRadius(at);
            Vec3 from = ground.subtract(centre).normalize().scale(radius).add(centre).subtract(camera), to = ground.subtract(camera);
            double flicker = new double[]{1, .45, 1, .8, .5, .25, .1}[(int) life];
            long seed = k * 977L + (life < 3 ? 0 : 1);
            GlowBrush.lightning(glow, m, from, to, seed, 12, .09, .3, RfPalette.SPARK, 235 * flicker);
            GlowBrush.lightning(glow, m, from, to, seed, 12, .09, .8, RfPalette.ELECTRIC, 60 * flicker);
            Vec3 fork = from.lerp(to, .45 + .2 * hash(k, 304));
            Vec3 forkTo = to.add(Math.cos(k * 2.1) * (3 + 3 * hash(k, 305)), 0, Math.sin(k * 2.1) * (3 + 3 * hash(k, 305)));
            GlowBrush.lightning(glow, m, fork, forkTo, seed + 5, 7, .12, .14, RfPalette.SPARK, 170 * flicker);
            GlowBrush.dot(glow, m, to.add(0, .4, 0), 2.6 * flicker + .6, RfPalette.SPARK, 240 * flicker);
            GlowBrush.dot(glow, m, to.add(0, .4, 0), 7, RfPalette.ELECTRIC, 80 * flicker);
            for (int spark = 0; spark < 12; spark++) {
                double angle = hash(k * 13 + spark, 306) * Math.PI * 2, speed = .25 + .45 * hash(k * 13 + spark, 307), rise = .25 + .4 * hash(k * 13 + spark, 308);
                Vec3 flying = to.add(Math.cos(angle) * speed * life, .2 + rise * life - .05 * life * life, Math.sin(angle) * speed * life);
                GlowBrush.dot(glow, m, flying, .08, RfPalette.SPARK, 230 * (1 - life / 7));
            }
        }
    }

    /** The scorch each bolt leaves on the ground, darkening where it struck and fading as the blast falls silent; the dome takes those under it. */
    static void scorches(double time, Vec3 camera, VertexConsumer fill, Matrix4f m) {
        for (Blast blast : BLASTS) {
            double age = blast.age(time), fade = 1 - smooth((age - (RfArmageddon.RECOVER - 240)) / 200);
            if (fade <= 0) continue;
            for (int k = 0; k < RfArmageddon.bolts(); k++) {
                double at = RfArmageddon.boltAt(k);
                if (age < at) break;
                Vec3 ground = strike(blast, k);
                if (age >= RfArmageddon.IMPACT && Math.hypot(ground.x - blast.centre().x, ground.z - blast.centre().z) < RfArmageddon.DOME_RADIUS + 2) continue;
                double radius = 1.3 + 1.4 * hash(k, 303), grow = smooth((age - at) / 3);
                disc(fill, m, ground.subtract(camera).add(0, .035, 0), radius * (.5 + .5 * grow), RfPalette.SOOT, 175 * fade * grow);
            }
        }
    }

    /** A dark, soft-edged disc lying on the ground. */
    private static void disc(VertexConsumer fill, Matrix4f m, Vec3 centre, double radius, int colour, double alpha) {
        if (alpha < 1) return;
        for (int k = 0; k < 20; k++) {
            double a = Math.PI * 2 * k / 20, b = Math.PI * 2 * (k + 1) / 20;
            GlowBrush.vertex(fill, m, centre, colour, alpha);
            GlowBrush.vertex(fill, m, centre.add(Math.cos(b) * radius, 0, Math.sin(b) * radius), colour, 0);
            GlowBrush.vertex(fill, m, centre.add(Math.cos(a) * radius, 0, Math.sin(a) * radius), colour, 0);
        }
    }

    // --- the dome, the flash and the crater -------------------------------------------------------

    /**
     * The dome's light {@code t} ticks after the ball meets the ground: lightning inside it from the ground up to its
     * glass, whitening as it heats; sparks spraying where its edge cuts the ground; the orbits hugging it; then the
     * flash, the orbits blazing white and flung out, and a white disc swelling from its middle.
     */
    private static void dome(Blast blast, double t, double time, boolean plain, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        Vec3 c = blast.centre().subtract(camera);
        double radius = RfArmageddon.dome(Math.min(t, RfArmageddon.FLASH)), heat = smooth((t - 8) / (RfArmageddon.FLASH - 8));
        double shown = smooth(t / 3) * (1 - smooth((t - RfArmageddon.FLASH) / 3));
        int colour = GlowBrush.mix(RfPalette.ELECTRIC, 0xFFFFFF, heat);
        if (plain && shown > .01) GlowBrush.sphere(glow, m, c, radius, 0x000000, GlowBrush.mix(RfPalette.ICE, 0xFFFFFF, heat), 0, 140 * shown, 24);
        if (shown > .01) {
            long bucket = (long) Math.floor(time / 2);
            for (int k = 0; k < 8; k++) {
                double angle = hash(k, bucket * 41 + blast.impactAt) * Math.PI * 2, out = radius * .35 * hash(k, bucket * 41 + blast.impactAt + 1);
                Vec3 from = c.add(Math.cos(angle) * out, .5, Math.sin(angle) * out);
                double around = hash(k, bucket * 43 + blast.impactAt) * Math.PI * 2, up = .25 + .7 * hash(k, bucket * 43 + blast.impactAt + 1), flat = Math.sqrt(1 - up * up);
                Vec3 to = c.add(Math.cos(around) * flat * radius * .97, up * radius * .97, Math.sin(around) * flat * radius * .97);
                GlowBrush.lightning(glow, m, from, to, bucket * 59 + k, 10, .1, .09 + .1 * heat, colour, 215 * shown);
            }
            // Sparks spraying up where the edge cuts the ground.
            for (int k = 0; k < SPRAY; k++) {
                double angle = k * Math.PI * 2 / SPRAY + hash(k, 311) * .1, life = ((t * (.9 + .4 * hash(k, 312)) + hash(k, 313) * 10) % 10);
                Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle));
                Vec3 at = c.add(out.scale(radius + .35 * life)).add(0, 1.1 * life - .07 * life * life, 0);
                GlowBrush.dot(glow, m, at, .12, GlowBrush.mix(RfPalette.SPARK, 0xFFFFFF, heat), 220 * shown * (1 - life / 10));
            }
        }
        // The orbits hug the dome, spinning faster and faster; at the flash they blaze white and are flung out.
        double flashed = t < RfArmageddon.FLASH ? 0 : t - RfArmageddon.FLASH;
        double swell = 1 + .9 * smooth(flashed / 10), blaze = t < RfArmageddon.FLASH ? 0 : Math.exp(-flashed / 7);
        double orbits = (t < RfArmageddon.FLASH ? smooth(t / 5) : blaze);
        if (orbits > .01) for (int orbit = 0; orbit < ORBITS; orbit++) {
            Vec3[] axes = orbitAxes(orbit, time);
            double along = radius * 1.1 * swell, across = along * ORBIT_SHORT[orbit] / ORBIT_LONG[orbit] * 1.3;
            orbit(c.add(0, radius * .15, 0), 0, axes, along, across, .12 + .5 * blaze, GlowBrush.mix(RfPalette.HOLO_PALE, 0xFFFFFF, Math.max(heat, blaze)),
                    (120 + 135 * blaze) * orbits, glow, m);
        }
        if (flashed > 0 && flashed < 30) {
            double disc = Math.exp(-flashed / 8);
            GlowBrush.dot(glow, m, c.add(0, radius * .3, 0), radius * 1.2 + 90 * smooth(flashed / 6), 0xFFFFFF, 255 * disc);
        }
    }

    /**
     * After the flash, as long as the blast is heard: the blue glow at the crater's heart slowly fading, and small
     * discharges running over its floor.
     */
    private static void aftermath(Blast blast, double t, double time, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double fade = smooth((t - RfArmageddon.FLASH - 20) / 30) * (1 - smooth((t - RfArmageddon.FLASH - 60) / (RfArmageddon.BLAST - 80)));
        Vec3 centre = blast.centre();
        if (fade < .01 || centre.distanceTo(camera) > RfArmageddon.RADIUS * 1.5) return;
        ClientLevel level = blast.level;
        Vec3 heart = new Vec3(centre.x, ground(level, centre.x, centre.z, centre.y), centre.z).subtract(camera).add(0, .6, 0);
        double pulse = .5 + .5 * Math.sin(time * .15);
        GlowBrush.dot(glow, m, heart, 3.5 + pulse, RfPalette.SPARK, 170 * fade);
        GlowBrush.dot(glow, m, heart, 11 + 2 * pulse, RfPalette.ELECTRIC, 80 * fade);
        GlowBrush.dot(glow, m, heart, 26, RfPalette.HOLO_DEEP, 40 * fade);
        long bucket = (long) Math.floor(time / 3);
        double flicker = new double[]{1, .5, .8}[(int) Math.floor(time) % 3];
        for (int k = 0; k < 6; k++) {
            double angle = hash(k, bucket * 71 + blast.impactAt) * Math.PI * 2, out = RfArmageddon.DOME_RADIUS * .55 * Math.sqrt(hash(k, bucket * 71 + blast.impactAt + 1));
            double x = centre.x + Math.cos(angle) * out, z = centre.z + Math.sin(angle) * out;
            double turn = angle + (hash(k, bucket * 73) - .5) * 2.4, reach = 2 + 3 * hash(k, bucket * 73 + 1);
            double x2 = x + Math.cos(turn) * reach, z2 = z + Math.sin(turn) * reach;
            Vec3 from = new Vec3(x, ground(level, x, z, centre.y) + .15, z).subtract(camera), to = new Vec3(x2, ground(level, x2, z2, centre.y) + .15, z2).subtract(camera);
            GlowBrush.lightning(glow, m, from, to, bucket * 83 + k, 5, .22, .05, RfPalette.SPARK, 200 * fade * flicker);
        }
    }

    /** The ground's height at {@code x}, {@code z} as this client has it, or {@code fallback} where it has none. */
    private static double ground(ClientLevel level, double x, double z, double fallback) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        return y <= level.getMinBuildHeight() + 1 ? fallback : y;
    }

    // --- sounds ------------------------------------------------------------------------------------

    /**
     * A blast's sounds as their moments come, if this client is in time for them: the ball's flight (following it,
     * bolts and all), the dome of glass heating, the atomic blast, and the shock front passing.
     */
    private static void sounds(Blast blast, double time, Vec3 camera) {
        double age = blast.age(time), t = age - RfArmageddon.IMPACT, distance = blast.centre().distanceTo(camera);
        float volume = (float) Math.clamp(1.25 - distance / (RfArmageddon.RADIUS * 4), .35, 1);
        if (!blast.flightHeard && age >= RfArmageddon.FIRE) {
            blast.flightHeard = true;
            if (age < RfArmageddon.FIRE + 20) {
                blast.flight = new BallSound(blast);
                Minecraft.getInstance().getSoundManager().play(blast.flight);
            }
        }
        if (!blast.domeHeard && t >= 0) {
            blast.domeHeard = true;
            if (t < 20) hear(RelicSounds.RF_ARMAGEDDON_DOME.get(), volume, blast.impactAt + 3);
        }
        if (!blast.blastHeard && t >= RfArmageddon.FLASH) {
            blast.blastHeard = true;
            if (t < RfArmageddon.FLASH + 20) hear(RelicSounds.RF_ARMAGEDDON_BLAST.get(), volume, blast.impactAt);
            EffectLights.flash(blast.centre().add(0, 4, 0), 15, 120, 60);
        }
        if (!blast.shockHeard && distance <= RfArmageddon.RADIUS * 1.2 && t >= RfArmageddon.FLASH && t >= RfArmageddon.reaches(Math.min(distance, RfArmageddon.RADIUS))) {
            blast.shockHeard = true;
            hear(RelicSounds.ARMAGEDDON_SHOCK.get(), (float) Math.clamp(1.1 - distance / RfArmageddon.RADIUS * .6, .45, 1), blast.impactAt + 1);
        }
    }

    /** The ball's heavy flight, heard from where the ball is (loud at any distance, but from its direction), until it meets the ground. */
    private static final class BallSound extends AbstractTickableSoundInstance {
        private final Blast blast;

        BallSound(Blast blast) {
            super(RelicSounds.RF_ARMAGEDDON_FLIGHT.get(), SoundSource.PLAYERS, RandomSource.create(blast.impactAt + 5));
            this.blast = blast;
            this.attenuation = Attenuation.NONE;
            this.relative = false;
            this.looping = false;
            place();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (!BLASTS.contains(blast)) stop();
            else place();
        }

        private void place() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                stop();
                return;
            }
            double age = blast.age(minecraft.level.getGameTime());
            if (age > RfArmageddon.IMPACT + 10) {
                stop();
                return;
            }
            Vec3 ball = RfArmageddon.ball(blast.shot, Math.clamp(age, RfArmageddon.FIRE, RfArmageddon.IMPACT));
            x = ball.x;
            y = ball.y;
            z = ball.z;
            volume = (float) Math.clamp(1.3 - minecraft.gameRenderer.getMainCamera().getPosition().distanceTo(ball) / (RfArmageddon.RADIUS * 3), .3, 1);
        }

        void finish() {
            stop();
        }
    }

    // --- the blocks --------------------------------------------------------------------------------

    /** Blocks are drawn from a buffer of their own before the blast's light and grading go over the world, so those cover them too. */
    private static final MultiBufferSource.BufferSource ROCKS = MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 18));

    static void flushDebris() {
        ROCKS.endBatch();
    }

    /**
     * The blocks: each lifted from the land as the dome's edge reaches it and floating up inside it, tumbling; flung up
     * and out by the flash and falling back out of the sky for a few seconds after. Where the server keeps its land they
     * stay where they are, and a rim of them stands round the crater's edge instead, only while the blast lasts.
     */
    static void debris(Minecraft minecraft, double time, Vec3 camera, PoseStack poses) {
        if (BLASTS.isEmpty()) return;
        var blocks = minecraft.getBlockRenderer();
        boolean safe = ArmageddonController.safeClient();
        for (Blast blast : BLASTS) {
            double t = blast.age(time) - RfArmageddon.IMPACT;
            Vec3 centre = blast.centre();
            if (centre.distanceTo(camera) > RfArmageddon.RADIUS * 1.5 || t < -2) continue;
            if (safe) {
                rim(minecraft, blast, t, camera, poses);
                continue;
            }
            if (t > RfArmageddon.FALLEN + 20) continue;
            if (blast.stones == null) blast.stones = noteStones(minecraft, centre, blast.impactAt);
            for (Stone stone : blast.stones) {
                double since = t - stone.liftAt;
                if (since < 0) continue;
                Vec3 at = lifted(centre, stone, Math.min(t, RfArmageddon.FLASH));
                if (t > RfArmageddon.FLASH) {
                    double u = t - RfArmageddon.FLASH;
                    Vec3 out = new Vec3(at.x - centre.x, 0, at.z - centre.z);
                    out = out.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : out.normalize();
                    double speed = .3 + .7 * hash(stone.index, 11), up = 1.1 + 1.6 * hash(stone.index, 12);
                    at = at.add(out.scale(speed * u)).add(0, up * u - .045 * u * u, 0);
                    if (at.y < centre.y - 24) continue;
                }
                double size = (.5 + .55 * hash(stone.index, 13)) * (1 - .3 * smooth(since / 40));
                tumble(blocks, poses, stone.state, at.subtract(camera), size, since * .09 + stone.index, stone.index);
            }
        }
    }

    /** Where a lifted block floats {@code t} ticks after the ball met the ground (no later than the flash): up inside the dome, drifting in. */
    private static Vec3 lifted(Vec3 centre, Stone stone, double t) {
        double rise = smooth((t - stone.liftAt) / 30);
        Vec3 from = Vec3.atCenterOf(stone.pos);
        Vec3 in = new Vec3(centre.x - from.x, 0, centre.z - from.z).scale(stone.drift * rise);
        return from.add(in).add(0, stone.height * rise, 0);
    }

    /** A few hundred of the land's top blocks inside the crater's reach, spread evenly, each lifted as the dome's edge reaches it. */
    private static List<Stone> noteStones(Minecraft minecraft, Vec3 centre, long seed) {
        List<Stone> all = new ArrayList<>();
        double radius = RfArmageddon.DOME_RADIUS, most = radius * radius;
        int reach = (int) Math.ceil(radius), cx = (int) Math.floor(centre.x), cz = (int) Math.floor(centre.z);
        for (int x = cx - reach; x <= cx + reach; x++) for (int z = cz - reach; z <= cz + reach; z++) {
            double dx = x + .5 - centre.x, dz = z + .5 - centre.z, flat = dx * dx + dz * dz;
            if (flat > most) continue;
            int y = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (Math.abs(y + .5 - centre.y) > Math.sqrt(most - flat)) continue;
            BlockPos at = new BlockPos(x, y, z);
            BlockState state = minecraft.level.getBlockState(at);
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
            all.add(new Stone(at, state, RfArmageddon.carvedAt(Math.sqrt(flat)) - RfArmageddon.IMPACT, 0, 0, 0));
        }
        int step = Math.max(1, all.size() / STONES);
        List<Stone> sample = new ArrayList<>();
        for (int index = 0; index < all.size(); index += step) {
            Stone stone = all.get(index);
            int n = sample.size();
            sample.add(new Stone(stone.pos, stone.state, stone.liftAt, 4 + 24 * hash(n, seed + 91), .1 + .35 * hash(n, seed + 92), n));
        }
        return sample;
    }

    /**
     * Where the server keeps its land: a rim of the land's own blocks standing round the crater's edge, pushed up and
     * leaning out as the flash comes, and sinking back into the ground as the blast falls silent.
     */
    private static void rim(Minecraft minecraft, Blast blast, double t, Vec3 camera, PoseStack poses) {
        double push = smooth((t - RfArmageddon.FLASH) / 6) * (1 - smooth((t - (RfArmageddon.FLASH + RfArmageddon.BLAST - 120)) / 100));
        if (push <= .01) return;
        if (blast.rim == null) blast.rim = noteRim(minecraft, blast.centre());
        var blocks = minecraft.getBlockRenderer();
        for (RimBlock block : blast.rim) {
            double height = block.rise * push, tilt = .4 * push * block.rise / RfArmageddon.RIM_HEIGHT;
            Vec3 base = new Vec3(block.pos.getX() + .5, block.pos.getY() + 1, block.pos.getZ() + .5).subtract(camera);
            Vector3f tangent = new Vector3f((float) -block.outZ, 0, (float) block.outX);
            int light = LevelRenderer.getLightColor(minecraft.level, block.pos.above());
            if (height > 1) column(blocks, poses, block.fill, base, tangent, tilt, 0, height - 1, block.width, light);
            column(blocks, poses, block.top, base, tangent, tilt, Math.max(0, height - 1), Math.min(1, height), block.width, light);
        }
    }

    /** One stretch of a rim column: {@code tall} blocks of {@code state}, from {@code from} up, leaning out about its base. */
    private static void column(net.minecraft.client.renderer.block.BlockRenderDispatcher blocks, PoseStack poses, BlockState state, Vec3 base, Vector3f tangent,
            double tilt, double from, double tall, double width, int light) {
        if (tall <= .01) return;
        poses.pushPose();
        poses.translate(base.x, base.y, base.z);
        poses.mulPose(Axis.of(tangent).rotation((float) -tilt));
        poses.translate(0, from, 0);
        poses.scale((float) width, (float) tall, (float) width);
        poses.translate(-.5, 0, -.5);
        blocks.renderSingleBlock(state, poses, ROCKS, light, OverlayTexture.NO_OVERLAY);
        poses.popPose();
    }

    /** The land's top blocks round the crater's edge, a few hundred columns of them spread evenly, each as high as the rim stands there. */
    private static List<RimBlock> noteRim(Minecraft minecraft, Vec3 centre) {
        double outer = RfArmageddon.rimReach(), inner = RfArmageddon.DOME_RADIUS - .15 * RfArmageddon.RIM_WIDTH;
        int columns = (int) Math.round(Math.PI * (outer * outer - inner * inner)), step = Math.max(1, (int) Math.round(Math.sqrt(columns / (RIM_BLOCKS / 2.0))));
        int reach = (int) Math.ceil(outer), cx = (int) Math.floor(centre.x), cz = (int) Math.floor(centre.z);
        List<RimBlock> rim = new ArrayList<>();
        for (int x = cx - reach; x <= cx + reach; x += step) for (int z = cz - reach; z <= cz + reach; z += step) {
            double dx = x + .5 - centre.x, dz = z + .5 - centre.z, d = Math.sqrt(dx * dx + dz * dz), rise = RfArmageddon.rimHeight(d);
            if (rise < .3) continue;
            int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (Math.abs(y + .5 - centre.y) > RfArmageddon.RIM_REACH) continue;
            BlockPos at = new BlockPos(x, y, z);
            BlockState top = minecraft.level.getBlockState(at), under = minecraft.level.getBlockState(at.below());
            if (top.isAir() || top.getRenderShape() != RenderShape.MODEL) continue;
            rim.add(new RimBlock(at, top, under.getRenderShape() == RenderShape.MODEL ? under : top, rise, dx / d, dz / d, step));
        }
        return rim;
    }

    private static void tumble(net.minecraft.client.renderer.block.BlockRenderDispatcher blocks, PoseStack poses, BlockState state, Vec3 at, double size, double spin, int index) {
        poses.pushPose();
        poses.translate(at.x, at.y, at.z);
        poses.mulPose(Axis.YP.rotation((float) (spin + index * 1.7)));
        poses.mulPose(Axis.XP.rotation((float) (spin * 1.3 + index)));
        poses.scale((float) size, (float) size, (float) size);
        poses.translate(-.5, -.5, -.5);
        blocks.renderSingleBlock(state, poses, ROCKS, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poses.popPose();
    }

    // --- the stages for ArmageddonVolume ----------------------------------------------------------

    /** Queues every charging ball and every blast for the renderer's passes: the world's grading, the ball's dark core, the dome of glass. */
    static void volumes(double time, Vec3 camera) {
        for (ArmageddonState s : CHARGING) {
            double age = s.age(time), charge = RfArmageddon.charge(age);
            ArmageddonVolume.queueRf(s.target().subtract(camera), new ArmageddonVolume.RfStage(0, 0, 0, 0, 0, 0, RfArmageddon.nose(s).subtract(camera),
                    RfArmageddon.ballRadius(age), .95, .8 + .7 * charge, age, 0, 0, 0, 0, 0));
        }
        for (Blast blast : BLASTS) {
            double age = blast.age(time), t = age - RfArmageddon.IMPACT;
            if (age < RfArmageddon.FIRE || t > RfArmageddon.COLOUR + 20 && t > RfArmageddon.FLASH + RfArmageddon.SHOCK + 20) continue;
            ArmageddonVolume.queueRf(blast.centre().subtract(camera), stage(blast, time, camera));
        }
    }

    /**
     * A blast {@code t} ticks from the moment its ball meets the ground: the world draining grey as it hangs over the
     * target; the ball's core and rim; the dome of glass swelling and heating to white; the flash's white flood, the
     * silhouettes after it and the shock front running out; then the colour back.
     */
    static ArmageddonVolume.RfStage stage(Blast blast, double time, Vec3 camera) {
        double age = blast.age(time), t = age - RfArmageddon.IMPACT, near = ArmageddonVisual.near(blast.centre().distanceTo(camera));
        double grey = smooth((age - (RfArmageddon.ARRIVE - 20)) / 60) * (1 - smooth((t - RfArmageddon.SILHOUETTES) / (RfArmageddon.COLOUR - RfArmageddon.SILHOUETTES)));
        double silhouette = t < RfArmageddon.FLASH ? 0 : smooth((t - RfArmageddon.FLASH - 2) / 3) * (1 - smooth((t - (RfArmageddon.SILHOUETTES - 10)) / 35));
        double flood = t < RfArmageddon.FLASH ? 0 : smooth((t - RfArmageddon.FLASH) / 2) * (1 - smooth((t - RfArmageddon.FLASH - 4) / (RfArmageddon.FLOODED - RfArmageddon.FLASH)));
        double shock = t < RfArmageddon.FLASH ? 0 : 1 - smooth((t - RfArmageddon.FLASH - RfArmageddon.SHOCK) / 12);
        boolean flying = age < RfArmageddon.IMPACT + 1;
        Vec3 ball = flying ? RfArmageddon.ball(blast.shot, age).subtract(camera) : Vec3.ZERO;
        double hover = smooth((age - RfArmageddon.ARRIVE) / RfArmageddon.HOVER);
        double dome = t >= 0 && t < RfArmageddon.FLASH + 4 ? RfArmageddon.dome(Math.min(t, RfArmageddon.FLASH)) : 0;
        double heat = smooth((t - 8) / (RfArmageddon.FLASH - 8)), glass = smooth(t / 3) * (1 - smooth((t - RfArmageddon.FLASH) / 4));
        double edge = smooth(t / 4) * (1 + heat) * (1 - smooth((t - RfArmageddon.FLASH) / 6));
        return new ArmageddonVolume.RfStage(grey * near, .28 * grey * near, silhouette * near, flood * near, RfArmageddon.reach(t), shock * near,
                ball, flying ? RfArmageddon.ballRadius(age) : 0, .95, 1.5 + .6 * hover, age, dome, glass, heat, .5 + 1.6 * heat, edge);
    }

    // --- the ground shaking ------------------------------------------------------------------------

    /** How hard the ground shakes at {@code camera}: the heavy ball overhead, its meeting the ground, the dome swelling and the shock front passing. */
    static double shake(Vec3 camera, double time) {
        double shake = 0;
        for (Blast blast : BLASTS) {
            double age = blast.age(time), t = age - RfArmageddon.IMPACT, distance = camera.distanceTo(blast.centre());
            if (distance > RfArmageddon.RADIUS * 1.3) continue;
            double near = 1 - distance / (RfArmageddon.RADIUS * 1.3);
            double hum = age > RfArmageddon.FIRE && age < RfArmageddon.IMPACT ? .1 + .3 * smooth((age - RfArmageddon.ARRIVE) / RfArmageddon.HOVER) : 0;
            double thud = t >= 0 ? .9 * Math.exp(-t / 10) : 0, swelling = t >= 0 && t < RfArmageddon.FLASH ? .25 + .5 * t / RfArmageddon.FLASH : 0;
            double reaches = RfArmageddon.reaches(Math.min(distance, RfArmageddon.RADIUS));
            double front = t >= RfArmageddon.FLASH && t >= reaches ? 2 * Math.exp(-(t - Math.max(reaches, RfArmageddon.FLASH)) / 16) : 0;
            shake = Math.max(shake, (hum + thud + swelling + front) * near);
        }
        return shake;
    }

    // --- helpers -------------------------------------------------------------------------------------

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

    private RfArmageddonVisual() {
    }
}
