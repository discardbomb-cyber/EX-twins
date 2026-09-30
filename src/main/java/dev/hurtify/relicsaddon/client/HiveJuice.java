package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.drone.HiveType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * What a swarm's blows leave in the world for a moment, all of it geometry in the world rather than pictures
 * turned to the camera: a white-hot core that turns to the family's colour, a shock ring through the air and
 * another over the ground, a spray of many small sparks along the blow (RF throws drops of metal that fall),
 * and what lingers half a second to a second after (a scorched ring, ripples as on water, a dark smoke).
 * A newer blow of the same strike group cuts the older one's tail short. Near blows nudge the camera.
 * The prompt's aim circles, laid on the ground under a target, are drawn here too.
 */
public final class HiveJuice {
    /** How much of this is drawn: {@code low} is about the swarm before, {@code high} more sparks and longer tails. */
    public enum Detail {
        LOW, NORMAL, HIGH;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One blow: where, along which way, whose, when and how hard; {@code key} names its strike group. */
    private record Impact(Vec3 at, Vec3 normal, HiveType type, int style, double start, double strength, long key, double ground) { }

    /** The kinds of blow, which set how it bursts. */
    public static final int STRIKE = 0, CHARGE = 1, ZAP = 2, SPARK = 3, GROUNDED = 4, REFLECTED = 5, PUFF = 6;
    private static final List<Impact> IMPACTS = new ArrayList<>();
    private static final int MAX_IMPACTS = 48;
    /** Ticks a blow lasts in all, its core flash, and its shock ring. */
    private static final double LIFE = 24, FLASH = 2, RING = 9;

    public static Detail detail() {
        return AddonClientConfig.hiveEffects();
    }

    /**
     * A blow lands: {@code normal} is the way it came, {@code strength} about 1 for a strike group's blow. A
     * previous blow of the same {@code key} (strike group) is cut short.
     */
    public static void impact(Vec3 at, Vec3 normal, HiveType type, int style, double time, double strength, long key) {
        if (detail() == Detail.LOW && style == SPARK) return;
        IMPACTS.removeIf(old -> old.key == key);
        if (IMPACTS.size() >= MAX_IMPACTS) IMPACTS.removeFirst();
        // A turned-back blow keeps its whole way, to the creature it falls on.
        Vec3 way = style == REFLECTED ? normal : normal.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : normal.normalize();
        IMPACTS.add(new Impact(at, way, type, style, time, strength, key, groundBelow(at)));
    }

    /** The height of the ground up to five blocks below {@code at}, or NaN if there is none. */
    private static double groundBelow(Vec3 at) {
        var level = Minecraft.getInstance().level;
        if (level == null) return Double.NaN;
        var hit = level.clip(new ClipContext(at, at.subtract(0, 5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? Double.NaN : hit.getLocation().y + .03;
    }

    public static void clear() {
        IMPACTS.clear();
    }

    public static void render(Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, double time) {
        Detail detail = detail();
        for (Iterator<Impact> iterator = IMPACTS.iterator(); iterator.hasNext(); ) {
            Impact impact = iterator.next();
            double age = time - impact.start;
            double life = LIFE * (detail == Detail.HIGH ? 1.4 : detail == Detail.LOW ? .5 : 1);
            if (age > life || age < -1) {
                iterator.remove();
                continue;
            }
            if (age < 0) continue;
            burst(impact, age, life, camera, glow, fill, m, detail, impact.at.distanceToSqr(camera) < FAR * FAR);
        }
    }

    /** Beyond this many blocks a blow keeps its flash and rings but not its sparks or what lingers after it. */
    private static final double FAR = 40;

    private static void burst(Impact hit, double age, double life, Vec3 camera, VertexConsumer glow, VertexConsumer fill, Matrix4f m, Detail detail, boolean near) {
        int color = HiveModeVisual.color(hit.type), hot = GlowBrush.mix(color, 0xFFFFFF, .6);
        Vec3 at = hit.at.subtract(camera);
        double s = hit.strength;
        if (hit.style == PUFF) {
            // A puff of dark violet smoke rolling up and thinning.
            double fade = Math.sin(Math.PI * Math.min(1, age / life));
            for (int puff = 0; puff < 4; puff++) {
                double swirl = age * .07 + puff * 1.6, rise = age * (.035 + .02 * hash(hit.key, puff));
                Vec3 centre = at.add(Math.cos(swirl) * .3 * s, rise + puff * .12, Math.sin(swirl) * .3 * s);
                GlowBrush.sphere(fill, m, centre, (.18 + .02 * age) * s, 0x0A0612, 0x3A0F66, 80 * fade, 25 * fade, 6);
            }
            return;
        }
        // The core: white for a frame or two, then the family's colour, gone within a few ticks.
        if (age < FLASH) GlowBrush.dot(glow, m, at, .45 * s, 0xFFFFFF, 255 * (1 - age / FLASH));
        if (age < 6) GlowBrush.dot(glow, m, at, (.3 + .25 * age / 6) * s, age < FLASH ? hot : color, 200 * (1 - age / 6));
        // The shock ring, across the blow in the air.
        if (age < RING && hit.style != SPARK && hit.style != GROUNDED && hit.style != REFLECTED) {
            double t = age / RING, radius = s * (.3 + 2.6 * (1 - (1 - t) * (1 - t)));
            Vec3[] axes = dev.hurtify.relicsaddon.drone.HiveShapes.axes(hit.normal);
            GlowBrush.circle(glow, m, at, axes[1], axes[2], radius, 48, .03 + .05 * (1 - t), hot, 210 * (1 - t));
        }
        // And over the ground under it.
        boolean grounded = !Double.isNaN(hit.ground) && hit.at.y - hit.ground < 4;
        if (grounded && hit.style != SPARK && hit.style != GROUNDED && hit.style != REFLECTED) {
            Vec3 floor = new Vec3(hit.at.x, hit.ground, hit.at.z).subtract(camera);
            double t = Math.min(1, age / (RING * 1.4)), radius = s * (.5 + 3.4 * (1 - (1 - t) * (1 - t)));
            GlowBrush.circle(glow, m, floor, new Vec3(1, 0, 0), new Vec3(0, 0, 1), radius, 40, .04 * (1 - t) + .01, color, 170 * (1 - t));
            if (near) linger(hit, age, life, floor, glow, fill, m, color);
        }
        if (near) sparks(hit, age, at, glow, m, detail, color, hot);
        if (hit.style == CHARGE) shot(hit, age, at, glow, fill, m, hot);
        if (hit.style == GROUNDED && age < 7 && grounded) {
            // A shot the cage caught runs off it into the ground as branching lightning.
            Vec3 floor = new Vec3(hit.at.x, hit.ground, hit.at.z).subtract(camera);
            long flicker = (long) Math.floor(age / 1.5);
            GlowBrush.lightning(glow, m, at, floor, hit.key * 7 + flicker, 8, .18, .012, 0xBFF6FF, 240 * (1 - age / 7));
            GlowBrush.lightning(glow, m, at.lerp(floor, .5), floor.add(.6, 0, .4), hit.key * 11 + flicker, 4, .3, .008, 0xFFB347, 180 * (1 - age / 7));
        }
        if (hit.style == REFLECTED && age < 10) {
            // A blow the lotus turned back: golden sparks arcing from the petal onto the creature that struck.
            Vec3 onto = hit.at.add(hit.normal).subtract(camera);
            for (int mote = 0; mote < 8; mote++) {
                double t = Math.clamp(age / 8 - mote * .05, 0, 1);
                Vec3 point = at.lerp(onto, t).add(0, Math.sin(Math.PI * t) * .8, 0);
                GlowBrush.dot(glow, m, point, .07, 0xFFD27A, 230 * (1 - t));
            }
        }
    }

    /**
     * A barrage shot breaking on its target: the RF ring bursts in branching discharges every way, the Twins
     * icosahedron flies apart in shards of violet glass.
     */
    private static void shot(Impact hit, double age, Vec3 at, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int hot) {
        if (hit.type == HiveType.RF && age < 6) {
            double fade = 1 - age / 6, reach = .6 + .9 * Math.min(1, age / 2);
            for (int bolt = 0; bolt < 9; bolt++) {
                double a = hash(hit.key, bolt + 70) * Math.PI * 2, b = Math.acos(hash(hit.key, bolt + 80) * 2 - 1);
                Vec3 out = new Vec3(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a));
                GlowBrush.lightning(glow, m, at, at.add(out.scale(reach)), hit.key * 17 + bolt + (long) age, 5, .3, .008, hot, 220 * fade);
            }
        } else if (hit.type == HiveType.TWINS && age < 14) {
            double fade = 1 - age / 14;
            int[][] faces = HiveProjectiles.icosahedronFaces();
            for (int shard = 0; shard < faces.length; shard++) {
                Vec3 a = HiveProjectiles.icosahedronCorner(faces[shard][0], HiveProjectiles.RADIUS, 0);
                Vec3 b = HiveProjectiles.icosahedronCorner(faces[shard][1], HiveProjectiles.RADIUS, 0);
                Vec3 d = HiveProjectiles.icosahedronCorner(faces[shard][2], HiveProjectiles.RADIUS, 0);
                Vec3 middle = a.add(b).add(d).scale(1 / 3.0), out = middle.normalize();
                Vec3 flown = out.scale(age * (.12 + .1 * hash(hit.key, shard + 90))).subtract(0, .004 * age * age, 0);
                Vec3 axis = new Vec3(hash(hit.key, shard) - .5, hash(hit.key, shard + 1) - .5, hash(hit.key, shard + 2) - .5);
                axis = axis.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : axis.normalize();
                double spin = age * .5;
                Vec3 pa = at.add(middle).add(flown).add(dev.hurtify.relicsaddon.drone.HiveShapes.rotate(a.subtract(middle), axis, spin));
                Vec3 pb = at.add(middle).add(flown).add(dev.hurtify.relicsaddon.drone.HiveShapes.rotate(b.subtract(middle), axis, spin));
                Vec3 pd = at.add(middle).add(flown).add(dev.hurtify.relicsaddon.drone.HiveShapes.rotate(d.subtract(middle), axis, spin));
                GlowBrush.quad(fill, m, pa, pb, pd, pd, 0x3A0F66, 0x3A0F66, 0x3A0F66, 0x3A0F66, 150 * fade, 150 * fade, 150 * fade, 150 * fade);
                GlowBrush.line(glow, m, pa, pb, .006, 0xE7C6FF, 220 * fade);
                GlowBrush.line(glow, m, pb, pd, .006, 0xE7C6FF, 220 * fade);
                GlowBrush.line(glow, m, pd, pa, .006, 0xE7C6FF, 220 * fade);
            }
        }
    }

    /** What lingers on the ground after the ring has passed: scorched on RF, ripples on Mana, smoke and cracks on Twins. */
    private static void linger(Impact hit, double age, double life, Vec3 floor, VertexConsumer glow, VertexConsumer fill, Matrix4f m, int color) {
        double fade = Math.clamp(1 - (age - 4) / (life - 4), 0, 1) * Math.min(1, age / 4);
        if (fade <= 0) return;
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        double s = hit.strength;
        switch (hit.type) {
            case RF -> {
                GlowBrush.circle(glow, m, floor, x, z, .9 * s, 40, .05, 0xFF8A3D, 150 * fade);
                GlowBrush.circle(glow, m, floor, x, z, .55 * s, 32, .03, color, 110 * fade);
            }
            case MANA -> {
                // Ripples as on water: rings running out one after another, each fainter.
                for (int ring = 0; ring < 3; ring++) {
                    double t = (age - ring * 4) / 18;
                    if (t <= 0 || t >= 1) continue;
                    GlowBrush.circle(glow, m, floor, x, z, s * (.4 + 3.2 * t), 36, .012, GlowBrush.mix(color, 0xFFFFFF, .3), 150 * (1 - t) * fade);
                }
            }
            case TWINS -> {
                for (int crack = 0; crack < 6; crack++) {
                    double angle = crack * Math.PI / 3 + hash(hit.key, crack) * .8;
                    Vec3 end = floor.add(Math.cos(angle) * s * (1 + hash(hit.key, crack + 9)), 0, Math.sin(angle) * s * (1 + hash(hit.key, crack + 9)));
                    GlowBrush.lightning(glow, m, floor, end, hit.key * 13 + crack, 4, .15, .012, color, 160 * fade);
                }
                // A dark smoke curling up out of a strike group's blow (a barrage clump sheds its own now and then).
                for (int puff = 0; puff < (hit.style == STRIKE ? 3 : 0); puff++) {
                    double rise = age * (.05 + .03 * hash(hit.key, puff + 20)), swirl = age * .08 + puff * 1.3;
                    Vec3 centre = floor.add(Math.cos(swirl) * .4 * s, .3 + rise, Math.sin(swirl) * .4 * s);
                    GlowBrush.sphere(fill, m, centre, (.25 + .02 * age) * s, 0x0A0612, 0x2A0A45, 70 * fade, 20 * fade, 6);
                }
            }
        }
    }

    /** Many small sparks thrown along the blow, not a few big ones; RF also throws drops of metal that fall. */
    private static void sparks(Impact hit, double age, Vec3 at, VertexConsumer glow, Matrix4f m, Detail detail, int color, int hot) {
        int count = switch (detail) {
            case LOW -> 6;
            case NORMAL -> 26;
            case HIGH -> 60;
        };
        if (hit.style == SPARK || hit.style == ZAP) count /= 3;
        Vec3[] axes = dev.hurtify.relicsaddon.drone.HiveShapes.axes(hit.normal);
        for (int spark = 0; spark < count; spark++) {
            double life = 6 + 10 * hash(hit.key + (long) hit.start, spark);
            if (age > life) continue;
            boolean metal = hit.type == HiveType.RF && spark % 3 == 0;
            double spread = 1.1, a = hash(hit.key * 7 + (long) hit.start, spark) * Math.PI * 2, b = hash(hit.key * 11 + (long) hit.start, spark) * spread;
            Vec3 dir = axes[0].scale(-Math.cos(b)).add(axes[1].scale(Math.sin(b) * Math.cos(a))).add(axes[2].scale(Math.sin(b) * Math.sin(a)));
            // Thrown back out of the blow, and up a little.
            dir = dir.scale(-1).add(0, .35, 0);
            double speed = (.12 + .3 * hash(hit.key, spark + 50)) * hit.strength, gravity = metal ? .025 : .006;
            Vec3 now = at.add(dir.scale(speed * age)).subtract(0, gravity * age * age, 0);
            Vec3 before = at.add(dir.scale(speed * Math.max(0, age - 1.2))).subtract(0, gravity * Math.pow(Math.max(0, age - 1.2), 2), 0);
            double fade = 1 - age / life;
            GlowBrush.line(glow, m, before, now, metal ? .03 : .015, metal ? 0xFFB347 : spark % 2 == 0 ? hot : color, 230 * fade);
        }
    }

    /**
     * An aim circle on the ground under a target, as on a heads-up display: an outer ring, a broken inner
     * ring, ticks round it and small marks circling it, all drawn tight as {@code closing} runs from 0 to 1.
     */
    public static void aim(Vec3 feet, double size, double closing, double time, int color, int accent, Vec3 camera, VertexConsumer glow, Matrix4f m) {
        double ground = groundUnder(feet);
        Vec3 at = new Vec3(feet.x, ground, feet.z).subtract(camera);
        double radius = size * (1.6 - .9 * closing), alpha = 170 * Math.sin(Math.PI * Math.min(1, closing * 1.1 + .05));
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        GlowBrush.circle(glow, m, at, x, z, radius, 64, .02, color, alpha);
        double turn = time * .05;
        for (int dash = 0; dash < 12; dash++) {
            double a0 = turn + dash * Math.PI / 6, a1 = a0 + Math.PI / 9;
            Vec3 p0 = at.add(Math.cos(a0) * radius * .78, 0, Math.sin(a0) * radius * .78), p1 = at.add(Math.cos(a1) * radius * .78, 0, Math.sin(a1) * radius * .78);
            GlowBrush.line(glow, m, p0, p1, .025, accent, alpha);
        }
        for (int tick = 0; tick < 36; tick++) {
            double a = -turn * .5 + tick * Math.PI / 18, length = tick % 3 == 0 ? .16 : .08;
            Vec3 in = at.add(Math.cos(a) * radius, 0, Math.sin(a) * radius), out = at.add(Math.cos(a) * (radius + length * size), 0, Math.sin(a) * (radius + length * size));
            GlowBrush.line(glow, m, in, out, .012, color, alpha * .8);
        }
        for (int mark = 0; mark < 3; mark++) {
            double a = turn * 1.6 + mark * Math.PI * 2 / 3;
            Vec3 centre = at.add(Math.cos(a) * radius * 1.12, 0, Math.sin(a) * radius * 1.12);
            GlowBrush.circle(glow, m, centre, x, z.scale(.5), .09 * size, 12, .01, accent, alpha);
        }
    }

    /** The ground under each aimed-at block, found once a tick rather than every frame. */
    private static final java.util.Map<Long, double[]> GROUND = new java.util.HashMap<>();

    private static double groundUnder(Vec3 feet) {
        var level = Minecraft.getInstance().level;
        long tick = level == null ? 0 : level.getGameTime(), key = net.minecraft.core.BlockPos.containing(feet).asLong();
        double[] cached = GROUND.get(key);
        if (cached != null && cached[1] == tick) return cached[0];
        if (GROUND.size() > 256) GROUND.clear();
        double ground = groundBelow(feet.add(0, .5, 0));
        ground = Double.isNaN(ground) ? feet.y + .03 : ground;
        GROUND.put(key, new double[]{ground, tick});
        return ground;
    }

    /** How hard near blows nudge the camera at {@code camera}: within 16 blocks, harder for harder blows, fading at once. */
    public static double shake(Vec3 camera, double time) {
        double scale = AddonClientConfig.hiveShake();
        if (scale <= 0) return 0;
        double shake = 0;
        for (Impact hit : IMPACTS) {
            double age = time - hit.start, distance = camera.distanceTo(hit.at);
            if (age < 0 || age > 8 || distance > 16 || hit.style == SPARK || hit.style == PUFF) continue;
            shake = Math.max(shake, .45 * hit.strength * (1 - distance / 16) * Math.exp(-age / 2.5));
        }
        return shake * scale;
    }

    private static double hash(long seed, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private HiveJuice() { }
}
