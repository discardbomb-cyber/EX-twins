package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import dev.hurtify.relicsaddon.shipshield.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Hull-local materials using the amulets' palettes, travelling waves and additive light. */
public final class ShipShellRenderer {
    private static final net.minecraft.client.renderer.MultiBufferSource.BufferSource FILLS = net.minecraft.client.renderer.MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 20));
    private static final Map<ShipDeviceBlockEntity, Ownership> OWNERS = new WeakHashMap<>();
    private static final Map<ShellMesh, List<ShipShellPanels.Panel>> CELLS = new WeakHashMap<>(), FILMS = new WeakHashMap<>();
    private static final Map<ShipDeviceBlockEntity, Flight> FLIGHTS = new WeakHashMap<>();
    private record Flight(ShipShieldView before, ShipShieldView after, double receivedAt) { }
    private static Object lastLevel;
    private record Ownership(List<ShellMesh> meshes, List<Float> seats, List<Boolean> held, List<int[]> owners) { }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (lastLevel != mc.level) { OWNERS.clear(); CELLS.clear(); FILMS.clear(); FLIGHTS.clear(); lastLevel = mc.level; }
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = mc.level.getGameTime() + partial;
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        for (ShipDeviceBlockEntity generator : new ArrayList<>(ShipDeviceBlockEntity.CLIENT_LOADED)) {
            if (generator.isRemoved() || generator.getLevel() != mc.level) {
                ShipDeviceBlockEntity.CLIENT_LOADED.remove(generator); OWNERS.remove(generator); FLIGHTS.remove(generator); continue;
            }
            ShipFrame frame = ShipFrame.drawn(generator, partial);
            if (frame.centre().distanceToSqr(camera) > 192 * 192) continue;
            ShipShieldView view = generator.view();
            Flight flight = FLIGHTS.get(generator);
            if (flight == null || flight.after() != view) {
                flight = new Flight(flight == null ? view : flight.after(), view, time);
                FLIGHTS.put(generator, flight);
            }
            List<ShellMesh> meshes = generator.clientLayers();
            if (meshes.isEmpty() || view.seatCount() == 0) continue;
            boolean liveHit = view.impacts().stream().anyMatch(hit -> time >= hit.time() && time - hit.time() < ShipShieldImpact.LIFETIME);
            VertexConsumer glow = ShieldGlow.consumer();
            if (view.active() || liveHit) {
                Ownership owners = ownership(generator, meshes, view);
                VertexConsumer fill = FILLS.getBuffer(ShieldVisualRenderer.SHIELD_RENDER_TYPE);
                for (int layer = 0; layer < meshes.size(); layer++) shell(fill, glow, matrix, meshes.get(layer), owners.owners().get(layer), view, frame, camera, generator.family(), time, layer);
                ShieldRefraction.flush(matrix);
                FILLS.endBatch(ShieldVisualRenderer.SHIELD_RENDER_TYPE);
                if (generator.family() == ShipFamily.TWINS && view.active()) traces(glow, matrix, meshes.getLast(), view, frame, camera, time);
            }
            drones(glow, matrix, view, frame, camera, generator.family(), time, flight);
            ShieldGlow.flush();
        }
    }

    private static Ownership ownership(ShipDeviceBlockEntity generator, List<ShellMesh> meshes, ShipShieldView view) {
        List<Boolean> heldList = new ArrayList<>();
        for (int seat = 0; seat < view.seatCount(); seat++) heldList.add(view.held(seat));
        Ownership old = OWNERS.get(generator);
        if (old != null && old.meshes().equals(meshes) && old.seats().equals(view.seats()) && old.held().equals(heldList)) return old;
        Vec3[] seats = new Vec3[view.seatCount()];
        boolean[] held = new boolean[seats.length];
        boolean any = false;
        for (int seat = 0; seat < seats.length; seat++) { seats[seat] = view.seat(seat); held[seat] = view.held(seat); any |= held[seat]; }
        // Keep the last facets attributable while overloaded emitters fly home.
        if (!any) Arrays.fill(held, true);
        Ownership result = new Ownership(meshes, view.seats(), heldList, meshes.stream().map(mesh -> {
            ShellPatches patches = ShellPatches.of(mesh, seats, held);
            return panels(mesh, generator.family()).stream().mapToInt(panel -> patches.seatAt(panel.centre())).toArray();
        }).toList());
        OWNERS.put(generator, result);
        return result;
    }

    private static ShieldShellVisual.Palette palette(ShipFamily family) {
        return switch (family) { case RF -> ShieldShellVisual.RF; case MANA -> ShieldShellVisual.MANA; case TWINS -> ShieldShellVisual.TWINS_CELLS; };
    }

    private static List<ShipShellPanels.Panel> panels(ShellMesh mesh, ShipFamily family) {
        return (family == ShipFamily.MANA ? FILMS : CELLS).computeIfAbsent(mesh, m -> ShipShellPanels.build(m, family != ShipFamily.MANA));
    }

    private static void shell(VertexConsumer fill, VertexConsumer glow, Matrix4f matrix, ShellMesh mesh, int[] owners,
            ShipShieldView view, ShipFrame frame, Vec3 camera, ShipFamily family, double time, int layer) {
        var palette = palette(family);
        Vec3 origin = Vec3.atLowerCornerOf(view.origin());
        boolean inside = mesh.field().inside(frame.toPlot(camera));
        var panels = panels(mesh, family);
        for (int quad = 0; quad < panels.size(); quad++) {
            var panel = panels.get(quad);
            int owner = owners[quad];
            if (owner < 0) continue;
            Vec3 centre = panel.centre(), relative = centre.subtract(origin);
            double hp = view.integrity(layer, owner) / (double) view.patchMax();
            double activity = view.active() ? activation(relative.length(), time - view.raisedAt(), view.raisedAt() >= 0) : 0;
            double wave = 0, breakingAge = -1;
            for (ShipShieldImpact hit : view.impacts()) {
                double age = time - hit.time();
                if (age < 0 || age >= ShipShieldImpact.LIFETIME) continue;
                double distance = relative.distanceTo(hit.position());
                double crest = ShieldRipple.profileDistance(distance * .24, age);
                wave += crest * ShieldRipple.strength(hit.absorbed());
                activity = Math.max(activity, Math.exp(-distance * .3 - age * .085) + Math.abs(crest) * .8);
                if ((hit.brokenLayers() & 1 << layer) != 0 && hp == 0 && (hit.overload() || distance < 8)) breakingAge = age;
            }
            boolean fragment = hp <= 0 && breakingAge >= 0 && breakingAge < 26;
            if (hp <= 0 && !fragment || activity < .008 && !fragment) continue;
            if (fragment) activity = (1 - breakingAge / 26) * .7;
            activity = Math.min(1, activity);
            Vec3 normal = panel.normal(), worldNormal = frame.turn(normal);
            double facing = worldNormal.dot(camera.subtract(frame.toWorld(centre)).normalize());
            double fresnel = inside ? .25 : Math.pow(1 - Math.abs(facing), 2.2);
            Vec3 eye = inside ? Vec3.ZERO : camera.subtract(frame.toWorld(centre)).normalize();
            double visibility = ShieldSurfaceLighting.visibility(worldNormal.x, worldNormal.y, worldNormal.z, eye);
            int health = (int) Math.round(hp * dev.hurtify.relicsaddon.shield.ShieldStackState.CELL_MAX);
            int healthy = family == ShipFamily.RF ? ShieldCellVisual.warm(palette.edge(), health) : ShieldCellVisual.dim(palette.edge(), health);
            Vec3 displacement = normal.scale(family == ShipFamily.RF ? .025 : Math.clamp(wave, -1, 1) * .2 * (family == ShipFamily.TWINS ? .4 : 1));
            double shrink = family == ShipFamily.MANA ? 1 : .985;
            if (fragment) { displacement = normal.scale(breakingAge * .075).add(0, -.005 * breakingAge * breakingAge, 0); shrink = Math.max(.04, 1 - breakingAge / 27); }
            int count = panel.corners().size();
            Vec3[] local = new Vec3[count], world = new Vec3[count];
            double[] heights = new double[count];
            for (int corner = 0; corner < count; corner++) {
                Vec3 point = panel.corners().get(corner);
                Vec3 lift = displacement;
                if (!fragment && family != ShipFamily.RF) {
                    double height = 0;
                    for (ShipShieldImpact hit : view.impacts()) height += ShieldRipple.profileDistance(point.subtract(origin).distanceTo(hit.position()) * .24, time - hit.time()) * ShieldRipple.strength(hit.absorbed());
                    heights[corner] = Math.clamp(height, -1, 1) * (family == ShipFamily.TWINS ? .35 : 1);
                    if (family == ShipFamily.MANA) lift = mesh.field().outward(point).scale(Math.clamp(height, -1, 1) * .2);
                }
                local[corner] = centre.lerp(point, shrink).add(lift);
                world[corner] = frame.toWorld(local[corner]).subtract(camera);
            }
            if (!fragment && family != ShipFamily.RF) ShieldRefraction.queueSurface(world, heights, activity, palette.bright());
            Vec3 hub = frame.toWorld(centre.add(displacement)).subtract(camera);
            double flare = Math.min(1, Math.abs(wave));
            if (family != ShipFamily.RF) {
                var glass = family == ShipFamily.MANA ? ShieldShellVisual.MANA : ShieldShellVisual.TWINS_DOME;
                int glassColor = ShieldShellVisual.mix(ShieldShellVisual.mix(glass.deep(), glass.bright(), Math.min(1, fresnel * .85 + Math.max(0, wave) * .35)), 0xFFFFFF, flare * .55);
                int alpha = (int) Math.clamp((activity * (9 + 80 * fresnel) + flare * 95 + Math.abs(wave) * 45 * activity) * visibility, 0, 185);
                for (int k = 0; k < count; k++) triangle(fill, matrix, hub, world[k], world[(k + 1) % count], glassColor, alpha);
            }
            if (family != ShipFamily.MANA) {
                Vec3 sun = new Vec3(Math.cos(time * .011), .62, Math.sin(time * .011)).normalize();
                double sheen = ShieldShellVisual.sheen(worldNormal.x, worldNormal.y, worldNormal.z, sun.x, sun.y, sun.z, eye.x, eye.y, eye.z);
                double base = activity * (1 + .6 * fresnel) + flare * .9;
                int tint = ShieldShellVisual.mix(palette.fill(), healthy, .35);
                int hubColor = ShieldShellVisual.mix(tint, 0xFFFFFF, sheen * .7);
                int rimColor = ShieldShellVisual.mix(ShieldShellVisual.mix(tint, healthy, .3), 0xFFFFFF, sheen * .7);
                int hubAlpha = (int) Math.clamp(ShieldShellVisual.paneAlpha(base, sheen, activity, visibility, false), 0, 255);
                int rimAlpha = (int) Math.clamp(ShieldShellVisual.paneAlpha(base, sheen, activity, visibility, true), 0, 255);
                for (int k = 0; k < count; k++) {
                    vertex(fill, matrix, hub, hubColor, hubAlpha);
                    vertex(fill, matrix, world[k], rimColor, rimAlpha);
                    vertex(fill, matrix, world[(k + 1) % count], rimColor, rimAlpha);
                }
            }
            activity *= visibility;
            if (family != ShipFamily.MANA || fragment) for (int corner = 0; corner < count; corner++) {
                strip(glow, matrix, world[corner], world[(corner + 1) % count], worldNormal, .018, palette.edge(), (int) (activity * 185));
                strip(glow, matrix, world[corner], world[(corner + 1) % count], worldNormal, .055, palette.edge(), (int) (activity * 30));
            }
            if (quad % 16 == 0 && activity > .08) {
                Vec3 at = frame.toWorld(centre.add(displacement));
                EffectLights.glow(at, Math.min(12, activity * 14), 2);
                if (family != ShipFamily.RF) octahedron(glow, matrix, at.subtract(camera), frame, .025, palette.node(), (int) (activity * 170));
            }
        }
    }

    static double activation(double distance, double age, boolean raised) {
        if (!raised || age < 0 || age > 65) return 0;
        double since = age - distance / .8;
        return since < 0 || since > 18 ? 0 : Math.sin(Math.PI * since / 18) * (1 - age / 75);
    }

    private static void traces(VertexConsumer glow, Matrix4f matrix, ShellMesh mesh, ShipShieldView view, ShipFrame frame, Vec3 camera, double time) {
        Vec3 origin = Vec3.atLowerCornerOf(view.origin());
        boolean inside = mesh.field().inside(frame.toPlot(camera));
        for (ShipShieldImpact hit : view.impacts()) {
            if (time < hit.time() || time - hit.time() >= ShipShieldImpact.LIFETIME) continue;
            Vec3 point = origin.add(hit.position()), n = mesh.field().outward(point);
            Vec3 t1 = n.cross(Math.abs(n.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), t2 = n.cross(t1);
            var impact = new dev.hurtify.relicsaddon.shield.ShieldImpact(n, hit.time(), 0, hit.absorbed(), false);
            ShieldCircuitTraces.renderOnSurface(glow, glow, matrix, impact, time, inside, (u, v) -> {
                Vec3 at = point.add(t1.scale(u * 4)).add(t2.scale(v * 4));
                for (int iteration = 0; iteration < 4; iteration++) at = at.add(mesh.field().outward(at).scale(mesh.offset() + .035 - mesh.field().distance(at)));
                return frame.toWorld(at).subtract(camera);
            });
        }
    }

    private static void drones(VertexConsumer glow, Matrix4f matrix, ShipShieldView view, ShipFrame frame, Vec3 camera, ShipFamily family, double time, Flight flight) {
        var p = palette(family);
        for (int drone = 0; drone < view.droneCount(); drone++) {
            var state = view.state(drone);
            if (state == EmitterDrone.State.DOCKED || state == EmitterDrone.State.CHARGING) continue;
            Vec3 local = view.drone(drone);
            if (drone < flight.before().droneCount()) local = flight.before().drone(drone).lerp(local, Math.clamp((time - flight.receivedAt()) / 10, 0, 1));
            Vec3 at = frame.toWorld(local);
            int color = state == EmitterDrone.State.RETURNING ? 0xFF6B43 : p.node();
            octahedron(glow, matrix, at.subtract(camera), frame, .11, color, 230);
            EffectLights.glow(at, 7, 1.3);
            Vec3 previous = null;
            for (int point = 0; point <= 12; point++) {
                double angle = point * Math.PI / 6 + time * .025;
                Vec3 next = frame.toWorld(local.add(Math.cos(angle) * .22, 0, Math.sin(angle) * .22)).subtract(camera);
                if (previous != null) strip(glow, matrix, previous, next, frame.turn(new Vec3(0, 1, 0)), .018, p.edge(), 130);
                previous = next;
            }
        }
    }

    /** Surface tangent strips, never camera-facing billboards. */
    private static void strip(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 normal, double width, int color, int alpha) {
        Vec3 side = b.subtract(a).cross(normal).normalize().scale(width);
        triangle(out, matrix, a.add(side), b.add(side), b.subtract(side), color, alpha);
        triangle(out, matrix, a.add(side), b.subtract(side), a.subtract(side), color, alpha);
    }
    private static void octahedron(VertexConsumer out, Matrix4f matrix, Vec3 at, ShipFrame frame, double size, int color, int alpha) {
        Vec3 x = frame.turn(new Vec3(size, 0, 0)), y = frame.turn(new Vec3(0, size, 0)), z = frame.turn(new Vec3(0, 0, size));
        Vec3[] ring = {at.add(x), at.add(z), at.subtract(x), at.subtract(z)};
        for (int i = 0; i < 4; i++) {
            triangle(out, matrix, at.add(y), ring[i], ring[(i + 1) % 4], color, alpha);
            triangle(out, matrix, at.subtract(y), ring[(i + 1) % 4], ring[i], color, alpha);
        }
    }
    private static void triangle(VertexConsumer out, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, int color, int alpha) {
        for (Vec3 at : new Vec3[]{a, b, c}) vertex(out, matrix, at, color, alpha);
    }
    private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 at, int color, int alpha) {
        out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha);
    }
    private ShipShellRenderer() { }
}
