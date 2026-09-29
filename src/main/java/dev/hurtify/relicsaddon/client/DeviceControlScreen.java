package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.DeviceUpgrade;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Machine-console screen for one device: a status monitor, XP and integrity gauges, a module bay
 * and side tabs for upgrades and hive tasks. Every change is a server-validated menu button.
 */
public final class DeviceControlScreen extends AbstractContainerScreen<DeviceControlMenu> {
    private enum Tab { MAIN, UPGRADES, TASKS }

    private record Control(int x, int y, int w, int h, Supplier<Component> label, BooleanSupplier active, Runnable action,
                           Supplier<List<Component>> tooltip) { }

    private static final int MONITOR_X = 22, MONITOR_Y = 16, MONITOR_W = 132, MONITOR_H = 50;
    private static final int XP_GAUGE_X = 7, INTEGRITY_GAUGE_X = 159, GAUGE_Y = 16, GAUGE_W = 10, GAUGE_H = 110;
    private static final int XP_COLOR = 0xFF7BD35B, HIVE_COLOR = 0xFFE0B04A;
    private static Tab lastTab = Tab.MAIN;

    private final List<Control> controls = new ArrayList<>();
    private Tab tab = lastTab;

    public DeviceControlScreen(DeviceControlMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 222;
        inventoryLabelY = DeviceControlMenu.INVENTORY_Y - 11;
        titleLabelY = 5;
    }

    private ItemStack device() { return menu.device(); }
    private RelicRole role() { return device().getItem() instanceof AutonomousRelicItem item ? item.role() : RelicRole.RF_SHIELD; }

    @Override protected void init() {
        super.init();
        if (tab == Tab.TASKS && !role().isHive()) tab = Tab.MAIN;
        rebuildControls();
    }

    private void select(Tab next) {
        tab = lastTab = next;
        rebuildControls();
    }

    private void rebuildControls() {
        controls.clear();
        menu.setModulesVisible(tab == Tab.MAIN);
        boolean multiple = minecraft != null && minecraft.player != null && DeviceTargets.collect(minecraft.player).size() > 1;
        controls.add(new Control(MONITOR_X + 3, MONITOR_Y + 3, 11, 11, () -> Component.literal("<"), () -> multiple,
                () -> DeviceTargets.cycle(menu.charm(), menu.deviceSlot(), -1), () -> List.of(Component.translatable("screen.relics_addon.previous_device"))));
        controls.add(new Control(MONITOR_X + MONITOR_W - 14, MONITOR_Y + 3, 11, 11, () -> Component.literal(">"), () -> multiple,
                () -> DeviceTargets.cycle(menu.charm(), menu.deviceSlot(), 1), () -> List.of(Component.translatable("screen.relics_addon.next_device"))));
        switch (tab) {
            case MAIN -> {
                controls.add(new Control(MONITOR_X, 98, 60, 18,
                        () -> Component.translatable(RelicRuntime.enabled(device()) ? "screen.relics_addon.power_off" : "screen.relics_addon.power_on"),
                        () -> true, () -> press(DeviceControlMenu.BUTTON_TOGGLE), () -> List.of(Component.translatable("screen.relics_addon.toggle"))));
            }
            case UPGRADES -> {
                List<DeviceUpgrade> upgrades = DeviceUpgrade.availableUpgrades(role());
                for (int row = 0; row < upgrades.size(); row++) {
                    DeviceUpgrade upgrade = upgrades.get(row);
                    controls.add(new Control(MONITOR_X + MONITOR_W - 18, 70 + row * 19, 18, 17, () -> Component.literal("+"),
                            () -> canBuy(upgrade), () -> press(DeviceControlMenu.BUTTON_UPGRADE_BASE + upgrade.ordinal()), () -> upgradeTooltip(upgrade)));
                }
            }
            case TASKS -> {
                int[] buttons = {DeviceControlMenu.BUTTON_HEALERS_MINUS_10, DeviceControlMenu.BUTTON_HEALERS_MINUS_1,
                        DeviceControlMenu.BUTTON_HEALERS_PLUS_1, DeviceControlMenu.BUTTON_HEALERS_PLUS_10};
                String[] labels = {"-10", "-1", "+1", "+10"};
                for (int index = 0; index < buttons.length; index++) {
                    int id = buttons[index];
                    String label = labels[index];
                    controls.add(new Control(MONITOR_X + index * 34, 106, 30, 18, () -> Component.literal(label),
                            () -> id <= DeviceControlMenu.BUTTON_HEALERS_MINUS_1 ? healers() > 0 : healers() < hiveCapacity(),
                            () -> press(id), () -> List.of(Component.translatable("screen.relics_addon.healers_adjust", label))));
                }
            }
        }
    }

    private void press(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private boolean canBuy(DeviceUpgrade upgrade) {
        DeviceProgression state = RelicRuntime.progression(device());
        return state.points() > 0 && state.level() >= upgrade.requiredLevel() && state.rank(upgrade.id()) < 3;
    }

    private int hiveCapacity() { return minecraft == null || minecraft.player == null ? 0 : HiveController.capacity(minecraft.player, device()); }
    private int healers() { return HiveTaskController.settings(device()).healerCount(hiveCapacity()); }

    private static String upgradeKey(RelicRole role, DeviceUpgrade upgrade) { return "upgrade.relics_addon." + role.itemId() + "." + upgrade.id(); }

    private List<Component> upgradeTooltip(DeviceUpgrade upgrade) {
        DeviceProgression state = RelicRuntime.progression(device());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(upgradeKey(role(), upgrade)).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable(upgradeKey(role(), upgrade) + ".desc").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("screen.relics_addon.upgrade_rank", state.rank(upgrade.id()), 3));
        lines.add(Component.translatable("screen.relics_addon.upgrade_requirement", upgrade.requiredLevel())
                .withStyle(state.level() >= upgrade.requiredLevel() ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Component.translatable("screen.relics_addon.upgrade_cost", 1)
                .withStyle(state.points() > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        return lines;
    }

    // --- rendering -------------------------------------------------------------------------

    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        ConsolePaint.panel(g, x, y, imageWidth, imageHeight);
        Tab[] tabs = role().isHive() ? Tab.values() : new Tab[] {Tab.MAIN, Tab.UPGRADES};
        for (int index = 0; index < tabs.length; index++) {
            int tx = x + imageWidth - 1, ty = y + 8 + index * 24;
            ConsolePaint.tab(g, tx, ty, tabs[index] == tab, inside(mouseX, mouseY, tx, ty, 22, 22));
            g.renderItem(tabIcon(tabs[index]), tx + 2, ty + 3);
        }
        ItemStack stack = device();
        DeviceProgression state = RelicRuntime.progression(stack);
        int needed = RelicRuntime.experienceToNext(state.level());
        double xp = state.level() >= DeviceProgression.MAX_LEVEL ? 1 : state.experience() / (double) needed;
        ConsolePaint.gauge(g, x + XP_GAUGE_X, y + GAUGE_Y, GAUGE_W, GAUGE_H, xp, XP_COLOR);
        ConsolePaint.gauge(g, x + INTEGRITY_GAUGE_X, y + GAUGE_Y, GAUGE_W, GAUGE_H, integrity(stack),
                role().isHive() ? HIVE_COLOR : 0xFF000000 | role().color());
        renderMonitor(g, stack, state);
        switch (tab) {
            case MAIN -> renderModules(g, state);
            case UPGRADES -> renderUpgrades(g, state);
            case TASKS -> renderTasks(g);
        }
        ConsolePaint.screen(g, x + DeviceControlMenu.INVENTORY_X - 2, y + DeviceControlMenu.INVENTORY_Y - 2, 9 * 18 + 4, 3 * 18 + 4);
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            ConsolePaint.slot(g, x + DeviceControlMenu.INVENTORY_X + column * 18, y + DeviceControlMenu.INVENTORY_Y + row * 18);
        for (int column = 0; column < 9; column++) ConsolePaint.slot(g, x + DeviceControlMenu.INVENTORY_X + column * 18, y + DeviceControlMenu.INVENTORY_Y + 58);
        for (Control control : controls) {
            boolean hovered = inside(mouseX, mouseY, x + control.x, y + control.y, control.w, control.h);
            boolean active = control.active.getAsBoolean();
            ConsolePaint.button(g, x + control.x, y + control.y, control.w, control.h, active, hovered, 0xFF000000 | role().color());
            g.drawCenteredString(font, control.label.get(), x + control.x + control.w / 2 + 1, y + control.y + (control.h - 8) / 2,
                    active ? 0xFFFFFF : 0x9A9A9A);
        }
    }

    private ItemStack tabIcon(Tab value) {
        return switch (value) {
            case MAIN -> new ItemStack(ModItems.DEVICE_MODULE.get());
            case UPGRADES -> new ItemStack(Items.EXPERIENCE_BOTTLE);
            case TASKS -> new ItemStack(Items.GLISTERING_MELON_SLICE);
        };
    }

    private double integrity(ItemStack stack) {
        if (minecraft == null || minecraft.player == null) return 0;
        if (role().isHive()) {
            HiveStackState hive = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
            int alive = (int) hive.units().stream().filter(unit -> unit.hp() > 0).count();
            return alive / (double) Math.max(1, hiveCapacity());
        }
        ShieldStackState shield = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        return shield.totalIntegrity() / (double) Math.max(1, ShieldParameters.totalCapacity(minecraft.player, stack));
    }

    private void renderMonitor(GuiGraphics g, ItemStack stack, DeviceProgression state) {
        int x = leftPos + MONITOR_X, y = topPos + MONITOR_Y;
        ConsolePaint.screen(g, x, y, MONITOR_W, MONITOR_H);
        g.renderItem(stack, x + 5, y + 18);
        String name = font.plainSubstrByWidth(stack.getHoverName().getString(), MONITOR_W - 36);
        g.drawCenteredString(font, name, x + MONITOR_W / 2, y + 5, ConsolePaint.SCREEN_TEXT);
        boolean enabled = RelicRuntime.enabled(stack);
        g.drawString(font, Component.translatable("screen.relics_addon.monitor_level", state.level(), DeviceProgression.MAX_LEVEL), x + 26, y + 18, ConsolePaint.SCREEN_TEXT, false);
        g.drawString(font, Component.translatable("screen.relics_addon.monitor_points", state.points()), x + 26, y + 28, ConsolePaint.SCREEN_TEXT, false);
        g.drawString(font, Component.translatable(enabled ? "screen.relics_addon.monitor_online" : "screen.relics_addon.monitor_offline"),
                x + 26, y + 38, enabled ? 0x7BF28B : 0xF2837B, false);
        g.fill(x + MONITOR_W - 8, y + 39, x + MONITOR_W - 4, y + 43, enabled ? 0xFF7BF28B : 0xFFF2837B);
        String location = Component.translatable(menu.charm() ? "screen.relics_addon.charm_slot" : "screen.relics_addon.inventory_slot", menu.deviceSlot() + 1).getString();
        g.drawString(font, location, x + MONITOR_W - 4 - font.width(location), y + 28, ConsolePaint.SCREEN_DIM, false);
    }

    private void renderModules(GuiGraphics g, DeviceProgression state) {
        for (int index = 0; index < DeviceProgression.MODULE_SLOTS; index++) {
            int sx = leftPos + DeviceControlMenu.MODULE_X + index * DeviceControlMenu.MODULE_SPACING, sy = topPos + DeviceControlMenu.MODULE_Y;
            ConsolePaint.slot(g, sx, sy);
            g.fill(sx - 1, sy + 18, sx + 17, sy + 20, state.hasModule(index) ? 0xFF000000 | role().color() : 0xFF4A4E55);
        }
        g.drawString(font, Component.translatable("screen.relics_addon.module_bay"), leftPos + MONITOR_X, topPos + DeviceControlMenu.MODULE_Y + 4, 0x404040, false);
    }

    private void renderUpgrades(GuiGraphics g, DeviceProgression state) {
        List<DeviceUpgrade> upgrades = DeviceUpgrade.availableUpgrades(role());
        for (int row = 0; row < upgrades.size(); row++) {
            DeviceUpgrade upgrade = upgrades.get(row);
            int rx = leftPos + MONITOR_X, ry = topPos + 70 + row * 19;
            ConsolePaint.screen(g, rx, ry, MONITOR_W - 20, 17);
            ResourceLocation icon = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID,
                    "textures/gui/upgrades/" + role().itemId() + "/" + upgrade.id() + ".png");
            g.blit(icon, rx + 2, ry + 2, 10, 13, 0, 0, 22, 31, 22, 31);
            boolean unlocked = state.level() >= upgrade.requiredLevel();
            String name = font.plainSubstrByWidth(Component.translatable(upgradeKey(role(), upgrade)).getString(), MONITOR_W - 62);
            g.drawString(font, name, rx + 15, ry + 5, unlocked ? ConsolePaint.SCREEN_TEXT : ConsolePaint.SCREEN_DIM, false);
            int rank = state.rank(upgrade.id());
            for (int pip = 0; pip < 3; pip++) {
                int px = rx + MONITOR_W - 42 + pip * 7;
                g.fill(px, ry + 6, px + 5, ry + 11, pip < rank ? 0xFF000000 | role().color() : 0xFF2F3A45);
            }
        }
    }

    private void renderTasks(GuiGraphics g) {
        int capacity = hiveCapacity(), healers = healers();
        int x = leftPos + MONITOR_X, y = topPos + 70;
        ConsolePaint.screen(g, x, y, MONITOR_W, 32);
        g.drawString(font, Component.translatable("screen.relics_addon.fighters", capacity - healers), x + 5, y + 5, 0x84DCEA, false);
        g.drawString(font, Component.translatable("screen.relics_addon.healers", healers), x + 5, y + 18, 0x9DDEAD, false);
        int bar = MONITOR_W - 70;
        int filled = capacity == 0 ? 0 : bar * healers / capacity;
        g.fill(x + 64, y + 20, x + 64 + bar, y + 25, 0xFF2F3A45);
        g.fill(x + 64, y + 20, x + 64 + filled, y + 25, 0xFF9DDEAD);
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, (imageWidth - font.width(title)) / 2, titleLabelY, 0x303030, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        if (menu.getCarried().isEmpty() && hoveredSlot == null) {
            List<Component> tooltip = hoverTooltip(mouseX, mouseY);
            if (!tooltip.isEmpty()) g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private List<Component> hoverTooltip(int mouseX, int mouseY) {
        for (Control control : controls) {
            if (inside(mouseX, mouseY, leftPos + control.x, topPos + control.y, control.w, control.h)) return control.tooltip.get();
        }
        ItemStack stack = device();
        DeviceProgression state = RelicRuntime.progression(stack);
        if (inside(mouseX, mouseY, leftPos + XP_GAUGE_X, topPos + GAUGE_Y, GAUGE_W, GAUGE_H)) {
            return state.level() >= DeviceProgression.MAX_LEVEL ? List.of(Component.translatable("screen.relics_addon.max_level"))
                    : List.of(Component.translatable("screen.relics_addon.experience", state.experience(), RelicRuntime.experienceToNext(state.level())));
        }
        if (inside(mouseX, mouseY, leftPos + INTEGRITY_GAUGE_X, topPos + GAUGE_Y, GAUGE_W, GAUGE_H) && minecraft != null && minecraft.player != null) {
            if (role().isHive()) {
                HiveStackState hive = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
                long alive = hive.units().stream().filter(unit -> unit.hp() > 0).count();
                return List.of(Component.translatable("screen.relics_addon.drones_alive", alive, hiveCapacity()));
            }
            ShieldStackState shield = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            return List.of(Component.translatable("screen.relics_addon.shield_integrity", shield.totalIntegrity(), ShieldParameters.totalCapacity(minecraft.player, stack)));
        }
        if (tab == Tab.MAIN) {
            for (int index = 0; index < DeviceProgression.MODULE_SLOTS; index++) {
                int sx = leftPos + DeviceControlMenu.MODULE_X + index * DeviceControlMenu.MODULE_SPACING, sy = topPos + DeviceControlMenu.MODULE_Y;
                if (inside(mouseX, mouseY, sx - 1, sy - 1, 18, 21)) {
                    String kind = role().isHive() ? "hive" : "shield";
                    return List.of(Component.translatable("screen.relics_addon.module", index + 1).withStyle(ChatFormatting.AQUA),
                            Component.translatable("screen.relics_addon.module_effect." + kind + "." + index).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (tab == Tab.UPGRADES) {
            List<DeviceUpgrade> upgrades = DeviceUpgrade.availableUpgrades(role());
            for (int row = 0; row < upgrades.size(); row++) {
                if (inside(mouseX, mouseY, leftPos + MONITOR_X, topPos + 70 + row * 19, MONITOR_W - 20, 17)) return upgradeTooltip(upgrades.get(row));
            }
        }
        Tab[] tabs = role().isHive() ? Tab.values() : new Tab[] {Tab.MAIN, Tab.UPGRADES};
        for (int index = 0; index < tabs.length; index++) {
            if (inside(mouseX, mouseY, leftPos + imageWidth - 1, topPos + 8 + index * 24, 22, 22))
                return List.of(Component.translatable("screen.relics_addon.tab." + tabs[index].name().toLowerCase(java.util.Locale.ROOT)));
        }
        return List.of();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Control control : controls) {
                if (control.active.getAsBoolean() && inside(mouseX, mouseY, leftPos + control.x, topPos + control.y, control.w, control.h)) {
                    control.action.run();
                    playClick();
                    return true;
                }
            }
            Tab[] tabs = role().isHive() ? Tab.values() : new Tab[] {Tab.MAIN, Tab.UPGRADES};
            for (int index = 0; index < tabs.length; index++) {
                if (inside(mouseX, mouseY, leftPos + imageWidth - 1, topPos + 8 + index * 24, 22, 22)) {
                    if (tab != tabs[index]) { select(tabs[index]); playClick(); }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        boolean onTabs = mouseX >= left + imageWidth - 1 && mouseX < left + imageWidth + 22 && mouseY >= top + 8 && mouseY < top + 8 + 3 * 24;
        return !onTabs && super.hasClickedOutside(mouseX, mouseY, left, top, button);
    }

    private void playClick() {
        if (minecraft != null) minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, .6F));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
