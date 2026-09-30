package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.network.ArmageddonPayloads;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.server.ArmageddonController;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * "Activate Armageddon?": the last word before the Twins hive fires its ultimate at the point its owner
 * was looking at when they pressed the key. It shows how far off that is, what the blast will reach and
 * that it empties the hive, and fires only on an explicit yes (the button or Enter).
 */
public final class ArmageddonScreen extends Screen {
    private static final int WIDTH = 262, HEIGHT = 158, CUT = 10;
    private static final int ACCENT = RelicRole.TWINS_HIVE.color();
    private final Vec3 target;
    private final double distance;
    private final int shieldPercent;
    private int left, top;

    private ArmageddonScreen(Vec3 target, double distance, int shieldPercent) {
        super(Component.translatable("screen.relics_addon.armageddon.title"));
        this.target = target;
        this.distance = distance;
        this.shieldPercent = shieldPercent;
    }

    /** The key was pressed: offers the shot if the hive can fire, or says on the action bar why not. */
    static void request() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) return;
        ItemStack hive = ArmageddonController.twinsHive(player);
        String reason = hive.isEmpty() ? "message.relics_addon.armageddon.no_hive" : ArmageddonController.unavailable(player, hive, minecraft.level.getGameTime());
        if (reason != null) {
            player.displayClientMessage(Component.translatable(reason).withStyle(ChatFormatting.LIGHT_PURPLE), true);
            return;
        }
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        HitResult hit = player.pick(Armageddon.REACH, partial, false);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 target = hit.getType() == HitResult.Type.MISS ? eye.add(player.getViewVector(partial).scale(Armageddon.REACH)) : hit.getLocation();
        minecraft.setScreen(new ArmageddonScreen(target, eye.distanceTo(target), shieldPercent(player, hive)));
    }

    /** What a worn Twins shield would hand over, as a share of the hive's battery; 0 without one. */
    private static int shieldPercent(Player player, ItemStack hive) {
        ItemStack shield = ArmageddonController.twinsShield(player);
        if (shield.isEmpty()) return 0;
        return (int) Math.round(100.0 * ArmageddonController.shieldGives(player, shield, hive) / (2.0 * DevicePower.capacity(hive)));
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        HoloPaint.window(g, left, top, WIDTH, HEIGHT, CUT);
        long now = minecraft == null || minecraft.level == null ? 0 : minecraft.level.getGameTime();
        double pulse = .5 + .5 * Math.sin((now + partialTick) * .18);
        // A band of violet under the title that breathes, like the hive's core charging.
        g.fill(left + 14, top + 13, left + WIDTH - 14, top + 31, (int) (0x28 + 0x30 * pulse) << 24 | ACCENT);
        g.fill(left + 14, top + 31, left + WIDTH - 14, top + 32, 0xFF000000 | ACCENT);
        g.pose().pushPose();
        g.pose().translate(left + WIDTH / 2F, top + 16, 0);
        g.pose().scale(1.5F, 1.5F, 1);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD), 0, 0, 0xFFFFFFFF);
        g.pose().popPose();
        g.drawCenteredString(font, Component.translatable("screen.relics_addon.armageddon.question"), left + WIDTH / 2, top + 40, HoloPaint.TEXT);

        int y = top + 58;
        line(g, Component.translatable("screen.relics_addon.armageddon.target", Math.round(distance)), y, HoloPaint.TEXT_DIM);
        line(g, Component.translatable("screen.relics_addon.armageddon.radius", (int) Armageddon.RADIUS), y += 12, HoloPaint.TEXT_DIM);
        line(g, Component.translatable("screen.relics_addon.armageddon.drain", Armageddon.FIRE / 20), y += 12, HoloPaint.TEXT_DIM);
        if (shieldPercent > 0) line(g, Component.translatable("screen.relics_addon.armageddon.shield", shieldPercent), y += 12, 0xFF000000 | 0xE7C6FF);
        HoloPaint.bar(g, left + 18, top + 110, WIDTH - 36, 7, 1, 0xFF000000 | ACCENT);

        boolean yes = inside(mouseX, mouseY, yesX(), buttonY(), 110, 20), no = inside(mouseX, mouseY, noX(), buttonY(), 90, 20);
        HoloPaint.button(g, yesX(), buttonY(), 110, 20, true, yes, true, ACCENT);
        HoloPaint.button(g, noX(), buttonY(), 90, 20, true, no, false, ACCENT);
        g.drawCenteredString(font, Component.translatable("screen.relics_addon.armageddon.activate"), yesX() + 55, buttonY() + 6, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.relics_addon.armageddon.cancel"), noX() + 45, buttonY() + 6, HoloPaint.TEXT);
    }

    private void line(GuiGraphics g, Component text, int y, int color) {
        g.drawString(font, text, left + 18, y, color, false);
    }

    private int yesX() {
        return left + 18;
    }

    private int noX() {
        return left + WIDTH - 18 - 90;
    }

    private int buttonY() {
        return top + HEIGHT - 32;
    }

    private static boolean inside(double x, double y, int bx, int by, int w, int h) {
        return x >= bx && y >= by && x < bx + w && y < by + h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, yesX(), buttonY(), 110, 20)) {
            fire();
            return true;
        }
        if (button == 0 && inside(mouseX, mouseY, noX(), buttonY(), 90, 20)) {
            click();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            fire();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void fire() {
        click();
        PacketDistributor.sendToServer(new ArmageddonPayloads.Fire(target));
        onClose();
    }

    private void click() {
        if (minecraft != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, .6F));
    }
}
