package dev.hurtify.relicsaddon.sound;

import dev.hurtify.relicsaddon.drone.ManaArmageddon;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mana Armageddon's sounds score its stages, so each must last exactly as long as its stage: above all the
 * blast, as long as {@link ManaArmageddon#BLAST_SECONDS}, the one number its column of light grows by. Reads
 * each Ogg Vorbis file's length from its last page (the samples encoded) and its first (the sample rate).
 */
public final class ArmageddonSoundCheck {
    private static final Path SOUNDS = Path.of("src/main/resources/assets/relics_addon/sounds/combat");

    public static void main(String[] args) throws IOException {
        require("hive_mana_armageddon_charge", ManaArmageddon.FIRE / 20.0, "the flowers' charge lasts until the streams leave");
        require("hive_mana_armageddon_collision", (ManaArmageddon.IGNITE - ManaArmageddon.ARRIVE) / 20.0, "the collision lasts until the sun ignites");
        require("hive_mana_armageddon_sphere", (ManaArmageddon.IMPACT - ManaArmageddon.IGNITE) / 20.0, "the sphere cracks until it shatters");
        require("hive_mana_armageddon_blast", ManaArmageddon.BLAST_SECONDS, "the blast is heard exactly as long as the column of light grows");
        if (Math.abs(ManaArmageddon.BLAST - ManaArmageddon.BLAST_SECONDS * 20) > .5) throw new AssertionError("the blast's ticks are made from its seconds");
        System.out.println("Armageddon sounds: Mana's charge, collision, sphere and " + ManaArmageddon.BLAST_SECONDS + " s blast last exactly as long as their stages");
    }

    private static void require(String name, double seconds, String what) throws IOException {
        double length = seconds(Files.readAllBytes(SOUNDS.resolve(name + ".ogg")));
        if (Math.abs(length - seconds) > .05) throw new AssertionError(what + ": " + name + " lasts " + length + " s, not " + seconds + " s");
    }

    /** An Ogg Vorbis stream's length: the granule position of its last page over the rate in its identification header. */
    static double seconds(byte[] ogg) {
        ByteBuffer bytes = ByteBuffer.wrap(ogg).order(ByteOrder.LITTLE_ENDIAN);
        int rate = -1;
        long samples = -1;
        for (int at = 0; at + 27 <= ogg.length; ) {
            if (ogg[at] != 'O' || ogg[at + 1] != 'g' || ogg[at + 2] != 'g' || ogg[at + 3] != 'S') throw new AssertionError("not an Ogg page at " + at);
            long granule = bytes.getLong(at + 6);
            int segments = ogg[at + 26] & 255, body = 0;
            for (int segment = 0; segment < segments; segment++) body += ogg[at + 27 + segment] & 255;
            int start = at + 27 + segments;
            // The identification header: packet type 1, "vorbis", version, channels, then the sample rate.
            if (rate < 0 && body >= 16 && ogg[start] == 1 && ogg[start + 1] == 'v') rate = bytes.getInt(start + 12);
            if (granule >= 0) samples = granule;
            at = start + body;
        }
        if (rate <= 0 || samples < 0) throw new AssertionError("no Vorbis length found");
        return samples / (double) rate;
    }

    private ArmageddonSoundCheck() {
    }
}
