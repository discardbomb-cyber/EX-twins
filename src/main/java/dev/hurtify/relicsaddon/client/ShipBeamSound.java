package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.ship.LanceModule;
import dev.hurtify.relicsaddon.ship.LanceShape;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** A lance's beam, heard for as long as it burns: it swells in, follows the turret with its ship and fades out. */
final class ShipBeamSound extends AbstractTickableSoundInstance {
    private static final float SWELL = .25F, FADE = .2F;
    private final ShipHiveBlockEntity hive;

    ShipBeamSound(ShipHiveBlockEntity hive) {
        super(RelicSounds.SHIP_LANCE_BEAM.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.hive = hive;
        looping = true;
        delay = 0;
        volume = .05F;
        follow();
    }

    @Override
    public void tick() {
        boolean burning = !hive.isRemoved() && hive.module() instanceof LanceModule lance && lance.firing();
        volume = burning ? Math.min(1, volume + SWELL) : volume - FADE;
        if (volume <= 0) {
            stop();
            return;
        }
        follow();
    }

    /** The sound stands at the turret's middle, wherever the ship has taken it. */
    private void follow() {
        ShipFrame frame = ShipFrame.drawn(hive, 0);
        Vec3 at = LanceShape.mount(frame.centre(), frame.turn(hive.normal()).normalize());
        x = at.x;
        y = at.y;
        z = at.z;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
