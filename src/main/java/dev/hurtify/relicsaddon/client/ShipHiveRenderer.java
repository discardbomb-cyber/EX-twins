package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.ship.AegisModule;
import dev.hurtify.relicsaddon.ship.AegisShape;
import dev.hurtify.relicsaddon.ship.EscortModule;
import dev.hurtify.relicsaddon.ship.LanceModule;
import dev.hurtify.relicsaddon.ship.LanceShape;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity;
import dev.hurtify.relicsaddon.ship.ShipRays;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the ship hives' drones and beams where the ships really are: each hive's frame is taken from its ship's pose
 * for this very frame, so the drones ride the ship smoothly however it moves and turns.
 */
public final class ShipHiveRenderer {
    /** Farthest a hive's drones are drawn, and within what distance they get their full model. */
    private static final double RANGE = 192, DETAIL_RANGE = 32;
    private static final int ESCORT_COLOR = 0x38E8FF;
    private static final int LANCE_COLOR = 0xB151FF, HOT_COLOR = 0xFF7A2E, OVERHEAT_COLOR = 0xFF3A24, AEGIS_COLOR = 0x42E6C8, AEGIS_HIT = 0xC8FFF4;
    /** Ticks an aegis shield takes to rise or fall. */
    private static final double RAISE_TICKS = 16;
    /** Each hive's turret as drawn: its aim, turned smoothly towards its mark, and its ring's spin. */
    private static final Map<ShipHiveBlockEntity, View> VIEWS = new WeakHashMap<>();

    private static final class View {
        Vec3 aim, lean = Vec3.ZERO;
        /** Where each escort wing is drawn: eased towards where the server says it is, so corrections never jump. */
        final Vec3[] wings = new Vec3[EscortModule.WINGS];
        /** The last arc of each wing that has thrown its light. */
        final long[] flashed = new long[EscortModule.WINGS];
        /** The lance's beam as it is heard, while it burns, and when a beam was last started. */
        ShipBeamSound beam;
        double beamTriedAt = Double.NaN;
        double spin, drawnAt = Double.NaN, firingSince = Double.NaN;
        long sparkTick = Long.MIN_VALUE;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || ShipHiveBlockEntity.CLIENT_LOADED.isEmpty()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = level.getGameTime() + partial;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poses = event.getPoseStack();
        Matrix4f matrix = poses.last().pose();
        VertexConsumer glow = ShieldGlow.consumer();
        boolean drew = false;
        for (ShipHiveBlockEntity hive : new ArrayList<>(ShipHiveBlockEntity.CLIENT_LOADED)) {
            if (hive.isRemoved() || hive.getLevel() != level) {
                ShipHiveBlockEntity.CLIENT_LOADED.remove(hive);
                VIEWS.remove(hive);
                continue;
            }
            ShipFrame frame = ShipFrame.drawn(hive, partial);
            if (hive.module() instanceof LanceModule lance) {
                if (frame.centre().distanceToSqr(camera) > RANGE * RANGE) continue;
                lance(minecraft, level, event, hive, lance, frame, time, partial, camera, poses, glow, matrix);
                drew = true;
            } else if (hive.module() instanceof EscortModule escort) {
                escort(minecraft, level, event, hive, escort, frame, time, partial, camera, poses, glow, matrix);
                drew = true;
            } else if (hive.module() instanceof AegisModule aegis) {
                AegisShape shape = AegisShape.of(frame, hive.getBlockPos());
                double far = RANGE + shape.reach();
                if (frame.toWorld(shape.centre()).distanceToSqr(camera) > far * far) continue;
                aegis(minecraft, event, hive, aegis, frame, shape, time, camera, poses, glow, matrix);
                drew = true;
            }
        }
        if (!drew) return;
        minecraft.renderBuffers().bufferSource().endBatch();
        ShieldGlow.flush();
    }

    private static void lance(Minecraft minecraft, ClientLevel level, RenderLevelStageEvent event, ShipHiveBlockEntity hive, LanceModule lance,
            ShipFrame frame, double time, float partial, Vec3 camera, PoseStack poses, VertexConsumer glow, Matrix4f matrix) {
        Vec3 normal = frame.turn(hive.normal()).normalize();
        Vec3 across = frame.turn(LanceShape.across(hive.facing())).normalize();
        Vec3 mount = LanceShape.mount(frame.centre(), normal);
        View view = VIEWS.computeIfAbsent(hive, ignored -> new View());
        if (view.aim == null) view.aim = normal;
        double step = Double.isNaN(view.drawnAt) ? 0 : Math.clamp(time - view.drawnAt, 0, 2);
        view.drawnAt = time;

        // The mark as this client sees it, when it does; otherwise where the server said the turret points.
        Entity target = lance.targetId() >= 0 ? level.getEntity(lance.targetId()) : null;
        Vec3 mark = target == null ? null : target.getPosition(partial).add(0, lance.aimEye() ? target.getEyeHeight() : target.getBbHeight() * .5, 0);
        Vec3 want = mark != null ? LanceShape.clampToFace(mark.subtract(mount).normalize(), normal, -.6)
                : lance.deployed() ? frame.turn(lance.aimLocal()).normalize() : normal;
        view.aim = LanceShape.turnTowards(view.aim, want, .24 * step);
        view.spin += step * (lance.firing() ? .16 : .03);

        double since = time - lance.deployedAt();
        double deploy = lance.deployed() ? since / LanceShape.DEPLOY_TICKS : 1 - since / LanceShape.DEPLOY_TICKS;
        deploy = Math.clamp(deploy, 0, 1);
        Vec3[] drones = LanceShape.drones(frame.centre(), normal, across, view.aim, deploy, view.spin);
        double shown = LanceShape.smooth(deploy);
        Vec3 forward = normal.lerp(view.aim, shown).normalize();
        Vec3 middle = drones[0].add(drones[1]).add(drones[2]).scale(1 / 3.0);
        double heat = lance.heat(time) / LanceModule.HOT;
        int hot = heatColor(heat, lance.overheated());
        if (lance.overheated()) {
            // Spent, the drones sag in towards the turret's middle and shiver as they cool.
            for (int k = 0; k < drones.length; k++) {
                double shiver = .025 * Math.sin(time * 3.7 + k * 2.1);
                drones[k] = drones[k].lerp(middle, .16).add(normal.scale(-.12 + shiver));
            }
            middle = drones[0].add(drones[1]).add(drones[2]).scale(1 / 3.0);
        }

        boolean detailed = frame.centre().distanceToSqr(camera) < DETAIL_RANGE * DETAIL_RANGE;
        for (Vec3 at : drones) {
            if (!event.getFrustum().isVisible(new AABB(at, at).inflate(LanceShape.DRONE))) continue;
            Vec3 up = at.subtract(middle);
            up = up.subtract(forward.scale(up.dot(forward)));
            if (up.lengthSqr() < 1e-6) up = across;
            drone(minecraft, HiveType.TWINS, at, forward, up.normalize(), LanceShape.DRONE, camera, poses, detailed);
            // Each drone's core glows with the turret's heat.
            GlowBrush.dot(glow, matrix, at.subtract(camera), .22 + .18 * heat, hot, 90 + 140 * Math.max(heat, lance.firing() ? .5 : 0));
        }

        // The three joined: a triangle of light between them, strongest as they stand out and while they burn.
        if (shown > .05) for (int k = 0; k < drones.length; k++) {
            Vec3 a = drones[k].subtract(camera), b = drones[(k + 1) % drones.length].subtract(camera);
            int link = lance.overheated() ? hot : LANCE_COLOR;
            GlowBrush.lightning(glow, matrix, a, b, (long) (time / 3) * 31 + k, 5, .035, .018, link, (lance.firing() ? 150 : 70) * shown);
        }
        // A beam the sound engine let go of (muted, or reloaded) is started again, but no more than once a second.
        if (lance.firing() && (view.beam == null || view.beam.isStopped() || !minecraft.getSoundManager().isActive(view.beam))
                && (Double.isNaN(view.beamTriedAt) || time - view.beamTriedAt >= 20 || time < view.beamTriedAt)) {
            view.beamTriedAt = time;
            view.beam = new ShipBeamSound(hive);
            minecraft.getSoundManager().play(view.beam);
        }
        if (!lance.firing()) {
            view.firingSince = Double.NaN;
            if (lance.overheated()) vent(level, view, drones, time);
            return;
        }
        if (Double.isNaN(view.firingSince)) view.firingSince = time;
        Vec3 focus = LanceShape.focus(frame.centre(), normal, view.aim);
        Vec3 end = mark != null ? ShipRays.reach(level, focus, view.aim, mark.distanceTo(focus) + 1.5)
                : focus.add(view.aim.scale(Math.max(1, lance.reach())));
        if (mark != null && mark.distanceToSqr(focus) < end.distanceToSqr(focus)) {
            // The beam ends in the creature it burns, at the point of its line nearest the creature's middle.
            double along = Math.max(0, mark.subtract(focus).dot(view.aim));
            end = focus.add(view.aim.scale(along));
        }
        // The beam shoots out from the focus over its first few ticks, then holds.
        double length = end.distanceTo(focus), grown = Math.min(length, (time - view.firingSince) * 14);
        boolean arrived = grown >= length - 1e-3;
        end = focus.add(view.aim.scale(grown));
        double flicker = .85 + .15 * Math.sin(time * 2.3) * Math.sin(time * 5.1);
        int core = mix(LANCE_COLOR, hot, heat * .6);
        Vec3 f = focus.subtract(camera), e = end.subtract(camera);
        for (Vec3 at : drones) {
            Vec3 muzzle = at.add(forward.scale(LanceShape.DRONE * .45)).subtract(camera);
            GlowBrush.beam(glow, matrix, muzzle, f, .05, LANCE_COLOR, 190 * flicker);
        }
        // A lens of light at the focus, turned to the line of fire.
        Vec3 u = view.aim.cross(Math.abs(view.aim.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize(), v = view.aim.cross(u);
        GlowBrush.circle(glow, matrix, f, u, v, .46, 28, .035, LANCE_COLOR, 190);
        GlowBrush.circle(glow, matrix, f, u, v, .28 + .05 * Math.sin(time * .8), 22, .025, mix(LANCE_COLOR, 0xFFFFFF, .5), 150);
        GlowBrush.dot(glow, matrix, f, .55 * flicker, core, 240);
        // The lance: a white-hot core in its glow, a wide faint halo, and two strands of light winding round it.
        GlowBrush.beam(glow, matrix, f, e, .14 * flicker, core, 240);
        GlowBrush.line(glow, matrix, f, e, .6, LANCE_COLOR, 40);
        helix(glow, matrix, f, e, u, v, time, LANCE_COLOR);
        if (arrived) GlowBrush.dot(glow, matrix, e, .75 * flicker, core, 230);
        EffectLights.glow(focus, 12, 6);
        if (arrived) {
            EffectLights.glow(end, 10, 5);
            sparks(level, view, end, time);
        }
    }

    /**
     * An aegis hive: its six drones out at their places on the shell (leaning towards the ship's threats and drifting
     * slowly round), each tied to the hive by a thread of light and lighting up the part of the shield it holds; the
     * shield itself a honeycomb, faint but at its rim and round the drones, rippling out from each hit.
     */
    private static void aegis(Minecraft minecraft, RenderLevelStageEvent event, ShipHiveBlockEntity hive, AegisModule aegis, ShipFrame frame,
            AegisShape shape, double time, Vec3 camera, PoseStack poses, VertexConsumer glow, Matrix4f matrix) {
        View view = VIEWS.computeIfAbsent(hive, ignored -> new View());
        double step = Double.isNaN(view.drawnAt) ? 1 : Math.clamp(time - view.drawnAt, 0, 2);
        view.drawnAt = time;
        view.lean = view.lean.lerp(aegis.lean(), Math.min(1, .08 * step));
        double since = time - aegis.changedAt();
        double up = Math.clamp(since / RAISE_TICKS, 0, 1);
        double shown = LanceShape.smooth(aegis.raised() ? up : 1 - up);
        Vec3 normal = frame.turn(hive.normal()).normalize();
        Vec3 across = frame.turn(LanceShape.across(hive.facing())).normalize(), side = normal.cross(across);
        Vec3 face = frame.centre().add(normal.scale(.5));
        Vec3 middle = frame.toWorld(shape.centre());
        Vec3[] stations = stations(view.lean, time);
        boolean detailed = face.distanceToSqr(camera) < DETAIL_RANGE * DETAIL_RANGE * 4;
        for (int k = 0; k < stations.length; k++) {
            double angle = Math.PI * 2 * k / stations.length;
            Vec3 dock = frame.centre().add(normal.scale(1.05)).add(across.scale(Math.cos(angle) * .72)).add(side.scale(Math.sin(angle) * .72));
            Vec3 post = shape.worldOnShell(frame, stations[k]);
            // Out along an arc from the hive: straight out from the face first, then round to the place on the shell.
            Vec3 lift = dock.add(normal.scale(3 * Math.sin(Math.PI * shown)));
            Vec3 at = dock.lerp(lift, 1 - shown).lerp(post, shown);
            Vec3 out = post.subtract(middle);
            Vec3 facing = normal.lerp(out.lengthSqr() < 1e-6 ? normal : out.normalize(), shown).normalize();
            Vec3 upward = frame.turn(new Vec3(0, 1, 0));
            if (Math.abs(upward.dot(facing)) > .95) upward = across;
            if (event.getFrustum().isVisible(new AABB(at, at).inflate(LanceShape.DRONE))) {
                drone(minecraft, HiveType.MANA, at, facing, upward, LanceShape.DRONE, camera, poses, detailed);
            }
            GlowBrush.dot(glow, matrix, at.subtract(camera), .3, AEGIS_COLOR, 60 + 120 * shown);
            if (shown > .05) GlowBrush.line(glow, matrix, face.subtract(camera), at.subtract(camera), .02, AEGIS_COLOR, 55 * shown);
        }
        if (shown > .02 && event.getFrustum().isVisible(new AABB(middle, middle).inflate(shape.reach()))) {
            shell(glow, matrix, frame, shape, aegis, stations, shown, time, camera, middle.distanceTo(camera) - shape.reach() > 64);
        }
        if (shown > .3) EffectLights.glow(middle, 6 * shown, Math.min(15, shape.reach()));
    }

    /**
     * An escort hive's two wings: each a linked pair of drones, an arc crackling between them, with its little swarm
     * circling round it; out after a mark, the pair lashes it with the arc and its little drones dive at it.
     */
    private static void escort(Minecraft minecraft, ClientLevel level, RenderLevelStageEvent event, ShipHiveBlockEntity hive, EscortModule escort,
            ShipFrame frame, double time, float partial, Vec3 camera, PoseStack poses, VertexConsumer glow, Matrix4f matrix) {
        View view = VIEWS.computeIfAbsent(hive, ignored -> new View());
        double step = Double.isNaN(view.drawnAt) ? 1 : Math.clamp(time - view.drawnAt, 0, 2);
        view.drawnAt = time;
        Vec3 normal = frame.turn(hive.normal()).normalize();
        Vec3 across = frame.turn(LanceShape.across(hive.facing())).normalize();
        Vec3 up = frame.turn(new Vec3(0, 1, 0));
        for (int index = 0; index < EscortModule.WINGS; index++) {
            EscortModule.Wing wing = escort.wings()[index];
            Vec3 dock = EscortModule.dock(frame.centre(), normal, across, index);
            boolean docked = wing.phase() == EscortModule.Phase.DOCKED || wing.position() == null;
            Vec3 goal = docked ? dock : wing.position().add(wing.velocity().scale(Math.clamp(time - wing.positionAt(), 0, 10)));
            Vec3 shown = view.wings[index];
            if (docked || shown == null || shown.distanceToSqr(goal) > 64 * 64) shown = goal;
            else shown = shown.add(wing.velocity().scale(step)).lerp(goal, Math.min(1, .3 * step));
            view.wings[index] = shown;
            Entity target = wing.targetId() >= 0 ? level.getEntity(wing.targetId()) : null;
            Vec3 mark = target == null ? null : target.getPosition(partial).add(0, target.getBbHeight() * .5, 0);
            // The pair faces where it flies, or its mark while it fights; the two drones side by side across that.
            Vec3 heading = docked ? normal : mark != null ? mark.subtract(shown) : wing.velocity();
            if (heading.lengthSqr() < 1e-4) heading = normal;
            heading = heading.normalize();
            Vec3 side = heading.cross(up);
            if (side.lengthSqr() < 1e-4) side = across;
            side = side.normalize();
            double spread = docked ? .5 : .85, bob = docked ? 0 : .08 * Math.sin(time * .15 + index);
            Vec3 left = shown.add(side.scale(spread)).add(up.scale(bob)), right = shown.subtract(side.scale(spread)).subtract(up.scale(bob));
            boolean detailed = shown.distanceToSqr(camera) < DETAIL_RANGE * DETAIL_RANGE;
            for (Vec3 at : new Vec3[]{left, right}) {
                if (event.getFrustum().isVisible(new AABB(at, at).inflate(LanceShape.DRONE))) {
                    drone(minecraft, HiveType.RF, at, heading, up, docked ? .75 : LanceShape.DRONE, camera, poses, detailed);
                }
            }
            // The link between the two, the joint that makes them one wing.
            GlowBrush.lightning(glow, matrix, left.subtract(camera), right.subtract(camera), (long) (time / 2) * 17 + index, 6, .09, .025,
                    ESCORT_COLOR, docked ? 60 : 170);
            swarm(minecraft, event, level, wing, shown, heading, side, up, mark, docked, time, camera, poses, glow, matrix);
            if (mark != null) {
                double arc = time - wing.arcAt();
                if (arc >= 0 && arc < 6) {
                    double fade = 1 - arc / 6;
                    long seed = wing.arcAt() * 31 + index;
                    GlowBrush.lightning(glow, matrix, left.subtract(camera), mark.subtract(camera), seed, 8, .12, .05, ESCORT_COLOR, 240 * fade);
                    GlowBrush.lightning(glow, matrix, right.subtract(camera), mark.subtract(camera), seed + 7, 8, .12, .05, ESCORT_COLOR, 240 * fade);
                    GlowBrush.dot(glow, matrix, mark.subtract(camera), 1.1 * fade, ESCORT_COLOR, 230);
                    if (view.flashed[index] != wing.arcAt()) {
                        view.flashed[index] = wing.arcAt();
                        EffectLights.flash(mark, 12, 8, 4);
                    }
                }
            }
            if (!docked) EffectLights.glow(shown, 7, 4);
        }
    }

    /** A wing's little drones: eight on two tilted rings round the pair, one of them diving at the mark now and then. */
    private static void swarm(Minecraft minecraft, RenderLevelStageEvent event, ClientLevel level, EscortModule.Wing wing, Vec3 middle, Vec3 heading,
            Vec3 side, Vec3 up, Vec3 mark, boolean docked, double time, Vec3 camera, PoseStack poses, VertexConsumer glow, Matrix4f matrix) {
        Vec3 top = side.cross(heading).normalize();
        double radius = docked ? .7 : 1.75;
        double dive = time - wing.diveAt();
        int diver = (int) Math.floorMod(wing.diveAt() / EscortModule.DIVE_EVERY, 8);
        for (int k = 0; k < 8; k++) {
            double angle = time * (docked ? .02 : .13) + k * Math.PI / 4;
            double tilt = k % 2 == 0 ? .45 : -.45;
            Vec3 ring = side.scale(Math.cos(angle)).add(heading.scale(Math.sin(angle)));
            Vec3 at = middle.add(ring.scale(radius)).add(top.scale(Math.sin(angle) * tilt * radius));
            Vec3 facing = ring.cross(top);
            if (mark != null && k == diver && dive >= 0 && dive < EscortModule.DIVE_EVERY) {
                // Out to the mark and back along a curve: a quick sting.
                double t = dive / EscortModule.DIVE_EVERY, out = Math.sin(Math.PI * t);
                Vec3 path = at.lerp(mark, out).add(top.scale(out * (1 - out) * 2));
                facing = t < .5 ? mark.subtract(at) : at.subtract(mark);
                at = path;
                if (t > .35 && t < .65) GlowBrush.dot(glow, matrix, mark.subtract(camera), .5, ESCORT_COLOR, 200);
            }
            if (facing.lengthSqr() < 1e-6) facing = heading;
            if (event.getFrustum().isVisible(new AABB(at, at).inflate(.3))) {
                drone(minecraft, HiveType.RF, at, facing.normalize(), up, .3, camera, poses, false);
            }
            GlowBrush.dot(glow, matrix, at.subtract(camera), .12, ESCORT_COLOR, docked ? 60 : 140);
        }
    }

    /**
     * Where the six drones stand on the shell (unit directions in the shield's unit terms): one to each side of the
     * ship, above and below, the set turning slowly about the ship's up and leaning towards {@code lean}.
     */
    static Vec3[] stations(Vec3 lean, double time) {
        double turn = time * .006, cos = Math.cos(turn), sin = Math.sin(turn);
        Vec3[] base = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        Vec3[] out = new Vec3[base.length];
        for (int k = 0; k < base.length; k++) {
            Vec3 d = base[k];
            Vec3 turned = new Vec3(d.x * cos - d.z * sin, d.y, d.x * sin + d.z * cos);
            // A little tilt keeps the top and bottom drones off the poles, so the set never looks stuck.
            Vec3 leaned = turned.add(lean.scale(.7)).add(0, 0, .0001 * k);
            out[k] = leaned.normalize();
        }
        return out;
    }

    /** The shield's honeycomb: every cell's rim, bright at the shell's edge as seen, round the drones and where it was hit. */
    private static void shell(VertexConsumer glow, Matrix4f matrix, ShipFrame frame, AegisShape shape, AegisModule aegis, Vec3[] stations,
            double shown, double time, Vec3 camera, boolean far) {
        double charge = aegis.charge() / (double) AegisModule.FULL;
        double width = Math.clamp(shape.reach() * .004, .02, .08);
        for (ShieldHoneycomb.Cell cell : ShieldHoneycomb.CELLS) {
            float[] c = cell.center();
            Vec3 unit = new Vec3(c[0], c[1], c[2]);
            double light = 14;
            for (Vec3 station : stations) light += 120 * Math.exp(-(1 - unit.dot(station)) / .05);
            for (AegisModule.Hit hit : aegis.hits()) {
                double age = time - hit.at();
                if (age < 0 || age > 40) continue;
                double ring = age * .07, apart = Math.acos(Math.clamp(unit.dot(hit.unit()), -1, 1)) - ring;
                light += 230 * hit.strength() * Math.exp(-apart * apart / .012) * (1 - age / 40);
                if (age < 6) light += 160 * hit.strength() * Math.exp(-(1 - unit.dot(hit.unit())) / .01) * (1 - age / 6);
            }
            Vec3 centre = shape.worldOnShell(frame, unit);
            Vec3 normal = frame.turn(shape.normalAt(unit));
            Vec3 toEye = camera.subtract(centre);
            double fresnel = 1 - Math.abs(normal.dot(toEye.normalize()));
            light += 90 * fresnel * fresnel * fresnel;
            // A weak shield flickers.
            if (charge < .25) light *= .55 + .45 * Math.abs(Math.sin(time * .9 + c[0] * 13 + c[2] * 7));
            light *= shown;
            // From far off only what glows is drawn: the rim, the drones' pieces and the hits, not the faint lattice.
            if (light < (far ? 30 : 3)) continue;
            int color = light > 140 ? GlowBrush.mix(AEGIS_COLOR, AEGIS_HIT, (light - 140) / 160) : AEGIS_COLOR;
            float[] p = cell.perimeter();
            int corners = p.length / 3;
            Vec3 previous = shape.worldOnShell(frame, new Vec3(p[(corners - 1) * 3], p[(corners - 1) * 3 + 1], p[(corners - 1) * 3 + 2])).subtract(camera);
            for (int k = 0; k < corners; k++) {
                Vec3 corner = shape.worldOnShell(frame, new Vec3(p[k * 3], p[k * 3 + 1], p[k * 3 + 2])).subtract(camera);
                GlowBrush.line(glow, matrix, previous, corner, width, color, Math.min(255, light));
                previous = corner;
            }
        }
    }

    /** Two strands of light winding round the beam from {@code a} to {@code b} (camera-relative), running outwards. */
    private static void helix(VertexConsumer glow, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 u, Vec3 v, double time, int color) {
        Vec3 span = b.subtract(a);
        double length = span.length();
        if (length < .3) return;
        Vec3 dir = span.scale(1 / length);
        int steps = Math.min(160, (int) (length / .35) + 1);
        for (int strand = 0; strand < 2; strand++) {
            Vec3 previous = null;
            for (int k = 0; k <= steps; k++) {
                double s = length * k / steps, angle = s * 1.7 - time * 1.1 + strand * Math.PI;
                double radius = .2 * Math.min(1, s / 1.5);
                Vec3 point = a.add(dir.scale(s)).add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
                if (previous != null) GlowBrush.line(glow, matrix, previous, point, .025, color, 150 * (1 - .5 * s / length));
                previous = point;
            }
        }
    }

    /** One drone model, a block across, looking along {@code forward} with its top towards {@code up}. */
    private static void drone(Minecraft minecraft, HiveType look, Vec3 at, Vec3 forward, Vec3 up, double size, Vec3 camera, PoseStack poses, boolean detailed) {
        poses.pushPose();
        poses.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        // The models look along -Z: their -Z goes to forward, their +Y to up.
        Vector3f z = new Vector3f((float) -forward.x, (float) -forward.y, (float) -forward.z).normalize();
        Vector3f y = new Vector3f((float) up.x, (float) up.y, (float) up.z);
        // Looking straight along its up, a drone takes whichever axis lies most across its sight instead.
        if (new Vector3f(y).cross(z).lengthSquared() < 1e-6F) y = Math.abs(z.y) < .9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f x = new Vector3f(y).cross(z).normalize();
        y = new Vector3f(z).cross(x).normalize();
        poses.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(x, y, z)));
        poses.scale((float) size, (float) size, (float) size);
        poses.translate(-.5, -.5, -.5);
        HiveVisualRenderer.renderModel(look, poses, minecraft.renderBuffers().bufferSource(), detailed, !detailed);
        poses.popPose();
    }

    /** Sparks where the beam burns, a few a tick. */
    private static void sparks(ClientLevel level, View view, Vec3 at, double time) {
        long tick = (long) time;
        if (tick <= view.sparkTick) return;
        view.sparkTick = tick;
        var random = level.getRandom();
        for (int k = 0; k < 3; k++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                    (random.nextDouble() - .5) * .5, random.nextDouble() * .3, (random.nextDouble() - .5) * .5);
        }
        if (random.nextInt(3) == 0) level.addParticle(new DustParticleOptions(new Vector3f(.69F, .32F, 1F), 1.2F), at.x, at.y, at.z, 0, .02, 0);
    }

    /** Steam venting off an overheated turret, and now and then a spark of slag. */
    private static void vent(ClientLevel level, View view, Vec3[] drones, double time) {
        long tick = (long) time;
        if (tick <= view.sparkTick) return;
        view.sparkTick = tick;
        var random = level.getRandom();
        Vec3 at = drones[random.nextInt(drones.length)];
        level.addParticle(ParticleTypes.WHITE_SMOKE, at.x + (random.nextDouble() - .5) * .5, at.y + .3, at.z + (random.nextDouble() - .5) * .5,
                (random.nextDouble() - .5) * .04, .06, (random.nextDouble() - .5) * .04);
        if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.LAVA, at.x, at.y, at.z, 0, 0, 0);
    }

    private static int heatColor(double heat, boolean overheated) {
        if (overheated) return OVERHEAT_COLOR;
        return heat < .5 ? mix(LANCE_COLOR, HOT_COLOR, heat * 2) : mix(HOT_COLOR, OVERHEAT_COLOR, (heat - .5) * 2);
    }

    private static int mix(int a, int b, double t) {
        return GlowBrush.mix(a, b, t);
    }

    /** Forgets every turret as the player leaves the world. */
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        VIEWS.clear();
        ShipHiveBlockEntity.CLIENT_LOADED.clear();
    }

    private ShipHiveRenderer() {
    }
}
