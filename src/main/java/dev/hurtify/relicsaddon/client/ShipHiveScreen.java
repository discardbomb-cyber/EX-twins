package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.menu.ShipHiveMenu;
import dev.hurtify.relicsaddon.ship.AegisModule;
import dev.hurtify.relicsaddon.ship.EscortModule;
import dev.hurtify.relicsaddon.ship.LanceModule;
import dev.hurtify.relicsaddon.ship.ShipHiveKind;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/** A ship hive's holographic window: its state, battery and meters, and its switch. */
public final class ShipHiveScreen extends AbstractContainerScreen<ShipHiveMenu> {
    private static final int WIDTH = 212, HEIGHT = 150, CUT = 8;
    private static final int LEFT = 14, RIGHT = WIDTH - 14, BAR_H = 11;
    private static final int BUTTON_X = WIDTH - 86, BUTTON_Y = HEIGHT - 21, BUTTON_W = 72, BUTTON_H = 15;

    public ShipHiveScreen(ShipHiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    private int accent() {
        return switch (menu.kind()) {
            case AEGIS -> 0x42E6C8;
            case ESCORT -> 0x38E8FF;
            case LANCE -> 0xB151FF;
        };
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        HoloPaint.window(g, x, y, WIDTH, HEIGHT, CUT);
        HoloPaint.ticks(g, x + 14, y + HEIGHT - 4, WIDTH - 28, 6);
        int capacity = menu.kind().capacity;
        HoloPaint.bar(g, x + LEFT, y + 46, RIGHT - LEFT, BAR_H, menu.energy() / (double) capacity, 0xFFE0523C);
        switch (menu.kind()) {
            case LANCE -> {
                double heat = menu.meterA() / (double) LanceModule.HOT;
                HoloPaint.bar(g, x + LEFT, y + 66, RIGHT - LEFT, BAR_H, heat, heat > .75 ? 0xFFFF3A24 : heat > .4 ? 0xFFFF7A2E : 0xFF000000 | accent());
            }
            case AEGIS -> HoloPaint.bar(g, x + LEFT, y + 66, RIGHT - LEFT, BAR_H, menu.meterA() / (double) AegisModule.FULL, 0xFF000000 | accent());
            case ESCORT -> {
                int half = (RIGHT - LEFT - 6) / 2;
                HoloPaint.bar(g, x + LEFT, y + 66, half, BAR_H, menu.meterA() / (double) EscortModule.FULL, 0xFF000000 | accent());
                HoloPaint.bar(g, x + LEFT + half + 6, y + 66, half, BAR_H, menu.meterB() / (double) EscortModule.FULL, 0xFF000000 | accent());
            }
        }
        boolean hovered = inside(mouseX, mouseY, x + BUTTON_X, y + BUTTON_Y, BUTTON_W, BUTTON_H);
        HoloPaint.button(g, x + BUTTON_X, y + BUTTON_Y, BUTTON_W, BUTTON_H, menu.canControl(), hovered, menu.enabled(), accent());
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 26, 8, 0xFF000000 | accent(), false);
        if (!menu.owner().isEmpty()) {
            Component owner = Component.translatable("screen.relics_addon.ship.owner", menu.owner());
            g.drawString(font, owner, RIGHT - font.width(owner), 8, HoloPaint.TEXT_DIM, false);
        }
        g.drawString(font, menu.status().text(), LEFT, 24, HoloPaint.TEXT, false);
        int capacity = menu.kind().capacity;
        g.drawString(font, Component.translatable("screen.relics_addon.ship.energy", menu.energy() / 1000, capacity / 1000), LEFT + 3, 48, HoloPaint.TEXT, true);
        switch (menu.kind()) {
            case LANCE -> g.drawString(font, Component.translatable("screen.relics_addon.ship.heat", menu.meterA() * 100 / LanceModule.HOT), LEFT + 3, 68, HoloPaint.TEXT, true);
            case AEGIS -> g.drawString(font, Component.translatable("screen.relics_addon.ship.shield", menu.meterA() * 100 / AegisModule.FULL), LEFT + 3, 68, HoloPaint.TEXT, true);
            case ESCORT -> {
                int half = (RIGHT - LEFT - 6) / 2;
                for (int wing = 0; wing < EscortModule.WINGS; wing++) {
                    int charge = wing == 0 ? menu.meterA() : menu.meterB();
                    Component line = Component.translatable("screen.relics_addon.ship.wing", wing + 1, charge * 100 / EscortModule.FULL);
                    g.drawString(font, line, LEFT + 3 + wing * (half + 6), 68, HoloPaint.TEXT, true);
                    Component where = Component.translatable("screen.relics_addon.ship.phase." + menu.phase(wing).name().toLowerCase(java.util.Locale.ROOT));
                    g.drawString(font, where, LEFT + wing * (half + 6), 80, HoloPaint.TEXT_DIM, false);
                }
            }
        }
        List<FormattedCharSequence> hint = font.split(Component.translatable("screen.relics_addon.ship.hint." + menu.kind().name().toLowerCase(java.util.Locale.ROOT)), RIGHT - LEFT);
        int top = menu.kind() == ShipHiveKind.ESCORT ? 96 : 86;
        for (int line = 0; line < Math.min(3, hint.size()); line++) g.drawString(font, hint.get(line), LEFT, top + line * 10, HoloPaint.TEXT_FAINT, false);
        Component label = Component.translatable(menu.enabled() ? "screen.relics_addon.ship.switch_off" : "screen.relics_addon.ship.switch_on");
        g.drawString(font, label, BUTTON_X + (BUTTON_W - font.width(label)) / 2, BUTTON_Y + 4, menu.canControl() ? HoloPaint.TEXT : HoloPaint.TEXT_FAINT, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (inside(mouseX, mouseY, leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H) && !menu.canControl()) {
            g.renderTooltip(font, Component.translatable("screen.relics_addon.ship.not_yours").withStyle(ChatFormatting.GRAY), mouseX, mouseY);
        } else if (inside(mouseX, mouseY, leftPos + LEFT, topPos + 46, RIGHT - LEFT, BAR_H)) {
            g.renderTooltip(font, Component.translatable("screen.relics_addon.ship.energy.hint").withStyle(ChatFormatting.GRAY), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && menu.canControl() && inside(mouseX, mouseY, leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H)) {
            if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ShipHiveMenu.BUTTON_TOGGLE);
            if (minecraft != null) minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, .6F));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
