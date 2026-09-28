package dev.hurtify.relicsaddon.gametest.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModItems;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opt-in development screen; excluded from the production jar with the rest of gametest. */
@EventBusSubscriber(modid = RelicsAddon.MOD_ID, value = Dist.CLIENT)
public final class NativeModelSmoke {
    private static boolean opened;
    private static int menuTicks;
    private static boolean menuCaptured;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if ((Boolean.getBoolean("relics_addon.startupSmoke") || Boolean.getBoolean("relics_addon.interactiveSmoke")) && minecraft.screen instanceof TitleScreen
                && minecraft.getOverlay() == null) menuTicks++;
        if (Boolean.getBoolean("relics_addon.captureAndExit") && Boolean.getBoolean("relics_addon.visualSmoke")
                && !opened && minecraft.screen instanceof TitleScreen
                && minecraft.getOverlay() == null) {
            opened = true;
            minecraft.getWindow().setWindowed(Boolean.getBoolean("relics_addon.hiveGif") ? 1280 : 1920,
                    Boolean.getBoolean("relics_addon.hiveGif") ? 720 : 1080);
            minecraft.setScreen(Boolean.getBoolean("relics_addon.hiveSmoke") ? new NativeHiveGallery()
                    : Boolean.getBoolean("relics_addon.researchSmoke") ? new NativeResearchGallery()
                    : Boolean.getBoolean("relics_addon.shieldSmoke") ? new NativeShieldGallery() : new Gallery());
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (menuTicks >= 40 && !menuCaptured && minecraft.screen instanceof TitleScreen) {
            menuCaptured = true;
            Screenshot.grab(minecraft.gameDirectory, "relics-normal-startup.png", minecraft.getMainRenderTarget(), message -> {
                RelicsAddon.LOGGER.info("Normal startup: TitleScreen, no preview; {}", message.getString());
                if (Boolean.getBoolean("relics_addon.startupSmoke")) minecraft.execute(minecraft::stop);
            });
        }
        if (minecraft.screen instanceof Gallery gallery) {
            gallery.capture(minecraft);
        }
        if (minecraft.screen instanceof NativeShieldGallery gallery) gallery.capture(minecraft);
        if (minecraft.screen instanceof NativeResearchGallery gallery) gallery.capture(minecraft);
        if (minecraft.screen instanceof NativeHiveGallery gallery) gallery.capture(minecraft);
    }

    private static final class Gallery extends Screen {
        private long stableSince = Util.getMillis();
        private int captureWidth;
        private int captureHeight;
        private int captures;
        private final ItemStack[] stacks = {new ItemStack(ModItems.RF_SHIELD.get()), new ItemStack(ModItems.MANA_SHIELD.get()),
                new ItemStack(ModItems.TWINS_SHIELD.get())};

        private Gallery() {
            super(Component.literal("Relics / Native Model Test"));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF202226);
            graphics.drawString(font, title, 12, 10, 0xFFF1F4F8, false);
            int cellWidth = width / 3;
            int cellHeight = height - 34;
            float scale = Math.min((cellWidth - 28) / 15.0F, (cellHeight - 36) / 18.0F);
            for (int index = 0; index < stacks.length; index++) {
                int x = (index % 3) * cellWidth;
                int y = 30 + (index / 3) * cellHeight;
                graphics.fill(x + 10, y, x + cellWidth - 10, y + 1, 0xFF45484F);
                graphics.drawString(font, stacks[index].getHoverName(), x + 12, y + 7, 0xFFD5DBE2, false);
                graphics.pose().pushPose();
                graphics.pose().translate(x + cellWidth / 2.0F - 8 * scale, y + 24 + (cellHeight - 26) / 2.0F - 8 * scale, 0);
                graphics.pose().scale(scale, scale, scale);
                graphics.renderItem(stacks[index], 0, 0);
                graphics.pose().popPose();
            }
        }

        private void capture(Minecraft minecraft) {
            int currentWidth = minecraft.getMainRenderTarget().width;
            int currentHeight = minecraft.getMainRenderTarget().height;
            if (captureWidth != currentWidth || captureHeight != currentHeight) {
                captureWidth = currentWidth;
                captureHeight = currentHeight;
                stableSince = Util.getMillis();
                captures = 0;
            }
            long age = Util.getMillis() - stableSince;
            if ((captures == 0 && age >= 2500) || (captures == 1 && age >= 8500)) {
                captures++;
                int frame = captures;
                String name = "relics-native-models-" + captureWidth + "x" + captureHeight + "-" + captures + ".png";
                Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                        message -> {
                            RelicsAddon.LOGGER.info("Native visual evidence: {}", message.getString());
                            if (frame == 2 && Boolean.getBoolean("relics_addon.captureAndExit")) minecraft.execute(minecraft::stop);
                        });
            }
        }

        @Override
        public void onClose() {
            minecraft.setScreen(new TitleScreen());
        }
    }

    private NativeModelSmoke() {
    }
}
