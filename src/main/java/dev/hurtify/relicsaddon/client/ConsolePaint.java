package dev.hurtify.relicsaddon.client;

import net.minecraft.client.gui.GuiGraphics;

/** Flat, bevelled machine-console primitives drawn with fills, so the GUI needs no texture sheet. */
final class ConsolePaint {
    static final int PANEL = 0xFFC3C6CC, PANEL_LIGHT = 0xFFF4F6FA, PANEL_SHADOW = 0xFF6B7078, OUTLINE = 0xFF1C1E22;
    static final int SCREEN = 0xFF10151B, SCREEN_EDGE = 0xFF2F3A45, SCREEN_TEXT = 0xFF7FE9F2, SCREEN_DIM = 0xFF4F8C94;
    static final int SLOT = 0xFF8B8F96;

    /** Outlined panel with a light top-left and dark bottom-right bevel. */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, OUTLINE);
        g.fill(x, y + 1, x + w, y + h - 1, OUTLINE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 1, y + 1, x + w - 2, y + 3, PANEL_LIGHT);
        g.fill(x + 1, y + 1, x + 3, y + h - 2, PANEL_LIGHT);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, PANEL_SHADOW);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, PANEL_SHADOW);
    }

    /** Recessed 18x18 item slot, positioned at the slot's item origin. */
    static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, SLOT);
        g.fill(x - 1, y - 1, x + 16, y, PANEL_SHADOW);
        g.fill(x - 1, y - 1, x, y + 16, PANEL_SHADOW);
        g.fill(x, y + 16, x + 17, y + 17, PANEL_LIGHT);
        g.fill(x + 16, y, x + 17, y + 17, PANEL_LIGHT);
    }

    /** Dark display window with an inset edge. */
    static void screen(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_SHADOW);
        g.fill(x + 1, y + 1, x + w, y + h, PANEL_LIGHT);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, SCREEN_EDGE);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, SCREEN);
    }

    /** Vertical gauge filled bottom-up, with tick marks every quarter. */
    static void gauge(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
        screen(g, x, y, w, h);
        int inner = h - 4;
        int filled = (int) Math.round(inner * Math.clamp(fraction, 0, 1));
        if (filled > 0) {
            g.fill(x + 2, y + h - 2 - filled, x + w - 2, y + h - 2, color);
            g.fill(x + 2, y + h - 2 - filled, x + 3, y + h - 2, brighten(color));
        }
        for (int tick = 1; tick < 4; tick++) {
            int ty = y + 2 + inner * tick / 4;
            g.fill(x + w - 4, ty, x + w - 2, ty + 1, 0x80FFFFFF);
        }
    }

    /** Side tab attached to the right edge of the panel; the selected tab merges with the panel. */
    static void tab(GuiGraphics g, int x, int y, boolean selected, boolean hovered) {
        int body = selected ? PANEL : hovered ? 0xFFD6D9DE : 0xFFA9ADB4;
        g.fill(x, y, x + 22, y + 22, OUTLINE);
        g.fill(selected ? x - 2 : x, y + 1, x + 21, y + 21, body);
        g.fill(x, y + 1, x + 20, y + 3, PANEL_LIGHT);
        g.fill(x + 19, y + 3, x + 21, y + 21, PANEL_SHADOW);
    }

    /** Small bevelled push button body. */
    static void button(GuiGraphics g, int x, int y, int w, int h, boolean active, boolean hovered, int accent) {
        g.fill(x, y, x + w, y + h, OUTLINE);
        int body = !active ? 0xFF8A8D93 : hovered ? 0xFFDDE1E6 : 0xFFB9BDC4;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, body);
        if (active) {
            g.fill(x + 1, y + 1, x + w - 1, y + 2, PANEL_LIGHT);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PANEL_SHADOW);
            g.fill(x + 1, y + 2, x + 2, y + h - 2, accent);
        }
    }

    static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 255) + 70), gr = Math.min(255, ((color >> 8) & 255) + 70), b = Math.min(255, (color & 255) + 70);
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private ConsolePaint() {
    }
}
