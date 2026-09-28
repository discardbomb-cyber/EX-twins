package dev.hurtify.relicsaddon.gametest.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModItems;
import it.hurts.sskirillss.relics.client.screen.description.misc.DescriptionTextures;
import it.hurts.sskirillss.relics.items.relics.base.data.research.ResearchTemplate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Resource/layout smoke test; deliberately not a substitute for native UI interaction testing. */
final class NativeResearchGallery extends Screen {
    // Relics 0.12.8 AbilityResearchScreen: background at (67, 54), 110x155,
    // then StarWidget at (67 + 5*x - 8, 54 + 5*y - 8), sized 17x17.
    private static final int CARD_WIDTH = 22;
    private static final int CARD_HEIGHT = 31;
    private static final int NATIVE_WIDTH = 110;
    private static final int NATIVE_HEIGHT = 155;
    private static final int STAR_SIZE = 17;
    private static final int STAR_HALF_SIZE = STAR_SIZE / 2;
    private static final int COORDINATE_SCALE = 5;
    private static final int PADDING = 6;
    private static final int ENTRIES_PER_PAGE = 6;
    private record Entry(String title, ResourceLocation icon, ResearchTemplate graph) { }
    private final List<Entry> entries = new ArrayList<>();
    private final long started = Util.getMillis();
    private long pageStarted = started;
    private int page;
    private boolean captured;

    NativeResearchGallery() {
        super(Component.literal("Relics / ability resources and research layouts"));
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            addEntries(item, "_shield");
        }
        for (var item : List.of(ModItems.RF_HIVE.get(), ModItems.MANA_HIVE.get(), ModItems.TWINS_HIVE.get())) {
            addEntries(item, "_hive");
        }
    }

    private void addEntries(dev.hurtify.relicsaddon.relic.AutonomousRelicItem item, String suffix) {
        var stack = new ItemStack(item);
        var abilities = item.getRelicData(null, stack).getAbilitiesData();
        String family = item.role().itemId().replace(suffix, "");
        var ids = new ArrayList<>(abilities.getAbilityIDs());
        ids.sort(String::compareTo);
        for (String id : ids) {
            if (!abilities.getAbilityIDs().contains(id)) continue;
            var texture = DescriptionTextures.getAbilityCardTexture(stack, id);
            if (texture.getPath().endsWith("/missing.png")) throw new IllegalStateException("Missing native ability card " + id);
            var graph = abilities.getAbilityData(id).getTemplate().getResearchTemplate();
            validateNativeBounds(item.role().itemId() + "/" + id, graph);
            String name = id.equals(item.role().abilityId()) ? suffix.substring(1) : id;
            entries.add(new Entry(family + " / " + name, texture, graph));
            RelicsAddon.LOGGER.info("Native research layout: {} ({} stars, 110x155 card, x=3..19 y=3..27 padded)",
                    item.role().itemId() + "/" + id, graph.getStars().size());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF20262E);
        graphics.drawString(font, title, 8, 8, 0xFFEAF5FA, false);
        int pages = pageCount();
        graphics.drawString(font, "Page " + (page + 1) + "/" + pages, width - 52, 8, 0xFF9FB8C6, false);
        int columns = 2;
        int cw = width / columns, ch = (height - 24) / 3;
        int start = page * ENTRIES_PER_PAGE;
        int end = Math.min(start + ENTRIES_PER_PAGE, entries.size());
        for (int index = start; index < end; index++) {
            var entry = entries.get(index);
            int localIndex = index - start;
            int x = localIndex % columns * cw, y = 24 + localIndex / columns * ch;
            graphics.fill(x + 4, y, x + cw - 4, y + 1, 0xFF455767);
            graphics.drawString(font, entry.title, x + 6, y + 6, 0xFFDEEAF0, false);
            float scale = Math.min(1.0F, Math.min((cw - 12.0F) / NATIVE_WIDTH, (ch - 28.0F) / NATIVE_HEIGHT));
            graphics.pose().pushPose();
            graphics.pose().translate(x + (cw - NATIVE_WIDTH * scale) / 2.0F, y + 22, 0);
            graphics.pose().scale(scale, scale, 1.0F);
            renderNativeCard(graphics, entry, 0, 0);
            graphics.pose().popPose();
        }
    }

    private static void renderNativeCard(GuiGraphics graphics, Entry entry, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(COORDINATE_SCALE, COORDINATE_SCALE, 1.0F);
        graphics.blit(entry.icon, 0, 0, 0, 0, CARD_WIDTH, CARD_HEIGHT, CARD_WIDTH, CARD_HEIGHT);
        graphics.pose().popPose();
        var stars = entry.graph.getStars();
        for (var link : entry.graph.getLinks().entries()) {
            var a = stars.get(link.getKey());
            var b = stars.get(link.getValue());
            line(graphics, x + a.getX() * COORDINATE_SCALE, y + a.getY() * COORDINATE_SCALE,
                    x + b.getX() * COORDINATE_SCALE, y + b.getY() * COORDINATE_SCALE);
        }
        for (var star : stars.values()) {
            int sx = x + star.getX() * COORDINATE_SCALE;
            int sy = y + star.getY() * COORDINATE_SCALE;
            graphics.fill(sx - 3, sy, sx + 4, sy + 1, 0xFF9CD5E1);
            graphics.fill(sx, sy - 3, sx + 1, sy + 4, 0xFF9CD5E1);
            graphics.fill(sx, sy, sx + 1, sy + 1, 0xFFFFFFFF);
        }
    }

    private static void validateNativeBounds(String id, ResearchTemplate graph) {
        for (var star : graph.getStars().values()) {
            int left = star.getX() * COORDINATE_SCALE - STAR_HALF_SIZE;
            int top = star.getY() * COORDINATE_SCALE - STAR_HALF_SIZE;
            int right = left + STAR_SIZE;
            int bottom = top + STAR_SIZE;
            if (left < PADDING || top < PADDING || right > NATIVE_WIDTH - PADDING || bottom > NATIVE_HEIGHT - PADDING) {
                throw new IllegalStateException("Research star outside native padded card: " + id + " at "
                        + star.getX() + "," + star.getY() + " -> " + left + "," + top + ".." + right + "," + bottom);
            }
        }
    }

    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int step = 0; step <= steps; step++) {
            float t = steps == 0 ? 0 : step / (float) steps;
            int x = Math.round(x0 + (x1 - x0) * t), y = Math.round(y0 + (y1 - y0) * t);
            graphics.fill(x, y, x + 1, y + 1, 0xFF426D84);
        }
    }

    void capture(Minecraft minecraft) {
        if (!captured && Util.getMillis() - pageStarted > 4000) {
            captured = true;
            String name = String.format(java.util.Locale.ROOT, "relics-research-resources-page-%02d.png", page + 1);
            Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {
                RelicsAddon.LOGGER.info("Research resource evidence page {}/{}: {}", page + 1, pageCount(), message.getString());
                minecraft.execute(() -> {
                    if (++page >= pageCount()) minecraft.stop();
                    else {
                        pageStarted = Util.getMillis();
                        captured = false;
                    }
                });
            });
        }
    }

    private int pageCount() {
        return Math.max(1, (entries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }
}
