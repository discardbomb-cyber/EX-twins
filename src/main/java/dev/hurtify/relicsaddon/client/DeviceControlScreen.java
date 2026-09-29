package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.network.DeviceControlPayload;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.DeviceUpgrade;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;

/** Original compact device console: no Mekanism code or assets are used. */
public final class DeviceControlScreen extends Screen {
    private record Entry(boolean charm, int slot, ItemStack snapshot) { }
    private final List<Entry> entries;
    private int selected;
    private int left, top, panelWidth;
    private String identity = "";
    private final List<Button> controls = new ArrayList<>();

    private DeviceControlScreen(List<Entry> entries) { super(Component.translatable("screen.relics_addon.device_control")); this.entries = List.copyOf(entries); }
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var entries = new ArrayList<Entry>();
        CuriosApi.getCuriosInventory(mc.player).flatMap(h -> h.getStacksHandler("charm")).ifPresent(h -> {
            for (int i = 0; i < h.getStacks().getSlots(); i++) add(entries, true, i, h.getStacks().getStackInSlot(i));
        });
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) add(entries, false, i, mc.player.getInventory().getItem(i));
        mc.setScreen(new DeviceControlScreen(entries));
    }
    private static void add(List<Entry> entries, boolean charm, int slot, ItemStack stack) {
        if (stack.getItem() instanceof AutonomousRelicItem) entries.add(new Entry(charm, slot, stack.copy()));
    }
    private ItemStack current() {
        if (entries.isEmpty() || minecraft.player == null) return ItemStack.EMPTY;
        Entry entry = entries.get(selected);
        return HiveTaskController.locate(minecraft.player, entry.charm, entry.slot);
    }
    @Override protected void init() {
        panelWidth = Math.min(410, width - 16); left = (width - panelWidth) / 2; top = Math.max(5, (height - 250) / 2);
        if (!entries.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { selected = Math.floorMod(selected - 1, entries.size()); rebuildWidgets(); }).bounds(left + 10, top + 31, 22, 20).build()).active = entries.size() > 1;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { selected = Math.floorMod(selected + 1, entries.size()); rebuildWidgets(); }).bounds(left + panelWidth - 32, top + 31, 22, 20).build()).active = entries.size() > 1;
            addControl(Component.translatable("screen.relics_addon.toggle"), 10, 72, DeviceControlPayload.Action.TOGGLE, -1, "");
            if (current().getItem() instanceof AutonomousRelicItem item && item.role().isHive()) {
                addRenderableWidget(Button.builder(Component.translatable("screen.relics_addon.hive_tasks"), b -> HiveSettingsScreen.open()).bounds(left + 130, top + 72, 115, 20).build());
            }
            for (int slot = 0; slot < DeviceProgression.MODULE_SLOTS; slot++) addControl(Component.translatable("screen.relics_addon.module", slot + 1), 10 + slot * 125, 102, DeviceControlPayload.Action.MODULE, slot, "");
            int index = 0;
            for (DeviceUpgrade upgrade : DeviceUpgrade.values()) {
                addControl(Component.translatable("screen.relics_addon.upgrade", upgrade.id()), 10 + (index % 2) * 195, 140 + (index / 2) * 25, DeviceControlPayload.Action.UPGRADE, -1, upgrade.id());
                index++;
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(left + panelWidth - 90, top + 222, 80, 20).build());
        refresh();
    }
    private void addControl(Component text, int x, int y, DeviceControlPayload.Action action, int value, String upgrade) {
        Button button = addRenderableWidget(Button.builder(text, b -> send(action, value, upgrade)).bounds(left + x, top + y, action == DeviceControlPayload.Action.UPGRADE ? 185 : 115, 20).build());
        controls.add(button);
    }
    private void send(DeviceControlPayload.Action action, int value, String upgrade) {
        if (entries.isEmpty() || identity.isEmpty()) return;
        Entry entry = entries.get(selected);
        PacketDistributor.sendToServer(new DeviceControlPayload(entry.charm, entry.slot, identity, action, value, upgrade));
    }
    private void refresh() {
        ItemStack stack = current();
        identity = stack.getOrDefault(ModDataComponents.INSTANCE_ID.get(), "");
        for (Button button : controls) button.active = !stack.isEmpty() && !identity.isEmpty();
    }
    @Override public void tick() { super.tick(); refresh(); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC018191D); g.fill(left, top, left + panelWidth, top + 250, 0xFF282A31); g.fill(left, top, left + panelWidth, top + 2, 0xFFA895C1);
        g.drawCenteredString(font, title, width / 2, top + 11, 0xF2ECFA);
        if (entries.isEmpty()) g.drawCenteredString(font, Component.translatable("screen.relics_addon.no_devices"), width / 2, top + 105, 0xC9C7D0);
        else {
            ItemStack stack = current(); DeviceProgression state = RelicRuntime.progression(stack);
            g.drawCenteredString(font, stack.getHoverName(), width / 2, top + 37, 0xF3EDF8); g.renderItem(stack, left + 13, top + 57);
            g.drawString(font, Component.translatable("screen.relics_addon.device_progress", state.level(), DeviceProgression.MAX_LEVEL, state.points()), left + 42, top + 59, 0xECE7F3, false);
            g.drawString(font, Component.translatable("screen.relics_addon.device_state", RelicRuntime.enabled(stack) ? Component.translatable("tooltip.relics_addon.state.enabled") : Component.translatable("tooltip.relics_addon.state.disabled")), left + 42, top + 82, 0xC9C7D0, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
}
