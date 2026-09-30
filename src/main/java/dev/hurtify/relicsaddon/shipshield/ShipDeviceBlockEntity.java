package dev.hurtify.relicsaddon.shipshield;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.registry.ShipBlocks;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * A ship shield generator or drone dock on a structure. The device keeps its state on an item
 * stack of its own block item, exactly like a worn relic keeps it on the amulet: level, points,
 * batteries and the switch all live in data components, so {@link DevicePower} and the device
 * console work unchanged, and the block drops with everything it earned.
 *
 * <p>Every few seconds the device scans the structure it stands on (an airship, or the connected
 * blocks around it) and notes the docks, generators and item stores it shares it with. Only one
 * generator may run per structure: the second one to be switched on refuses and says why.
 */
public final class ShipDeviceBlockEntity extends BlockEntity {
    /** Ticks between structure scans; a scan of a large build touches thousands of block states. */
    public static final int SCAN_INTERVAL = 200;
    /** Emitter drones a dock holds at level 0 and how many more each level adds (8 at 0, 128 at 10). */
    public static final int DOCK_BASE_CAPACITY = 8, DOCK_CAPACITY_PER_LEVEL = 12;
    /** Blocks of the owner's reach within which a mana battery refills from them. */
    public static final double OWNER_MANA_RANGE = 32;
    public static final String NOTICE_OTHER_GENERATOR = "notice.relics_addon.ship.other_generator";
    public static final String NOTICE_NO_POWER = "notice.relics_addon.ship.no_power";
    public static final String NOTICE_TRUNCATED = "notice.relics_addon.ship.truncated";

    private ItemStack device = ItemStack.EMPTY;
    private @Nullable UUID owner;
    private long enabledAt;
    private long scannedAt = Long.MIN_VALUE;
    private @Nullable ShipStructure structure;
    private final List<BlockPos> docks = new ArrayList<>();
    private final List<BlockPos> stores = new ArrayList<>();
    private @Nullable BlockPos generator;
    private final @Nullable DroneStore drones;
    private final @Nullable IEnergyStorage energy;
    private boolean syncDue;

    public ShipDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ShipBlocks.DEVICE.get(), pos, state);
        RelicRole role = role();
        drones = role.isDroneDock() ? new DroneStore(ModItems.EMITTER_DRONES.get(ShipFamily.of(role)).get(), this::droneCapacity, this::onDronesChanged) : null;
        energy = DevicePower.hasRf(role) ? new Energy() : null;
    }

    // --- identity and state --------------------------------------------------------------------

    public RelicRole role() { return getBlockState().getBlock() instanceof ShipDeviceBlock block ? block.role() : RelicRole.RF_SHIP_GENERATOR; }
    public ShipFamily family() { return ShipFamily.of(role()); }

    /** The stack holding this device's components; fresh until the block is placed or loaded. */
    public ItemStack device() {
        if (device.isEmpty()) {
            device = new ItemStack(ShipBlocks.item(role()));
            ShipDeviceItem.ensureState(device);
        }
        return device;
    }

    public ShipDeviceState state() { return device().getOrDefault(ModDataComponents.SHIP_DEVICE_STATE.get(), ShipDeviceState.DEFAULT); }
    private void setState(ShipDeviceState state) {
        device().set(ModDataComponents.SHIP_DEVICE_STATE.get(), state);
        deviceChanged();
    }

    public boolean enabled() { return state().enabled(); }
    public int level() { return device().getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT).level(); }
    public @Nullable UUID owner() { return owner; }
    public @Nullable ShipStructure structure() { return structure; }
    public List<BlockPos> docks() { return List.copyOf(docks); }
    public List<BlockPos> stores() { return List.copyOf(stores); }
    public @Nullable BlockPos generator() { return generator; }
    public @Nullable IEnergyStorage energyStorage() { return energy; }
    public @Nullable IItemHandler droneHandler() { return drones; }

    /** Whether the device can run right now: switched on and its batteries hold charge. */
    public boolean operating() { return enabled() && DevicePower.powered(null, device()); }

    /** Marks the device's stack changed: saved with the chunk and shown to consoles shortly. */
    public void deviceChanged() {
        setChanged();
        syncDue = true;
    }

    // --- drones (docks) -------------------------------------------------------------------------

    public static int dockCapacity(int level) { return DOCK_BASE_CAPACITY + DOCK_CAPACITY_PER_LEVEL * level; }
    public int droneCapacity() { return drones == null ? 0 : dockCapacity(level()); }
    public int droneCount() { return drones == null ? 0 : drones.count(); }
    public boolean acceptsDrones(ItemStack stack) { return drones != null && drones.isItemValid(0, stack) && drones.count() < droneCapacity(); }
    public int insertDrones(ItemStack stack) { return drones == null ? 0 : drones.insert(stack); }

    private void onDronesChanged() {
        if (drones != null) setState(state().withDrones(drones.count(), droneCapacity()));
    }

    // --- switching --------------------------------------------------------------------------------

    /**
     * Switches the device on or off for {@code player}. A generator only comes on when no other
     * generator runs on the same structure; the refusal is kept as the device's notice and told
     * to the player. Returns whether the switch changed.
     */
    public boolean setEnabled(@Nullable Player player, boolean enabled) {
        if (!(level instanceof ServerLevel world)) return false;
        ShipDeviceState state = state();
        if (state.enabled() == enabled) return false;
        if (enabled) {
            scan(world, world.getGameTime());
            if (role().isShipGenerator()) {
                BlockPos other = runningGenerator(world, Long.MAX_VALUE);
                if (other != null) {
                    setState(state().withEnabled(false).withNotice(NOTICE_OTHER_GENERATOR, position(other)));
                    if (player != null) player.displayClientMessage(notice(), true);
                    return false;
                }
            }
            enabledAt = world.getGameTime();
        }
        setState(state().withEnabled(enabled).withNotice("", ""));
        RelicRuntime.setEnabled(player, device(), enabled);
        updateLit(enabled);
        RelicSounds.ui(player, RelicSounds.Ui.TOGGLE);
        return true;
    }

    public boolean toggle(@Nullable Player player) { return setEnabled(player, !enabled()); }

    /** The current notice as text, for the player's action bar. */
    public Component notice() {
        ShipDeviceState state = state();
        return state.notice().isEmpty() ? Component.empty() : Component.translatable(state.notice(), state.detail());
    }

    private void updateLit(boolean lit) {
        if (level != null && getBlockState().hasProperty(ShipDeviceBlock.LIT) && getBlockState().getValue(ShipDeviceBlock.LIT) != lit) {
            level.setBlock(worldPosition, getBlockState().setValue(ShipDeviceBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }

    private static String position(BlockPos pos) { return pos.toShortString(); }

    // --- structure ----------------------------------------------------------------------------

    /** Rescans the structure and the devices on it. Cheap enough to call on demand, done on a timer otherwise. */
    public void scan(ServerLevel world, long now) {
        scannedAt = now;
        structure = ShipStructures.locate(world, worldPosition);
        docks.clear();
        stores.clear();
        generator = null;
        ShipFamily family = family();
        RelicRole role = role();
        structure.forEach(pos -> {
            BlockEntity entity = world.getBlockEntity(pos);
            if (entity == null || pos.equals(worldPosition)) return;
            if (entity instanceof ShipDeviceBlockEntity other) {
                if (other.role().isDroneDock() && other.family() == family && role.isShipGenerator()) docks.add(pos.immutable());
                if (other.role().isShipGenerator() && other.family() == family && role.isDroneDock() && (generator == null || other.enabled())) generator = pos.immutable();
                return;
            }
            if (world.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) stores.add(pos.immutable());
        });
        ShipDeviceState state = state().withStructure(structure.size(), docks.size(), stores.size());
        if (state.notice().isEmpty() || state.notice().equals(NOTICE_TRUNCATED)) {
            state = structure.truncated() ? state.withNotice(NOTICE_TRUNCATED, String.valueOf(structure.size())) : state.withNotice("", "");
        }
        setState(state);
    }

    /** Another switched-on generator on this structure that came on before {@code before}, or null. */
    private @Nullable BlockPos runningGenerator(ServerLevel world, long before) {
        if (structure == null) return null;
        BlockPos[] found = {null};
        structure.forEach(pos -> {
            if (found[0] != null || pos.equals(worldPosition)) return;
            if (world.getBlockEntity(pos) instanceof ShipDeviceBlockEntity other && other.role().isShipGenerator() && other.enabled()
                    && (other.enabledAt < before || other.enabledAt == before && pos.asLong() < worldPosition.asLong())) {
                found[0] = pos.immutable();
            }
        });
        return found[0];
    }

    /** Emitter drones the structure needs: eight per 64 blocks, rounded up. */
    public int dronesWanted() {
        int per = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_DRONES_PER_64_BLOCKS.get() : 8;
        return structure == null ? 0 : (structure.size() + 63) / 64 * per;
    }

    // --- ticking ---------------------------------------------------------------------------------

    void serverTick() {
        if (!(level instanceof ServerLevel world)) return;
        long now = world.getGameTime();
        if (now - scannedAt >= SCAN_INTERVAL) {
            scan(world, now);
            if (enabled() && role().isShipGenerator()) {
                BlockPos other = runningGenerator(world, enabledAt);
                if (other != null) {
                    setState(state().withEnabled(false).withNotice(NOTICE_OTHER_GENERATOR, position(other)));
                    RelicRuntime.setEnabled(null, device(), false);
                    updateLit(false);
                }
            }
        }
        if (enabled()) {
            if (now % 20 == 0) {
                int upkeep = role().isShipGenerator() ? DevicePower.SHIELD_UPKEEP : DevicePower.HIVE_UPKEEP + droneCount() / 10;
                boolean paid = DevicePower.drain(null, device(), upkeep);
                ShipDeviceState state = state();
                if (!paid && state.notice().isEmpty()) setState(state.withNotice(NOTICE_NO_POWER, ""));
                else if (paid && state.notice().equals(NOTICE_NO_POWER)) setState(state.withNotice("", ""));
                else deviceChanged();
            }
            if (now % 10 == 5) refillMana(world);
        }
        if (syncDue && now % 10 == 0) {
            syncDue = false;
            world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Mana batteries refill from the owner nearby (magic mods, then experience) and from Mana Cells in a linked store. */
    private void refillMana(ServerLevel world) {
        if (!DevicePower.hasMana(role())) return;
        DeviceEnergy energy = DevicePower.energy(device());
        int capacity = DevicePower.capacity(device());
        if (!energy.manaOn() || energy.mana() >= capacity) return;
        if (owner != null && world.getPlayerByUUID(owner) instanceof ServerPlayer player && player.isAlive()
                && player.level() == world && player.distanceToSqr(worldPosition.getCenter()) <= OWNER_MANA_RANGE * OWNER_MANA_RANGE) {
            int before = energy.mana();
            DevicePower.chargeMana(player, device());
            if (DevicePower.energy(device()).mana() != before) deviceChanged();
        }
        energy = DevicePower.energy(device());
        int cellPoints = AddonConfig.SPEC.isLoaded() ? AddonConfig.SHIP_MANA_CELL_POINTS.get() : 12_500;
        if (capacity - energy.mana() >= cellPoints && takeFromStores(world, ModItems.MANA_CELL.get())) {
            device().set(ModDataComponents.DEVICE_ENERGY.get(), energy.withMana(Math.min(capacity, energy.mana() + cellPoints)));
            deviceChanged();
        }
    }

    /** Takes one {@code item} out of the first linked store that has it. */
    public boolean takeFromStores(ServerLevel world, net.minecraft.world.item.Item item) {
        for (BlockPos pos : stores) {
            IItemHandler handler = world.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (handler.getStackInSlot(slot).is(item) && !handler.extractItem(slot, 1, false).isEmpty()) return true;
            }
        }
        return false;
    }

    // --- placing, breaking, saving -------------------------------------------------------------

    void onPlaced(@Nullable LivingEntity placer, ItemStack stack) {
        if (stack.getItem() instanceof ShipDeviceItem) {
            device = stack.copyWithCount(1);
            ShipDeviceItem.ensureState(device);
        }
        if (placer instanceof Player player) owner = player.getUUID();
        // A generator carried while switched on comes back off: it must check its new structure first.
        if (state().enabled()) {
            device().set(ModDataComponents.SHIP_DEVICE_STATE.get(), state().withEnabled(false));
        }
        if (level instanceof ServerLevel world) scan(world, world.getGameTime());
        deviceChanged();
    }

    void onRemoved() {
        if (drones != null && drones.count() > 0 && level != null) {
            int left = drones.extract(drones.count());
            while (left > 0) {
                int stack = Math.min(64, left);
                net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), new ItemStack(drones.drone(), stack));
                left -= stack;
            }
        }
    }

    /** Copies the device's components onto {@code stack} (the pick-block and drop item). */
    public void saveToItem(ItemStack stack, HolderLookup.Provider registries) {
        stack.applyComponents(deviceComponents());
    }

    private DataComponentMap deviceComponents() {
        DataComponentMap.Builder builder = DataComponentMap.builder();
        collectImplicitComponents(builder);
        return builder.build();
    }

    @Override protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        ItemStack stack = device();
        builder.set(ModDataComponents.INSTANCE_ID.get(), stack.get(ModDataComponents.INSTANCE_ID.get()));
        builder.set(ModDataComponents.DEVICE_PROGRESSION.get(), stack.get(ModDataComponents.DEVICE_PROGRESSION.get()));
        builder.set(ModDataComponents.DEVICE_ENERGY.get(), stack.get(ModDataComponents.DEVICE_ENERGY.get()));
        // Drones stay in the dock only while it stands; the dropped block starts empty.
        builder.set(ModDataComponents.SHIP_DEVICE_STATE.get(), state().withEnabled(false).withNotice("", "").withDrones(0, droneCapacity()));
    }

    @Override protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        ItemStack stack = device();
        var id = input.get(ModDataComponents.INSTANCE_ID.get());
        var progression = input.get(ModDataComponents.DEVICE_PROGRESSION.get());
        var energy = input.get(ModDataComponents.DEVICE_ENERGY.get());
        var state = input.get(ModDataComponents.SHIP_DEVICE_STATE.get());
        if (id != null) stack.set(ModDataComponents.INSTANCE_ID.get(), id);
        if (progression != null) stack.set(ModDataComponents.DEVICE_PROGRESSION.get(), progression);
        if (energy != null) stack.set(ModDataComponents.DEVICE_ENERGY.get(), energy);
        if (state != null) stack.set(ModDataComponents.SHIP_DEVICE_STATE.get(), state.withEnabled(false));
    }

    @Override public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("device");
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("device", device().saveOptional(registries));
        if (owner != null) tag.putUUID("owner", owner);
        tag.putLong("enabled_at", enabledAt);
        if (drones != null) tag.putInt("drones", drones.count());
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ItemStack loaded = ItemStack.parseOptional(registries, tag.getCompound("device"));
        if (loaded.getItem() instanceof ShipDeviceItem item && item.role() == role()) {
            device = loaded;
            ShipDeviceItem.ensureState(device);
        }
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        enabledAt = tag.getLong("enabled_at");
        if (drones != null) drones.setCount(tag.getInt("drones"));
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    /** Comparators read the fuller battery, 0..15. */
    public int comparatorSignal() {
        ItemStack stack = device();
        DeviceEnergy energy = DevicePower.energy(stack);
        double rf = DevicePower.hasRf(role()) ? energy.rf() / (double) DevicePower.feCapacity(stack) : 0;
        double mana = DevicePower.hasMana(role()) ? energy.mana() / (double) DevicePower.capacity(stack) : 0;
        return (int) Math.round(Math.max(rf, mana) * 15);
    }

    /** The RF battery as a Forge Energy block: receive only, like the item capability of worn devices. */
    private final class Energy implements IEnergyStorage {
        @Override public int receiveEnergy(int amount, boolean simulate) {
            int accepted = DevicePower.receiveFe(device(), amount, simulate);
            if (accepted > 0 && !simulate) deviceChanged();
            return accepted;
        }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return DevicePower.energy(device()).rf(); }
        @Override public int getMaxEnergyStored() { return DevicePower.feCapacity(device()); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    }

}
