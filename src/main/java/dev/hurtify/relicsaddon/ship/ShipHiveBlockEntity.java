package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModBlockEntities;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A ship hive's block: who owns it, its FE battery, whether it is switched on, and what its kind of hive is doing
 * ({@link ShipModule}). It ticks where it stands, which on a ship is the ship's plot; {@link ShipFrame} carries it out to
 * where the ship really is. Clients get the module's state to draw the drones from.
 */
public final class ShipHiveBlockEntity extends BlockEntity {
    /** Hives loaded on this client, for the renderer (which draws them in the world, not in their plot). */
    public static final Set<ShipHiveBlockEntity> CLIENT_LOADED = Collections.newSetFromMap(new WeakHashMap<>());
    /** Fewest ticks between two updates of a hive's drones sent to clients. */
    private static final int SYNC_GAP = 2;

    private final ShipHiveKind kind;
    private final Battery battery;
    private final ShipModule module;
    @Nullable
    private UUID owner;
    private String ownerName = "";
    private boolean enabled = true;
    private boolean syncWanted;
    private long syncedAt = Long.MIN_VALUE / 4;
    /** The frame the hive last ticked in (server) and the tick of it. */
    @Nullable
    private ShipFrame frame;

    public ShipHiveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHIP_HIVE.get(), pos, state);
        kind = state.getBlock() instanceof ShipHiveBlock block ? block.kind() : ShipHiveKind.LANCE;
        battery = new Battery(kind.capacity);
        module = switch (kind) {
            case AEGIS -> new AegisModule();
            case ESCORT -> new EscortModule();
            case LANCE -> new LanceModule();
        };
    }

    public ShipHiveKind kind() {
        return kind;
    }

    public ShipModule module() {
        return module;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean enabled() {
        return enabled;
    }

    public IEnergyStorage energy() {
        return battery;
    }

    /** The face the drones launch from. */
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(ShipHiveBlock.FACING) ? state.getValue(ShipHiveBlock.FACING) : Direction.UP;
    }

    /** The launch face's outward normal in the ship's own terms. */
    public Vec3 normal() {
        return Vec3.atLowerCornerOf(facing().getNormal());
    }

    /** Makes {@code player} the hive's owner: sworn to its ship, and credited with what it kills while they are on. */
    public void claim(Player player) {
        owner = player.getUUID();
        ownerName = player.getGameProfile().getName();
        setChanged();
    }

    /** Whether the hive is switched on and no redstone signal grounds it (whether it can pay for its work is asked as it works). */
    boolean switchedOn(Level level) {
        return enabled && !level.hasNeighborSignal(worldPosition);
    }

    /** Takes {@code amount} FE for the work of a tick; false (and nothing taken) when the battery cannot pay it. */
    boolean draw(int amount) {
        return battery.draw(amount);
    }

    /** Switches the hive on or off (its owner's switch; a redstone signal grounds it besides). */
    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        changed();
    }

    /** Asks for the module's state to be sent to clients soon. */
    void changed() {
        syncWanted = true;
        setChanged();
    }

    int comparatorSignal() {
        int stored = battery.getEnergyStored();
        return stored <= 0 ? 0 : Math.max(1, (int) ((long) stored * 15 / battery.getMaxEnergyStored()));
    }

    /** A right click: an unowned hive is claimed; the owner switches it with a sneak; anyone else sees its state. */
    void use(Player player) {
        if (owner == null) claim(player);
        if (player.isShiftKeyDown() && (player.getUUID().equals(owner) || player.hasPermissions(2))) setEnabled(!enabled);
        if (player instanceof ServerPlayer server) server.displayClientMessage(status(), true);
    }

    private Component status() {
        int percent = (int) Math.round(100.0 * battery.getEnergyStored() / battery.getMaxEnergyStored());
        Component state = !enabled ? Component.translatable("ship.relics_addon.status.off").withStyle(ChatFormatting.GRAY)
                : level != null && level.hasNeighborSignal(worldPosition) ? Component.translatable("ship.relics_addon.status.grounded").withStyle(ChatFormatting.GOLD)
                : module.status();
        return Component.translatable("block.relics_addon." + kind.id).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  " + percent + "%  ").withStyle(ChatFormatting.WHITE)).append(state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ShipHiveBlockEntity hive) {
        if (level instanceof ServerLevel server) hive.tick(server, server.getGameTime());
    }

    private void tick(ServerLevel level, long now) {
        ShipFrame here = ShipFrame.of(this);
        frame = here;
        ShipBrain brain = ShipBrain.of(level, here, this, now);
        module.tick(this, level, here, brain, now);
        if (syncWanted && (now - syncedAt >= SYNC_GAP || now < syncedAt)) {
            syncWanted = false;
            syncedAt = now;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, ShipHiveBlockEntity hive) {
        CLIENT_LOADED.add(hive);
    }

    /** The frame the hive last ticked in on the server, or null before its first tick. */
    @Nullable
    public ShipFrame lastFrame() {
        return frame;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) CLIENT_LOADED.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_LOADED.remove(this);
        module.stop(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_LOADED.remove(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (!ownerName.isEmpty()) tag.putString("OwnerName", ownerName);
        tag.putBoolean("Enabled", enabled);
        tag.putInt("Energy", battery.getEnergyStored());
        CompoundTag state = new CompoundTag();
        module.save(state);
        tag.put("Module", state);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.getString("OwnerName");
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        battery.set(tag.getInt("Energy"));
        module.load(tag.getCompound("Module"));
        // Only clients get what the drones are doing now; a hive loaded from disk starts from rest.
        if (tag.contains("Sync")) module.loadSync(tag.getCompound("Sync"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        CompoundTag sync = new CompoundTag();
        module.saveSync(sync);
        tag.put("Sync", sync);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The hive's FE battery: filled from outside, drawn only by the hive itself. */
    private final class Battery extends EnergyStorage {
        Battery(int capacity) {
            super(capacity, Math.max(1, capacity / 40), 0);
        }

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int received = super.receiveEnergy(toReceive, simulate);
            if (received > 0 && !simulate) setChanged();
            return received;
        }

        boolean draw(int amount) {
            if (amount <= 0 || !DevicePower.required()) return true;
            if (energy < amount) return false;
            energy -= amount;
            setChanged();
            return true;
        }

        void set(int stored) {
            energy = Math.clamp(stored, 0, capacity);
        }
    }
}
