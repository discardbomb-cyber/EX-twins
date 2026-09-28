package dev.hurtify.relicsaddon.gametest.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.HiveSettingsScreen;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import it.hurts.sskirillss.relics.client.screen.description.research.AbilityResearchScreen;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.item.ItemStack;

/** Dedicated test-client capture sequence. It never runs without both opt-in properties. */
final class NativeUiCapture {
    private enum Stage { IDLE, HIVE_854, HIVE_1280, RESEARCH, GALLERY, COMPLETE }
    private record ResearchEntry(int slot, String itemId, String abilityId) { }

    private static final boolean ACTIVE = Boolean.getBoolean("relics_addon.uiSmoke")
            && Boolean.getBoolean("relics_addon.captureAndExit");
    private static final long CAPTURE_DELAY_MILLIS = 1800L;
    private static Stage stage = Stage.IDLE;
    private static long openedAt;
    private static boolean capturePending;
    private static List<ResearchEntry> researchEntries = List.of();
    private static int researchIndex;

    static boolean active() {
        return ACTIVE;
    }

    static void tick(Minecraft minecraft) {
        if (!ACTIVE || stage != Stage.IDLE || minecraft.getOverlay() != null) return;
        if (!(minecraft.screen instanceof TitleScreen) && minecraft.level == null) return;
        RelicsAddon.LOGGER.info("UI smoke capture starting; configure the dedicated test client for ru_ru before launch.");
        openHiveSettings(minecraft, 854, 480, Stage.HIVE_854);
    }

    static void frame(Minecraft minecraft) {
        if (minecraft.getOverlay() != null) { openedAt = Util.getMillis(); return; }
        if (stage == Stage.GALLERY && minecraft.screen instanceof NativeResearchGallery gallery) {
            gallery.capture(minecraft);
            return;
        }
        if (!ACTIVE || capturePending || stage == Stage.IDLE || stage == Stage.COMPLETE
                || Util.getMillis() - openedAt < CAPTURE_DELAY_MILLIS) return;
        capturePending = true;
        String name = switch (stage) {
            case HIVE_854 -> "relics-ui-hive-settings-854x480.png";
            case HIVE_1280 -> "relics-ui-hive-settings-1280x720.png";
            case RESEARCH -> researchName();
            case GALLERY -> "relics-ui-research-gallery-fallback-1280x720.png";
            default -> throw new IllegalStateException("Unexpected UI capture stage " + stage);
        };
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message ->
                minecraft.execute(() -> afterCapture(minecraft, name, message.getString())));
    }

    private static void afterCapture(Minecraft minecraft, String name, String result) {
        RelicsAddon.LOGGER.info("UI smoke capture {}: {}", name, result);
        capturePending = false;
        switch (stage) {
            case HIVE_854 -> openHiveSettings(minecraft, 1280, 720, Stage.HIVE_1280);
            case HIVE_1280 -> openResearchOrFallback(minecraft);
            case RESEARCH -> {
                researchIndex++;
                if (researchIndex < researchEntries.size()) openResearch(minecraft);
                else finish(minecraft, "captured " + researchEntries.size() + " live Relics research screens");
            }
            case GALLERY -> finish(minecraft, "no live Relics research slot was available; captured native-coordinate gallery fallback");
            default -> throw new IllegalStateException("Unexpected UI capture completion " + stage);
        }
    }

    private static void openHiveSettings(Minecraft minecraft, int width, int height, Stage nextStage) {
        minecraft.getWindow().setWindowed(width, height);
        if (minecraft.player != null) HiveSettingsScreen.open();
        else minecraft.setScreen(new HiveSettingsScreen(seededHiveEntries()));
        stage = nextStage;
        openedAt = Util.getMillis();
        RelicsAddon.LOGGER.info("UI smoke stage {} at {}x{} ({})", nextStage, width, height,
                minecraft.player == null ? "seeded read-only Hive settings; no local player" : "live Hive settings");
    }

    private static List<HiveSettingsScreen.Entry> seededHiveEntries() {
        var entries = new ArrayList<HiveSettingsScreen.Entry>();
        int slot = 0;
        for (var item : List.of(ModItems.RF_HIVE.get(), ModItems.MANA_HIVE.get(), ModItems.TWINS_HIVE.get())) {
            var stack = new ItemStack(item);
            stack.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(5));
            stack.set(ModDataComponents.INSTANCE_ID.get(), "ui-smoke-" + item.role().itemId());
            RelicRuntime.ability(null, stack).setLevel(10);
            entries.add(new HiveSettingsScreen.Entry(false, slot++, stack));
        }
        return List.copyOf(entries);
    }

    private static void openResearchOrFallback(Minecraft minecraft) {
        researchEntries = findResearchEntries(minecraft);
        researchIndex = 0;
        if (researchEntries.isEmpty()) {
            minecraft.setScreen(new NativeResearchGallery());
            stage = Stage.GALLERY;
            openedAt = Util.getMillis();
            RelicsAddon.LOGGER.warn("UI smoke cannot open AbilityResearchScreen without a local player and relic container slot; using gallery fallback");
            return;
        }
        openResearch(minecraft);
    }

    private static void openResearch(Minecraft minecraft) {
        ResearchEntry entry = researchEntries.get(researchIndex);
        Screen parent = minecraft.screen == null ? new TitleScreen() : minecraft.screen;
        minecraft.setScreen(new AbilityResearchScreen(minecraft.player, 0, entry.slot(), parent, entry.abilityId()));
        stage = Stage.RESEARCH;
        openedAt = Util.getMillis();
        RelicsAddon.LOGGER.info("UI smoke native research {}/{}: {}/{} at menu slot {}", researchIndex + 1,
                researchEntries.size(), entry.itemId(), entry.abilityId(), entry.slot());
    }

    private static List<ResearchEntry> findResearchEntries(Minecraft minecraft) {
        if (minecraft.player == null) return List.of();
        var entries = new ArrayList<ResearchEntry>();
        Set<String> seen = new HashSet<>();
        for (int slot = 0; slot < minecraft.player.containerMenu.slots.size(); slot++) {
            ItemStack stack = minecraft.player.containerMenu.getSlot(slot).getItem();
            if (!(stack.getItem() instanceof AutonomousRelicItem item) || (!item.role().isShield() && !item.role().isHive())) continue;
            var abilities = item.getRelicData(minecraft.player, stack).getAbilitiesData();
            for (String abilityId : abilities.getAbilityIDs()) {
                var ability = abilities.getAbilityData(abilityId);
                if (ability.getTemplate() == null || ability.getTemplate().getResearchTemplate() == null) continue;
                String key = item.role().itemId() + "/" + abilityId;
                if (seen.add(key)) entries.add(new ResearchEntry(slot, item.role().itemId(), abilityId));
            }
        }
        return List.copyOf(entries);
    }

    private static String researchName() {
        ResearchEntry entry = researchEntries.get(researchIndex);
        return String.format(java.util.Locale.ROOT, "relics-ui-research-%02d-%s-%s-1280x720.png",
                researchIndex + 1, entry.itemId(), entry.abilityId());
    }

    private static void finish(Minecraft minecraft, String reason) {
        stage = Stage.COMPLETE;
        RelicsAddon.LOGGER.info("UI smoke complete: {}; stopping explicitly opted-in capture client", reason);
        minecraft.stop();
    }

    private NativeUiCapture() { }
}
