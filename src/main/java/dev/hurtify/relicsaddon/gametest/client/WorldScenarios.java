package dev.hurtify.relicsaddon.gametest.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveSettings;
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

    /** One scene: the devices worn, the attackers (relative to the owner), the camera, and how long to film it. */
    private record Scene(String name, RelicRole hive, int hiveLevel, AttackMode mode, RelicRole shield, List<Vec3> foes, boolean foesFight,
                         Vec3 camera, Vec3 look, int warmTicks, int frames, int killFirstAtFrame, String foeType) {
        Scene(String name, RelicRole hive, int hiveLevel, AttackMode mode, RelicRole shield, List<Vec3> foes, boolean foesFight,
              Vec3 camera, Vec3 look, int warmTicks, int frames, int killFirstAtFrame) {
            this(name, hive, hiveLevel, mode, shield, foes, foesFight, camera, look, warmTicks, frames, killFirstAtFrame, "minecraft:husk");
        }
    }

    private static final List<Scene> SCENES = List.of(
            new Scene("droplet-multi", RelicRole.RF_HIVE, 10, AttackMode.DROPLET, null,
                    List.of(new Vec3(-3, 0, -9), new Vec3(2, 0, -11), new Vec3(5, 0, -8), new Vec3(-7, 0, -12)), false,
                    new Vec3(15, 8, -1), new Vec3(0, 3, -5), 60, 90, -1),
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
            // A slower, level 3 hive keeps its figures in the fan longer, close to the camera.
            new Scene("drone-closeup", RelicRole.RF_HIVE, 3, AttackMode.DROPLET, null,
                    List.of(new Vec3(0, 0, -26)), false, new Vec3(2.5, 3.6, -2.2), new Vec3(0, 3.8, 3), 60, 50, -1));

    /** Scenes filmed against a chequered wall behind the black hole, so that its lens shows. */
    private static final java.util.Set<String> BACKDROP = java.util.Set.of("containment-twins", "containment-twins-close", "containment-twins-golem");

    private enum Phase { WAIT_WORLD, SETUP, WARM, CAPTURE, TEARDOWN, DONE }

    private static Phase phase = Phase.WAIT_WORLD;
    private static int ticks, scene = -1, frame, pendingGrabs;
    private static boolean due;
    private static List<Scene> plan;
    private static final AtomicInteger CAMERA = new AtomicInteger(-1);
    private static final List<Integer> FOES = new ArrayList<>();
    private static Vec3 origin;

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
                next(minecraft);
            }
            case SETUP -> { }
            case WARM -> {
                attachCamera(minecraft);
                if (++ticks >= plan.get(scene).warmTicks()) {
                    phase = Phase.CAPTURE;
                    ticks = 0;
                    frame = 0;
                }
            }
            case CAPTURE -> {
                attachCamera(minecraft);
                if (++ticks % 2 == 0) due = true;
            }
            case TEARDOWN -> {
                if (++ticks >= 10) next(minecraft);
            }
            case DONE -> { }
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (!enabled() || phase != Phase.CAPTURE || !due || pendingGrabs > 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        due = false;
        Scene current = plan.get(scene);
        int index = frame++;
        pendingGrabs++;
        Screenshot.grab(minecraft.gameDirectory, String.format(Locale.ROOT, "scenario-%s-%03d.png", current.name(), index),
                minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> pendingGrabs--));
        if (index == current.killFirstAtFrame()) onServer(minecraft, level -> {
            if (!FOES.isEmpty() && level.getEntity(FOES.getFirst()) instanceof net.minecraft.world.entity.LivingEntity foe) foe.kill();
        });
        if (frame >= current.frames()) {
            RelicsAddon.LOGGER.info("World scenario {}: {} frames", current.name(), frame);
            onServer(minecraft, WorldScenarios::teardown);
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
        if (camera != null && minecraft.getCameraEntity() != camera) {
            minecraft.setCameraEntity(camera);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }
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
        ServerPlayer player = owner(level);
        if (origin == null) {
            int x = player.getBlockX(), z = player.getBlockZ();
            origin = new Vec3(x + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + .5);
        }
        clear(level);
        backdrop(level, BACKDROP.contains(scene.name()));
        level.setDayTime(11_500);
        level.setWeatherParameters(6000, 0, false, false);
        player.setGameMode(GameType.SURVIVAL);
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 4, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        player.setHealth(player.getMaxHealth());
        // The owner faces north, towards the attackers.
        player.teleportTo(level, origin.x, origin.y, origin.z, 180, 0);
        var curios = CuriosApi.getCuriosInventory(player).orElseThrow();
        var charms = curios.getStacksHandler(RelicRole.EQUIPMENT_SLOT).orElseThrow().getStacks();
        for (int slot = 0; slot < charms.getSlots(); slot++) charms.setStackInSlot(slot, ItemStack.EMPTY);
        curios.setEquippedCurio(RelicRole.EQUIPMENT_SLOT, 0, device(scene.hive(), scene.hiveLevel(), scene.mode()));
        if (scene.shield() != null) curios.setEquippedCurio(RelicRole.EQUIPMENT_SLOT, 1, device(scene.shield(), 0, null));

        FOES.clear();
        for (Vec3 offset : scene.foes()) {
            net.minecraft.world.entity.Mob husk = scene.foesFight() ? EntityType.PILLAGER.create(level)
                    : EntityType.byString(scene.foeType()).map(type -> type.create(level)).orElse(null) instanceof net.minecraft.world.entity.Mob mob ? mob : null;
            if (husk == null) continue;
            if (scene.foesFight()) husk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.CROSSBOW));
            Vec3 at = origin.add(offset);
            husk.moveTo(at.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z, 0, 0);
            husk.setPersistenceRequired();
            husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(5000);
            husk.setHealth(husk.getMaxHealth());
            husk.setNoAi(!scene.foesFight());
            if (scene.foesFight()) husk.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MobEffectInstance.INFINITE_DURATION, 6, false, false));
            level.addFreshEntity(husk);
            husk.setTarget(player);
            FOES.add(husk.getId());
        }

        ArmorStand camera = EntityType.ARMOR_STAND.create(level);
        if (camera != null) {
            Vec3 eye = origin.add(scene.camera()), look = origin.add(scene.look()).subtract(eye);
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
        if (mode != null) stack.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, mode));
        return stack;
    }

    private static void teardown(ServerLevel level) {
        clear(level);
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

    /** Removes every creature, arrow and item round the stage but the owner. */
    private static void clear(ServerLevel level) {
        if (origin == null) return;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(origin, origin).inflate(64), entity -> !(entity instanceof Player))) {
            entity.discard();
        }
        FOES.clear();
    }

    private WorldScenarios() {
    }
}
