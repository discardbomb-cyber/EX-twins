package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.client.EffectLights.Light;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public final class EffectLightsCheck {
    public static void main(String[] args) {
        Vec3 camera = new Vec3(0, 64, 0);
        require(EffectLights.merge(List.of(), camera, EffectLights.MAX_LIGHTS).isEmpty(), "No effects, no light sources");

        List<Light> pair = EffectLights.merge(List.of(new Light(0, 64, 0, 8, .5), new Light(1, 64, 0, 12, .5)), camera, EffectLights.MAX_LIGHTS);
        require(pair.size() == 1, "Lights a block apart become one source");
        Light merged = pair.getFirst();
        require(merged.luminance() == 12, "A merged light keeps its brightest member's level");
        require(merged.x() > .5 && merged.x() < 1, "A merged light leans towards its brighter member");
        require(merged.radius() + 1e-9 >= Math.max(merged.x(), 1 - merged.x()) + .5, "A merged light still covers both members");

        List<Light> apart = EffectLights.merge(List.of(new Light(0, 64, 0, 10, .5), new Light(12, 64, 0, 10, .5)), camera, EffectLights.MAX_LIGHTS);
        require(apart.size() == 2, "Separate effects stay separate while under the cap");

        // A deployed swarm: 16 groups, their charges and a string of blasts, far more reports than sources.
        List<Light> swarm = new ArrayList<>();
        for (int report = 0; report < 400; report++) {
            double angle = report * 2.399963;
            swarm.add(new Light(Math.cos(angle) * (3 + report % 40), 64 + report % 7, Math.sin(angle) * (3 + report % 40), 4 + report % 12, .2 + report % 5 * .2));
        }
        List<Light> capped = EffectLights.merge(swarm, camera, EffectLights.MAX_LIGHTS);
        require(!capped.isEmpty() && capped.size() <= EffectLights.MAX_LIGHTS, "Hundreds of reports merge down to the source cap");
        double brightest = swarm.stream().mapToDouble(Light::luminance).max().orElse(0);
        require(capped.stream().anyMatch(light -> light.luminance() == brightest), "Merging never dims the brightest effect");
        for (Light light : capped) {
            require(light.luminance() <= 15 && light.radius() <= EffectLights.MAX_RADIUS, "Merged sources stay within LambDynamicLights' range");
        }

        List<Light> tight = EffectLights.merge(List.of(new Light(0, 64, 0, 9, .5), new Light(40, 64, 0, 9, .5), new Light(80, 64, 0, 9, .5)), camera, 2);
        require(tight.size() == 2 && tight.stream().noneMatch(light -> light.x() > 60), "Over the cap, the farthest source goes first");

        require(EffectLights.fade(0, 8) == 1 && EffectLights.fade(4, 8) == .5 && EffectLights.fade(8, 8) == 0,
                "Flashes fade out linearly over their ticks");
        require(EffectLights.fade(-2, 8) == 0, "A flash waits dark until its event happens");

        System.out.println("Effect lights: nearby lights merge, 400 swarm reports fit " + capped.size() + "/" + EffectLights.MAX_LIGHTS
                + " sources, flashes fade");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
