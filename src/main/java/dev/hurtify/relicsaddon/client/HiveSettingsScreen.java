package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.network.HiveSettingsPayload;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;

/** Client editor for one identity-checked item; the server remains the allocation authority. */
public final class HiveSettingsScreen extends Screen {
    public record Entry(boolean charm, int slot, ItemStack snapshot) { }
    private final List<Entry> entries;
    private int selected, draft, capacity, left, top, panelWidth;
    private String identity = "";
    private int pendingTicks;
    private boolean pending, moved;
    private Button apply, less, more;
    private AllocationSlider slider;

    public HiveSettingsScreen(List<Entry> entries) {
        super(Component.translatable("screen.relics_addon.hive_tasks"));
        this.entries = List.copyOf(entries);
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var entries = new ArrayList<Entry>();
        CuriosApi.getCuriosInventory(mc.player).flatMap(handler -> handler.getStacksHandler("charm")).ifPresent(handler -> {
            for (int i = 0; i < handler.getStacks().getSlots(); i++) add(entries, true, i, handler.getStacks().getStackInSlot(i));
        });
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) add(entries, false, i, mc.player.getInventory().getItem(i));
        mc.setScreen(new HiveSettingsScreen(entries));
    }

    private static void add(List<Entry> entries, boolean charm, int slot, ItemStack stack) {
        if (stack.getItem() instanceof AutonomousRelicItem item && item.role().isHive()) entries.add(new Entry(charm, slot, stack.copy()));
    }

    @Override protected void init() {
        panelWidth = Math.min(388, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(5, (height - 224) / 2);
        if (!entries.isEmpty()) {
            ItemStack stack = current();
            if (!(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive()) stack = entries.get(selected).snapshot();
            identity = stack.getOrDefault(ModDataComponents.INSTANCE_ID.get(), "");
            capacity = HiveController.capacity(minecraft.player, stack);
            draft = HiveTaskController.settings(stack).healerCount(capacity);
            pending = false;
            addRenderableWidget(Button.builder(Component.literal("<"), button -> select(-1)).bounds(left + 10, top + 31, 22, 20).build()).active = entries.size() > 1;
            addRenderableWidget(Button.builder(Component.literal(">"), button -> select(1)).bounds(left + panelWidth - 32, top + 31, 22, 20).build()).active = entries.size() > 1;
            slider = addRenderableWidget(new AllocationSlider(left + 40, top + 116, panelWidth - 80));
            less = addRenderableWidget(Button.builder(Component.literal("-"), button -> adjust(-1)).bounds(left + 10, top + 116, 24, 20).build());
            more = addRenderableWidget(Button.builder(Component.literal("+"), button -> adjust(1)).bounds(left + panelWidth - 34, top + 116, 24, 20).build());
            apply = addRenderableWidget(Button.builder(Component.translatable("screen.relics_addon.apply"), button -> save())
                    .bounds(left + 10, top + 190, (panelWidth - 30) / 2, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left + panelWidth / 2 + 5, top + 190, (panelWidth - 30) / 2, 20).build());
        refresh();
    }

    private ItemStack current() {
        if (entries.isEmpty()) return ItemStack.EMPTY;
        Entry entry = entries.get(selected);
        return minecraft.player == null ? entry.snapshot() : HiveTaskController.locate(minecraft.player, entry.charm(), entry.slot());
    }

    private void select(int delta) {
        selected = Math.floorMod(selected + delta, entries.size());
        rebuildWidgets();
    }

    private void adjust(int delta) {
        draft = Math.clamp(draft + delta, 0, capacity);
        slider.sync();
        refresh();
    }

    private void save() {
        if (moved || pending || identity.isEmpty() || minecraft.player == null) return;
        Entry entry = entries.get(selected);
        PacketDistributor.sendToServer(new HiveSettingsPayload(entry.charm(), entry.slot(), identity, draft));
        pending = true;
        pendingTicks = 0;
        refresh();
    }

    @Override public void tick() {
        super.tick();
        if (pending && ++pendingTicks > 60) pending = false;
        refresh();
    }

    private void refresh() {
        if (entries.isEmpty() || apply == null) return;
        ItemStack stack = current();
        String currentId = stack.getOrDefault(ModDataComponents.INSTANCE_ID.get(), "");
        if (identity.isEmpty()) identity = currentId;
        moved = !(stack.getItem() instanceof AutonomousRelicItem item) || !item.role().isHive() || !identity.equals(currentId);
        if (!moved) {
            int latest = HiveController.capacity(minecraft.player, stack);
            if (capacity != latest) { capacity = latest; draft = Math.min(draft, capacity); slider.sync(); }
            if (HiveTaskController.settings(stack).healerCount(capacity) == draft) pending = false;
        }
        apply.active = minecraft.player != null && !moved && !pending && !identity.isEmpty()
                && HiveTaskController.settings(stack).healerCount(capacity) != draft;
        less.active = !moved && !pending && draft > 0;
        more.active = !moved && !pending && draft < capacity;
        slider.active = !moved && !pending;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC018191D);
        graphics.fill(left, top, left + panelWidth, top + 224, 0xFF282A31);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFFA895C1);
        graphics.drawCenteredString(font, title, width / 2, top + 11, 0xF2ECFA);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.relics_addon.no_hives"), width / 2, top + 94, 0xC9C7D0);
        } else {
            ItemStack stack = moved ? entries.get(selected).snapshot() : current();
            graphics.drawCenteredString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), panelWidth - 88), width / 2, top + 36, 0xF3EDF8);
            Entry entry = entries.get(selected);
            graphics.drawCenteredString(font, Component.translatable(entry.charm() ? "screen.relics_addon.charm_slot" : "screen.relics_addon.inventory_slot", entry.slot() + 1), width / 2, top + 57, 0xAAA7B4);
            graphics.renderItem(stack, left + 13, top + 72);
            graphics.drawString(font, Component.translatable("screen.relics_addon.drone_total", capacity), left + 40, top + 77, 0xECE7F3, false);
            graphics.drawString(font, Component.translatable("screen.relics_addon.fighters", capacity - draft), left + 12, top + 99, 0x84DCEA, false);
            graphics.drawString(font, Component.translatable("screen.relics_addon.healers", draft), left + 12, top + 145, 0x9DDEAD, false);
            String key = moved ? "screen.relics_addon.hive_moved" : pending ? "screen.relics_addon.saving"
                    : HiveTaskController.settings(stack).healerCount(capacity) == draft ? "screen.relics_addon.saved" : "screen.relics_addon.unsaved";
            graphics.drawString(font, Component.translatable(key), left + 12, top + 168, moved ? 0xF09A9A : 0xB6AEBD, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // Screen.render otherwise draws its blurred background over this panel before the buttons.
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }

    @Override public boolean isPauseScreen() { return false; }

    private final class AllocationSlider extends AbstractSliderButton {
        AllocationSlider(int x, int y, int width) { super(x, y, width, 20, Component.empty(), draft / (double) Math.max(1, capacity)); updateMessage(); }
        void sync() { value = draft / (double) Math.max(1, capacity); updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.translatable("screen.relics_addon.healers", draft)); }
        @Override protected void applyValue() { draft = (int) Math.round(value * capacity); updateMessage(); refresh(); }
    }
}
