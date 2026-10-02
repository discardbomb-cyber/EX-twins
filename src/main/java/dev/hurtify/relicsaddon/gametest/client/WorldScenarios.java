package dev.hurtify.relicsaddon.gametest.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.DeviceControlScreen;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Development only, excluded from the jar with the rest of gametest: plays scripted fights in a real
 * world and screenshots them, for checking the swarm and shields in game when nobody is at the keys.
 * Enabled by {@code -Drelics_addon.worldScenario=all} (or a comma-separated list of scenario names) with
 * {@code --quickPlaySingleplayer <world>}; see the {@code worldScenarioClient} run. The owner stands at
 * the origin of each scene facing north; husks with no AI that target the owner stand in for attackers,
 * and an invisible armor stand carries the camera so the scene can be framed from the side.
 */
@EventBusSubscriber(modid = RelicsAddon.MOD_ID, value = Dist.CLIENT)
public final class WorldScenarios {
    private static final String PROPERTY = "relics_addon.worldScenario";

    /**
     * One scene: the devices worn, the attackers (relative to the owner), the camera, and how long to film it.
     * A console scene ({@code console} set) films the hive's console on its Swarm tab instead.
     */
    private record Scene(String name, RelicRole hive, int hiveLevel, AttackMode mode, RelicRole shield, List<Vec3> foes, boolean foesFight,
                         Vec3 camera, Vec3 look, int warmTicks, int frames, int killFirstAtFrame, String foeType, Console console) {
        Scene(String name, RelicRole hive, int hiveLevel, AttackMode mode, RelicRole shield, List<Vec3> foes, boolean foesFight,
              Vec3 camera, Vec3 look, int warmTicks, int frames, int killFirstAtFrame, String foeType) {
            this(name, hive, hiveLevel, mode, shield, foes, foesFight, camera, look, warmTicks, frames, killFirstAtFrame, foeType, null);
        }

        Scene(String name, RelicRole hive, int hiveLevel, AttackMode mode, RelicRole shield, List<Vec3> foes, boolean foesFight,
              Vec3 camera, Vec3 look, int warmTicks, int frames, int killFirstAtFrame) {
            this(name, hive, hiveLevel, mode, shield, foes, foesFight, camera, look, warmTicks, frames, killFirstAtFrame, "minecraft:husk");
        }

        /** The console of a {@code hive} of {@code level} with {@code orders}, the mouse over {@code hover}'s slider (none if null). */
        static Scene console(String name, RelicRole hive, int level, HiveSettings orders, AttackMode hover) {
            return new Scene(name, hive, level, null, null, List.of(), false, new Vec3(0, 3, 4), new Vec3(0, 2, -4), 20, 2, -1, "minecraft:husk",
                    new Console(orders, hover, true));
        }

        /**
         * One attack filmed up close, a frame every tick: a level 10 {@code hive} with just {@code orders}, one
         * figure's worth, so its wind-up, flight, blow and what lingers each show clearly.
         */
        static Scene juice(RelicRole hive, AttackMode mode, HiveSettings orders) {
            String family = hive == RelicRole.RF_HIVE ? "rf" : hive == RelicRole.MANA_HIVE ? "mana" : "twins";
            boolean droplet = mode == AttackMode.DROPLET, lifted = hive == RelicRole.TWINS_HIVE && mode == AttackMode.CONTAINMENT;
            // Filmed from its first moments, so the figure setting out (or the construct closing) shows; a held creature is
            // killed near the end, so the construct comes apart on film.
            return new Scene("juice-" + family + "-" + mode.id(), hive, 10, mode, null, List.of(new Vec3(0, 0, droplet ? -9 : -7)), false,
                    droplet ? new Vec3(10, 5.5, -2) : lifted ? new Vec3(7.5, 5.5, -2) : new Vec3(5.5, 3.6, -3.5),
                    droplet ? new Vec3(0, 2.8, -3.5) : lifted ? new Vec3(0, 5.6, -7) : new Vec3(0, 2.2, -7), 5, 140,
                    mode == AttackMode.CONTAINMENT ? 115 : -1, "minecraft:husk", new Console(orders, null, false));
        }
    }

    /** What a console scene shows: the hive's orders, and which mode's slider the mouse rests on (none if {@code hover} is null). */
    private record Console(HiveSettings orders, AttackMode hover, boolean screen) { }

    /** Whether a scene films the console rather than the world. */
    private static boolean screen(Scene scene) {
        return scene.console() != null && scene.console().screen();
    }

    private static final List<Scene> SCENES = List.of(
            new Scene("droplet-multi", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, null,
                    List.of(new Vec3(-3, 0, -9), new Vec3(2, 0, -11), new Vec3(5, 0, -8), new Vec3(-7, 0, -12)), false,
                    new Vec3(15, 8, -1), new Vec3(0, 3, -5), 60, 90, -1),
            new Scene("droplet-mana", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, null,
                    List.of(new Vec3(-3, 0, -9), new Vec3(3, 0, -11)), false, new Vec3(15, 9, -1), new Vec3(0, 4, -6), 60, 90, -1),
            new Scene("droplet-twins", RelicRole.TWINS_HIVE, 10, AttackMode.DROPLET, null,
                    List.of(new Vec3(-3, 0, -9), new Vec3(3, 0, -11)), false, new Vec3(15, 8, 1), new Vec3(0, 3, -5), 60, 90, -1),
            new Scene("barrage-rf", RelicRole.RF_HIVE, 10, AttackMode.BARRAGE, null,
                    List.of(new Vec3(0, 0, -10)), false, new Vec3(9, 6, -4), new Vec3(0, 3, -10), 70, 70, -1),
            new Scene("barrage-twins", RelicRole.TWINS_HIVE, 10, AttackMode.BARRAGE, null,
                    List.of(new Vec3(0, 0, -10)), false, new Vec3(9, 6, -4), new Vec3(0, 3, -10), 70, 70, -1),
            new Scene("barrage-multi", RelicRole.MANA_HIVE, 10, AttackMode.BARRAGE, null,
                    List.of(new Vec3(-5, 0, -9), new Vec3(4, 0, -10), new Vec3(0, 0, -15)), false,
                    new Vec3(14, 9, -2), new Vec3(0, 2.5, -11), 70, 90, -1),
            new Scene("containment-rf", RelicRole.RF_HIVE, 10, AttackMode.CONTAINMENT, null,
                    List.of(new Vec3(0, 0, -9)), false, new Vec3(12, 7, -2), new Vec3(0, 4, -9), 60, 70, -1),
            new Scene("containment-mana", RelicRole.MANA_HIVE, 10, AttackMode.CONTAINMENT, null,
                    List.of(new Vec3(0, 0, -9)), false, new Vec3(13, 8, -2), new Vec3(0, 5.5, -9), 60, 70, -1),
            new Scene("containment-twins", RelicRole.TWINS_HIVE, 10, AttackMode.CONTAINMENT, null,
                    List.of(new Vec3(0, 0, -9)), false, new Vec3(21, 12, 0), new Vec3(0, 8.3, -9), 70, 80, -1),
            // Close by: the colliders inside the tori, and the world bent and darkened round the black hole.
            new Scene("containment-twins-close", RelicRole.TWINS_HIVE, 10, AttackMode.CONTAINMENT, null,
                    List.of(new Vec3(0, 0, -9)), false, new Vec3(11, 10, -1), new Vec3(0, 8.3, -9), 70, 60, -1),
            new Scene("containment-mana-close", RelicRole.MANA_HIVE, 10, AttackMode.CONTAINMENT, null,
                    List.of(new Vec3(0, 0, -9)), false, new Vec3(4.5, 4.5, -4), new Vec3(0, 3, -9), 60, 60, -1),
            new Scene("containment-rf-close", RelicRole.RF_HIVE, 10, AttackMode.CONTAINMENT, null,
                    List.of(new Vec3(0, 0, -9)), false, new Vec3(4.5, 5, -4), new Vec3(0, 3.4, -9), 60, 60, -1),
            // A crossbowman shoots at the owner; the level 0 shield (radius 2) stops the bolts, and the swarm answers the shooter.
            new Scene("shield-provoke", RelicRole.TWINS_HIVE, 10, AttackMode.BARRAGE, RelicRole.RF_SHIELD,
                    List.of(new Vec3(0, 0, -10)), true, new Vec3(9, 5, 0), new Vec3(0, 2, -5), 5, 110, -1),
            new Scene("retarget", RelicRole.TWINS_HIVE, 10, AttackMode.DROPLET, null,
                    List.of(new Vec3(-4, 0, -9), new Vec3(6, 0, -11)), false, new Vec3(15, 8, -1), new Vec3(0, 3, -6), 60, 90, 30),
            // Containment round a bigger creature: the constructs grow with its hitbox.
            new Scene("containment-rf-golem", RelicRole.RF_HIVE, 10, AttackMode.CONTAINMENT, null, List.of(new Vec3(0, 0, -10)), false,
                    new Vec3(13, 8, -2), new Vec3(0, 5, -10), 60, 60, -1, "minecraft:iron_golem"),
            new Scene("containment-mana-golem", RelicRole.MANA_HIVE, 10, AttackMode.CONTAINMENT, null, List.of(new Vec3(0, 0, -10)), false,
                    new Vec3(13, 8, -2), new Vec3(0, 5, -10), 60, 60, -1, "minecraft:iron_golem"),
            new Scene("containment-twins-golem", RelicRole.TWINS_HIVE, 10, AttackMode.CONTAINMENT, null, List.of(new Vec3(0, 0, -10)), false,
                    new Vec3(27, 15, 0), new Vec3(0, 11.4, -10), 70, 60, -1, "minecraft:iron_golem"),
            // Armageddon in one take, from the first moment of the charge to the last of the blast: the cannon builds
            // over the owner (a Twins shield feeds it) and fires at a crowd 150 blocks off, seen from just behind.
            new Scene("armageddon", RelicRole.TWINS_HIVE, 10, AttackMode.DROPLET, RelicRole.TWINS_SHIELD,
                    List.of(new Vec3(-6, 0, -148), new Vec3(4, 0, -152), new Vec3(9, 0, -145), new Vec3(-10, 0, -156), new Vec3(0, 0, -160)), false,
                    new Vec3(22, 12, 24), new Vec3(-2, 10, -40), 40, 2200, -1),
            new Scene("armageddon-close", RelicRole.TWINS_HIVE, 10, AttackMode.DROPLET, RelicRole.TWINS_SHIELD,
                    List.of(new Vec3(-4, 0, -60), new Vec3(5, 0, -62)), false, new Vec3(11, 8, 10), new Vec3(0, 8, -2), 40, 200, -1),
            // The black hole arriving and eating the land round it, seen from 50 blocks off.
            new Scene("armageddon-devour", RelicRole.TWINS_HIVE, 10, AttackMode.DROPLET, RelicRole.TWINS_SHIELD,
                    List.of(new Vec3(-8, 0, -58), new Vec3(6, 0, -66), new Vec3(12, 0, -52)), false, new Vec3(38, 16, -28), new Vec3(0, 3, -60), 40, 132, -1),
            // The blast seen from the ground 200 blocks off, as the blast it follows is framed: the dome filling most
            // of the sky, the column running up out of sight, the shock wave reaching the camera after the column falls.
            new Scene("armageddon-blast", RelicRole.TWINS_HIVE, 10, AttackMode.DROPLET, RelicRole.TWINS_SHIELD,
                    List.of(new Vec3(-8, 0, -58), new Vec3(6, 0, -66)), false, new Vec3(150, 8, 80), new Vec3(0, 36, -60), 40, 900, -1),
            // Mana Armageddon in one take, from the swarm spiralling into the flowers to the white sky after the blast:
            // the flowers over the owner (a Mana shield feeds them) fire their streams at a crowd 150 blocks off.
            new Scene("mana-armageddon", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, RelicRole.MANA_SHIELD,
                    List.of(new Vec3(-6, 0, -148), new Vec3(4, 0, -152), new Vec3(9, 0, -145), new Vec3(-10, 0, -156), new Vec3(0, 0, -160)), false,
                    new Vec3(16, 9, 17), new Vec3(-1, 6, -40), 40, 2600, -1),
            // The flowers close up from in front and to the side, as their runes are written.
            new Scene("mana-armageddon-flowers", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, RelicRole.MANA_SHIELD,
                    List.of(new Vec3(0, 0, -60)), false, new Vec3(5.5, 4.2, -8.5), new Vec3(0, 2.7, 0), 40, 260, -1),
            // Everything at the target from 150 blocks off: the streams arriving and colliding, the vortex, the sphere
            // on its seal, the blast, the dome and the column growing until it dissolves into the white sky.
            new Scene("mana-armageddon-blast", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, RelicRole.MANA_SHIELD,
                    List.of(new Vec3(-8, 0, -58), new Vec3(6, 0, -66)), false, new Vec3(120, 14, 70), new Vec3(0, 26, -60), 200, 1400, -1),
            // Over the owner's shoulder: the streams leaving the flowers, meeting at the target and tearing up the land.
            new Scene("mana-armageddon-streams", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, RelicRole.MANA_SHIELD,
                    List.of(new Vec3(-8, 0, -58)), false, new Vec3(1.5, 3.4, 4.5), new Vec3(0, 3, -60), 60, 200, -1),
            // The sphere of runes and the seal under it from above and to the side, from the sun igniting through the blast.
            new Scene("mana-armageddon-seal", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, RelicRole.MANA_SHIELD,
                    List.of(new Vec3(-8, 0, -58)), false, new Vec3(46, 38, -22), new Vec3(0, 4, -60), 200, 420, -1),
            // Looking up from beside the blast at the column rising into the sky, the white it dissolves into and the moon.
            new Scene("mana-armageddon-sky", RelicRole.MANA_HIVE, 10, AttackMode.DROPLET, RelicRole.MANA_SHIELD,
                    List.of(new Vec3(-8, 0, -58)), false, new Vec3(70, 3, 20), new Vec3(0, 150, -20), 200, 400, -1),
            // RF Armageddon in one take, from the swarm building the relay hologram to the crater after the blast: the
            // hologram over the owner (an RF shield feeds it) launches its ball at a crowd 150 blocks off.
            new Scene("rf-armageddon", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-6, 0, -148), new Vec3(4, 0, -152), new Vec3(9, 0, -145), new Vec3(-10, 0, -156), new Vec3(0, 0, -160)), false,
                    new Vec3(14, 13, 16), new Vec3(-1, 9, -40), 40, 2600, -1),
            // The hologram being built and its panels starting to open, from in front and to the side.
            new Scene("rf-armageddon-hologram", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(0, 0, -60)), false, new Vec3(13, 14, -17), new Vec3(0, 9.6, -3), 40, 270, -1),
            // From behind the owner: the full cross and the ball before the nose, the panels snapping shut and the ball leaving.
            new Scene("rf-armageddon-charged", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(0, 0, -60)), false, new Vec3(4, 12, 11), new Vec3(0, 9.6, -10), 60, 200, -1),
            // From the side of its way: the ball's heavy flight and its bolts, its hover while the world goes grey, its sinking.
            new Scene("rf-armageddon-flight", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-8, 0, -118)), false, new Vec3(62, 24, -48), new Vec3(0, 20, -85), 200, 340, -1),
            // The blast from 125 blocks off: the dome of glass heating, the flash, the silhouettes and the shock front passing.
            new Scene("rf-armageddon-blast", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-8, 0, -118), new Vec3(6, 0, -126)), false, new Vec3(95, 22, -40), new Vec3(0, 12, -120), 200, 420, -1),
            // Looking down on it all from over the edge: the dome, the flash and the silhouettes, then the crater, its rim, the glow
            // fading at its heart and the discharges over its floor.
            new Scene("rf-armageddon-crater", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-8, 0, -118)), false, new Vec3(30, 62, -52), new Vec3(0, 0, -120), 200, 300, -1),
            // The same from over the edge on a server that keeps its land: no crater, only a rim of light-lifted blocks while the blast lasts.
            new Scene("rf-armageddon-safe", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-8, 0, -118)), false, new Vec3(30, 62, -52), new Vec3(0, 0, -120), 200, 300, -1),
            // Aimed up at the underside of a stone ceiling hanging 45 blocks up: the ball hangs under it and flies up into it.
            new Scene("rf-armageddon-ceiling", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-8, 0, -40)), false, new Vec3(72, 16, -30), new Vec3(0, 28, -40), 200, 420, -1),
            // Aimed straight at a stone wall 88 blocks off: the ball hangs before it and flies into it.
            new Scene("rf-armageddon-wall", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, RelicRole.RF_SHIELD,
                    List.of(new Vec3(-8, 0, -80)), false, new Vec3(64, 14, -52), new Vec3(0, 14, -84), 200, 420, -1),
            // A lance hive on a small deck three blocks up burns two husks in turn, until it overheats and cools.
            new Scene("ship-lance", null, 0, null, null, List.of(new Vec3(-6, 0, -16), new Vec3(6, 0, -19)), false,
                    new Vec3(13, 8, 1), new Vec3(0, 4, -9), 30, 150, -1),
            // The turret close up: the three drones rising off the face, joining their beams at the focus.
            new Scene("ship-lance-close", null, 0, null, null, List.of(new Vec3(-3, 0, -16)), false,
                    new Vec3(3.5, 7.5, -0.5), new Vec3(0, 6, -4), 10, 100, -1),
            // An aegis hive on its deck: crossbowmen outside its dome shoot at the owner inside, and the shield stops the bolts.
            new Scene("ship-aegis", null, 0, null, null, List.of(new Vec3(-5, 0, -17), new Vec3(7, 0, -16)), true,
                    new Vec3(17, 9, 9), new Vec3(0, 3, -6), 40, 150, -1),
            // The aegis and a lance on one deck: the shield takes the bolts while the lance burns the crossbowmen.
            new Scene("ship-duo", null, 0, null, null, List.of(new Vec3(-5, 0, -17), new Vec3(7, 0, -16)), true,
                    new Vec3(17, 9, 9), new Vec3(0, 3, -6), 40, 150, -1),
            // A real Sable ship (run with shipScenarioClient): a small deck carrying all three ship hives, assembled into a
            // ship, held still in the air and turned; skeletons below shoot at the owner on its deck.
            new Scene("ship-sable", null, 0, null, null, List.of(new Vec3(-9, 0, -16), new Vec3(9, 0, -17), new Vec3(0, 0, -21)), true,
                    new Vec3(21, 15, 8), new Vec3(0, 7, -9), 60, 200, -1, "minecraft:skeleton"),
            // The same ship from close by, level with its deck.
            new Scene("ship-sable-close", null, 0, null, null, List.of(new Vec3(-9, 0, -16), new Vec3(9, 0, -17), new Vec3(0, 0, -21)), true,
                    new Vec3(10, 9.5, -1), new Vec3(0, 7.5, -8), 60, 160, -1, "minecraft:skeleton"),
            // An escort hive's window, open over its deck while its wings fly.
            new Scene("ship-window", null, 0, null, null, List.of(new Vec3(-4, 0, -14)), false,
                    new Vec3(6, 7, 2), new Vec3(0, 4, -5), 60, 30, -1),
            // A slower, level 3 hive keeps its figures in the fan longer, close to the camera.
            new Scene("drone-closeup", RelicRole.RF_HIVE, 3, AttackMode.DROPLET, null,
                    List.of(new Vec3(0, 0, -26)), false, new Vec3(2.5, 3.6, -2.2), new Vec3(0, 3.8, 3), 60, 50, -1),
            // The six ship shield blocks and a chest on an iron deck, the RF generator switched on; then the console
            // of the Mana generator, which refused to come on beside it.
            new Scene("ship-devices", RelicRole.RF_HIVE, 0, AttackMode.DROPLET, null, List.of(), false,
                    new Vec3(3.5, 3.2, 3.5), new Vec3(0, 0.6, -2), 60, 4, -1),
            new Scene("ship-console", RelicRole.RF_HIVE, 0, AttackMode.DROPLET, null, List.of(), false,
                    new Vec3(3.5, 3.2, 3.5), new Vec3(0, 0.6, -2), 60, 4, -1),
            // The RF shield's shell round the deck as a wire mesh, its eight drones at their seats; from the side and from above.
            new Scene("ship-shell", RelicRole.RF_HIVE, 0, AttackMode.DROPLET, null, List.of(), false,
                    new Vec3(11, 5, 7), new Vec3(0, 0.5, -2), 160, 4, -1),
            new Scene("ship-shell-above", RelicRole.RF_HIVE, 0, AttackMode.DROPLET, null, List.of(), false,
                    new Vec3(2, 12, 6), new Vec3(0, 0, -2), 160, 4, -1),
            new Scene("ship-shell-rf", null, 0, null, null, List.of(), false,
                    new Vec3(11, 7, 8), new Vec3(0, 0, -2), 160, 24, -1),
            new Scene("ship-shell-mana", null, 0, null, null, List.of(), false,
                    new Vec3(11, 7, 8), new Vec3(0, 0, -2), 160, 24, -1),
            new Scene("ship-shell-twins", null, 0, null, null, List.of(), false,
                    new Vec3(11, 7, 8), new Vec3(0, 0, -2), 160, 24, -1),
            new Scene("ship-shell-flight", null, 0, null, null, List.of(), false,
                    new Vec3(16, 11, 5), new Vec3(0, 4, -7.5), 220, 48, -1),
            // The console's Swarm tab: drones shared between all three modes and the healers, a slider hovered;
            // Containment with no free drones for its tori, dimmed, with its reason; a Droplet squeezed out of the air by Barrage.
            // The nine attacks up close, one figure each (see Scene.juice).
            Scene.juice(RelicRole.RF_HIVE, AttackMode.DROPLET, new HiveSettings(0, 16, 0, 0)),
            Scene.juice(RelicRole.MANA_HIVE, AttackMode.DROPLET, new HiveSettings(0, 14, 0, 0)),
            Scene.juice(RelicRole.TWINS_HIVE, AttackMode.DROPLET, new HiveSettings(0, 18, 0, 0)),
            Scene.juice(RelicRole.RF_HIVE, AttackMode.BARRAGE, new HiveSettings(0, 0, 64, 0)),
            Scene.juice(RelicRole.MANA_HIVE, AttackMode.BARRAGE, new HiveSettings(0, 0, 96, 0)),
            Scene.juice(RelicRole.TWINS_HIVE, AttackMode.BARRAGE, new HiveSettings(0, 0, 128, 0)),
            Scene.juice(RelicRole.RF_HIVE, AttackMode.CONTAINMENT, new HiveSettings(0, 0, 0, 42)),
            Scene.juice(RelicRole.MANA_HIVE, AttackMode.CONTAINMENT, new HiveSettings(0, 0, 0, 37)),
            Scene.juice(RelicRole.TWINS_HIVE, AttackMode.CONTAINMENT, new HiveSettings(0, 0, 0, 48)),
            Scene.console("console-shared", RelicRole.RF_HIVE, 10, new HiveSettings(40, 608, 900, 432), AttackMode.DROPLET),
            Scene.console("console-refused", RelicRole.RF_HIVE, 0, new HiveSettings(10, 16, 64, 0), AttackMode.CONTAINMENT),
            Scene.console("console-no-room", RelicRole.MANA_HIVE, 10, new HiveSettings(0, 14, 1986, 0), null));

    /**
     * Armageddon scenes: where the owner fires (from the owner's feet) as filming starts, how far into the
     * minute of charging the cannon already is, and how many ticks go by between frames; their foes keep
     * ordinary health.
     */
    private record Shot(Vec3 aim, int headStart, int cadence, float tickRate, boolean exact) {
        /** Aimed at the ground under {@code aim}. */
        Shot(Vec3 aim, int headStart, int cadence, float tickRate) {
            this(aim, headStart, cadence, tickRate, false);
        }
    }
    private static final java.util.Map<String, Shot> ARMAGEDDON = java.util.Map.ofEntries(
            java.util.Map.entry("armageddon", new Shot(new Vec3(0, 0, -150), 0, 1, 20)),
            java.util.Map.entry("armageddon-close", new Shot(new Vec3(0, 0, -60), 1000, 2, 20)),
            java.util.Map.entry("armageddon-devour", new Shot(new Vec3(0, 0, -60), 1150, 1, 5)),
            java.util.Map.entry("armageddon-blast", new Shot(new Vec3(0, 0, -60), 1265, 1, 5)),
            java.util.Map.entry("mana-armageddon", new Shot(new Vec3(0, 0, -150), 0, 1, 20)),
            java.util.Map.entry("mana-armageddon-flowers", new Shot(new Vec3(0, 0, -60), 300, 2, 20)),
            java.util.Map.entry("mana-armageddon-blast", new Shot(new Vec3(0, 0, -60), 1160, 1, 20)),
            java.util.Map.entry("mana-armageddon-seal", new Shot(new Vec3(0, 0, -60), 1300, 1, 20)),
            java.util.Map.entry("mana-armageddon-sky", new Shot(new Vec3(0, 0, -60), 1423, 3, 20)),
            java.util.Map.entry("mana-armageddon-streams", new Shot(new Vec3(0, 0, -60), 1190, 1, 20)),
            java.util.Map.entry("rf-armageddon", new Shot(new Vec3(0, 0, -150), 0, 1, 20)),
            java.util.Map.entry("rf-armageddon-hologram", new Shot(new Vec3(0, 0, -60), 0, 2, 20)),
            java.util.Map.entry("rf-armageddon-charged", new Shot(new Vec3(0, 0, -60), 1050, 1, 20)),
            java.util.Map.entry("rf-armageddon-flight", new Shot(new Vec3(0, 0, -120), 1190, 1, 20)),
            java.util.Map.entry("rf-armageddon-blast", new Shot(new Vec3(0, 0, -120), 1440, 1, 20)),
            java.util.Map.entry("rf-armageddon-crater", new Shot(new Vec3(0, 0, -120), 1499, 3, 20)),
            java.util.Map.entry("rf-armageddon-safe", new Shot(new Vec3(0, 0, -120), 1499, 3, 20)),
            java.util.Map.entry("rf-armageddon-ceiling", new Shot(new Vec3(0, 45, -40), 1190, 2, 20, true)),
            java.util.Map.entry("rf-armageddon-wall", new Shot(new Vec3(0, 12, -88), 1190, 2, 20, true)));
    /** Scenes played on a server that keeps its land (Armageddon's safe mode), put back as it was when they end. */
    private static final java.util.Set<String> SAFE = java.util.Set.of("rf-armageddon-safe");
    /** Whether the server kept its land before a safe scene changed it; null while no safe scene has. */
    private static Boolean wasSafe;
    /** How many frames may be on their way to disk at once. */
    private static final int GRABS_IN_FLIGHT = 6;
    /** When the current take started, in game time: an Armageddon take ends when its blast has burnt out. */
    private static double captureStart;

    /** A ship hive set on a deck for a scene: its kind, where it stands (from the stage) and the face it launches from. */
    private record Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind kind, Vec3 offset, net.minecraft.core.Direction facing) { }
    private static final java.util.Map<String, List<Mount>> SHIPS = java.util.Map.of(
            "ship-lance", List.of(new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.LANCE, new Vec3(0, 3, -4), net.minecraft.core.Direction.UP)),
            "ship-lance-close", List.of(new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.LANCE, new Vec3(0, 3, -4), net.minecraft.core.Direction.UP)),
            "ship-aegis", List.of(new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.AEGIS, new Vec3(0, 3, -4), net.minecraft.core.Direction.UP)),
            "ship-window", List.of(new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.ESCORT, new Vec3(0, 3, -4), net.minecraft.core.Direction.UP)),
            "ship-duo", List.of(new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.AEGIS, new Vec3(-2, 3, -4), net.minecraft.core.Direction.UP),
                    new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.LANCE, new Vec3(2, 3, -4), net.minecraft.core.Direction.UP)));
    /** Scenes played on a Sable ship: its deck and hives are built, then assembled into a ship, which is held and turned. */
    private static final java.util.Set<String> SABLE = java.util.Set.of("ship-sable", "ship-sable-close", "ship-shell-flight");
    /** The Sable ship the current scene assembled, if any, and where to find it just after its assembly. */
    private static java.util.UUID shipId;
    private static dev.ryanhcode.sable.companion.math.BoundingBox3d shipBox;
    /** Blocks a scene put up (decks and hives), taken down with it. */
    private static final List<net.minecraft.core.BlockPos> BUILT = new ArrayList<>();

    /** Scenes filmed against a chequered wall behind the black hole, so that its lens shows. */
    private static final java.util.Set<String> BACKDROP = java.util.Set.of("containment-twins", "containment-twins-close", "containment-twins-golem");

    private enum Phase { WAIT_WORLD, SETUP, WARM, CAPTURE, TEARDOWN, DONE }

    private static Phase phase = Phase.WAIT_WORLD;
    private static int ticks, scene = -1, frame, pendingGrabs;
    private static boolean due;
    private static long warmFps;
    private static int warmFrames;
    private static List<Scene> plan;
    private static final AtomicInteger CAMERA = new AtomicInteger(-1);
    private static final List<Integer> FOES = new ArrayList<>();
    private static Vec3 origin;
    /** Where the current scene plays: the origin, or for an Armageddon scene fresh ground well away from any earlier shot's hole. */
    private static Vec3 stage;

    private static boolean enabled() {
        return System.getProperty(PROPERTY) != null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        switch (phase) {
            case WAIT_WORLD -> {
                if (minecraft.level == null || minecraft.player == null || server == null || minecraft.screen != null) return;
                if (++ticks < 80) return;
                String wanted = System.getProperty(PROPERTY);
                plan = SCENES.stream().filter(s -> wanted.equals("all") || List.of(wanted.split(",")).contains(s.name())).toList();
                minecraft.getWindow().setWindowed(1280, 720);
                minecraft.options.hideGui = true;
                // A plain lens, not the wide angle the world was played with.
                minecraft.options.fov().set(55);
                // The whole blast reaches 256 blocks: draw the world as far as the game can.
                minecraft.options.renderDistance().set(32);
                minecraft.options.simulationDistance().set(12);
                next(minecraft);
            }
            case SETUP -> { }
            case WARM -> {
                attachCamera(minecraft);
                // The frame rate over the second half of the warm-up, before any frame is saved.
                if (ticks * 2 >= plan.get(scene).warmTicks()) {
                    warmFps += minecraft.getFps();
                    warmFrames++;
                }
                if (ticks == 5 && SABLE.contains(plan.get(scene).name())) onServer(minecraft, WorldScenarios::turnShip);
                if (ticks == plan.get(scene).warmTicks() - 12 && plan.get(scene).name().startsWith("ship-shell-")) onServer(minecraft, WorldScenarios::strikeShipShield);
                if (ticks == 30 && plan.get(scene).name().equals("ship-console")) onServer(minecraft, level -> {
                    ServerPlayer player = owner(level);
                    net.minecraft.core.BlockPos console = net.minecraft.core.BlockPos.containing(stage).offset(0, 0, -2);
                    if (level.getBlockEntity(console) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity device) device.setEnabled(player, true);
                    dev.hurtify.relicsaddon.menu.DeviceControlMenu.openBlock(player, console);
                });
                if (++ticks >= plan.get(scene).warmTicks()) {
                    phase = Phase.CAPTURE;
                    ticks = 0;
                    frame = 0;
                    captureStart = minecraft.level.getGameTime();
                    Shot shot = ARMAGEDDON.get(plan.get(scene).name());
                    if (screen(plan.get(scene))) onServer(minecraft, level -> DeviceControlMenu.open(owner(level), true, 0));
                    RelicsAddon.LOGGER.info("World scenario {}: {} fps warming up", plan.get(scene).name(), String.format(Locale.ROOT, "%.1f",
                            warmFrames == 0 ? 0 : warmFps / (double) warmFrames));
                    warmFps = 0;
                    warmFrames = 0;
                    if (shot != null) onServer(minecraft, level -> {
                        // Slowed down, every tick gets its frame: the film plays back smoothly at full speed.
                        level.getServer().tickRateManager().setTickRate(shot.tickRate());
                        Vec3 aim = shot.aim();
                        Vec3 target = shot.exact() ? stage.add(aim) : stage.add(aim.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                                (int) Math.floor(stage.x + aim.x), (int) Math.floor(stage.z + aim.z)) - stage.y, aim.z);
                        String refused = dev.hurtify.relicsaddon.server.ArmageddonController.request(owner(level), target, shot.headStart());
                        if (refused != null) RelicsAddon.LOGGER.warn("World scenario {}: Armageddon refused: {}", plan.get(scene).name(), refused);
                    });
                }
            }
            case CAPTURE -> {
                Console console = plan.get(scene).console();
                if (console != null && console.screen()) {
                    // The console opens a few ticks after it is asked for; then its Swarm tab, the mouse where the scene wants it.
                    if (minecraft.screen instanceof DeviceControlScreen screen) {
                        if (ticks == 0) screen.showSwarmTab();
                        if (++ticks >= 8 && ticks % 4 == 0) due = true;
                    }
                    return;
                }
                attachCamera(minecraft);
                // A key pressed into the game window must not open a screen over the shot (but a scene of a window keeps its own).
                if (minecraft.screen != null && !java.util.Set.of("ship-console", "ship-window").contains(plan.get(scene).name())) minecraft.setScreen(null);
                Shot shot = ARMAGEDDON.get(plan.get(scene).name());
                if (ticks == 20 && plan.get(scene).name().startsWith("ship-shell-")) onServer(minecraft, WorldScenarios::strikeShipShield);
                if (ticks % 10 == 0 && plan.get(scene).name().equals("ship-shell-flight")) {
                    int travelTick = ticks;
                    onServer(minecraft, level -> {
                        if (shipId != null) command(level, String.format(Locale.ROOT, "sable teleport %s %.2f %.2f %.2f %.2f 8", shipId,
                                stage.x + .5 + travelTick * .02, stage.y + 4, stage.z - 7.5, 25 + travelTick * .3));
                    });
                }
                if (++ticks % (shot == null ? plan.get(scene).name().startsWith("juice-") ? 1 : 2 : shot.cadence()) == 0) due = true;
                if (ticks % 40 == 0 && plan.get(scene).name().startsWith("ship-")) onServer(minecraft, level -> {
                    for (String line : dev.hurtify.relicsaddon.ship.ShipBrain.report()) RelicsAddon.LOGGER.info("World scenario {}: {}", plan.get(scene).name(), line);
                });
            }
            case TEARDOWN -> {
                if (++ticks >= 10) next(minecraft);
            }
            case DONE -> { }
        }
    }

    /** Rests the mouse on a console scene's button (or out of the way) before the frame is drawn, so its tooltip shows. */
    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!enabled() || phase != Phase.CAPTURE || !screen(plan.get(scene))) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof DeviceControlScreen screen)) return;
        Console console = plan.get(scene).console();
        int[] at = console.hover() == null ? new int[]{2, 2} : screen.sliderAt(console.hover());
        var window = minecraft.getWindow();
        try {
            for (String field : new String[]{"xpos", "ypos"}) {
                var handle = net.minecraft.client.MouseHandler.class.getDeclaredField(field);
                handle.setAccessible(true);
                boolean x = field.equals("xpos");
                handle.setDouble(minecraft.mouseHandler, (x ? at[0] : at[1]) * (double) (x ? window.getScreenWidth() : window.getScreenHeight())
                        / (x ? window.getGuiScaledWidth() : window.getGuiScaledHeight()));
            }
        } catch (ReflectiveOperationException exception) {
            RelicsAddon.LOGGER.warn("World scenario {}: could not place the mouse", plan.get(scene).name(), exception);
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (!enabled() || phase != Phase.CAPTURE || !due || pendingGrabs >= GRABS_IN_FLIGHT) return;
        Minecraft minecraft = Minecraft.getInstance();
        due = false;
        Scene current = plan.get(scene);
        int index = frame++;
        pendingGrabs++;
        Screenshot.grab(minecraft.gameDirectory, String.format(Locale.ROOT, "scenario-%s-%03d.png", current.name(), index),
                minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> pendingGrabs--));
        // Frames come as fast as they can be saved, not evenly: note the game time of each, to cut a real-time video.
        if (minecraft.level != null) {
            java.nio.file.Path ticks = minecraft.gameDirectory.toPath().resolve("screenshots").resolve("scenario-" + current.name() + ".ticks");
            try {
                java.nio.file.Files.writeString(ticks, index + "," + (minecraft.level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(true)) + "\n",
                        index == 0 ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING}
                                : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND});
            } catch (java.io.IOException exception) {
                RelicsAddon.LOGGER.warn("World scenario {}: could not note frame times", current.name(), exception);
            }
        }
        if (index == current.killFirstAtFrame()) onServer(minecraft, level -> {
            if (!FOES.isEmpty() && level.getEntity(FOES.getFirst()) instanceof net.minecraft.world.entity.LivingEntity foe) foe.kill();
        });
        Shot filming = ARMAGEDDON.get(current.name());
        int burnsOut = filming == null ? 0 : switch (current.hive()) {
            case MANA_HIVE -> dev.hurtify.relicsaddon.drone.ManaArmageddon.IMPACT + dev.hurtify.relicsaddon.drone.ManaArmageddon.QUIET + 40;
            case RF_HIVE -> dev.hurtify.relicsaddon.drone.RfArmageddon.RECOVER + 40;
            default -> dev.hurtify.relicsaddon.drone.Armageddon.IMPACT + dev.hurtify.relicsaddon.drone.Armageddon.GONE + 40;
        };
        boolean burntOut = filming != null && minecraft.level != null && minecraft.level.getGameTime() - captureStart >= burnsOut - filming.headStart();
        if (frame >= current.frames() || burntOut) {
            RelicsAddon.LOGGER.info("World scenario {}: {} frames", current.name(), frame);
            onServer(minecraft, WorldScenarios::teardown);
            if (screen(current) && minecraft.player != null) minecraft.player.closeContainer();
            minecraft.setCameraEntity(minecraft.player);
            phase = Phase.TEARDOWN;
            ticks = 0;
        }
    }

    private static void next(Minecraft minecraft) {
        scene++;
        if (scene >= plan.size()) {
            phase = Phase.DONE;
            RelicsAddon.LOGGER.info("World scenarios captured: {}", plan.stream().map(Scene::name).toList());
            minecraft.execute(minecraft::stop);
            return;
        }
        phase = Phase.SETUP;
        Scene current = plan.get(scene);
        CAMERA.set(-1);
        onServer(minecraft, level -> {
            setup(level, current);
            minecraft.execute(() -> {
                phase = Phase.WARM;
                ticks = 0;
            });
        });
    }

    private static void attachCamera(Minecraft minecraft) {
        int id = CAMERA.get();
        if (id < 0 || minecraft.level == null) return;
        Entity camera = minecraft.level.getEntity(id);
        // Held every tick, so nothing pressed in the game window can move or swap the view.
        if (camera != null && minecraft.getCameraEntity() != camera) minecraft.setCameraEntity(camera);
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) minecraft.options.setCameraType(CameraType.FIRST_PERSON);
    }

    private interface LevelTask { void run(ServerLevel level); }

    private static void onServer(Minecraft minecraft, LevelTask task) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) return;
        var uuid = minecraft.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) task.run(player.serverLevel());
        });
    }

    private static ServerPlayer owner(ServerLevel level) {
        return level.getServer().getPlayerList().getPlayers().getFirst();
    }

    private static void setup(ServerLevel level, Scene scene) {
        if (SAFE.contains(scene.name())) {
            if (wasSafe == null) wasSafe = dev.hurtify.relicsaddon.AddonConfig.ARMAGEDDON_SAFE.get();
            dev.hurtify.relicsaddon.AddonConfig.ARMAGEDDON_SAFE.set(true);
        }
        ServerPlayer player = owner(level);
        if (origin == null) {
            int x = player.getBlockX(), z = player.getBlockZ();
            origin = new Vec3(x + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + .5);
        }
        clear(level);
        stage = origin;
        if (ARMAGEDDON.containsKey(scene.name())) {
            // Each Armageddon take eats a hole in the land, so every take plays on untouched ground.
            int x = (int) Math.floor(origin.x) + 400 * (int) (1 + Math.floorMod(level.getGameTime() / 2400 + scene.name().hashCode(), 60)), z = (int) Math.floor(origin.z);
            level.getChunk(x >> 4, z >> 4);
            level.getChunk(x >> 4, (z - 64) >> 4);
            stage = new Vec3(x + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + .5);
        }
        backdrop(level, BACKDROP.contains(scene.name()));
        if (scene.name().equals("rf-armageddon-ceiling")) build(level, -30, 30, 45, 45, -70, -10);
        if (scene.name().equals("rf-armageddon-wall")) build(level, -40, 40, 0, 50, -91, -88);
        if (scene.name().startsWith("ship-")) shipDeck(level, true, scene.name().startsWith("ship-shell") ? 8 : 5);
        level.setDayTime(11_500);
        // The long Armageddon takes would otherwise slide into sunset while they are filmed.
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.setWeatherParameters(6000, 0, false, false);
        player.setGameMode(GameType.SURVIVAL);
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 4, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        player.setHealth(player.getMaxHealth());
        // The owner faces north, towards the attackers.
        player.teleportTo(level, stage.x, stage.y, stage.z, 180, 0);
        var curios = CuriosApi.getCuriosInventory(player).orElseThrow();
        var charms = curios.getStacksHandler(RelicRole.EQUIPMENT_SLOT).orElseThrow().getStacks();
        for (int slot = 0; slot < charms.getSlots(); slot++) charms.setStackInSlot(slot, ItemStack.EMPTY);
        if (scene.hive() != null) {
            ItemStack hive = device(scene.hive(), scene.hiveLevel(), scene.mode());
            if (scene.console() != null) hive.set(ModDataComponents.HIVE_SETTINGS.get(), scene.console().orders());
            curios.setEquippedCurio(RelicRole.EQUIPMENT_SLOT, 0, hive);
        }
        for (Mount mount : SHIPS.getOrDefault(scene.name(), List.of())) {
            // A small iron deck with the hive in its middle, set into it.
            net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(stage.add(mount.offset()));
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                net.minecraft.core.BlockPos deck = at.offset(dx, 0, dz);
                level.setBlock(deck, net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(), 3);
                BUILT.add(deck);
            }
            level.setBlock(at, dev.hurtify.relicsaddon.registry.ModBlocks.SHIP_HIVES.get(mount.kind()).get().defaultBlockState()
                    .setValue(dev.hurtify.relicsaddon.ship.ShipHiveBlock.FACING, mount.facing()), 3);
            if (level.getBlockEntity(at) instanceof dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity hive) {
                prime(hive, player);
            }
        }
        if (scene.shield() != null) curios.setEquippedCurio(RelicRole.EQUIPMENT_SLOT, 1, device(scene.shield(), 0, null));

        if (SABLE.contains(scene.name())) sableShip(level, player);
        if (scene.name().equals("ship-window") && level.getBlockEntity(net.minecraft.core.BlockPos.containing(stage.add(0, 3, -4)))
                instanceof dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity hive) {
            dev.hurtify.relicsaddon.menu.ShipHiveMenu.open(player, hive);
        }

        FOES.clear();
        for (Vec3 offset : scene.foes()) {
            net.minecraft.world.entity.Mob husk = scene.foesFight() && !scene.foeType().equals("minecraft:husk")
                    ? EntityType.byString(scene.foeType()).map(type -> type.create(level)).orElse(null) instanceof net.minecraft.world.entity.Mob mob ? mob : null
                    : scene.foesFight() ? EntityType.PILLAGER.create(level)
                    : EntityType.byString(scene.foeType()).map(type -> type.create(level)).orElse(null) instanceof net.minecraft.world.entity.Mob mob ? mob : null;
            if (husk == null) continue;
            if (scene.foesFight() && husk instanceof net.minecraft.world.entity.monster.AbstractSkeleton) {
                husk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.BOW));
                // A helmet keeps them from burning in the evening sun.
                husk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
            } else if (scene.foesFight()) husk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.CROSSBOW));
            Vec3 at = stage.add(offset);
            husk.moveTo(at.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z, 0, 0);
            husk.setPersistenceRequired();
            if (!ARMAGEDDON.containsKey(scene.name())) {
                husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(5000);
                husk.setHealth(husk.getMaxHealth());
            }
            husk.setNoAi(!scene.foesFight());
            // A close-up's target never swings at the drones, so its one figure is never cut short.
            if (scene.name().startsWith("juice-") && husk.getAttribute(Attributes.ATTACK_DAMAGE) != null) husk.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0);
            if (scene.foesFight()) husk.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MobEffectInstance.INFINITE_DURATION, 6, false, false));
            level.addFreshEntity(husk);
            husk.setTarget(player);
            FOES.add(husk.getId());
        }

        ArmorStand camera = EntityType.ARMOR_STAND.create(level);
        if (camera != null) {
            Vec3 eye = stage.add(scene.camera()), look = stage.add(scene.look()).subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
            camera.setInvisible(true);
            camera.setNoGravity(true);
            camera.setSilent(true);
            camera.setInvulnerable(true);
            camera.moveTo(eye.x, eye.y - camera.getEyeHeight(), eye.z, yaw, pitch);
            camera.setYHeadRot(yaw);
            level.addFreshEntity(camera);
            CAMERA.set(camera.getId());
        }
    }

    /** A fresh, switched-on, fully charged device of {@code level} (a hive in {@code mode}). */
    private static ItemStack device(RelicRole role, int level, AttackMode mode) {
        ItemStack stack = new ItemStack(switch (role) {
            case RF_SHIELD -> ModItems.RF_SHIELD.get();
            case MANA_SHIELD -> ModItems.MANA_SHIELD.get();
            case TWINS_SHIELD -> ModItems.TWINS_SHIELD.get();
            case RF_HIVE -> ModItems.RF_HIVE.get();
            case MANA_HIVE -> ModItems.MANA_HIVE.get();
            default -> ModItems.TWINS_HIVE.get();
        });
        AutonomousRelicItem.ensureState(stack);
        stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, level, 0, 0));
        stack.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(stack));
        if (mode != null) stack.set(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.legacy(0, mode));
        return stack;
    }

    /**
     * Builds a small ship over the stage (an iron deck 7 by 11 with a rail, a lance hive at the bow, an aegis hive
     * amidships and an escort hive at the stern), turns it into a Sable ship, stops Sable's physics so it hangs where
     * it is, turns it a little in yaw and pitch, and puts the owner on its deck.
     */
    private static void sableShip(ServerLevel level, ServerPlayer player) {
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(stage);
        var iron = net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState();
        var rail = net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE.defaultBlockState();
        for (int x = -3; x <= 3; x++) for (int z = -13; z <= -3; z++) {
            level.setBlock(base.offset(x, 6, z), iron, 3);
            if (Math.abs(x) == 3 || z == -13 || z == -3) level.setBlock(base.offset(x, 7, z), rail, 3);
        }
        for (Mount mount : List.of(new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.LANCE, new Vec3(0, 7, -11), net.minecraft.core.Direction.UP),
                new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.AEGIS, new Vec3(0, 7, -8), net.minecraft.core.Direction.UP),
                new Mount(dev.hurtify.relicsaddon.ship.ShipHiveKind.ESCORT, new Vec3(0, 7, -5), net.minecraft.core.Direction.UP))) {
            net.minecraft.core.BlockPos at = base.offset((int) mount.offset().x, (int) mount.offset().y, (int) mount.offset().z);
            level.setBlock(at, dev.hurtify.relicsaddon.registry.ModBlocks.SHIP_HIVES.get(mount.kind()).get().defaultBlockState()
                    .setValue(dev.hurtify.relicsaddon.ship.ShipHiveBlock.FACING, mount.facing()), 3);
            if (level.getBlockEntity(at) instanceof dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity hive) {
                prime(hive, player);
            }
        }
        net.minecraft.core.BlockPos from = base.offset(-3, 6, -13), to = base.offset(3, 7, -3);
        if (plan.get(scene).name().equals("ship-shell-flight")) {
            var family = dev.hurtify.relicsaddon.shipshield.ShipFamily.TWINS;
            net.minecraft.core.BlockPos generatorPos = base.offset(0, 7, -8);
            level.setBlock(generatorPos, dev.hurtify.relicsaddon.registry.ShipBlocks.GENERATORS.get(family).get().defaultBlockState(), 3);
            for (int z : new int[]{-11, -5}) {
                net.minecraft.core.BlockPos dockPos = base.offset(0, 7, z);
                level.setBlock(dockPos, dev.hurtify.relicsaddon.registry.ShipBlocks.DOCKS.get(family).get().defaultBlockState(), 3);
                if (level.getBlockEntity(dockPos) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity dock) dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(family).get(), 8));
            }
            if (level.getBlockEntity(generatorPos) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity generator) generator.setEnabled(null, true);
        }
        Vec3 middle = stage.add(.5, 7, -7.5);
        command(level, "sable assemble area " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + to.getX() + " " + to.getY() + " " + to.getZ());
        command(level, "sable paused true");
        shipId = null;
        shipBox = new dev.ryanhcode.sable.companion.math.BoundingBox3d(from.getX(), from.getY(), from.getZ(), to.getX() + 1, to.getY() + 1, to.getZ() + 1);
        player.teleportTo(level, stage.x, stage.y + 9, stage.z - 7, 180, 10);
    }

    /**
     * A few ticks after it was assembled (Sable takes the new ship in at the end of a tick), the ship is found where its
     * blocks stood and turned by its id, and the owner is put back on its deck.
     */
    private static void turnShip(ServerLevel level) {
        if (shipBox == null) return;
        for (var ship : dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getAllIntersecting(level, shipBox)) shipId = ship.getUniqueId();
        shipBox = null;
        if (shipId == null) {
            RelicsAddon.LOGGER.warn("World scenario: no ship was assembled");
            return;
        }
        Vec3 middle = stage.add(.5, 4, -7.5);
        command(level, String.format(Locale.ROOT, "sable teleport %s %.2f %.2f %.2f 25 8", shipId, middle.x, middle.y, middle.z));
        owner(level).teleportTo(level, stage.x, stage.y + 7, stage.z - 7, 180, 10);
    }

    /** A hive as if it had stood a while: owned by the player, its battery full, its shield or wings charged. */
    private static void prime(dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity hive, ServerPlayer player) {
        hive.claim(player);
        while (hive.energy().receiveEnergy(Integer.MAX_VALUE, false) > 0) {
            // A tick's worth of input at a time.
        }
        var tag = hive.saveCustomOnly(player.level().registryAccess());
        tag.getCompound("Module").putInt("Charge", dev.hurtify.relicsaddon.ship.AegisModule.FULL);
        tag.getCompound("Module").putIntArray("Charges", new int[]{dev.hurtify.relicsaddon.ship.EscortModule.FULL, dev.hurtify.relicsaddon.ship.EscortModule.FULL});
        hive.loadCustomOnly(tag, player.level().registryAccess());
    }

    /** Runs a command as the server, its answer in the log. */
    private static void command(ServerLevel level, String line) {
        var source = level.getServer().createCommandSourceStack().withLevel(level).withPosition(stage).withPermission(4);
        RelicsAddon.LOGGER.info("World scenario: /{}", line);
        level.getServer().getCommands().performPrefixedCommand(source, line);
    }

    private static void teardown(ServerLevel level) {
        if (scene >= 0 && scene < plan.size() && SABLE.contains(plan.get(scene).name())) {
            if (shipId != null) command(level, "sable remove " + shipId);
            shipId = null;
            command(level, "sable paused false");
        }
        if (wasSafe != null) {
            dev.hurtify.relicsaddon.AddonConfig.ARMAGEDDON_SAFE.set(wasSafe);
            wasSafe = null;
        }
        level.getServer().tickRateManager().setTickRate(20);
        shipDeck(level, false, 0);
        clear(level);
        for (net.minecraft.core.BlockPos at : BUILT) level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        BUILT.clear();
        backdrop(level, false);
        ServerPlayer player = owner(level);
        CuriosApi.getCuriosInventory(player).ifPresent(curios -> curios.getStacksHandler(RelicRole.EQUIPMENT_SLOT).ifPresent(handler -> {
            for (int slot = 0; slot < handler.getStacks().getSlots(); slot++) handler.getStacks().setStackInSlot(slot, ItemStack.EMPTY);
        }));
    }

    /**
     * Builds (or takes down) a wall of black and white squares 22 blocks west of the owner, 60 blocks long
     * and 30 high: the cameras of the black hole scenes look west past the hole onto it.
     */
    private static void backdrop(ServerLevel level, boolean build) {
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(origin.x - 22, origin.y, origin.z);
        for (int z = -48; z < 12; z++) for (int y = 0; y < 30; y++) {
            net.minecraft.core.BlockPos at = base.offset(0, y, z);
            var state = !build ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    : Math.floorMod(Math.floorDiv(z, 3) + Math.floorDiv(y, 3), 2) == 0
                    ? net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.BLACK_CONCRETE.defaultBlockState();
            if (level.getBlockState(at) != state) level.setBlock(at, state, 2);
        }
    }

    /** The blocks the ship deck replaced, put back when its scene is over. */
    private static final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> DECK = new java.util.HashMap<>();

    /**
     * An iron deck in front of the owner with the three generators, three docks and a chest on it (or takes it
     * down). The RF generator is switched on; the Mana generator beside it then refuses, which its console shows.
     */
    private static void shipDeck(ServerLevel level, boolean build, int drones) {
        if (!build) {
            DECK.forEach((pos, state) -> level.setBlock(pos, state, 2));
            DECK.clear();
            return;
        }
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(stage);
        var iron = net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 0; z++) {
            net.minecraft.core.BlockPos at = base.offset(x, -1, z);
            DECK.putIfAbsent(at, level.getBlockState(at));
            level.setBlock(at, iron, 2);
            net.minecraft.core.BlockPos above = base.offset(x, 0, z);
            DECK.putIfAbsent(above, level.getBlockState(above));
            level.setBlock(above, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        var families = dev.hurtify.relicsaddon.shipshield.ShipFamily.values();
        net.minecraft.world.level.block.Block[] row = {
                dev.hurtify.relicsaddon.registry.ShipBlocks.GENERATORS.get(families[0]).get(), dev.hurtify.relicsaddon.registry.ShipBlocks.DOCKS.get(families[0]).get(),
                net.minecraft.world.level.block.Blocks.CHEST,
                dev.hurtify.relicsaddon.registry.ShipBlocks.GENERATORS.get(families[1]).get(), dev.hurtify.relicsaddon.registry.ShipBlocks.DOCKS.get(families[1]).get(),
                dev.hurtify.relicsaddon.registry.ShipBlocks.GENERATORS.get(families[2]).get(), dev.hurtify.relicsaddon.registry.ShipBlocks.DOCKS.get(families[2]).get()};
        for (int index = 0; index < row.length; index++) {
            net.minecraft.core.BlockPos at = base.offset(index - 3, 0, -2);
            var state = row[index].defaultBlockState();
            if (state.hasProperty(dev.hurtify.relicsaddon.shipshield.ShipDeviceBlock.FACING)) state = state.setValue(dev.hurtify.relicsaddon.shipshield.ShipDeviceBlock.FACING, net.minecraft.core.Direction.SOUTH);
            level.setBlock(at, state, 2);
        }
        if (level.getBlockEntity(base.offset(-3, 0, -2)) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity rf) rf.setEnabled(null, true);
        if (level.getBlockEntity(base.offset(-2, 0, -2)) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity dock) {
            dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(families[0]).get(), drones));
        }
        String name = plan.get(scene).name();
        int selected = name.equals("ship-shell-mana") ? 1 : name.equals("ship-shell-twins") ? 2 : 0;
        if (selected > 0) {
            if (level.getBlockEntity(base.offset(-3, 0, -2)) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity rf) rf.setEnabled(null, false);
            int x = selected == 1 ? 0 : 2;
            if (level.getBlockEntity(base.offset(x + 1, 0, -2)) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity dock) dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(families[selected]).get(), drones));
            if (level.getBlockEntity(base.offset(x, 0, -2)) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity generator) generator.setEnabled(null, true);
        }
    }

    private static void strikeShipShield(ServerLevel level) {
        String name = plan.get(scene).name();
        if (name.equals("ship-shell-flight")) {
            var region = new dev.ryanhcode.sable.companion.math.BoundingBox3d(stage.x - 64, stage.y - 32, stage.z - 64, stage.x + 64, stage.y + 32, stage.z + 64);
            for (var ship : dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getAllIntersecting(level, region)) {
                if (!ship.getUniqueId().equals(shipId)) continue;
                Vec3 plot = ship.logicalPose().transformPositionInverse(ship.boundingBox().toMojang().getCenter());
                var structure = dev.hurtify.relicsaddon.shipshield.ShipStructures.locate(level, net.minecraft.core.BlockPos.containing(plot));
                for (var pos : structure.blockEntities()) if (level.getBlockEntity(pos) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity generator
                        && generator.shield() != null && generator.shield().outer() != null && generator.shield().heldSeats() > 0) {
                    Vec3 aim = ship.logicalPose().transformPositionInverse(stage.add(4, 8, -4));
                    var mesh = generator.shield().outer();
                    generator.shield().hit(mesh.vertex(mesh.nearestVertex(aim.x, aim.y, aim.z)), 12, level.getGameTime());
                    RelicsAddon.LOGGER.info("Ship shield capture: {} blocks, {} emitters aboard {}", structure.size(), generator.shield().heldSeats(), shipId);
                }
            }
            return;
        }
        int x = name.equals("ship-shell-mana") ? 0 : name.equals("ship-shell-twins") ? 2 : -3;
        if (level.getBlockEntity(net.minecraft.core.BlockPos.containing(stage).offset(x, 0, -2)) instanceof dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity generator
                && generator.shield().outer() != null && generator.shield().heldSeats() > 0) {
            var mesh = generator.shield().outer();
            int vertex = mesh.nearestVertex(stage.x + 4, stage.y + 3, stage.z + 1);
            generator.shield().hit(mesh.vertex(vertex), 12, level.getGameTime());
        }
    }

    /** Fills a box of stone round the stage (from its feet: x, then height, then z), for a shot to hit a ceiling or a wall. */
    private static void build(ServerLevel level, int x0, int x1, int y0, int y1, int z0, int z1) {
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(stage);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) level.setBlock(base.offset(x, y, z), stone, 2);
    }

    /** Removes every creature, arrow and item round the stage but the owner. */
    private static void clear(ServerLevel level) {
        Vec3 middle = stage != null ? stage : origin;
        if (middle == null) return;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(middle, middle).inflate(64), entity -> !(entity instanceof Player))) {
            entity.discard();
        }
        FOES.clear();
    }

    private WorldScenarios() {
    }
}
