package dev.hurtify.relicsaddon.gametest.client;

import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.GlowBrush;
import dev.hurtify.relicsaddon.client.HiveModeVisual;
import dev.hurtify.relicsaddon.client.HiveVisualRenderer;
import dev.hurtify.relicsaddon.client.ShieldGlow;
import dev.hurtify.relicsaddon.client.ShieldVisualRenderer;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.server.HiveContainment;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Explicit development capture only; never replaces the normal title screen. Every hive family in
 * every attack mode with a full deployment of 250 drones: droplet, then barrage, then containment,
 * each flown out from the owner (left) to a target (the outlined figure).
 */
final class NativeHiveGallery extends Screen {
    private static final AttackMode[] STAGES = {AttackMode.DROPLET, AttackMode.BARRAGE, AttackMode.CONTAINMENT};
    private static final long STAGE_MS = 6400;
    private static final int SLOTS = HiveType.MAX_DEPLOYED, UNITS = HiveType.MAX_DRONES, TRAVEL = 20, INTERVAL = 40;
    private static final boolean GIF = Boolean.getBoolean("relics_addon.hiveGif");
    private static final int GIF_FRAMES = 300;
    private final ItemStack[] items = {new ItemStack(ModItems.RF_HIVE.get()), new ItemStack(ModItems.MANA_HIVE.get()),
            new ItemStack(ModItems.TWINS_HIVE.get())};
    private final long start = Util.getMillis();
    private int captures, frames, gifFrame;
    private boolean gifPending;
    private long renderNanos;

    NativeHiveGallery() { super(Component.literal("EX-twins / Hives and swarms")); }

    private long age() {
        return GIF ? gifFrame * 64L : Util.getMillis() - start;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long begin = System.nanoTime();
        graphics.fill(0, 0, width, height, 0xFF202226);
        long age = age();
        int stage = (int) Math.min(STAGES.length - 1, age / STAGE_MS);
        AttackMode mode = STAGES[stage];
        double time = age / 50.0, combatStart = stage * STAGE_MS / 50.0;
        graphics.drawString(font, title.getString() + " / " + mode.id() + " / " + SLOTS + " drones out of " + UNITS, 10, 8, 0xFFF1F4F8, false);
        int cw = width / 3;
        for (int family = 0; family < 3; family++) {
            HiveType type = HiveType.values()[family];
            int x = cw * family;
            graphics.drawString(font, items[family].getHoverName(), x + 34, 28, 0xFFD5DBE2, false);
            graphics.pose().pushPose();
            graphics.pose().translate(x + 10, 22, 0);
            graphics.renderItem(items[family], 0, 0);
            graphics.pose().popPose();
            renderSwarm(graphics, type, mode, time, combatStart, x + cw / 2.0, cw);
        }
        graphics.flush();
        renderNanos += System.nanoTime() - begin;
        frames++;
    }

    private void renderSwarm(GuiGraphics graphics, HiveType type, AttackMode mode, double time, double combatStart, double centreX, int cw) {
        boolean contain = mode == AttackMode.CONTAINMENT, droplet = mode == AttackMode.DROPLET;
        // Droplet figures form up in a fan behind their owner: that view is turned so the fan opens out
        // across the frame and the figures fly off to the right, and shifted to take in both ends.
        double reach = contain ? 12 : droplet ? 9 : 8, shift = droplet ? 1.9 : 0;
        float turn = droplet ? .65F : 0F;
        double scale = Math.min((cw - 16) / (reach * 2), (height - 60) / (reach * 1.9));
        Vec3 target = droplet ? new Vec3(7.5, 0, 0) : Vec3.ZERO;
        if (contain) {
            // Twins lift their target into the rifts, RF into the middle of their rings.
            double lift = Math.clamp((time - combatStart - TRAVEL * .5) / 30, 0, 1);
            double height = switch (type) {
                case TWINS -> dev.hurtify.relicsaddon.drone.HiveFormation.twinsLift(.6, 1.8);
                case RF -> dev.hurtify.relicsaddon.drone.HiveFormation.ringLift(.6, 1.8, false);
                case MANA -> dev.hurtify.relicsaddon.drone.HiveFormation.wardLift(.6, 1.8);
            };
            target = target.add(0, height * lift * lift * (3 - 2 * lift), 0);
        }
        Vec3 owner = droplet ? new Vec3(-1.5, 0, 0) : new Vec3(contain ? -4.5 : -8, 0, 1.5);
        double cycleStart = combatStart + TRAVEL;
        int groups = HiveSlots.groups(SLOTS, mode, type);
        int[] members = new int[groups];
        Vec3[] drones = new Vec3[SLOTS];
        Vec3 core = HiveFormation.core(target, 1.8);

        graphics.flush();
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX + shift * scale, 60 + (height - 60) * (contain ? .55 : droplet ? .8 : .62), 150);
        pose.scale((float) scale, (float) -scale, (float) scale);
        pose.mulPose(Axis.XP.rotation(.30F));
        pose.mulPose(Axis.YP.rotation(turn));
        // Towards the viewer, in world space: the inverse of the two turns applied to +z.
        GlowBrush.setFlatView(new Vec3(-Math.cos(.30) * Math.sin(turn), Math.sin(.30), Math.cos(.30) * Math.cos(turn)));
        try {
            for (int slot = 0; slot < SLOTS; slot++) {
                Vec3 station = HiveFormation.station(mode, type, slot, SLOTS, owner, target, .6, 1.8, time, cycleStart, INTERVAL);
                Vec3 at = HiveFormation.deployed(owner, -90, station, slot, UNITS, type, time, combatStart, TRAVEL);
                drones[slot] = at;
                members[HiveSlots.group(slot, groups)]++;
                pose.pushPose();
                pose.translate(at.x, at.y, at.z);
                pose.scale(.2F, .2F, .2F);
                Vec3 facing = core.subtract(at);
                pose.mulPose(Axis.YP.rotation((float) Math.atan2(-facing.x, -facing.z)));
                pose.translate(-.5, -.5, -.5);
                HiveVisualRenderer.renderModel(type, pose, graphics.bufferSource(), slot == 0, true);
                pose.popPose();
            }
            graphics.flush();
            var glow = ShieldGlow.consumer();
            var fill = graphics.bufferSource().getBuffer(ShieldVisualRenderer.renderType());
            var matrix = pose.last().pose();
            // The target: a zombie-sized outline; for droplet the owner too, in the hive's colour.
            outline(glow, matrix, target, 0xC8CED6);
            if (droplet) outline(glow, matrix, owner, HiveModeVisual.color(type));
            HiveModeVisual.render(new HiveModeVisual.Scene(mode, type, SLOTS, groups, members, drones, owner,
                    java.util.List.of(new dev.hurtify.relicsaddon.drone.HiveTarget(-1, target, .6, 1.8)), time, cycleStart,
                    INTERVAL, time >= combatStart + TRAVEL * .5, null, groups), Vec3.ZERO, glow, fill, matrix);
            graphics.bufferSource().endBatch(ShieldVisualRenderer.renderType());
            ShieldGlow.flush();
        } finally {
            GlowBrush.setFlatView(null);
            pose.popPose();
        }
    }

    private static void outline(com.mojang.blaze3d.vertex.VertexConsumer glow, org.joml.Matrix4f matrix, Vec3 at, int color) {
        double[][] box = {{-.3, 0, -.3}, {.3, 0, -.3}, {.3, 0, .3}, {-.3, 0, .3}};
        for (int corner = 0; corner < 4; corner++) {
            Vec3 a = at.add(box[corner][0], 0, box[corner][2]), b = at.add(box[(corner + 1) % 4][0], 0, box[(corner + 1) % 4][2]);
            GlowBrush.line(glow, matrix, a, b, .03, color, 150);
            GlowBrush.line(glow, matrix, a.add(0, 1.8, 0), b.add(0, 1.8, 0), .03, color, 150);
            GlowBrush.line(glow, matrix, a, a.add(0, 1.8, 0), .03, color, 150);
        }
    }

    void capture(Minecraft minecraft) {
        long age = age();
        if (GIF) {
            if (Util.getMillis() - start < 1800 || gifPending || gifFrame >= GIF_FRAMES) return;
            gifPending = true;
            Screenshot.grab(minecraft.gameDirectory, String.format(Locale.ROOT, "relics-hive-gif-%03d.png", gifFrame),
                    minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> {
                        gifFrame++;
                        gifPending = false;
                        if (gifFrame >= GIF_FRAMES) {
                            RelicsAddon.LOGGER.info("Hive GIF evidence: {} native frames captured", GIF_FRAMES);
                            minecraft.stop();
                        }
                    }));
            return;
        }
        // Mid-flight out, then each mode formed and working.
        long[] at = {900, STAGE_MS - 500, 2 * STAGE_MS - 500, 3 * STAGE_MS - 400};
        if (captures < at.length && age > at[captures]) {
            int frame = ++captures;
            String name = frame == 1 ? "deploy" : STAGES[frame - 2].id();
            Screenshot.grab(minecraft.gameDirectory, "relics-hive-" + name + ".png", minecraft.getMainRenderTarget(), message -> {
                RelicsAddon.LOGGER.info("Hive evidence: {}; {} drones per family, mean gallery render submission {} ms across {} frames",
                        message.getString(), SLOTS, renderNanos / 1_000_000.0 / Math.max(1, frames), frames);
                if (frame == at.length) minecraft.execute(minecraft::stop);
            });
        }
    }
}
