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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
 * generator may run per structure: the second one to be switched on refuses and says why. A
 * generator raises a {@link ShipShield} round the structure; a dock lends its emitter drones to
 * the generator of its family and keeps a snapshot of the ship for repairs.
 *
 * <p>On a client the block entity keeps the generator's {@link ShipShieldView} and traces the same
 * shell from its own blocks, for the renderer.
 */
public final class ShipDeviceBlockEntity extends BlockEntity {
    /** Ticks between structure scans; a scan of a large build touches thousands of block states. */
    public static final int SCAN_INTERVAL = 200;
    /** Ticks a scan asked for by a block change waits, so a burst of changes is one scan. */
    public static final int SCAN_SOON = 10;
    /** Emitter drones a dock holds at level 0 and how many more each level adds (8 at 0, 128 at 10). */
    public static final int DOCK_BASE_CAPACITY = 8, DOCK_CAPACITY_PER_LEVEL = 12;
    /** Blocks of the owner's reach within which a mana battery refills from them. */
    public static final double OWNER_MANA_RANGE = 32;
    public static final String NOTICE_OTHER_GENERATOR = "notice.relics_addon.ship.other_generator";
    public static final String NOTICE_NO_POWER = "notice.relics_addon.ship.no_power";
    public static final String NOTICE_TRUNCATED = "notice.relics_addon.ship.truncated";
    /** The generators a client has loaded, for the shell renderer. */
    public static final Set<ShipDeviceBlockEntity> CLIENT_LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private ItemStack device = ItemStack.EMPTY;
    private @Nullable UUID owner;
    private long enabledAt;
    /** Game time of the last structure scan; negative until the first one (a fresh or reloaded block scans at once). */
    private long scannedAt = -1;
    private long scanSoonAt = -1;
    private @Nullable ShipStructure structure;
    private final List<BlockPos> docks = new ArrayList<>();
    private final List<BlockPos> stores = new ArrayList<>();
    private @Nullable BlockPos generator;
    private final @Nullable DroneStore drones;
    private int dronesOut;
    private final @Nullable IEnergyStorage energy;
    private final @Nullable ShipShield shield;
    private @Nullable ShipRepair repair;
    private boolean syncDue;
    private @Nullable CompoundTag loadedShield;

    // Client side: the generator's shield as last told, and the shell traced here from the same blocks.
    private ShipShieldView view = ShipShieldView.NONE;
    private List<ShellMesh> clientLayers = List.of();
    private long clientKey, clientCheckedAt = Long.MIN_VALUE;
    private @Nullable CompletableFuture<List<ShellMesh>> clientPending;
    private long clientPendingKey;

    public ShipDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ShipBlocks.DEVICE.get(), pos, state);
        RelicRole role = role();
        drones = role.isDroneDock() ? new DroneStore(ModItems.EMITTER_DRONES.get(ShipFamily.of(role)).get(), this::droneCapacity, this::onDronesChanged, () -> dronesOut) : null;
        energy = DevicePower.hasRf(role) ? new Energy() : null;
        shield = role.isShipGenerator() ? new ShipShield(this) : null;
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
    /** The shield a generator raises; null on a dock. */
    public @Nullable ShipShield shield() { return shield; }

    /** Whether the device can run right now: switched on and its batteries hold charge. */
    public boolean operating() { return enabled() && DevicePower.powered(null, device()); }

    /** The owner, when they are on this server. */
    public @Nullable ServerPlayer ownerOnline() {
        if (owner == null || !(level instanceof ServerLevel world)) return null;
        ServerPlayer player = world.getServer().getPlayerList().getPlayer(owner);
        return player != null && player.isAlive() ? player : null;
    }

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
    /** Drones of this dock out on the shell (they stay in the dock's count, but hoppers cannot take them). */
    public int dronesOut() { return dronesOut; }
    /** Drones of this dock at home and free to go out. */
    public int spareDrones() { return Math.max(0, droneCount() - dronesOut); }

    /** A drone leaves for the shell. */
    void sendDrone() {
        dronesOut = Math.min(droneCount(), dronesOut + 1);
        onDronesChanged();
    }

    /** A drone is back for good (its seat is gone), or vanished with its generator. */
    void droneHome() {
        dronesOut = Math.max(0, dronesOut - 1);
        onDronesChanged();
    }

    private void onDronesChanged() {
        if (drones == null) return;
        if (dronesOut > drones.count()) dronesOut = drones.count();
        setState(state().withDrones(drones.count(), droneCapacity(), 0).withRepair(dronesOut, state().repairQueue()));
        // The generator's count of docked drones follows on its next scan; ask for one now.
        if (generator != null && level != null && level.getBlockEntity(generator) instanceof ShipDeviceBlockEntity owner) owner.requestScan();
    }

    /** Makes the next server tick rescan the structure. */
    public void requestScan() {
        scannedAt = -1;
    }

    /** Rescans the structure within a few ticks (a block changed under the shield). */
    public void requestScanSoon() {
        if (scanSoonAt < 0 && level != null) scanSoonAt = level.getGameTime() + SCAN_SOON;
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
        } else if (shield != null) {
            shield.recall();
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
        scanSoonAt = -1;
        structure = ShipStructures.locate(world, worldPosition);
        docks.clear();
        stores.clear();
        generator = null;
        ShipFamily family = family();
        RelicRole role = role();
        int[] docked = {0, 0, 0};
        for (BlockPos pos : structure.blockEntities()) {
            BlockEntity entity = world.getBlockEntity(pos);
            if (entity == null || pos.equals(worldPosition)) continue;
            if (entity instanceof ShipDeviceBlockEntity other) {
                if (other.role().isDroneDock() && other.family() == family && role.isShipGenerator()) {
                    docks.add(pos.immutable());
                    docked[0] += other.droneCount();
                    docked[1] += other.droneCapacity();
                    docked[2] += other.dronesOut();
                }
                if (other.role().isShipGenerator() && other.family() == family && role.isDroneDock() && (generator == null || other.enabled())) generator = pos.immutable();
                continue;
            }
            if (world.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) stores.add(pos.immutable());
        }
        ShipDeviceState state = state().withStructure(structure.size(), docks.size(), stores.size());
        // A generator reports the drones of all its docks against what the structure needs; a dock reports its own.
        state = role.isShipGenerator() ? state.withDrones(docked[0], docked[1], dronesWanted()) : state.withDrones(droneCount(), droneCapacity(), 0);
        if (shield != null) state = state.withShield(docked[2], shield.layerCount(), shield.integrity().total(), shield.integrity().capacity());
        if (repair != null) state = state.withRepair(dronesOut, repair.queue());
        if (!state.notice().equals(NOTICE_OTHER_GENERATOR) && !state.notice().equals(NOTICE_NO_POWER)) {
            String notice = structure.truncated() ? NOTICE_TRUNCATED : shield == null ? "" : shield.notice(now);
            String detail = structure.truncated() ? String.valueOf(structure.size()) : shield == null ? "" : shield.noticeDetail(now);
            state = state.withNotice(notice, detail);
        }
        setState(state);
    }

    /** Another switched-on generator on this structure that came on before {@code before}, or null. */
    private @Nullable BlockPos runningGenerator(ServerLevel world, long before) {
        if (structure == null) return null;
        for (BlockPos pos : structure.blockEntities()) {
            if (pos.equals(worldPosition)) continue;
            if (world.getBlockEntity(pos) instanceof ShipDeviceBlockEntity other && other.role().isShipGenerator() && other.enabled()
                    && (other.enabledAt < before || other.enabledAt == before && pos.asLong() < worldPosition.asLong())) {
                return pos;
            }
        }
        return null;
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
        if (loadedShield != null && shield != null) {
            shield.load(loadedShield, now);
            loadedShield = null;
        }
        if (scannedAt < 0 || now - scannedAt >= SCAN_INTERVAL || scanSoonAt >= 0 && now >= scanSoonAt) {
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
        if (shield != null) shield.tick(world, now);
        if (repair != null) repair.tick(world, now);
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
                && player.level() == world && player.distanceToSqr(ShipStructures.worldPosition(world, worldPosition.getCenter())) <= OWNER_MANA_RANGE * OWNER_MANA_RANGE) {
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

    /** Whether a linked store holds {@code item}. */
    public boolean storesHave(ServerLevel world, net.minecraft.world.item.Item item) {
        for (BlockPos pos : stores) {
            IItemHandler handler = world.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) if (handler.getStackInSlot(slot).is(item)) return true;
        }
        return false;
    }

    // --- client ---------------------------------------------------------------------------------------

    /** What the server last said about the shield (client side). */
    public ShipShieldView view() { return view; }

    /**
     * The shell layers as traced on this client from its own blocks, innermost first; empty until
     * traced. The structure is looked at again every few seconds while the shield stands.
     */
    public List<ShellMesh> clientLayers() {
        if (level == null || !level.isClientSide() || shield == null) return List.of();
        long now = level.getGameTime();
        if (clientPending != null && clientPending.isDone()) {
            try {
                List<ShellMesh> traced = clientPending.join();
                if (!traced.isEmpty() && !traced.get(0).isEmpty()) {
                    clientLayers = traced;
                    clientKey = clientPendingKey;
                }
            } catch (RuntimeException ignored) {
                // A failed trace is tried again at the next look.
            }
            clientPending = null;
        }
        if (view.active() && clientPending == null && now - clientCheckedAt >= 40) {
            clientCheckedAt = now;
            ShipStructure structure = ShipStructures.locate(level, worldPosition);
            long key = structure.fingerprint() * 31 + Double.doubleToLongBits(view.offset()) + view.layers() * 1024L + view.cellLimit();
            if (key != clientKey && structure.size() > 0) {
                var blocks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(structure.size());
                structure.forEach(pos -> blocks.add(pos.asLong()));
                List<CompletableFuture<ShellMesh>> traces = new ArrayList<>();
                for (int layer = 0; layer < view.layers(); layer++) {
                    traces.add(ShellCache.trace(blocks, view.offset() + layer, view.cellLimit(), net.minecraft.Util.backgroundExecutor()));
                }
                clientPendingKey = key;
                clientPending = CompletableFuture.allOf(traces.toArray(CompletableFuture[]::new)).thenApply(ignored -> traces.stream().map(CompletableFuture::join).toList());
            }
        }
        return clientLayers;
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide() && shield != null) CLIENT_LOADED.add(this);
    }

    @Override public void setRemoved() {
        super.setRemoved();
        CLIENT_LOADED.remove(this);
    }

    @Override public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_LOADED.remove(this);
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
        if (level instanceof ServerLevel world && shield != null) shield.release(world);
        if (drones != null && drones.count() > 0 && level != null) {
            dronesOut = 0;
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
        builder.set(ModDataComponents.SHIP_DEVICE_STATE.get(), state().withEnabled(false).withNotice("", "").withDrones(0, droneCapacity(), 0).withRepair(0, 0));
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
        if (drones != null) {
            tag.putInt("drones", drones.count());
            tag.putInt("drones_out", dronesOut);
        }
        if (shield != null && level != null) {
            CompoundTag state = new CompoundTag();
            shield.save(state, level.getGameTime());
            tag.put("shield", state);
        }
        if (repair != null) tag.put("repair", repair.save(registries));
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
        if (drones != null) {
            drones.setCount(tag.getInt("drones"));
            dronesOut = Math.min(drones.count(), tag.getInt("drones_out"));
        }
        if (shield != null && tag.contains("shield", Tag.TAG_COMPOUND)) loadedShield = tag.getCompound("shield");
        if (role().isDroneDock() && tag.contains("repair", Tag.TAG_COMPOUND)) repair().load(tag.getCompound("repair"), registries);
        if (tag.contains("view", Tag.TAG_COMPOUND)) {
            view = ShipShieldView.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("view")).result().orElse(ShipShieldView.NONE);
        }
    }

    /** The dock's repair work, made on first use; null on a generator. */
    public @Nullable ShipRepair repair() {
        if (repair == null && role().isDroneDock()) repair = new ShipRepair(this);
        return repair;
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        // The shield's drones and patches travel as the view; the saved form and the snapshot stay home.
        tag.remove("shield");
        tag.remove("repair");
        if (shield != null && level != null) {
            ShipShieldView.CODEC.encodeStart(NbtOps.INSTANCE, shield.view(level.getGameTime())).result().ifPresent(view -> tag.put("view", view));
        }
        return tag;
    }

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

    /** The level, for the shield's helpers. */
    public @Nullable Level world() { return level; }
}
