package dev.hurtify.relicsaddon.menu;

import dev.hurtify.relicsaddon.registry.ModMenus;
import dev.hurtify.relicsaddon.ship.AegisModule;
import dev.hurtify.relicsaddon.ship.EscortModule;
import dev.hurtify.relicsaddon.ship.LanceModule;
import dev.hurtify.relicsaddon.ship.ShipFrame;
import dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity;
import dev.hurtify.relicsaddon.ship.ShipHiveKind;
import dev.hurtify.relicsaddon.ship.ShipStatus;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * A ship hive's window: what it is doing, its battery, the meter of its kind (the lance's heat, the aegis's charge,
 * the escort wings' charges and whereabouts) and its switch. It holds no items. Being on a ship, the hive stands far
 * off in its ship's plot; the window stays open while the player is near where the hive really is.
 */
public final class ShipHiveMenu extends AbstractContainerMenu {
    public static final int BUTTON_TOGGLE = 0;
    /** Data slots, each sent to the client as a short: the battery in two halves, then the rest. */
    private static final int ENERGY_LOW = 0, ENERGY_HIGH = 1, ENABLED = 2, STATUS = 3, VALUE = 4, METER_A = 5, METER_B = 6,
            PHASE_A = 7, PHASE_B = 8, CONTROL = 9, SLOTS = 10;
    private static final double REACH = 8;

    @Nullable
    private final ShipHiveBlockEntity hive;
    private final BlockPos pos;
    private final ShipHiveKind kind;
    private final String owner;
    private final ContainerData data;

    /** The server's side, reading the hive as it runs. */
    private ShipHiveMenu(int id, ShipHiveBlockEntity hive, Player player) {
        super(ModMenus.SHIP_HIVE.get(), id);
        this.hive = hive;
        this.pos = hive.getBlockPos();
        this.kind = hive.kind();
        this.owner = hive.ownerName();
        this.data = new ContainerData() {
            @Override
            public int get(int index) {
                return read(hive, player, index);
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return SLOTS;
            }
        };
        addDataSlots(data);
    }

    /** The client's side, filled by the server's data slots. */
    public ShipHiveMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        super(ModMenus.SHIP_HIVE.get(), id);
        this.pos = buffer.readBlockPos();
        int kindIndex = buffer.readVarInt();
        this.kind = ShipHiveKind.values()[Math.clamp(kindIndex, 0, ShipHiveKind.values().length - 1)];
        this.owner = buffer.readUtf(64);
        this.hive = inventory.player.level().getBlockEntity(pos) instanceof ShipHiveBlockEntity found ? found : null;
        this.data = new SimpleContainerData(SLOTS);
        addDataSlots(data);
    }

    public static void open(ServerPlayer player, ShipHiveBlockEntity hive) {
        if (!player.isAlive() || player.isSpectator()) return;
        Component title = Component.translatable("block.relics_addon." + hive.kind().id);
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new ShipHiveMenu(id, hive, player), title),
                buffer -> buffer.writeBlockPos(hive.getBlockPos()).writeVarInt(hive.kind().ordinal()).writeUtf(hive.ownerName(), 64));
    }

    private static int read(ShipHiveBlockEntity hive, Player player, int index) {
        int energy = hive.energy().getEnergyStored();
        return switch (index) {
            case ENERGY_LOW -> energy & 0x7FFF;
            case ENERGY_HIGH -> energy >>> 15;
            case ENABLED -> hive.enabled() ? 1 : 0;
            case STATUS -> hive.status().status().ordinal();
            case VALUE -> hive.status().value();
            case METER_A -> switch (hive.module()) {
                case LanceModule lance -> lance.heat();
                case AegisModule aegis -> aegis.charge();
                case EscortModule escort -> escort.wings()[0].charge();
                default -> 0;
            };
            case METER_B -> hive.module() instanceof EscortModule escort ? escort.wings()[1].charge() : 0;
            case PHASE_A -> hive.module() instanceof EscortModule escort ? escort.wings()[0].phase().ordinal() : 0;
            case PHASE_B -> hive.module() instanceof EscortModule escort ? escort.wings()[1].phase().ordinal() : 0;
            case CONTROL -> hive.controlledBy(player) ? 1 : 0;
            default -> 0;
        };
    }

    public ShipHiveKind kind() {
        return kind;
    }

    public String owner() {
        return owner;
    }

    public int energy() {
        return data.get(ENERGY_LOW) & 0x7FFF | data.get(ENERGY_HIGH) << 15;
    }

    public boolean enabled() {
        return data.get(ENABLED) != 0;
    }

    public ShipStatus.Line status() {
        return ShipStatus.of(data.get(STATUS)).with(data.get(VALUE));
    }

    /** The lance's heat, the aegis's charge, or the first escort wing's charge. */
    public int meterA() {
        return data.get(METER_A);
    }

    /** The second escort wing's charge. */
    public int meterB() {
        return data.get(METER_B);
    }

    public EscortModule.Phase phase(int wing) {
        EscortModule.Phase[] all = EscortModule.Phase.values();
        int ordinal = data.get(wing == 0 ? PHASE_A : PHASE_B);
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : EscortModule.Phase.DOCKED;
    }

    /** Whether this player may switch the hive (its owner, or an operator). */
    public boolean canControl() {
        return data.get(CONTROL) != 0;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (hive == null || !stillValid(player) || id != BUTTON_TOGGLE || !hive.controlledBy(player)) return false;
        hive.setEnabled(!hive.enabled());
        RelicSounds.ui(player, RelicSounds.Ui.TOGGLE);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (hive == null || hive.isRemoved() || !player.isAlive() || player.isSpectator()) return false;
        // Measured to where the hive really is: on a ship, its block stands far off in the ship's plot.
        return ShipFrame.of(hive).centre().distanceToSqr(player.getEyePosition()) <= REACH * REACH;
    }
}
