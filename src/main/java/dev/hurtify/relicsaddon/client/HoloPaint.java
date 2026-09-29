package dev.hurtify.relicsaddon.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Holographic window primitives: frosted grey glass with a bright rim, cut top-left and
 * bottom-right corners with hatching, a thin slider track and ruler ticks. Everything is drawn
 * with fills, so the console needs no texture sheet and scales cleanly.
 */
final class HoloPaint {
    static final int TEXT = 0xFFF3F6F9, TEXT_DIM = 0xFFA9B1BB, TEXT_FAINT = 0xFF78808A;
    private static final int GLASS_TOP = 0xD2464B53, GLASS_BOTTOM = 0xD22B2F35;
    private static final int RIM = 0xF2E6EBF0, RIM_SOFT = 0x66E6EBF0, GLOW = 0x22FFFFFF, INNER_LINE = 0x40FFFFFF;

    /** Main window: glow, frosted rim, glass body, corner hatching and an inner hairline. */
    static void window(GuiGraphics g, int x, int y, int w, int h, int cut) {
        chamfer(g, x - 2, y - 2, w + 4, h + 4, cut + 2, GLOW, GLOW);
        chamfer(g, x, y, w, h, cut, RIM_SOFT, RIM_SOFT);
        chamfer(g, x + 3, y + 3, w - 6, h - 6, cut - 1, GLASS_TOP, GLASS_BOTTOM);
        outline(g, x, y, w, h, cut, RIM);
        outline(g, x + 3, y + 3, w - 6, h - 6, cut - 1, INNER_LINE);
        for (int stripe = 0; stripe < 3; stripe++) {
            diagonal(g, x + 5 + stripe * 4, y + cut + 6, cut - 2, RIM_SOFT, true);
            diagonal(g, x + w - 6 - stripe * 4, y + h - cut - 7, cut - 2, RIM_SOFT, false);
        }
    }

    /** Lighter secondary panel (the inventory tray). */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int cut) {
        chamfer(g, x, y, w, h, cut, RIM_SOFT, RIM_SOFT);
        chamfer(g, x + 2, y + 2, w - 4, h - 4, Math.max(1, cut - 1), 0xC03A3F46, 0xC0272B30);
        outline(g, x, y, w, h, cut, 0xB0E6EBF0);
    }

    /** Filled shape with the top-left and bottom-right corners cut at 45 degrees; colour fades top to bottom. */
    static void chamfer(GuiGraphics g, int x, int y, int w, int h, int cut, int top, int bottom) {
        for (int row = 0; row < h; row++) {
            int left = Math.max(0, cut - row), right = Math.max(0, row - (h - 1 - cut));
            g.fill(x + left, y + row, x + w - right, y + row + 1, lerp(top, bottom, h <= 1 ? 0 : row / (float) (h - 1)));
        }
    }

    static void outline(GuiGraphics g, int x, int y, int w, int h, int cut, int color) {
        g.fill(x + cut, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w - cut, y + h, color);
        g.fill(x, y + cut, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h - cut, color);
        for (int step = 0; step < cut; step++) {
            g.fill(x + cut - step, y + step, x + cut - step + 1, y + step + 1, color);
            g.fill(x + w - cut + step - 1, y + h - 1 - step, x + w - cut + step, y + h - step, color);
        }
    }

    private static void diagonal(GuiGraphics g, int x, int y, int length, int color, boolean upLeft) {
        for (int step = 0; step < length; step++) {
            int px = upLeft ? x + step : x - step, py = upLeft ? y - step : y + step;
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** Thin vertical track with a square handle at {@code fraction} (0 = bottom). */
    static void slider(GuiGraphics g, int x, int top, int height, double fraction, int accent) {
        g.fill(x, top, x + 1, top + height, RIM_SOFT);
        int filled = (int) Math.round(height * Math.clamp(fraction, 0, 1));
        g.fill(x, top + height - filled, x + 1, top + height, 0xFF000000 | accent);
        int handle = top + height - filled;
        g.fill(x - 2, handle - 2, x + 3, handle + 3, RIM);
        g.fill(x - 1, handle - 1, x + 2, handle + 2, 0xFF000000 | accent);
    }

    /** Ruler ticks along the bottom rim, as in a measuring window. */
    static void ticks(GuiGraphics g, int x, int y, int width, int spacing) {
        for (int tick = x; tick <= x + width; tick += spacing) g.fill(tick, y - 3, tick + 1, y, RIM_SOFT);
        g.fill(x + width / 3, y - 4, x + width / 3 + 4, y, RIM_SOFT);
        g.fill(x + width * 2 / 3, y - 4, x + width * 2 / 3 + 4, y, RIM_SOFT);
    }

    /** Horizontal meter; the label is drawn by the caller on top. */
    static void bar(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
        g.fill(x, y, x + w, y + h, 0x80101317);
        int filled = (int) Math.round((w - 2) * Math.clamp(fraction, 0, 1));
        if (filled > 0) {
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, color);
            g.fill(x + 1, y + 1, x + 1 + filled, y + 2, brighten(color));
        }
        box(g, x, y, w, h, 0x90E6EBF0);
    }

    /** Glass push button; {@code selected} marks an active toggle or tab. */
    static void button(GuiGraphics g, int x, int y, int w, int h, boolean active, boolean hovered, boolean selected, int accent) {
        int fill = !active ? 0x30FFFFFF : selected ? (0x90000000 | accent & 0xFFFFFF) : hovered ? 0x70FFFFFF : 0x40FFFFFF;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        box(g, x, y, w, h, !active ? 0x50E6EBF0 : hovered || selected ? RIM : 0xB0E6EBF0);
    }

    /** Recessed item slot, positioned at the slot's item origin. */
    static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0x70101317);
        box(g, x - 1, y - 1, 18, 18, 0x90E6EBF0);
    }

    static void box(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static int brighten(int color) {
        int r = Math.min(255, (color >> 16 & 255) + 60), gr = Math.min(255, (color >> 8 & 255) + 60), b = Math.min(255, (color & 255) + 60);
        return color & 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private static int lerp(int a, int b, float t) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = a >>> shift & 255, cb = b >>> shift & 255;
            result |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return result;
    }

    private HoloPaint() {
    }
}
