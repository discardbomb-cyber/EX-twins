package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.Armageddon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** The ground shakes under everyone near an Armageddon blast as the ball of light sweeps over them, and rumbles while it and the eruption last. */
public final class ArmageddonShake {
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        double time = minecraft.level.getGameTime() + event.getPartialTick();
        Vec3 camera = event.getCamera().getPosition();
        // A swarm's blows close by nudge the camera too, lightly.
        double shake = HiveJuice.shake(camera, time);
        for (ArmageddonVisual.Blast blast : ArmageddonVisual.BLASTS) {
            double t = time - blast.impactAt(), distance = camera.distanceTo(blast.centre());
            if (t < 0 || distance > Armageddon.RADIUS * 1.3) continue;
            double reaches = Armageddon.reaches(Math.min(distance, Armageddon.RADIUS));
            double kick = t >= reaches ? Math.exp(-(t - reaches) / 20) : 0, rumble = t > Armageddon.BALL && t < Armageddon.CRUSHED ? .3 : t > Armageddon.ERUPT && t < Armageddon.NARROW ? .2 : 0;
            shake = Math.max(shake, (2.2 * kick + rumble) * (1 - distance / (Armageddon.RADIUS * 1.3)));
        }
        shake = Math.max(shake, ManaArmageddonVisual.shake(camera, time));
        shake = Math.max(shake, RfArmageddonVisual.shake(camera, time));
        if (shake < .01) return;
        event.setRoll((float) (event.getRoll() + shake * Math.sin(time * 1.9)));
        event.setPitch((float) (event.getPitch() + shake * .7 * Math.sin(time * 2.7 + 1)));
        event.setYaw((float) (event.getYaw() + shake * .5 * Math.sin(time * 2.3 + 2)));
    }

    private ArmageddonShake() {
    }
}
