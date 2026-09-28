package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public final class ManaDomeSurfaceCheck {
    public static void main(String[] args) {
        Vec3 forward = new Vec3(0, 0, 1), side = new Vec3(1, 0, 0);
        var one = ManaDomeSurface.caps(List.of(new ShieldResponse.Threat(forward, 1)), List.of(), 100);
        require(ManaDomeSurface.presence(forward, one) > 0, "dome must face the projectile");
        require(ManaDomeSurface.presence(forward.scale(-1), one) == 0, "rear half must stay open");
        require(ManaDomeSurface.rim(side, one) == 1, "hemisphere needs a continuous rim");
        require(ManaDomeSurface.rim(forward, one) == 0, "center is glass, not a rim");
        Vec3 a = new Vec3(.4, .8, .4).normalize(), b = new Vec3(-.4, .8, .4).normalize();
        Vec3 c = new Vec3(0, .8, -.4).normalize();
        for (var polygon : ManaDomeSurface.clip(a, b, c, one)) for (Vec3 p : polygon) {
            require(p.z >= -1e-7 && Math.abs(p.length() - 1) < 1e-7, "clipped edge left the hemisphere");
        }
        require(ManaDomeSurface.clip(a.scale(-1), b.scale(-1), new Vec3(0, -1, -.2).normalize(), one).isEmpty(), "back triangle must not render");
        var repeated = ManaDomeSurface.caps(List.of(new ShieldResponse.Threat(forward, 1), new ShieldResponse.Threat(forward, 0)), List.of(), 100);
        require(repeated.size() == 1, "same direction must not double the glass opacity");
        var old = new ShieldImpact(forward, 80, 0, 6, false);
        var latest = new ShieldImpact(side, 98, 0, 6, false);
        var overlapping = ManaDomeSurface.caps(List.of(), List.of(old, latest), 100);
        require(overlapping.size() == 2, "new impact erased the previous dome");
        require(ManaDomeSurface.caps(List.of(), List.of(old, latest), 134).isEmpty(), "expired glass must disappear");
        var both = List.of(new ManaDomeSurface.Cap(forward, 1), new ManaDomeSurface.Cap(forward.scale(-1), 1));
        require(ManaDomeSurface.clip(a, b, c, both).size() == 2, "opposite attacks need two nonoverlapping surface pieces");
        System.out.println("Mana dome: incoming-facing half, clipped rim, deduplicated overlap and independent lifetime verified");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
