package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
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
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Holographic console for one device. A frosted-glass window holds a labelled tab bar
 * (overview, batteries, upgrades and, for hives, swarm tasks); every value is written out on
 * screen, controls say what they do, and the "?" button explains the whole window. Changes are
 * server-validated menu buttons.
 */
public final class DeviceControlScreen extends AbstractContainerScreen<DeviceControlMenu> {
    private enum Tab {
        OVERVIEW, POWER, UPGRADES, SWARM;

        String key() {
            return "screen.relics_addon.tab." + name().toLowerCase(Locale.ROOT);
        }
    }

    private record Control(int x, int y, int w, int h, Supplier<Component> label, BooleanSupplier active, BooleanSupplier selected,
                           Runnable action, Supplier<List<Component>> tooltip) { }

    private static final int WIDTH = 232, HEIGHT = 228, WINDOW_H = 130, CUT = 10;
    private static final int TAB_Y = 20, TAB_H = 13, TAB_W = 50;
    private static final int CX = 18, CY = 38, CR = WIDTH - 10;
    private static final int SLIDER_X = 9, SLIDER_TOP = 24, SLIDER_H = 96;
    private static final int TRAY_X = 28, TRAY_Y = 136, TRAY_W = 176;
    private static Tab lastTab = Tab.OVERVIEW;

    private final List<Control> controls = new ArrayList<>();
    private Tab tab = lastTab;
    private boolean help;

    public DeviceControlScreen(DeviceControlMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    private ItemStack device() { return menu.device(); }
    private RelicRole role() { return device().getItem() instanceof AutonomousRelicItem item ? item.role() : RelicRole.RF_SHIELD; }
    private int accent() { return role().color(); }

    private List<Tab> tabs() {
        return role().isHive() ? List.of(Tab.values()) : List.of(Tab.OVERVIEW, Tab.POWER, Tab.UPGRADES);
    }

    @Override protected void init() {
        super.init();
        if (!tabs().contains(tab)) tab = Tab.OVERVIEW;
        rebuildControls();
    }

    private void select(Tab next) {
        tab = lastTab = next;
        help = false;
        rebuildControls();
    }

    // --- controls ------------------------------------------------------------------------------

    private void rebuildControls() {
        controls.clear();
        menu.setVisibleSlots(tab == Tab.OVERVIEW && !help, tab == Tab.POWER && !help && DevicePower.hasRf(role()));
        controls.add(new Control(WIDTH - 36, 6, 13, 11, () -> Component.literal("?"), () -> true, () -> help,
                () -> { help = !help; rebuildControls(); }, () -> List.of(Component.translatable("screen.relics_addon.help_button"))));
        controls.add(new Control(WIDTH - 21, 6, 13, 11, () -> Component.literal("×"), () -> true, () -> false,
                this::onClose, () -> List.of(Component.translatable("screen.relics_addon.close"))));
        List<Tab> tabs = tabs();
        for (int index = 0; index < tabs.size(); index++) {
            Tab value = tabs.get(index);
            controls.add(new Control(CX + index * (TAB_W + 2), TAB_Y, TAB_W, TAB_H, () -> Component.translatable(value.key()),
                    () -> true, () -> tab == value, () -> select(value), List::of));
        }
        if (help) return;
        switch (tab) {
            case OVERVIEW -> controls.add(new Control(CX, 78, 110, 16,
                    () -> Component.translatable(RelicRuntime.enabled(device()) ? "screen.relics_addon.power_off" : "screen.relics_addon.power_on"),
                    () -> true, () -> RelicRuntime.enabled(device()), () -> press(DeviceControlMenu.BUTTON_TOGGLE),
                    () -> List.of(Component.translatable("screen.relics_addon.toggle.hint").withStyle(ChatFormatting.GRAY))));
            case POWER -> {
                int row = 0;
                for (boolean rf : batteries()) {
                    int y = CY + 2 + row++ * 25;
                    controls.add(new Control(CR - 50, y + 9, 50, 14,
                            () -> Component.translatable(batteryOn(rf) ? "screen.relics_addon.battery_on" : "screen.relics_addon.battery_off"),
                            () -> true, () -> batteryOn(rf), () -> press(rf ? DeviceControlMenu.BUTTON_RF_BATTERY : DeviceControlMenu.BUTTON_MANA_BATTERY),
                            () -> List.of(Component.translatable("screen.relics_addon.battery_toggle.hint").withStyle(ChatFormatting.GRAY))));
                }
                if (DevicePower.hasMana(role())) {
                    DeviceEnergy.ManaSource[] sources = DeviceEnergy.ManaSource.values();
                    for (int index = 0; index < sources.length; index++) {
                        DeviceEnergy.ManaSource source = sources[index];
                        controls.add(new Control(CX + index * 52, 102, 50, 14,
                                () -> Component.translatable("screen.relics_addon.mana_source." + source.id()),
                                () -> true, () -> DevicePower.energy(device()).source() == source,
                                () -> press(DeviceControlMenu.BUTTON_MANA_SOURCE_BASE + source.ordinal()),
                                () -> List.of(Component.translatable("screen.relics_addon.mana_source." + source.id() + ".hint").withStyle(ChatFormatting.GRAY))));
                    }
                }
            }
            case UPGRADES -> {
                List<DeviceUpgrade> upgrades = DeviceUpgrade.availableUpgrades(role());
                for (int row = 0; row < upgrades.size(); row++) {
                    DeviceUpgrade upgrade = upgrades.get(row);
                    controls.add(new Control(CR - 66, 54 + row * 24, 64, 14, () -> upgradeButton(upgrade),
                            () -> canBuy(upgrade), () -> false, () -> press(DeviceControlMenu.BUTTON_UPGRADE_BASE + upgrade.ordinal()),
                            () -> upgradeTooltip(upgrade)));
                }
            }
            case SWARM -> {
                int[] ids = {DeviceControlMenu.BUTTON_HEALERS_MINUS_10, DeviceControlMenu.BUTTON_HEALERS_MINUS_1,
                        DeviceControlMenu.BUTTON_HEALERS_PLUS_1, DeviceControlMenu.BUTTON_HEALERS_PLUS_10};
                String[] labels = {"−10", "−1", "+1", "+10"};
                int[] xs = {CX, CX + 32, CR - 62, CR - 30};
                for (int index = 0; index < ids.length; index++) {
                    int id = ids[index];
                    String label = labels[index];
                    controls.add(new Control(xs[index], 70, 30, 14, () -> Component.literal(label),
                            () -> id <= DeviceControlMenu.BUTTON_HEALERS_MINUS_1 ? healers() > 0 : healers() < hiveCapacity(),
                            () -> false, () -> press(id), () -> List.of(Component.translatable("screen.relics_addon.healers_adjust", label))));
                }
            }
        }
    }

    private void press(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private boolean[] batteries() {
        boolean rf = DevicePower.hasRf(role()), mana = DevicePower.hasMana(role());
        return rf && mana ? new boolean[]{true, false} : rf ? new boolean[]{true} : new boolean[]{false};
    }

    private boolean batteryOn(boolean rf) {
        DeviceEnergy energy = DevicePower.energy(device());
        return rf ? energy.rfOn() : energy.manaOn();
    }

    private double batteryFraction(boolean rf) {
        DeviceEnergy energy = DevicePower.energy(device());
        return rf ? energy.rf() / (double) DevicePower.feCapacity(device()) : energy.mana() / (double) DevicePower.capacity(device());
    }

    private boolean canBuy(DeviceUpgrade upgrade) {
        DeviceProgression state = RelicRuntime.progression(device());
        return state.points() > 0 && state.level() >= upgrade.requiredLevel() && state.rank(upgrade.id()) < 3;
    }

    private Component upgradeButton(DeviceUpgrade upgrade) {
        DeviceProgression state = RelicRuntime.progression(device());
        if (state.rank(upgrade.id()) >= 3) return Component.translatable("screen.relics_addon.upgrade_maxed");
        if (state.level() < upgrade.requiredLevel()) return Component.translatable("screen.relics_addon.upgrade_needs_level", upgrade.requiredLevel());
        if (state.points() <= 0) return Component.translatable("screen.relics_addon.upgrade_no_points");
        return Component.translatable("screen.relics_addon.upgrade_buy");
    }

    private int hiveCapacity() { return minecraft == null || minecraft.player == null ? 0 : HiveController.capacity(minecraft.player, device()); }
    private int healers() { return HiveTaskController.settings(device()).healerCount(hiveCapacity()); }

    private static String upgradeKey(RelicRole role, DeviceUpgrade upgrade) { return "upgrade.relics_addon." + role.itemId() + "." + upgrade.id(); }

    private List<Component> upgradeTooltip(DeviceUpgrade upgrade) {
        DeviceProgression state = RelicRuntime.progression(device());
        return List.of(Component.translatable(upgradeKey(role(), upgrade)).withStyle(ChatFormatting.AQUA),
                Component.translatable(upgradeKey(role(), upgrade) + ".desc").withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.relics_addon.upgrade_rank", state.rank(upgrade.id()), 3),
                Component.translatable("screen.relics_addon.upgrade_requirement", upgrade.requiredLevel())
                        .withStyle(state.level() >= upgrade.requiredLevel() ? ChatFormatting.GREEN : ChatFormatting.RED),
                Component.translatable("screen.relics_addon.upgrade_cost", 1).withStyle(state.points() > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    // --- rendering -----------------------------------------------------------------------------

    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        HoloPaint.window(g, x, y, WIDTH, WINDOW_H, CUT);
        HoloPaint.panel(g, x + TRAY_X, y + TRAY_Y, TRAY_W, HEIGHT - TRAY_Y, 6);
        DeviceProgression state = RelicRuntime.progression(device());
        double xp = state.level() >= DeviceProgression.MAX_LEVEL ? 1 : state.experience() / (double) RelicRuntime.experienceToNext(state.level());
        HoloPaint.slider(g, x + SLIDER_X, y + SLIDER_TOP, SLIDER_H, xp, accent());
        HoloPaint.ticks(g, x + 34, y + WINDOW_H - 4, WIDTH - 80, 16);
        g.fill(x + CX, y + TAB_Y + TAB_H + 1, x + CR, y + TAB_Y + TAB_H + 2, 0x50E6EBF0);
        if (!help) {
            switch (tab) {
                case OVERVIEW -> renderOverview(g, state);
                case POWER -> renderPower(g);
                case UPGRADES -> renderUpgrades(g, state);
                case SWARM -> renderSwarm(g);
            }
        }
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            HoloPaint.slot(g, x + DeviceControlMenu.INVENTORY_X + column * 18, y + DeviceControlMenu.INVENTORY_Y + row * 18);
        for (int column = 0; column < 9; column++) HoloPaint.slot(g, x + DeviceControlMenu.INVENTORY_X + column * 18, y + DeviceControlMenu.INVENTORY_Y + 58);
        for (Control control : controls) {
            boolean hovered = inside(mouseX, mouseY, x + control.x, y + control.y, control.w, control.h);
            boolean active = control.active.getAsBoolean();
            HoloPaint.button(g, x + control.x, y + control.y, control.w, control.h, active, hovered, control.selected.getAsBoolean(), accent());
            g.drawCenteredString(font, control.label.get(), x + control.x + control.w / 2, y + control.y + (control.h - 8) / 2,
                    active ? HoloPaint.TEXT : HoloPaint.TEXT_FAINT);
        }
    }

    private void renderOverview(GuiGraphics g, DeviceProgression state) {
        int x = leftPos, y = topPos;
        ItemStack stack = device();
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + CX, y + CY, 0);
        pose.scale(2, 2, 1);
        g.renderItem(stack, 0, 0);
        pose.popPose();
        g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), 76), x + CX + 36, y + CY + 2, HoloPaint.TEXT, false);
        boolean enabled = RelicRuntime.enabled(stack);
        boolean powered = minecraft == null || DevicePower.powered(minecraft.player, stack);
        String status = !enabled ? "screen.relics_addon.monitor_offline" : powered ? "screen.relics_addon.monitor_online" : "screen.relics_addon.monitor_no_power";
        int color = !enabled ? 0xF2837B : powered ? 0x7BF28B : 0xF2C94C;
        g.drawString(font, Component.literal("● ").append(Component.translatable(status)), x + CX + 36, y + CY + 13, color, false);
        g.drawString(font, Component.translatable("screen.relics_addon.overview_level", state.level(), DeviceProgression.MAX_LEVEL, state.points()),
                x + CX + 36, y + CY + 24, HoloPaint.TEXT_DIM, false);

        // Integrity (shield) or ready drones (hive), then battery charge.
        int barY = y + 100;
        if (role().isHive()) {
            HiveStackState hive = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
            long alive = hive.units().stream().filter(unit -> unit.hp() > 0).count();
            labelledBar(g, x + CX, barY, 110, alive / (double) Math.max(1, hiveCapacity()), 0xFFE0B04A,
                    Component.translatable("screen.relics_addon.overview_drones", alive, hiveCapacity()));
        } else if (minecraft != null && minecraft.player != null) {
            ShieldStackState shield = stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            int total = ShieldParameters.totalCapacity(minecraft.player, stack);
            labelledBar(g, x + CX, barY, 110, shield.totalIntegrity() / (double) Math.max(1, total), 0xFF000000 | accent(),
                    Component.translatable("screen.relics_addon.overview_integrity", shield.totalIntegrity(), total));
        }
        boolean[] batteries = batteries();
        int width = batteries.length == 2 ? 54 : 110;
        for (int index = 0; index < batteries.length; index++) {
            boolean rf = batteries[index];
            labelledBar(g, x + CX + index * 56, barY + 13, width, batteryFraction(rf), batteryColor(rf),
                    Component.translatable(rf ? "screen.relics_addon.battery_rf_short" : "screen.relics_addon.battery_mana_short")
                            .append(" " + Math.round(batteryFraction(rf) * 100) + "%"));
        }

        // Module bay with what each bay does written under it.
        int bayX = x + DeviceControlMenu.MODULE_X, bayY = y + DeviceControlMenu.MODULE_Y;
        g.drawString(font, Component.translatable("screen.relics_addon.module_bay"), bayX - 2, y + CY + 2, HoloPaint.TEXT, false);
        String kind = role().isHive() ? "hive" : "shield";
        for (int index = 0; index < DeviceProgression.MODULE_SLOTS; index++) {
            int sx = bayX + index * DeviceControlMenu.MODULE_SPACING;
            HoloPaint.slot(g, sx, bayY);
            boolean installed = state.hasModule(index);
            if (installed) g.fill(sx - 1, bayY + 17, sx + 17, bayY + 18, 0xFF000000 | accent());
            small(g, Component.translatable("screen.relics_addon.module_short." + kind + "." + index), sx + 8, bayY + 21,
                    installed ? HoloPaint.TEXT : HoloPaint.TEXT_FAINT);
        }
        List<FormattedCharSequence> hint = font.split(Component.translatable("screen.relics_addon.module_hint"), CR - bayX + 2);
        for (int line = 0; line < Math.min(3, hint.size()); line++) {
            g.drawString(font, hint.get(line), bayX - 2, bayY + 34 + line * 10, HoloPaint.TEXT_FAINT, false);
        }
    }

    private void renderPower(GuiGraphics g) {
        int x = leftPos, y = topPos;
        ItemStack stack = device();
        DeviceEnergy energy = DevicePower.energy(stack);
        int row = 0;
        for (boolean rf : batteries()) {
            int ry = y + CY + 2 + row++ * 25;
            g.drawString(font, Component.translatable(rf ? "screen.relics_addon.battery_rf" : "screen.relics_addon.battery_mana"), x + CX, ry, HoloPaint.TEXT, false);
            Component amount = rf ? Component.translatable("screen.relics_addon.battery_rf_amount", energy.rf(), DevicePower.feCapacity(stack))
                    : Component.translatable("screen.relics_addon.battery_mana_amount", energy.mana(), DevicePower.capacity(stack));
            labelledBar(g, x + CX, ry + 10, CR - 54 - CX, batteryFraction(rf), batteryOn(rf) ? batteryColor(rf) : 0xFF4A4E55, amount);
        }
        if (DevicePower.hasMana(role())) {
            g.drawString(font, Component.translatable("screen.relics_addon.mana_source_label"), x + CX, y + 92, HoloPaint.TEXT_DIM, false);
        } else {
            List<FormattedCharSequence> hint = font.split(Component.translatable("screen.relics_addon.battery_rf.hint"), 170);
            for (int line = 0; line < Math.min(3, hint.size()); line++) g.drawString(font, hint.get(line), x + CX, y + 92 + line * 10, HoloPaint.TEXT_FAINT, false);
        }
        if (DevicePower.hasRf(role())) {
            int sx = x + DeviceControlMenu.CHARGE_X, sy = y + DeviceControlMenu.CHARGE_Y;
            small(g, Component.translatable("screen.relics_addon.charge_slot_short"), sx + 8, sy - 9, HoloPaint.TEXT_DIM);
            HoloPaint.slot(g, sx, sy);
            if (menu.getSlot(DeviceControlMenu.CHARGE_SLOT).getItem().isEmpty()) {
                g.drawCenteredString(font, "⚡", sx + 8, sy + 4, 0x707880);
            }
        }
    }

    private void renderUpgrades(GuiGraphics g, DeviceProgression state) {
        int x = leftPos, y = topPos;
        g.drawString(font, Component.translatable("screen.relics_addon.upgrade_points", state.points()), x + CX, y + CY + 2, 0xFF000000 | accent(), false);
        Component level = Component.translatable("screen.relics_addon.monitor_level", state.level(), DeviceProgression.MAX_LEVEL);
        g.drawString(font, level, x + CR - font.width(level), y + CY + 2, HoloPaint.TEXT_DIM, false);
        List<DeviceUpgrade> upgrades = DeviceUpgrade.availableUpgrades(role());
        for (int row = 0; row < upgrades.size(); row++) {
            DeviceUpgrade upgrade = upgrades.get(row);
            int ry = y + 50 + row * 24;
            g.fill(x + CX, ry, x + CR, ry + 22, 0x30FFFFFF);
            HoloPaint.box(g, x + CX, ry, CR - CX, 22, 0x50E6EBF0);
            ResourceLocation icon = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID,
                    "textures/gui/upgrades/" + role().itemId() + "/" + upgrade.id() + ".png");
            g.blit(icon, x + CX + 3, ry + 4, 10, 14, 0, 0, 22, 31, 22, 31);
            String name = font.plainSubstrByWidth(Component.translatable(upgradeKey(role(), upgrade)).getString(), CR - CX - 90);
            g.drawString(font, name, x + CX + 17, ry + 3, state.level() >= upgrade.requiredLevel() ? HoloPaint.TEXT : HoloPaint.TEXT_FAINT, false);
            int rank = state.rank(upgrade.id());
            for (int pip = 0; pip < 3; pip++) {
                int px = x + CX + 17 + pip * 8;
                g.fill(px, ry + 14, px + 6, ry + 18, pip < rank ? 0xFF000000 | accent() : 0x60FFFFFF);
            }
            small(g, Component.translatable("screen.relics_addon.upgrade_rank", rank, 3), x + CX + 58, ry + 14, HoloPaint.TEXT_FAINT);
        }
    }

    private void renderSwarm(GuiGraphics g) {
        int x = leftPos, y = topPos;
        int capacity = hiveCapacity(), healers = healers(), fighters = capacity - healers;
        g.drawString(font, Component.translatable("screen.relics_addon.swarm_title"), x + CX, y + CY + 2, HoloPaint.TEXT, false);
        int barX = x + CX, barY = y + 52, barW = CR - CX;
        g.fill(barX, barY, barX + barW, barY + 12, 0x80101317);
        int split = capacity == 0 ? barW : (int) Math.round(barW * fighters / (double) capacity);
        g.fill(barX + 1, barY + 1, barX + Math.max(1, split), barY + 11, 0xC0000000 | accent() & 0xFFFFFF);
        g.fill(barX + split, barY + 1, barX + barW - 1, barY + 11, 0xC05FCB7A);
        HoloPaint.box(g, barX, barY, barW, 12, 0x90E6EBF0);
        g.drawString(font, Component.translatable("screen.relics_addon.fighters", fighters), barX + 4, barY + 2, HoloPaint.TEXT, true);
        Component healing = Component.translatable("screen.relics_addon.healers", healers);
        g.drawString(font, healing, barX + barW - 4 - font.width(healing), barY + 2, HoloPaint.TEXT, true);
        g.drawCenteredString(font, Component.translatable("screen.relics_addon.healers_move"), x + (CX + CR) / 2, y + 73, HoloPaint.TEXT_DIM);
        List<FormattedCharSequence> hint = font.split(Component.translatable("screen.relics_addon.swarm_hint"), CR - CX);
        for (int line = 0; line < Math.min(3, hint.size()); line++) g.drawString(font, hint.get(line), x + CX, y + 92 + line * 10, HoloPaint.TEXT_FAINT, false);
    }

    private void renderHelp(GuiGraphics g) {
        int x = leftPos, y = topPos;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x + 6, y + CY - 2, x + WIDTH - 6, y + WINDOW_H - 6, 0xE8202328);
        List<FormattedCharSequence> lines = font.split(Component.translatable("screen.relics_addon.help"), WIDTH - 28);
        for (int line = 0; line < Math.min(9, lines.size()); line++) g.drawString(font, lines.get(line), x + 14, y + CY + 2 + line * 10, HoloPaint.TEXT, false);
        g.pose().popPose();
    }

    private void labelledBar(GuiGraphics g, int x, int y, int width, double fraction, int color, Component label) {
        HoloPaint.bar(g, x, y, width, 11, fraction, color);
        g.drawString(font, font.plainSubstrByWidth(label.getString(), width - 6), x + 3, y + 2, HoloPaint.TEXT, true);
    }

    private void small(GuiGraphics g, Component text, int centerX, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(centerX, y, 0);
        g.pose().scale(.75F, .75F, 1);
        g.drawCenteredString(font, text, 0, 0, color);
        g.pose().popPose();
    }

    private static int batteryColor(boolean rf) {
        return rf ? 0xFFE0523C : 0xFF3FA9F5;
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        Component heading = Component.translatable("screen.relics_addon.console_title", device().getHoverName());
        g.drawString(font, font.plainSubstrByWidth(heading.getString(), WIDTH - 60), 18, 8, HoloPaint.TEXT, false);
        g.drawString(font, playerInventoryTitle, DeviceControlMenu.INVENTORY_X, TRAY_Y + 3, HoloPaint.TEXT_DIM, false);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (help) renderHelp(g);
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
        if (help) return List.of();
        DeviceProgression state = RelicRuntime.progression(device());
        if (inside(mouseX, mouseY, leftPos + SLIDER_X - 3, topPos + SLIDER_TOP - 3, 7, SLIDER_H + 6)) {
            return state.level() >= DeviceProgression.MAX_LEVEL ? List.of(Component.translatable("screen.relics_addon.max_level"))
                    : List.of(Component.translatable("screen.relics_addon.experience", state.experience(), RelicRuntime.experienceToNext(state.level())),
                            Component.translatable("screen.relics_addon.experience.hint").withStyle(ChatFormatting.GRAY));
        }
        if (tab == Tab.OVERVIEW) {
            String kind = role().isHive() ? "hive" : "shield";
            for (int index = 0; index < DeviceProgression.MODULE_SLOTS; index++) {
                int sx = leftPos + DeviceControlMenu.MODULE_X + index * DeviceControlMenu.MODULE_SPACING, sy = topPos + DeviceControlMenu.MODULE_Y;
                if (inside(mouseX, mouseY, sx - 1, sy - 1, 18, 28)) {
                    return List.of(Component.translatable("screen.relics_addon.module", index + 1).withStyle(ChatFormatting.AQUA),
                            Component.translatable("screen.relics_addon.module_effect." + kind + "." + index).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (tab == Tab.POWER && DevicePower.hasRf(role()) && inside(mouseX, mouseY, leftPos + DeviceControlMenu.CHARGE_X - 1, topPos + DeviceControlMenu.CHARGE_Y - 1, 18, 18)) {
            return List.of(Component.translatable("screen.relics_addon.charge_slot").withStyle(ChatFormatting.AQUA),
                    Component.translatable("screen.relics_addon.charge_slot.hint").withStyle(ChatFormatting.GRAY));
        }
        if (tab == Tab.POWER) {
            int row = 0;
            for (boolean rf : batteries()) {
                if (inside(mouseX, mouseY, leftPos + CX, topPos + CY + 2 + row++ * 25, CR - 54 - CX, 22)) {
                    return List.of(Component.translatable(rf ? "screen.relics_addon.battery_rf.hint" : "screen.relics_addon.battery_mana.hint").withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (tab == Tab.UPGRADES) {
            List<DeviceUpgrade> upgrades = DeviceUpgrade.availableUpgrades(role());
            for (int row = 0; row < upgrades.size(); row++) {
                if (inside(mouseX, mouseY, leftPos + CX, topPos + 50 + row * 24, CR - CX, 22)) return upgradeTooltip(upgrades.get(row));
            }
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
            if (help && inside(mouseX, mouseY, leftPos, topPos, WIDTH, WINDOW_H)) {
                help = false;
                rebuildControls();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playClick() {
        if (minecraft != null) minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, .6F));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
