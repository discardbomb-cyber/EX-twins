package dev.hurtify.relicsaddon.shipshield;

import java.util.function.IntSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * A dock's emitter drones, kept as a count and shown to hoppers and pipes as stacks of the dock's
 * own drone item. It only takes that item and never more than the dock's level allows.
 */
public final class DroneStore implements IItemHandler {
    private final Item drone;
    private final IntSupplier capacity;
    private final Runnable onChange;
    private int count;

    public DroneStore(Item drone, IntSupplier capacity, Runnable onChange) {
        this.drone = drone;
        this.capacity = capacity;
        this.onChange = onChange;
    }

    public int count() { return count; }
    public int capacity() { return capacity.getAsInt(); }
    public Item drone() { return drone; }

    public void setCount(int value) {
        int next = Math.max(0, value);
        if (next != count) {
            count = next;
            onChange.run();
        }
    }

    /** Takes as many drones from {@code stack} as fit and returns how many it took. */
    public int insert(ItemStack stack) {
        if (!stack.is(drone)) return 0;
        int taken = Math.min(stack.getCount(), Math.max(0, capacity() - count));
        if (taken > 0) setCount(count + taken);
        return taken;
    }

    /** Takes up to {@code wanted} drones out and returns how many it gave. */
    public int extract(int wanted) {
        int given = Math.min(Math.max(0, wanted), count);
        if (given > 0) setCount(count - given);
        return given;
    }

    @Override public int getSlots() { return Math.max(1, (capacity() + 63) / 64); }

    @Override public ItemStack getStackInSlot(int slot) {
        int inSlot = Math.min(64, count - slot * 64);
        return inSlot <= 0 ? ItemStack.EMPTY : new ItemStack(drone, inSlot);
    }

    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isItemValid(slot, stack)) return stack;
        int room = Math.max(0, capacity() - count);
        int taken = Math.min(stack.getCount(), room);
        if (taken <= 0) return stack;
        if (!simulate) setCount(count + taken);
        return taken == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - taken);
    }

    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int inSlot = Math.min(64, count - slot * 64);
        int given = Math.min(Math.max(0, amount), Math.max(0, inSlot));
        if (given <= 0) return ItemStack.EMPTY;
        if (!simulate) setCount(count - given);
        return new ItemStack(drone, given);
    }

    @Override public int getSlotLimit(int slot) { return 64; }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return stack.is(drone); }
}
