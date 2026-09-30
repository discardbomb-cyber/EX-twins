package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.ManaArmageddon;
import dev.hurtify.relicsaddon.drone.RfArmageddon;
import dev.hurtify.relicsaddon.network.ArmageddonPayloads;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.server.ArmageddonController;
import java.util.ArrayList;
import java.util.List;
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
 * "Activate Armageddon?": the last word before a Twins, Mana or RF hive fires its ultimate at the point its
 * owner was looking at when they pressed the key. It shows how far off that is and what the shot will do
 * there in that hive's own numbers, that it empties the hive, and what a worn shield of the same family
 * adds, and fires only on an explicit yes (the button or Enter).
 */
public final class ArmageddonScreen extends Screen {
    private static final int WIDTH = 262, CUT = 10, FIRST_LINE = 58, LINE = 12;
    private final HiveType type;
    private final int accent;
    private final Vec3 target;
    private final List<Line> lines;
    private int left, top, tall;

    private record Line(Component text, int color) { }

    private ArmageddonScreen(HiveType type, Vec3 target, List<Line> lines) {
        super(Component.translatable(words(type) + "title"));
        this.type = type;
        this.accent = type.role.color();
        this.target = target;
        this.lines = lines;
    }

    /** The key was pressed: offers the shot if the hive can fire, or says on the action bar why not. */
    static void request() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) return;
        ItemStack hive = ArmageddonController.hive(player);
        String reason = hive.isEmpty() ? ArmageddonController.NO_HIVE : ArmageddonController.unavailable(player, hive, minecraft.level.getGameTime());
        if (reason != null) {
            player.displayClientMessage(Component.translatable(reason).withStyle(ChatFormatting.LIGHT_PURPLE), true);
            return;
        }
        HiveType type = ArmageddonController.fires(hive);
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        double reach = switch (type) {
            case MANA -> ManaArmageddon.REACH;
            case RF -> RfArmageddon.REACH;
            case TWINS -> Armageddon.REACH;
        };
        HitResult hit = player.pick(reach, partial, false);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 target = hit.getType() == HitResult.Type.MISS ? eye.add(player.getViewVector(partial).scale(reach)) : hit.getLocation();
        minecraft.setScreen(new ArmageddonScreen(type, target, lines(type, eye.distanceTo(target), shieldPercent(player, hive, type))));
    }

    /** Where the window's words for {@code type}'s Armageddon are kept. */
    private static String words(HiveType type) {
        return switch (type) {
            case MANA -> "screen.relics_addon.mana_armageddon.";
            case RF -> "screen.relics_addon.rf_armageddon.";
            case TWINS -> "screen.relics_addon.armageddon.";
        };
    }

    /** What the window tells of the shot, in the words and numbers of {@code type}'s Armageddon. */
    private static List<Line> lines(HiveType type, double distance, int shieldPercent) {
        List<Line> lines = new ArrayList<>();
        boolean safe = ArmageddonController.safeClient();
        if (type == HiveType.RF) {
            lines.add(new Line(Component.translatable("screen.relics_addon.rf_armageddon.target", Math.round(distance)), HoloPaint.TEXT_DIM));
            lines.add(safe ? new Line(Component.translatable("screen.relics_addon.armageddon.safe"), HoloPaint.TEXT_DIM)
                    : new Line(Component.translatable("screen.relics_addon.rf_armageddon.crater", (int) (RfArmageddon.DOME_RADIUS * 2)), HoloPaint.TEXT_DIM));
            lines.add(new Line(Component.translatable("screen.relics_addon.rf_armageddon.radius", (int) RfArmageddon.RADIUS), HoloPaint.TEXT_DIM));
            lines.add(new Line(Component.translatable("screen.relics_addon.rf_armageddon.drain", RfArmageddon.FIRE / 20, (int) RfArmageddon.BLAST_SECONDS),
                    HoloPaint.TEXT_DIM));
            lines.add(shieldPercent > 0 ? new Line(Component.translatable("screen.relics_addon.rf_armageddon.shield", shieldPercent), 0xFF000000 | RfPalette.HOLO_PALE)
                    : new Line(Component.translatable("screen.relics_addon.rf_armageddon.no_shield"), HoloPaint.TEXT_FAINT));
        } else if (type == HiveType.MANA) {
            lines.add(new Line(Component.translatable("screen.relics_addon.mana_armageddon.target", Math.round(distance)), HoloPaint.TEXT_DIM));
            lines.add(safe ? new Line(Component.translatable("screen.relics_addon.armageddon.safe"), HoloPaint.TEXT_DIM)
                    : new Line(Component.translatable("screen.relics_addon.mana_armageddon.vortex", (int) ManaArmageddon.CARVE_RADIUS), HoloPaint.TEXT_DIM));
            lines.add(new Line(Component.translatable("screen.relics_addon.mana_armageddon.radius", (int) ManaArmageddon.RADIUS), HoloPaint.TEXT_DIM));
            lines.add(new Line(Component.translatable("screen.relics_addon.mana_armageddon.drain", ManaArmageddon.FIRE / 20, (int) ManaArmageddon.BLAST_SECONDS),
                    HoloPaint.TEXT_DIM));
            lines.add(shieldPercent > 0 ? new Line(Component.translatable("screen.relics_addon.mana_armageddon.shield", shieldPercent), 0xFF000000 | 0xBFF8EC)
                    : new Line(Component.translatable("screen.relics_addon.mana_armageddon.no_shield"), HoloPaint.TEXT_FAINT));
        } else {
            lines.add(new Line(Component.translatable("screen.relics_addon.armageddon.target", Math.round(distance)), HoloPaint.TEXT_DIM));
            lines.add(new Line(Component.translatable("screen.relics_addon.armageddon.radius", (int) Armageddon.RADIUS), HoloPaint.TEXT_DIM));
            lines.add(new Line(Component.translatable("screen.relics_addon.armageddon.drain", Armageddon.FIRE / 20), HoloPaint.TEXT_DIM));
            if (safe) lines.add(new Line(Component.translatable("screen.relics_addon.armageddon.safe"), HoloPaint.TEXT_DIM));
            if (shieldPercent > 0) lines.add(new Line(Component.translatable("screen.relics_addon.armageddon.shield", shieldPercent), 0xFF000000 | 0xE7C6FF));
        }
        return lines;
    }

    /** What a worn shield of the hive's family would hand over, as a share of the hive's battery; 0 without one. */
    private static int shieldPercent(Player player, ItemStack hive, HiveType type) {
        ItemStack shield = ArmageddonController.shield(player, type);
        if (shield.isEmpty()) return 0;
        return (int) Math.round(100.0 * ArmageddonController.shieldGives(player, shield, hive) / Math.max(1, ArmageddonController.points(hive)));
    }

    @Override
    protected void init() {
        tall = 110 + LINE * Math.max(4, lines.size());
        left = (width - WIDTH) / 2;
        top = (height - tall) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        HoloPaint.window(g, left, top, WIDTH, tall, CUT);
        long now = minecraft == null || minecraft.level == null ? 0 : minecraft.level.getGameTime();
        double pulse = .5 + .5 * Math.sin((now + partialTick) * .18);
        // A band of the hive's colour under the title that breathes, like its charge building.
        g.fill(left + 14, top + 13, left + WIDTH - 14, top + 31, (int) (0x28 + 0x30 * pulse) << 24 | accent);
        g.fill(left + 14, top + 31, left + WIDTH - 14, top + 32, 0xFF000000 | accent);
        // The Mana seal's split: turquoise to the left of the band's middle, gold to the right; the RF relay's copper belts.
        if (type == HiveType.MANA) g.fill(left + WIDTH / 2, top + 31, left + WIDTH - 14, top + 32, 0xFF000000 | ManaPalette.GOLD);
        if (type == HiveType.RF) for (int belt = 0; belt < 3; belt++) {
            int x = left + 14 + (WIDTH - 28) * (belt + 1) / 4;
            g.fill(x - 6, top + 31, x + 6, top + 32, 0xFF000000 | RfPalette.COPPER);
        }
        g.pose().pushPose();
        g.pose().translate(left + WIDTH / 2F, top + 16, 0);
        g.pose().scale(1.5F, 1.5F, 1);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD), 0, 0, 0xFFFFFFFF);
        g.pose().popPose();
        g.drawCenteredString(font, Component.translatable(words(type) + "question"), left + WIDTH / 2, top + 40, HoloPaint.TEXT);

        int y = top + FIRST_LINE;
        for (Line line : lines) {
            g.drawString(font, line.text(), left + 18, y, line.color(), false);
            y += LINE;
        }
        HoloPaint.bar(g, left + 18, top + FIRST_LINE + LINE * Math.max(4, lines.size()) + 4, WIDTH - 36, 7, 1, 0xFF000000 | accent);

        boolean yes = inside(mouseX, mouseY, yesX(), buttonY(), 110, 20), no = inside(mouseX, mouseY, noX(), buttonY(), 90, 20);
        HoloPaint.button(g, yesX(), buttonY(), 110, 20, true, yes, true, accent);
        HoloPaint.button(g, noX(), buttonY(), 90, 20, true, no, false, accent);
        g.drawCenteredString(font, Component.translatable("screen.relics_addon.armageddon.activate"), yesX() + 55, buttonY() + 6, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.relics_addon.armageddon.cancel"), noX() + 45, buttonY() + 6, HoloPaint.TEXT);
    }

    private int yesX() {
        return left + 18;
    }

    private int noX() {
        return left + WIDTH - 18 - 90;
    }

    private int buttonY() {
        return top + tall - 32;
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
