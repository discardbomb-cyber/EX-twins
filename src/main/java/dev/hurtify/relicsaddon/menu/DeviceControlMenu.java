package dev.hurtify.relicsaddon.menu;

import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveSettings;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.network.HiveAllocationPayload;
import dev.hurtify.relicsaddon.domain.energy.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModMenus;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
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
 * Control console for one shield or hive. The device itself stays where it is
 * (a Curios charm slot or the player inventory); the menu addresses it by slot and instance id.
 */
public final class DeviceControlMenu extends AbstractContainerMenu {
    public static final int BUTTON_TOGGLE = 0;
    public static final int BUTTON_HEALERS_MINUS_10 = 1, BUTTON_HEALERS_MINUS_1 = 2, BUTTON_HEALERS_PLUS_1 = 3, BUTTON_HEALERS_PLUS_10 = 4;
    public static final int BUTTON_RF_BATTERY = 5, BUTTON_MANA_BATTERY = 6;
    /** One button per {@link DeviceEnergy.ManaSource}, in ordinal order. */
    public static final int BUTTON_MANA_SOURCE_BASE = 7;
    public static final int BUTTON_UPGRADE_BASE = 20;
    /** One button per {@link AttackMode}, in ordinal order: every fighter into that mode, the others off. */
    public static final int BUTTON_MODE_BASE = 40;
    /** Four buttons per {@link AttackMode}, in ordinal order, adding or taking away {@link #STEPS} drones. */
    public static final int BUTTON_ALLOCATION_BASE = 50;
    public static final int[] STEPS = {-10, -1, 1, 10};

    /** The slider target ({@link HiveAllocationPayload}) that sets the healers; the attack modes are their ordinals. */
    public static final int HEALERS = AttackMode.values().length;

    /** The button that moves {@code step} (an index into {@link #STEPS}) drones into or out of {@code mode}. */
    public static int allocationButton(AttackMode mode, int step) {
        return BUTTON_ALLOCATION_BASE + mode.ordinal() * STEPS.length + step;
    }
    public static final int CHARGE_X = 204, CHARGE_Y = 102;
    /** Menu slot layout: the charge slot, then the player inventory. */
    public static final int CHARGE_SLOT = 0, INVENTORY_START = CHARGE_SLOT + 1;
    public static final int INVENTORY_X = 36, INVENTORY_Y = 146;

    private final Player player;
    private final boolean charm;
    private final int deviceSlot;
    private final String identity;
    /** Client-only presentation flag: the charge slot only shows on the batteries tab. */
    private boolean chargeVisible;
    private final SimpleContainer charge = new SimpleContainer(1);

    public DeviceControlMenu(int containerId, Inventory inventory, boolean charm, int deviceSlot) {
        super(ModMenus.DEVICE_CONTROL.get(), containerId);
        this.player = inventory.player;
        this.charm = charm;
        this.deviceSlot = deviceSlot;
        this.identity = device().getOrDefault(ModDataComponents.INSTANCE_ID.get(), "");
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
        if (item.role().isHive()) HiveController.normalize(player, device);
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new DeviceControlMenu(id, inventory, charm, slot),
                Component.translatable("screen.relics_addon.device_control")), buffer -> buffer.writeBoolean(charm).writeVarInt(slot));
        return true;
    }

    public ItemStack device() { return HiveTaskController.locate(player, charm, deviceSlot); }
    public boolean charm() { return charm; }
    public int deviceSlot() { return deviceSlot; }
    /** Client-side tab state: whether the charge slot can be seen and clicked. */
    public void setChargeVisible(boolean visible) {
        chargeVisible = visible;
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
            int delta = STEPS[id - BUTTON_HEALERS_MINUS_10];
            HiveSettings.Change change = HiveController.normalize(player, stack).adjustHealers(HiveType.of(item.role()), HiveController.capacity(player, stack), delta);
            return change.allowed() && HiveTaskController.configureHealers(player, charm, deviceSlot, identity, change.value());
        }
        int allocation = id - BUTTON_ALLOCATION_BASE;
        if (allocation >= 0 && allocation < AttackMode.values().length * STEPS.length) {
            if (!item.role().isHive()) return false;
            AttackMode mode = AttackMode.values()[allocation / STEPS.length];
            HiveSettings.Change change = HiveController.normalize(player, stack).adjust(HiveType.of(item.role()), HiveController.capacity(player, stack),
                    mode, STEPS[allocation % STEPS.length]);
            return change.allowed() && HiveTaskController.configureMode(player, charm, deviceSlot, identity, mode, change.value());
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
        int mode = id - BUTTON_MODE_BASE;
        if (mode >= 0 && mode < AttackMode.values().length) {
            if (!item.role().isHive()) return false;
            boolean changed = HiveTaskController.configureAllInto(player, charm, deviceSlot, identity, AttackMode.values()[mode]);
            if (changed) RelicSounds.ui(player, RelicSounds.Ui.TOGGLE);
            return changed;
        }
        int upgrade = id - BUTTON_UPGRADE_BASE;
        if (upgrade >= 0 && upgrade < DeviceUpgrade.values().length && RelicRuntime.purchaseUpgrade(stack, DeviceUpgrade.values()[upgrade].id())) {
            RelicSounds.ui(player, RelicSounds.Ui.UPGRADE);
            return true;
        }
        return false;
    }

    /**
     * A console slider let go: {@code count} drones for attack mode {@code target} (its ordinal) or, for
     * {@link #HEALERS}, healers. Refused, writing nothing, unless the hive allows it: a mode gets none or
     * at least its figure's corners, healers come from free drones, and nothing goes past the hive's size.
     */
    public boolean allocate(Player player, int target, int count) {
        if (!stillValid(player) || !EquippedRelicSetResolver.isRealPlayer(player) || !(device().getItem() instanceof AutonomousRelicItem item)
                || !item.role().isHive() || count < 0 || count > HiveType.MAX_DRONES || target < 0 || target > HEALERS) return false;
        if (target == HEALERS) return HiveTaskController.configureHealers(player, charm, deviceSlot, identity, count);
        return HiveTaskController.configureMode(player, charm, deviceSlot, identity, AttackMode.values()[target], count);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < INVENTORY_START) {
            if (!moveItemStackTo(stack, INVENTORY_START, slots.size(), true)) return ItemStack.EMPTY;
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

}
