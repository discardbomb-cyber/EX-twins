package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveFigures;
import dev.hurtify.relicsaddon.drone.HiveFlightPlan;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import dev.hurtify.relicsaddon.network.HiveAllocationPayload;
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
import dev.hurtify.relicsaddon.shipshield.ShipDeviceState;
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

    /** Screen area where text had to be cut this frame, and the full text shown when hovering it. */
    private record Clipped(int x, int y, int w, int h, Component text) { }

    private static final int WIDTH = 232, HEIGHT = 228, WINDOW_H = 130, CUT = 10;
    private static final int TAB_Y = 20, TAB_H = 13, TAB_W = 50;
    private static final int CX = 18, CY = 38, CR = WIDTH - 10;
    private static final int SLIDER_X = 9, SLIDER_TOP = 24, SLIDER_H = 96;
    private static final int TRAY_X = 28, TRAY_Y = 136, TRAY_W = 176;
    /** Labels shrink down to this size to fit their box before they are cut with an ellipsis. */
    private static final float MIN_TEXT_SCALE = .62F;
    private static final int STATS_X = 136;
    private static Tab lastTab = Tab.OVERVIEW;

    private final List<Control> controls = new ArrayList<>();
    private final List<Clipped> clipped = new ArrayList<>();
    private Tab tab = lastTab;
    private boolean help;

    public DeviceControlScreen(DeviceControlMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    private ItemStack device() { return menu.device(); }
    private RelicRole role() { return device().getItem() instanceof dev.hurtify.relicsaddon.relic.DeviceItem item ? item.role() : RelicRole.RF_SHIELD; }
    private ShipDeviceState shipState() { return device().getOrDefault(ModDataComponents.SHIP_DEVICE_STATE.get(), ShipDeviceState.DEFAULT); }
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
        menu.setChargeVisible(tab == Tab.POWER && !help && DevicePower.hasRf(role()));
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
                // One row per attack mode, then the healers: a count, a slider (drawn and dragged by the tab itself) and, for a mode, "all in".
                for (AttackMode mode : AttackMode.values()) {
                    int y = SWARM_ROW_Y + mode.ordinal() * SWARM_ROW_H;
                    controls.add(new Control(ALL_X, y + 2, ALL_W, 13, () -> Component.translatable("screen.relics_addon.all_in"),
                            () -> allocation().allInto(hiveType(), hiveCapacity(), mode).allowed(),
                            () -> allocation().allocated(mode) > 0 && allocation().allocated(mode) == allocation().fighters(hiveCapacity()),
                            () -> press(DeviceControlMenu.BUTTON_MODE_BASE + mode.ordinal()),
                            () -> allInTooltip(mode)));
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
    private HiveType hiveType() { return role().isHive() ? HiveType.of(role()) : HiveType.RF; }
    /** The hive's orders as the server will read them: an old save counted out, a mode that no longer fits switched off. */
    private HiveSettings allocation() { return HiveTaskController.settings(device()).resolve(hiveType(), hiveCapacity()); }

    // --- swarm tab -----------------------------------------------------------------------------

    private static final int SWARM_ROW_Y = 49, SWARM_ROW_H = 17, ALL_W = 22, ALL_X = CR - ALL_W;
    /** Each row's slider: its track under the row's label, and the area that takes a click on it. */
    private static final int TRACK_X = CX, TRACK_W = ALL_X - 4 - CX, TRACK_DY = 10, TRACK_H = 5, HIT_DY = 7, HIT_H = 10;
    private static final int HEALERS = DeviceControlMenu.HEALERS;
    /** The slider being dragged (a mode's ordinal or {@link #HEALERS}), or -1, and the count it stands at. */
    private int dragging = -1, dragged;

    /** For scripted captures: shows the Swarm tab. */
    public void showSwarmTab() {
        select(Tab.SWARM);
    }

    /** For scripted captures: the middle of {@code mode}'s slider, on screen. */
    public int[] sliderAt(AttackMode mode) {
        return new int[]{leftPos + TRACK_X + TRACK_W / 2, topPos + SWARM_ROW_Y + mode.ordinal() * SWARM_ROW_H + TRACK_DY + 2};
    }

    private static AttackMode mode(int target) { return AttackMode.values()[target]; }

    /** The orders as the tab shows them: the hive's own, with the slider being dragged where it stands. */
    private HiveSettings shown() {
        HiveSettings settings = allocation();
        if (dragging < 0) return settings;
        return dragging == HEALERS ? settings.withHealers(dragged) : settings.with(mode(dragging), dragged);
    }

    /** A slider's step: a whole figure for a mode, so it only ever stops on whole figures; one drone for the healers. */
    private int step(int target) { return target == HEALERS ? 1 : HiveFigures.minimum(hiveType(), mode(target)); }

    /** A slider's full length: every fighter for a mode, the whole hive for the healers. */
    private int span(int target) {
        return Math.max(1, target == HEALERS ? hiveCapacity() : allocation().fighters(hiveCapacity()));
    }

    private int count(HiveSettings settings, int target) {
        return target == HEALERS ? settings.healerCount(hiveCapacity()) : settings.allocated(mode(target));
    }

    /** How far a slider can go: its own drones and the free ones. */
    private int reach(int target) {
        HiveSettings settings = allocation();
        return count(settings, target) + settings.free(hiveCapacity());
    }

    /** Whether a slider can move at all: a mode with drones can always be switched off; otherwise the free drones must make a figure. */
    private boolean sliderActive(int target) {
        return count(allocation(), target) > 0 || reach(target) >= step(target);
    }

    /** The count under the mouse: the nearest whole figure (or healer) within reach. */
    private int valueAt(int target, double mouseX) {
        double fraction = Math.clamp((mouseX - leftPos - TRACK_X) / TRACK_W, 0, 1);
        int step = step(target), top = reach(target) / step * step;
        return Math.clamp(Math.round(fraction * span(target) / step) * (long) step, 0, top);
    }

    private boolean onSlider(int target, double mouseX, double mouseY) {
        return inside(mouseX, mouseY, leftPos + TRACK_X - 2, topPos + SWARM_ROW_Y + target * SWARM_ROW_H + HIT_DY, TRACK_W + 4, HIT_H);
    }

    /**
     * A row's slider: the whole track, the part within reach a little lighter, its drones filled in, a tick
     * at every whole figure and the handle where it stands.
     */
    private void slider(GuiGraphics g, int target, int count, int color) {
        int x0 = leftPos + TRACK_X, y0 = topPos + SWARM_ROW_Y + target * SWARM_ROW_H + TRACK_DY, span = span(target), step = step(target);
        boolean active = sliderActive(target);
        g.fill(x0, y0, x0 + TRACK_W, y0 + TRACK_H, 0x80101317);
        int reachX = x0 + (int) Math.round(TRACK_W * Math.min(1, reach(target) / (double) span));
        g.fill(x0, y0, reachX, y0 + TRACK_H, 0x28FFFFFF);
        int valueX = x0 + (int) Math.round(TRACK_W * Math.min(1, count / (double) span));
        g.fill(x0, y0 + 1, valueX, y0 + TRACK_H - 1, active ? 0xC0000000 | color & 0xFFFFFF : 0x60808080);
        if (span / step <= TRACK_W / 3) for (int tick = step; tick < span; tick += step) {
            int tx = x0 + (int) Math.round(TRACK_W * tick / (double) span);
            g.fill(tx, y0 + 1, tx + 1, y0 + TRACK_H - 1, 0x60E6EBF0);
        }
        HoloPaint.box(g, x0, y0, TRACK_W, TRACK_H, 0x70E6EBF0);
        g.fill(valueX - 1, y0 - 2, valueX + 1, y0 + TRACK_H + 2, active ? 0xFFF2F5F8 : 0xFF707880);
    }

    /** What a slider does, or why it cannot move. */
    private List<Component> sliderTooltip(int target) {
        HiveSettings settings = shown();
        int free = allocation().free(hiveCapacity());
        if (target == HEALERS) {
            return List.of(Component.translatable("screen.relics_addon.healers", settings.healerCount(hiveCapacity())).withStyle(ChatFormatting.AQUA),
                    Component.translatable("screen.relics_addon.healers_slider.hint").withStyle(ChatFormatting.GRAY));
        }
        AttackMode mode = mode(target);
        int minimum = step(target), count = settings.allocated(mode);
        Component name = Component.translatable("screen.relics_addon.mode." + mode.id());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.relics_addon.allocation_figures", name, count, count / minimum).withStyle(ChatFormatting.AQUA));
        if (!sliderActive(target)) {
            lines.add(refusal(HiveSettings.Refusal.BELOW_MINIMUM, minimum));
            lines.add(Component.translatable("screen.relics_addon.allocation_free_only", free).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("screen.relics_addon.allocation_minimum", minimum).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("screen.relics_addon.allocation_slider.hint").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private List<Component> allInTooltip(AttackMode mode) {
        HiveSettings.Change change = allocation().allInto(hiveType(), hiveCapacity(), mode);
        Component name = Component.translatable("screen.relics_addon.mode." + mode.id());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.relics_addon.all_in.hint", name).withStyle(ChatFormatting.AQUA));
        if (!change.allowed()) lines.add(refusal(change.refusal(), HiveFigures.minimum(hiveType(), mode)));
        lines.add(Component.translatable("screen.relics_addon.mode." + mode.id() + ".hint." + role().itemId()).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static Component refusal(HiveSettings.Refusal refusal, int minimum) {
        return switch (refusal) {
            case BELOW_MINIMUM -> Component.translatable("screen.relics_addon.allocation_below_minimum", minimum, minimum).withStyle(ChatFormatting.RED);
            case NONE_FREE -> Component.translatable("screen.relics_addon.allocation_none_free").withStyle(ChatFormatting.RED);
            case NOTHING_LEFT -> Component.translatable("screen.relics_addon.allocation_nothing_left").withStyle(ChatFormatting.GRAY);
        };
    }

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
            fit(g, control.label.get(), x + control.x + 2, y + control.y + (control.h - 8) / 2, control.w - 4,
                    active ? HoloPaint.TEXT : HoloPaint.TEXT_FAINT, false, true);
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
        int textWidth = STATS_X - CX - 42;
        fit(g, stack.getHoverName(), x + CX + 36, y + CY + 2, textWidth, HoloPaint.TEXT, false, false);
        boolean enabled = RelicRuntime.enabled(stack);
        boolean powered = minecraft == null || DevicePower.powered(minecraft.player, stack);
        String status = !enabled ? "screen.relics_addon.monitor_offline" : powered ? "screen.relics_addon.monitor_online" : "screen.relics_addon.monitor_no_power";
        int color = !enabled ? 0xF2837B : powered ? 0x7BF28B : 0xF2C94C;
        fit(g, Component.literal("● ").append(Component.translatable(status)), x + CX + 36, y + CY + 13, textWidth, color, false, false);
        fit(g, Component.translatable("screen.relics_addon.overview_level", state.level(), DeviceProgression.MAX_LEVEL, state.points()),
                x + CX + 36, y + CY + 24, textWidth, HoloPaint.TEXT_DIM, false, false);

        // Integrity (shield) or ready drones (hive), then battery charge.
        int barY = y + 100;
        if (role().isDroneDock()) {
            ShipDeviceState ship = shipState();
            labelledBar(g, x + CX, barY, 110, ship.drones() / (double) Math.max(1, ship.droneCapacity()), 0xFFE0B04A,
                    Component.translatable("screen.relics_addon.overview_drones", ship.drones(), ship.droneCapacity()));
        } else if (role().isShipGenerator()) {
            ShipDeviceState ship = shipState();
            int wanted = ship.dronesWanted();
            labelledBar(g, x + CX, barY, 110, wanted == 0 ? 0 : Math.min(1, ship.drones() / (double) wanted), 0xFFE0B04A,
                    Component.translatable("screen.relics_addon.overview_structure", ship.structureBlocks()));
        } else if (role().isHive()) {
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

        // What the device does at its current level, in plain words.
        int statsX = x + STATS_X, statsWidth = CR - STATS_X;
        HoloPaint.box(g, statsX - 4, y + CY - 2, statsWidth + 6, 94, 0x40E6EBF0);
        fit(g, Component.translatable("screen.relics_addon.stats"), statsX, y + CY + 2, statsWidth, HoloPaint.TEXT, false, false);
        List<Component> stats = stats();
        for (int line = 0; line < stats.size(); line++) {
            fit(g, stats.get(line), statsX, y + CY + 15 + line * 11, statsWidth, HoloPaint.TEXT_DIM, false, false);
        }
    }

    private List<Component> stats() {
        ItemStack stack = device();
        if (minecraft == null || minecraft.player == null) return List.of();
        var player = minecraft.player;
        if (role().isShipDevice()) {
            ShipDeviceState ship = shipState();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("screen.relics_addon.stat.structure", ship.structureBlocks()));
            if (role().isShipGenerator()) {
                lines.add(Component.translatable("screen.relics_addon.stat.drones_wanted", ship.dronesWanted(), ship.drones()));
                lines.add(Component.translatable("screen.relics_addon.stat.docks", ship.docks()));
                lines.add(Component.translatable("screen.relics_addon.stat.layers", ship.layers()));
                lines.add(Component.translatable("screen.relics_addon.stat.integrity", ship.integrity(), ship.integrityMax()));
            } else {
                lines.add(Component.translatable("screen.relics_addon.stat.dock_drones", ship.drones(), ship.droneCapacity()));
                lines.add(Component.translatable("screen.relics_addon.stat.drones_out", ship.dronesOut()));
                lines.add(Component.translatable("screen.relics_addon.stat.repair_queue", ship.repairQueue()));
            }
            lines.add(Component.translatable("screen.relics_addon.stat.stores", ship.stores()));
            if (!ship.notice().isEmpty()) lines.add(Component.translatable(ship.notice(), ship.detail()).withStyle(ChatFormatting.GOLD));
            return lines;
        }
        if (role().isHive()) {
            return List.of(
                    Component.translatable("screen.relics_addon.stat.drones", hiveCapacity()),
                    Component.translatable("screen.relics_addon.stat.damage", decimal(RelicRuntime.stat(player, stack, "attack_damage", 2, 1, 100))),
                    Component.translatable("screen.relics_addon.stat.interval", decimal(RelicRuntime.stat(player, stack, "attack_interval_max", 100, 20, 100) / 20)),
                    Component.translatable("screen.relics_addon.stat.drone_health", decimal(RelicRuntime.stat(player, stack, "drone_health", 12, 1, 1000))));
        }
        return List.of(
                Component.translatable("screen.relics_addon.stat.radius", decimal(ShieldParameters.radius(player, stack))),
                Component.translatable("screen.relics_addon.stat.buffer", ShieldParameters.capacity(player, stack)),
                Component.translatable("screen.relics_addon.stat.strike", decimal(ShieldParameters.strikeDamage(stack))),
                Component.translatable("screen.relics_addon.stat.repair", decimal(role().repairInterval() / 20.0)));
    }

    private static String decimal(double value) {
        return Math.abs(value - Math.rint(value)) < .05 ? String.valueOf((long) Math.rint(value)) : String.format(Locale.ROOT, "%.1f", value);
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
            paragraph(g, Component.translatable("screen.relics_addon.battery_rf.tab_hint"), x + CX, y + 92, 176, 30, HoloPaint.TEXT_FAINT);
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
            fit(g, Component.translatable(upgradeKey(role(), upgrade)), x + CX + 17, ry + 3, CR - CX - 88,
                    state.level() >= upgrade.requiredLevel() ? HoloPaint.TEXT : HoloPaint.TEXT_FAINT, false, false);
            int rank = state.rank(upgrade.id());
            for (int pip = 0; pip < 3; pip++) {
                int px = x + CX + 17 + pip * 8;
                g.fill(px, ry + 14, px + 6, ry + 18, pip < rank ? 0xFF000000 | accent() : 0x60FFFFFF);
            }
            small(g, Component.translatable("screen.relics_addon.upgrade_rank", rank, 3), x + CX + 58, ry + 14, HoloPaint.TEXT_FAINT);
        }
    }

    /**
     * The swarm tab: free drones at the top, then a row per mode (its drones, its figure's corners, how many of
     * them fly) and the healers; the last line says why the hive changed its orders by itself, if it did.
     */
    private void renderSwarm(GuiGraphics g) {
        int x = leftPos, y = topPos;
        int capacity = hiveCapacity();
        HiveSettings settings = shown();
        HiveFlightPlan plan = HiveFlightPlan.of(hiveType(), capacity, settings);
        fit(g, Component.translatable("screen.relics_addon.swarm_title"), x + CX, y + CY + 1, 70, HoloPaint.TEXT, false, false);
        Component free = Component.translatable("screen.relics_addon.allocation_free", settings.free(capacity), capacity);
        fit(g, free, x + CX + 72, y + CY + 1, CR - CX - 72, settings.free(capacity) > 0 ? 0xFFE0B04A : HoloPaint.TEXT_DIM, false, false);
        for (AttackMode mode : AttackMode.values()) {
            int ry = y + SWARM_ROW_Y + mode.ordinal() * SWARM_ROW_H;
            HiveFlightPlan.Wing wing = plan.wing(mode);
            int count = settings.allocated(mode);
            Component label = Component.translatable("screen.relics_addon.allocation_row", Component.translatable("screen.relics_addon.mode." + mode.id()), count);
            Component detail = wing.grounded()
                    ? Component.translatable("screen.relics_addon.allocation_grounded", HiveFigures.minimum(hiveType(), mode))
                    : Component.translatable("screen.relics_addon.allocation_detail", HiveFigures.minimum(hiveType(), mode), wing.slots());
            rowLabel(g, label, detail, ry, count > 0 ? HoloPaint.TEXT : HoloPaint.TEXT_DIM, wing.grounded() ? 0xFFF2837B : HoloPaint.TEXT_FAINT);
            slider(g, mode.ordinal(), count, accent());
        }
        int hy = y + SWARM_ROW_Y + HEALERS * SWARM_ROW_H;
        rowLabel(g, Component.translatable("screen.relics_addon.healers", settings.healerCount(capacity)),
                Component.translatable("screen.relics_addon.healers_detail", plan.healerSlots()), hy, 0xFF5FCB7A, HoloPaint.TEXT_FAINT);
        slider(g, HEALERS, settings.healerCount(capacity), 0x5FCB7A);
        HiveSettings.Notice notice = settings.notice();
        if (notice != null && notice.kind() != HiveSettings.Notice.Kind.LEGACY) {
            Component name = Component.translatable("screen.relics_addon.mode." + notice.mode().id());
            Component text = Component.translatable("screen.relics_addon.notice." + notice.kind().name().toLowerCase(Locale.ROOT), name, notice.had(), notice.need());
            fit(g, text, x + CX, hy + 18, CR - CX, 0xFFF2C94C, false, false);
        }
    }

    /** A row's first line: its name and count, then in small text its minimum and how many fly, up to the "All" button. */
    private void rowLabel(GuiGraphics g, Component label, Component detail, int y, int color, int detailColor) {
        int x = leftPos + CX, width = ALL_X - 4 - CX, labelWidth = Math.min(font.width(label), width / 2);
        fit(g, label, x, y, labelWidth, color, false, false);
        smallLine(g, detail, x + labelWidth + 4, y + 1, width - labelWidth - 4, detailColor);
    }

    /** One line of small text within {@code width}: it shrinks to fit, down to a little smaller, and is cut with an ellipsis past that. */
    private void smallLine(GuiGraphics g, Component text, int x, int y, int width, int color) {
        String shown = text.getString();
        float scale = Math.max(.6F, Math.min(.75F, width / (float) Math.max(1, font.width(shown))));
        int room = (int) (width / scale);
        if (font.width(shown) > room) {
            shown = font.plainSubstrByWidth(shown, Math.max(0, room - font.width("…"))) + "…";
            clipped.add(new Clipped(x, y - 1, width, 8, text));
        }
        g.pose().pushPose();
        g.pose().translate(x, y + (.75F - scale) * 4, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, shown, 0, 0, color, false);
        g.pose().popPose();
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
        fit(g, label, x + 3, y + 2, width - 6, HoloPaint.TEXT, true, false);
    }

    /**
     * Draws one line of text within {@code width} pixels. It shrinks to fit (down to {@link #MIN_TEXT_SCALE});
     * if it still does not fit it is cut with an ellipsis and the whole text shows when hovering it.
     */
    private void fit(GuiGraphics g, Component text, int x, int y, int width, int color, boolean shadow, boolean centered) {
        if (width <= 0) return;
        int full = font.width(text);
        float scale = full <= width ? 1 : Math.max(MIN_TEXT_SCALE, width / (float) full);
        String shown = text.getString();
        if (full * scale > width + .5F) {
            int room = (int) (width / scale) - font.width("…");
            shown = font.plainSubstrByWidth(shown, Math.max(0, room)) + "…";
            clipped.add(new Clipped(x, y - 1, width, 10, text));
        }
        float drawn = font.width(shown) * scale;
        float left = centered ? x + (width - drawn) / 2F : x;
        g.pose().pushPose();
        g.pose().translate(left, y + (1 - scale) * 4, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, shown, 0, 0, color, shadow);
        g.pose().popPose();
    }

    /** A block of wrapped text that shrinks until it fits {@code width} x {@code height}; a remainder is cut and shown on hover. */
    private void paragraph(GuiGraphics g, Component text, int x, int y, int width, int height, int color) {
        float scale = 1;
        List<FormattedCharSequence> lines = font.split(text, width);
        while (scale > MIN_TEXT_SCALE && lines.size() * 10 * scale > height) {
            scale = Math.max(MIN_TEXT_SCALE, scale - .06F);
            lines = font.split(text, (int) (width / scale));
        }
        int fits = Math.max(1, (int) (height / (10 * scale)));
        if (lines.size() > fits) clipped.add(new Clipped(x, y, width, height, text));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        for (int line = 0; line < Math.min(fits, lines.size()); line++) g.drawString(font, lines.get(line), 0, line * 10, color, false);
        g.pose().popPose();
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
        int before = clipped.size();
        fit(g, heading, 18, 8, WIDTH - 60, HoloPaint.TEXT, false, false);
        fit(g, playerInventoryTitle, DeviceControlMenu.INVENTORY_X, TRAY_Y + 3, TRAY_W - 12, HoloPaint.TEXT_DIM, false, false);
        // Labels are drawn relative to the window; clipped areas are kept in screen space.
        for (int index = before; index < clipped.size(); index++) {
            Clipped area = clipped.get(index);
            clipped.set(index, new Clipped(area.x() + leftPos, area.y() + topPos, area.w(), area.h(), area.text()));
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        clipped.clear();
        super.render(g, mouseX, mouseY, partialTick);
        if (help) renderHelp(g);
        renderTooltip(g, mouseX, mouseY);
        if (menu.getCarried().isEmpty() && hoveredSlot == null) {
            List<Component> tooltip = hoverTooltip(mouseX, mouseY);
            if (!tooltip.isEmpty()) g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private List<Component> hoverTooltip(int mouseX, int mouseY) {
        for (Clipped area : clipped) {
            if (inside(mouseX, mouseY, area.x(), area.y(), area.w(), area.h())) {
                List<Component> tooltip = new ArrayList<>();
                for (Control control : controls) {
                    if (inside(mouseX, mouseY, leftPos + control.x, topPos + control.y, control.w, control.h)) tooltip.addAll(control.tooltip.get());
                }
                tooltip.addFirst(area.text());
                return tooltip;
            }
        }
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
        if (tab == Tab.SWARM) {
            for (int target = 0; target <= HEALERS; target++) if (onSlider(target, mouseX, mouseY)) return sliderTooltip(target);
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
        if (button == 0 && tab == Tab.SWARM && !help && role().isHive()) {
            for (int target = 0; target <= HEALERS; target++) {
                if (onSlider(target, mouseX, mouseY) && sliderActive(target)) {
                    dragging = target;
                    dragged = valueAt(target, mouseX);
                    return true;
                }
            }
        }
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

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging >= 0) {
            dragged = valueAt(dragging, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** A slider let go sends its count to the server, which checks it like any other change. */
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging >= 0) {
            int target = dragging;
            dragging = -1;
            if (dragged != count(allocation(), target)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new HiveAllocationPayload(menu.containerId, target, dragged));
                playClick();
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void playClick() {
        if (minecraft != null) minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, .6F));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
