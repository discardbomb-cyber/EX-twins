package dev.hurtify.relicsaddon.gametest.client;

import com.mojang.math.Axis;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.HiveVisualRenderer;
import dev.hurtify.relicsaddon.client.HiveCombatVisual;
import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModItems;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Explicit development capture only. Never replaces the normal title screen. */
final class NativeHiveGallery extends Screen {
    private final ItemStack[] items = {new ItemStack(ModItems.RF_HIVE.get()), new ItemStack(ModItems.MANA_HIVE.get()),
            new ItemStack(ModItems.TWINS_HIVE.get())};
    private final long start = Util.getMillis();
    private int captures, frames;
    private long renderNanos;
    private static final boolean GIF = Boolean.getBoolean("relics_addon.hiveGif");
    private int gifFrame;
    private boolean gifPending;

    NativeHiveGallery() { super(Component.literal("EX-twins / Hives and swarms")); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long begin = System.nanoTime();
        graphics.fill(0, 0, width, height, 0xFF202226);
        graphics.drawString(font, title, 10, 8, 0xFFF1F4F8, false);
        int cw = width / 3;
        long age = GIF ? gifFrame * 100L : Util.getMillis() - start;
        double time = age / 50.0;
        boolean transit = age >= 5000 && age < 7500;
        boolean combat = age >= 7500;
        double progress = combat ? 1 : transit ? Math.clamp((age - 5000) / 2500.0, 0, 1) : 0;
        for (int family = 0; family < 3; family++) {
            int x = cw * family;
            graphics.drawString(font, items[family].getHoverName(), x + 9, 26, 0xFFD5DBE2, false);
            // On the 1920px evidence viewport this gives each top-row hive a
            // roughly 480px footprint, so surface detail is reviewable.
            float detailFootprint = Math.min(cw - 60F, height * .46F - 28F);
            float scale = detailFootprint / 12F;
            graphics.pose().pushPose();
            graphics.pose().translate(x + cw / 2F - 8 * scale, height * .29F - 8 * scale, 0);
            graphics.pose().scale(scale, scale, scale);
            graphics.renderItem(items[family], 0, 0);
            graphics.pose().popPose();
            graphics.drawString(font, (combat ? "combat formation / " : transit ? "droplet flight / " : "idle formation / ")
                    + HiveType.MAX_DRONES, x + 9, height / 2, 0xFFD5DBE2, false);
            var owner = new net.minecraft.world.phys.Vec3(transit ? -2 : 0, 0, 0);
            var target = new net.minecraft.world.phys.Vec3(0, 0, 0);
            var points = new java.util.ArrayList<net.minecraft.world.phys.Vec3>();
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < HiveType.MAX_DRONES; index++) {
                var point = HiveFormation.position(owner, 0, target, 1.0, 1.8, index, HiveType.MAX_DRONES, HiveType.values()[family], time, progress);
                points.add(point);
                double projectedY = point.y * Math.cos(.30) - point.z * Math.sin(.30);
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, projectedY); maxY = Math.max(maxY, projectedY);
            }
            double top = height / 2.0 + 24, bottom = height - 18;
            double formationScale = Math.min((cw - 36) / (maxX - minX + .8), (bottom - top) / (maxY - minY + .8));
            graphics.flush();
            graphics.pose().pushPose();
            graphics.pose().translate(x + cw / 2.0 - (minX + maxX) * .5 * formationScale,
                    (top + bottom) * .5 + (minY + maxY) * .5 * formationScale, 150);
            graphics.pose().scale((float) formationScale, (float) -formationScale, (float) formationScale);
            graphics.pose().mulPose(Axis.XP.rotation(.30F));
            for (int index = 0; index < HiveType.MAX_DRONES; index++) {
                var point = points.get(index);
                graphics.pose().pushPose();
                graphics.pose().translate(point.x, point.y, point.z);
                graphics.pose().scale(.20F, .20F, .20F);
                graphics.pose().mulPose(Axis.YP.rotationDegrees((float) (time * 2 + index * 137.5)));
                graphics.pose().translate(-.5, -.5, -.5);
                HiveVisualRenderer.renderModel(HiveType.values()[family], graphics.pose(), graphics.bufferSource(), index == 0, true);
                graphics.pose().popPose();
            }
            if (combat) HiveCombatVisual.renderFormation(HiveType.values()[family], points, target.add(0, .99, 0),
                    net.minecraft.world.phys.Vec3.ZERO, graphics.bufferSource(), graphics.pose().last().pose(), time);
            if (transit) HiveCombatVisual.renderTravel(HiveType.values()[family], owner, 0, target, 1.8, progress,
                    net.minecraft.world.phys.Vec3.ZERO, graphics.bufferSource(), graphics.pose().last().pose(), time);
            graphics.flush();
            graphics.pose().popPose();
        }
        graphics.flush();
        renderNanos += System.nanoTime() - begin;
        frames++;
    }

    void capture(Minecraft minecraft) {
        long age = Util.getMillis() - start;
        if (GIF) {
            if (age < 1800 || gifPending || gifFrame >= 120) return;
            gifPending = true;
            Screenshot.grab(minecraft.gameDirectory, String.format(java.util.Locale.ROOT, "relics-hive-gif-%03d.png", gifFrame),
                    minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> {
                        gifFrame++;
                        gifPending = false;
                        if (gifFrame >= 120) {
                            RelicsAddon.LOGGER.info("Hive GIF evidence: 120 native frames captured");
                            minecraft.stop();
                        }
                    }));
            return;
        }
        if (captures == 0 && age > 4000 || captures == 1 && age > 6500 || captures == 2 && age > 9500) {
            captures++;
            int frame = captures;
            Screenshot.grab(minecraft.gameDirectory, "relics-hive-formations-" + frame + ".png", minecraft.getMainRenderTarget(), message -> {
                RelicsAddon.LOGGER.info("Hive evidence: {}; {} models, mean gallery render submission {} ms across {} frames",
                        message.getString(), HiveType.MAX_DRONES * 3, renderNanos / 1_000_000.0 / Math.max(1, frames), frames);
                if (frame == 3) minecraft.execute(minecraft::stop);
            });
        }
    }
}
