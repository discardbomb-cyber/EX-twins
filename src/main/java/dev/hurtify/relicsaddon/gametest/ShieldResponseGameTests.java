package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import dev.hurtify.relicsaddon.client.ShieldResponse;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldResponseGameTests {
    @GameTest(template = "test_room")
    public static void boundaryAndVisualEnvelope(GameTestHelper helper) {
        Vec3 front = new Vec3(0, 0, 1);
        require(ShieldField.incoming(new Vec3(0, 0, 4), new Vec3(0, 0, -8), 1).time() == .25D, "High-speed crossing");
        require(ShieldField.incoming(new Vec3(0, 0, 4), new Vec3(0, 0, 1), 4) == null, "Outgoing shot");
        require(ShieldField.incoming(new Vec3(0, 0, 4), Vec3.ZERO, 4) == null, "Stationary shot");
        require(ShieldField.incoming(new Vec3(0, 0, 1), new Vec3(0, 0, -1), 4) == null, "No second entry from inside");
        require(ShieldField.incoming(new Vec3(3, 0, 4), new Vec3(0, 0, -8), 4) == null, "Misses boundary");
        require(ShieldField.incoming(new Vec3(2, 0, 4), new Vec3(0, 0, -8), 4) == null, "Tangent is not entry");
        require(ShieldField.incoming(new Vec3(Double.NaN, 0, 4), front, 4) == null, "Nonfinite rejection");
        for (int index = 0; index < 1000; index++) {
            Vec3 direction = new Vec3(Math.cos(index * .3), Math.sin(index * .7), Math.sin(index * .3)).normalize();
            var crossing = ShieldField.incoming(direction.scale(9), direction.scale(-30), 1);
            require(crossing != null && crossing.normal().distanceTo(direction) < 1e-8, "Three-dimensional crossing");
            require(Math.abs(crossing.time() - 7.0D / 30) < 1e-8, "Exact two-block boundary");
        }
        require(ShieldResponse.at(front, List.of(), null, 100, 12).presence() == 0, "Idle field is invisible");
        var threats = List.of(new ShieldResponse.Threat(front, 1));
        require(ShieldResponse.at(front, threats, null, 100, 12).presence() > .5, "Incoming shot reveals its panel");
        require(ShieldResponse.at(front.scale(-1), threats, null, 100, 12).presence() == 0, "Opposite panel stays invisible");
        require(ShieldResponse.at(front, threats, null, 100, 0).presence() == 0, "Broken sector cannot anticipate");
        var impact = new ShieldImpact(front, 100, 0, 5, true);
        require(ShieldResponse.at(front, List.of(), impact, 100, 0).destruction() == 1, "Broken panel still flashes");
        require(ShieldResponse.at(front, List.of(), impact, 99, 0).presence() == 0, "No future replay");
        require(ShieldResponse.at(front, List.of(), impact, 120, 12).presence() == 0, "Repair cannot replay hit");
        for (double time = 100; time <= 125; time += .05) {
            var response = ShieldResponse.at(front, List.of(), impact, time, 0);
            require(Double.isFinite(response.presence()) && response.presence() >= 0 && response.presence() <= 1, "Bounded fade");
        }
        System.out.println("Shield response: radius 2, 1000 swept crossings, no idle field, localized anticipation/absorption/destruction");
        helper.succeed();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
