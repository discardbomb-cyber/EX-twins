package dev.hurtify.relicsaddon.gametest.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.workbench.RfWorkbenchEffects;
import dev.hurtify.relicsaddon.client.workbench.RfWorkbenchModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Development-only scene: no real blocks, inventory changes or server packets. Excluded from release JARs. */
@EventBusSubscriber(modid = RelicsAddon.MOD_ID, value = Dist.CLIENT)
public final class RfWorkbenchPreview {
    private static final String[] CLIPS = {"uncharged", "charged_closed", "player_approach", "charged_open",
            "crafting_start", "crafting", "crafting_end", "charged_open", "player_leave", "charged_closed"};
    private static final float[] DURATIONS = {1.5f, .6f, 1.6f, 1, 1.2f, 4, 1.2f, .8f, 1.6f, .5f};
    private static final RfWorkbenchEffects EFFECTS = new RfWorkbenchEffects();
    private static Vec3 origin;
    private static Level level;
    private static long started;
    private static String mode = "cycle";
    private static boolean warned;
    private static boolean setupRequested, setupComplete, captureStarted, pending, finished, stopQueued;
    private static int warmTicks, frames, maxLive;
    private static long lastCaptureTick = Long.MIN_VALUE;

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal("rfworkbench_preview").executes(ctx -> start("cycle"));
        for (String clip : new String[]{"cycle", "uncharged", "charged_closed", "charged_open", "crafting"}) {
            root.then(LiteralArgumentBuilder.<CommandSourceStack>literal(clip).executes(ctx -> start(clip)));
        }
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("stop").executes(ctx -> { clear(); return 1; }));
        event.getDispatcher().register(root);
    }

    private static int start(String requested) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;
        var hit = mc.player.pick(8, 0, false);
        if (origin == null || mc.level != level) {
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
                var pos = block.getBlockPos(); origin = new Vec3(pos.getX() + 1, pos.getY() + 1.01, pos.getZ() + 1);
            } else {
                var look = mc.player.getLookAngle();
                origin = mc.player.position().add(look.x * 3, 0, look.z * 3);
            }
        }
        level = mc.level; mode = requested; started = mc.level.getGameTime(); warned = false; EFFECTS.clear();
        mc.player.displayClientMessage(Component.translatable("message.relics_addon.rf_workbench_preview", requested), false);
        return 1;
    }

    private record Scene(String clip, float seconds) { }
    private static Scene scene(float seconds) {
        if (!"cycle".equals(mode)) return new Scene(mode, seconds);
        float length = 0; for (float duration : DURATIONS) length += duration;
        float at = Math.max(0, seconds) % length;
        for (int i = 0; i < CLIPS.length; i++) {
            if (at < DURATIONS[i]) return new Scene(CLIPS[i], at);
            at -= DURATIONS[i];
        }
        return new Scene("uncharged", 0);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (Boolean.getBoolean("relics_addon.rfWorkbenchCapture")) nativeCaptureTick(mc);
        if (origin == null) return;
        if (mc.level != level || mc.player == null) { clear(); return; }
        if (mc.isPaused()) return;
        try {
            var model = RfWorkbenchModel.get();
            var scene = scene((level.getGameTime() - started) / 20f);
            EFFECTS.tick(level, origin, model, model.pose(scene.clip, scene.seconds));
            maxLive = Math.max(maxLive, dev.hurtify.relicsaddon.client.fx.ExFx.liveCount());
        } catch (Exception | LinkageError error) { fail(error); }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        boolean opaque = event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES;
        boolean glass = event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if ((!opaque && !glass) || origin == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level != level) { clear(); return; }
        try {
            var model = RfWorkbenchModel.get();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            var scene = scene((level.getGameTime() - started + partial) / 20f);
            var pose = model.pose(scene.clip, scene.seconds);
            if (opaque) model.renderOpaque(pose, event.getPoseStack(), origin, event.getCamera().getPosition());
            else model.renderGlass(pose, event.getPoseStack(), origin, event.getCamera().getPosition());
        } catch (Exception | LinkageError error) { fail(error); }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    private static void nativeCaptureTick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || mc.screen != null || finished) return;
        if (!setupRequested) {
            setupRequested = true;
            mc.getWindow().setWindowed(1024, 768);
            mc.options.hideGui = true;
            mc.options.fov().set(35);
            mc.options.renderDistance().set(4);
            mc.options.simulationDistance().set(5);
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(60);
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var world = server.overworld();
                world.setDayTime(6000);
                world.setWeatherParameters(6000, 0, false, false);
                world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    world.setBlockAndUpdate(new BlockPos(x, 100, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                }
                for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) {
                    world.setBlockAndUpdate(new BlockPos(x, 100, z), Blocks.SMOOTH_STONE.defaultBlockState());
                }
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (player == null) return;
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(world, 3.6, 101.6, 4.6, 142f, 15f);
                mc.execute(() -> setupComplete = true);
            });
            return;
        }
        if (!setupComplete || ++warmTicks < 60) return;
        if (!captureStarted) {
            captureStarted = true; level = mc.level; origin = new Vec3(0, 101.01, 0);
            mode = "cycle"; started = level.getGameTime(); EFFECTS.clear();
            RelicsAddon.LOGGER.info("RF_WORKBENCH_CAPTURE_START V4 2x2 blocks; native Photon; core/frame lightning; clips=8");
        }
        // Fixed camera framing is applied only to this opt-in, isolated capture world.
        mc.player.setYRot(142f); mc.player.setXRot(15f);
        if (level.getGameTime() - started >= 280) finished = true;
    }

    @SubscribeEvent
    public static void capture(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("relics_addon.rfWorkbenchCapture") || !captureStarted || pending) return;
        var mc = Minecraft.getInstance();
        if (finished) {
            if (!stopQueued) {
                stopQueued = true;
                RelicsAddon.LOGGER.info("RF_WORKBENCH_CAPTURE_DONE {} frames; PhotonMaxLive={}", frames, maxLive);
                mc.execute(mc::stop);
            }
            return;
        }
        if (mc.level == null || mc.level.getGameTime() == lastCaptureTick) return;
        lastCaptureTick = mc.level.getGameTime(); pending = true;
        long age = lastCaptureTick - started;
        var directory = new java.io.File("D:/ex-twins-captures/rf-workbench-native-v4");
        Screenshot.grab(directory, String.format(java.util.Locale.ROOT, "rf-workbench-%04d.png", age),
                mc.getMainRenderTarget(), message -> mc.execute(() -> { frames++; pending = false; }));
    }

    private static void clear() { origin = null; level = null; EFFECTS.clear(); }
    private static void fail(Throwable error) {
        if (!warned) { warned = true; RelicsAddon.LOGGER.error("RF workbench preview failed", error); }
        clear();
    }

    private RfWorkbenchPreview() { }
}
