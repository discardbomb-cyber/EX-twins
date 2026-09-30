package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Sounds that last, played by the client from what it already draws, so the server sends nothing for them:
 * each construct's hum while it holds its creature (louder with more drones in it), and the rush of a strike
 * group's figure in flight, following it and bending its pitch as it comes and goes. Only the few nearest
 * figures are heard, so a swarm never fills the sound channels.
 */
public final class HiveLoopSounds {
    /** Figures in flight heard at once, the nearest first. */
    private static final int FLIGHTS = 4;
    /** Ticks without news after which a loop stops. */
    private static final double STALE = 3;

    /** The latest news of each loop: where its source is, when it was seen, how loud. */
    private record News(Vec3 at, double time, float volume) { }
    private static final Map<Long, News> NEWS = new HashMap<>();
    private static final Map<Long, Loop> PLAYING = new HashMap<>();

    /** A construct holding {@code key}'s creature at {@code at} this frame, with {@code drones} drones. */
    static void hum(long key, HiveType type, Vec3 at, int drones, double time) {
        float volume = (float) Math.min(1, .35 + drones / 120.0);
        report(key | 1L << 62, type, at, time, volume, true);
    }

    /** A strike group's figure {@code key} flying at {@code at} this frame. */
    static void flying(long key, HiveType type, Vec3 at, double time) {
        if (HiveJuice.detail() == HiveJuice.Detail.LOW) return;
        report(key & ~(1L << 62), type, at, time, .55F, false);
    }

    private static void report(long key, HiveType type, Vec3 at, double time, float volume, boolean hum) {
        NEWS.put(key, new News(at, time, volume));
        if (PLAYING.containsKey(key)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (!hum) {
            // Only the nearest few figures: another starts only if it is nearer than the furthest playing.
            if (at.distanceTo(minecraft.player.position()) > 48) return;
            int flights = 0;
            long furthestKey = 0;
            double furthest = -1;
            for (var entry : PLAYING.entrySet()) {
                if (entry.getValue().hum) continue;
                flights++;
                double distance = distance(entry.getKey());
                if (distance > furthest) { furthest = distance; furthestKey = entry.getKey(); }
            }
            if (flights >= FLIGHTS) {
                if (minecraft.player.position().distanceTo(at) >= furthest) return;
                Loop furthestLoop = PLAYING.remove(furthestKey);
                if (furthestLoop != null) furthestLoop.end();
            }
        }
        SoundEvent event = (hum ? switch (type) {
            case RF -> RelicSounds.RF_CAGE_HUM;
            case MANA -> RelicSounds.MANA_LOTUS_HUM;
            case TWINS -> RelicSounds.TWINS_RIFT_HUM;
        } : switch (type) {
            case RF -> RelicSounds.RF_FLIGHT;
            case MANA -> RelicSounds.MANA_FLIGHT;
            case TWINS -> RelicSounds.TWINS_FLIGHT;
        }).get();
        Loop loop = new Loop(event, key, hum);
        PLAYING.put(key, loop);
        minecraft.getSoundManager().play(loop);
    }

    private static double distance(long key) {
        News news = NEWS.get(key);
        Minecraft minecraft = Minecraft.getInstance();
        return news == null || minecraft.player == null ? Double.MAX_VALUE : news.at.distanceTo(minecraft.player.position());
    }

    public static void clear() {
        for (Loop loop : PLAYING.values()) loop.end();
        PLAYING.clear();
        NEWS.clear();
    }

    /** A loop following its news until the news stops. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final long key;
        private final boolean hum;
        private double lastDistance = -1;

        Loop(SoundEvent event, long key, boolean hum) {
            super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.key = key;
            this.hum = hum;
            this.looping = true;
            this.delay = 0;
            this.volume = .01F;
            this.relative = false;
            News news = NEWS.get(key);
            if (news != null) move(news.at);
        }

        private void move(Vec3 at) {
            x = at.x;
            y = at.y;
            z = at.z;
        }

        void end() {
            stop();
        }

        @Override public void tick() {
            Minecraft minecraft = Minecraft.getInstance();
            News news = NEWS.get(key);
            double now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
            if (news == null || minecraft.player == null || now - news.time > STALE || now < news.time - 1) {
                PLAYING.remove(key);
                NEWS.remove(key);
                stop();
                return;
            }
            move(news.at);
            // Fades in over a few ticks, then holds at the news's loudness.
            volume = Math.min(news.volume, volume + news.volume * .25F);
            if (!hum) {
                // Rising as it comes on, falling as it goes away.
                double distance = news.at.distanceTo(minecraft.player.position());
                double closing = lastDistance < 0 ? 0 : lastDistance - distance;
                lastDistance = distance;
                pitch = (float) Math.clamp(1 + closing * .12, .7, 1.4);
            }
        }
    }

    private HiveLoopSounds() { }
}
