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
    private record Entry(String title, ResourceLocation icon, ResearchTemplate graph) { }
    private final List<Entry> entries = new ArrayList<>();
    private final long started = Util.getMillis();
    private boolean captured;

    NativeResearchGallery() {
        super(Component.literal("Relics / ability resources and research layouts"));
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = new ItemStack(item);
            var abilities = item.getRelicData(null, stack).getAbilitiesData();
            String family = item.role().itemId().replace("_shield", "");
            for (String id : List.of(item.role().abilityId(), "damage_distribution", "shield_gather")) {
                if (!abilities.getAbilityIDs().contains(id)) continue;
                var texture = DescriptionTextures.getAbilityCardTexture(stack, id);
                if (texture.getPath().endsWith("/missing.png")) throw new IllegalStateException("Missing native ability card " + id);
                var graph = abilities.getAbilityData(id).getTemplate().getResearchTemplate();
                String name = id.equals(item.role().abilityId()) ? "barrier" : id.equals("shield_gather") ? "gather" : "share";
                entries.add(new Entry(family + " / " + name, texture, graph));
                RelicsAddon.LOGGER.info("Native research card resolved: {} ({} stars)", texture, graph.getStars().size());
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF20262E);
        graphics.drawString(font, title, 8, 8, 0xFFEAF5FA, false);
        int cw = width / 4, ch = (height - 24) / 2;
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            int x = index % 4 * cw, y = 24 + index / 4 * ch;
            graphics.fill(x + 4, y, x + cw - 4, y + 1, 0xFF455767);
            graphics.drawString(font, entry.title, x + 6, y + 6, 0xFFDEEAF0, false);
            graphics.blit(entry.icon, x + 8, y + 31, 0, 0, 22, 31, 22, 31);
            float scale = Math.min((cw - 44) / 30f, (ch - 26) / 32f);
            var stars = entry.graph.getStars();
            for (var link : entry.graph.getLinks().entries()) {
                var a = stars.get(link.getKey()); var b = stars.get(link.getValue());
                line(graphics, x + 37 + Math.round(a.getX() * scale), y + 22 + Math.round(a.getY() * scale),
                        x + 37 + Math.round(b.getX() * scale), y + 22 + Math.round(b.getY() * scale));
            }
            for (var star : stars.values()) {
                int sx = x + 37 + Math.round(star.getX() * scale), sy = y + 22 + Math.round(star.getY() * scale);
                graphics.fill(sx - 2, sy, sx + 3, sy + 1, 0xFF9CD5E1);
                graphics.fill(sx, sy - 2, sx + 1, sy + 3, 0xFF9CD5E1);
                graphics.fill(sx, sy, sx + 1, sy + 1, 0xFFFFFFFF);
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
        if (!captured && Util.getMillis() - started > 4000) {
            captured = true;
            Screenshot.grab(minecraft.gameDirectory, "relics-research-resources.png", minecraft.getMainRenderTarget(), message -> {
                RelicsAddon.LOGGER.info("Research resource evidence: {}", message.getString());
                minecraft.execute(minecraft::stop);
            });
        }
    }
}
