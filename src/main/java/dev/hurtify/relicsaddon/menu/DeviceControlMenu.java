package dev.hurtify.relicsaddon.menu;

import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.registry.ModMenus;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.DeviceUpgrade;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Module bay and control console for one shield or hive. The device itself stays where it is
 * (a Curios charm slot or the player inventory); the menu addresses it by slot and instance id.
 */
public final class DeviceControlMenu extends AbstractContainerMenu {
    public static final int BUTTON_TOGGLE = 0;
    public static final int BUTTON_HEALERS_MINUS_10 = 1, BUTTON_HEALERS_MINUS_1 = 2, BUTTON_HEALERS_PLUS_1 = 3, BUTTON_HEALERS_PLUS_10 = 4;
    public static final int BUTTON_RF_BATTERY = 5, BUTTON_MANA_BATTERY = 6;
    /** One button per {@link DeviceEnergy.ManaSource}, in ordinal order. */
    public static final int BUTTON_MANA_SOURCE_BASE = 7;
    public static final int BUTTON_UPGRADE_BASE = 20;
    public static final int CHARGE_X = 204, CHARGE_Y = 102;
    /** Menu slot layout: module bays, the charge slot, then the player inventory. */
    public static final int CHARGE_SLOT = DeviceProgression.MODULE_SLOTS, INVENTORY_START = CHARGE_SLOT + 1;
    public static final int MODULE_X = 140, MODULE_Y = 54, MODULE_SPACING = 28;
    public static final int INVENTORY_X = 36, INVENTORY_Y = 146;

    private final Player player;
    private final boolean charm;
    private final int deviceSlot;
    private final String identity;
    /** Client-only presentation flag: module slots are hidden while another tab is shown. */
    private boolean modulesVisible = true;
    private boolean chargeVisible;
    private final SimpleContainer charge = new SimpleContainer(1);

    public DeviceControlMenu(int containerId, Inventory inventory, boolean charm, int deviceSlot) {
        super(ModMenus.DEVICE_CONTROL.get(), containerId);
        this.player = inventory.player;
        this.charm = charm;
        this.deviceSlot = deviceSlot;
        this.identity = device().getOrDefault(ModDataComponents.INSTANCE_ID.get(), "");
        Container modules = player.level().isClientSide() ? new SimpleContainer(DeviceProgression.MODULE_SLOTS) : new ModuleBay();
        for (int index = 0; index < DeviceProgression.MODULE_SLOTS; index++) {
            addSlot(new ModuleSlot(modules, index, MODULE_X + index * MODULE_SPACING, MODULE_Y));
        }
        addSlot(new ChargeSlot(charge, CHARGE_X, CHARGE_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
    }

    /** Server entry point used by the open payload. */
    public static boolean open(ServerPlayer player, boolean charm, int slot) {
        if (!player.isAlive() || player.isSpectator() || !EquippedRelicSetResolver.isRealPlayer(player)) return false;
        ItemStack device = HiveTaskController.locate(player, charm, slot);
        if (!(device.getItem() instanceof AutonomousRelicItem item) || !item.role().available()) return false;
        AutonomousRelicItem.ensureState(device);
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new DeviceControlMenu(id, inventory, charm, slot),
                Component.translatable("screen.relics_addon.device_control")), buffer -> buffer.writeBoolean(charm).writeVarInt(slot));
        return true;
    }

    public ItemStack device() { return HiveTaskController.locate(player, charm, deviceSlot); }
    public boolean charm() { return charm; }
    public int deviceSlot() { return deviceSlot; }
    /** Client-side tab state: which of the tab-specific slots can be seen and clicked. */
    public void setVisibleSlots(boolean modules, boolean chargeSlot) {
        modulesVisible = modules;
        chargeVisible = chargeSlot;
    }

    private boolean deviceValid() {
        ItemStack stack = device();
        return stack.getItem() instanceof AutonomousRelicItem && !identity.isEmpty()
                && identity.equals(stack.getOrDefault(ModDataComponents.INSTANCE_ID.get(), ""));
    }

    @Override public boolean stillValid(Player player) { return player.isAlive() && !player.isSpectator() && deviceValid(); }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (!stillValid(player) || !EquippedRelicSetResolver.isRealPlayer(player)) return false;
        ItemStack stack = device();
        AutonomousRelicItem item = (AutonomousRelicItem) stack.getItem();
        if (id == BUTTON_TOGGLE) {
            RelicRuntime.setEnabled(player, stack, !RelicRuntime.enabled(stack));
            RelicSounds.ui(player, RelicSounds.Ui.TOGGLE);
            return true;
        }
        if (id >= BUTTON_HEALERS_MINUS_10 && id <= BUTTON_HEALERS_PLUS_10) {
            if (!item.role().isHive()) return false;
            int capacity = HiveController.capacity(player, stack);
            int delta = switch (id) { case BUTTON_HEALERS_MINUS_10 -> -10; case BUTTON_HEALERS_MINUS_1 -> -1; case BUTTON_HEALERS_PLUS_1 -> 1; default -> 10; };
            int healers = Math.clamp(HiveTaskController.settings(stack).healerCount(capacity) + delta, 0, capacity);
            return HiveTaskController.configure(player, charm, deviceSlot, identity, healers);
        }
        RelicRole role = item.role();
        if (id == BUTTON_RF_BATTERY && DevicePower.hasRf(role) || id == BUTTON_MANA_BATTERY && DevicePower.hasMana(role)) {
            boolean rf = id == BUTTON_RF_BATTERY;
            DeviceEnergy energy = DevicePower.energy(stack);
            DevicePower.setBattery(stack, rf, !(rf ? energy.rfOn() : energy.manaOn()));
            RelicSounds.ui(player, RelicSounds.Ui.TOGGLE);
            return true;
        }
        int source = id - BUTTON_MANA_SOURCE_BASE;
        if (source >= 0 && source < DeviceEnergy.ManaSource.values().length && DevicePower.hasMana(role)) {
            DevicePower.setManaSource(stack, DeviceEnergy.ManaSource.values()[source]);
            return true;
        }
        int upgrade = id - BUTTON_UPGRADE_BASE;
        if (upgrade >= 0 && upgrade < DeviceUpgrade.values().length && RelicRuntime.purchaseUpgrade(stack, DeviceUpgrade.values()[upgrade].id())) {
            RelicSounds.ui(player, RelicSounds.Ui.UPGRADE);
            return true;
        }
        return false;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int modules = DeviceProgression.MODULE_SLOTS;
        if (index < INVENTORY_START) {
            if (!moveItemStackTo(stack, INVENTORY_START, slots.size(), true)) return ItemStack.EMPTY;
        } else if (stack.is(ModItems.DEVICE_MODULE.get())) {
            if (!moveItemStackTo(stack, 0, modules, false)) return ItemStack.EMPTY;
        } else if (ChargeSlot.accepts(stack)) {
            if (!moveItemStackTo(stack, CHARGE_SLOT, INVENTORY_START, false)) return ItemStack.EMPTY;
        } else if (index < INVENTORY_START + 27) {
            if (!moveItemStackTo(stack, INVENTORY_START + 27, slots.size(), false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_START + 27, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** Runs every server tick while the menu is open: pours FE from the charge slot into the RF battery. */
    @Override public void broadcastChanges() {
        ItemStack source = charge.getItem(0);
        if (!player.level().isClientSide() && !source.isEmpty() && deviceValid()) {
            IEnergyStorage from = source.getCapability(Capabilities.EnergyStorage.ITEM);
            ItemStack device = device();
            if (from != null && from.canExtract()) {
                int wanted = DevicePower.receiveFe(device, DevicePower.FE_TRANSFER_PER_TICK, true);
                int moved = wanted > 0 ? from.extractEnergy(wanted, false) : 0;
                if (moved > 0) DevicePower.receiveFe(device, moved, false);
            }
        }
        super.broadcastChanges();
    }

    @Override public void removed(Player player) {
        super.removed(player);
        clearContainer(player, charge);
    }

    private final class ChargeSlot extends Slot {
        ChargeSlot(Container container, int x, int y) { super(container, 0, x, y); }
        static boolean accepts(ItemStack stack) {
            IEnergyStorage energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            return energy != null && energy.canExtract() && !(stack.getItem() instanceof AutonomousRelicItem);
        }
        @Override public boolean mayPlace(ItemStack stack) { return accepts(stack); }
        @Override public int getMaxStackSize() { return 1; }
        @Override public boolean isActive() { return chargeVisible; }
    }

    private final class ModuleSlot extends Slot {
        ModuleSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return stack.is(ModItems.DEVICE_MODULE.get()); }
        @Override public int getMaxStackSize() { return 1; }
        @Override public boolean isActive() { return modulesVisible; }
    }

    /**
     * Server view of the device's module bitmask as three one-item slots. Stacks are cached so
     * vanilla slot code may shrink them in place; {@link #setChanged()} writes the result back.
     */
    private final class ModuleBay implements Container {
        private final ItemStack[] stacks = new ItemStack[DeviceProgression.MODULE_SLOTS];

        private ItemStack cached(int index) {
            boolean installed = RelicRuntime.progression(device()).hasModule(index);
            ItemStack current = stacks[index];
            if (current == null || current.isEmpty() == installed) stacks[index] = installed ? new ItemStack(ModItems.DEVICE_MODULE.get()) : ItemStack.EMPTY;
            return stacks[index];
        }

        @Override public int getContainerSize() { return stacks.length; }
        @Override public boolean isEmpty() {
            for (int index = 0; index < stacks.length; index++) if (!cached(index).isEmpty()) return false;
            return true;
        }
        @Override public ItemStack getItem(int index) { return cached(index); }
        @Override public ItemStack removeItem(int index, int count) {
            ItemStack current = cached(index);
            if (current.isEmpty() || count <= 0) return ItemStack.EMPTY;
            stacks[index] = ItemStack.EMPTY;
            write(index, false);
            return current;
        }
        @Override public ItemStack removeItemNoUpdate(int index) { return removeItem(index, 1); }
        @Override public void setItem(int index, ItemStack stack) {
            boolean installed = !stack.isEmpty() && stack.is(ModItems.DEVICE_MODULE.get());
            stacks[index] = installed ? stack : ItemStack.EMPTY;
            write(index, installed);
        }
        @Override public int getMaxStackSize() { return 1; }
        @Override public void setChanged() {
            for (int index = 0; index < stacks.length; index++) if (stacks[index] != null) write(index, !stacks[index].isEmpty());
        }
        @Override public boolean stillValid(Player player) { return deviceValid(); }
        @Override public void clearContent() { for (int index = 0; index < stacks.length; index++) setItem(index, ItemStack.EMPTY); }

        private void write(int index, boolean installed) {
            if (!deviceValid()) return;
            ItemStack device = device();
            if (RelicRuntime.progression(device).hasModule(index) == installed) return;
            RelicRuntime.setModule(device, index, installed);
            RelicSounds.ui(player, installed ? RelicSounds.Ui.MODULE_INSERT : RelicSounds.Ui.MODULE_REMOVE);
        }
    }
}
