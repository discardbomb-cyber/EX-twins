package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

public final class HiveController {
    public static final double RADIUS = 2.65;

    public record Equipped(HiveType type, ItemStack stack) { }

    /** The single operable hive in a charm slot; extra hives are ignored even if Curios holds them. */
    public static List<Equipped> active(Player player) {
        if (!EquippedRelicSetResolver.isRealPlayer(player) || !player.isAlive() || player.isSpectator()) return List.of();
        return CuriosApi.getCuriosInventory(player).map(handler -> {
            var result = new ArrayList<Equipped>(1);
            var optional = handler.getStacksHandler(RelicRole.EQUIPMENT_SLOT);
            if (optional.isEmpty()) return result;
            var stacks = optional.get().getStacks();
            for (int slot = 0; slot < stacks.getSlots(); slot++) {
                ItemStack stack = stacks.getStackInSlot(slot);
                if (handler.isSlotActive(RelicRole.EQUIPMENT_SLOT, slot) && stack.getItem() instanceof AutonomousRelicItem item
                        && item.role().isHive() && RelicRuntime.canOperate(player, stack)) {
                    result.add(new Equipped(HiveType.of(item.role()), stack));
                    break;
                }
            }
            return result;
        }).orElseGet(ArrayList::new);
    }

    public static int capacity(Player player, ItemStack stack) {
        HiveType type = HiveType.of(((AutonomousRelicItem) stack.getItem()).role());
        return (int) Math.round(RelicRuntime.stat(player, stack, "drone_count", type.initialCount, 12, HiveType.MAX_DRONES));
    }

    public static HiveStackState prepare(Player player, ItemStack stack, boolean repair) {
        HiveStackState old = stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        HiveStackState next = old.prepare(capacity(player, stack), player.level().getGameTime(), repair);
        if (repair && next != old) {
            int restored = restoredHealth(old, next);
            if (restored > 0 && !dev.hurtify.relicsaddon.power.DevicePower.drain(player, stack, restored * dev.hurtify.relicsaddon.power.DevicePower.HIVE_REPAIR_PER_HP)) {
                next = old.prepare(capacity(player, stack), player.level().getGameTime(), false);
            }
        }
        if (old != next) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), next);
        return next;
    }

    /** Past any flight out (70 ticks) and home (24), with room to spare. */
    private static final long SETTLE_QUIET_TICKS = 120;

    /** Drops timings the swarm no longer needs, so it goes back to one byte per drone on the wire (see {@link HiveStackState#settle}). */
    private static void settle(Player player, ItemStack stack, HiveStackState state) {
        var settings = HiveTaskController.settings(stack);
        int units = state.units().size();
        HiveStackState settled = state.settle(player.level().getGameTime(), dev.hurtify.relicsaddon.drone.HiveSlots.fighterSlots(units, settings),
                settings.fighters(units), SETTLE_QUIET_TICKS);
        if (settled != state) stack.set(ModDataComponents.HIVE_STACK_STATE.get(), settled);
    }

    /** HP regained by drones that already existed; drones added by a larger capacity arrive free. */
    private static int restoredHealth(HiveStackState before, HiveStackState after) {
        int sum = 0, shared = Math.min(before.units().size(), after.units().size());
        for (int index = 0; index < shared; index++) sum += Math.max(0, after.units().get(index).hp() - before.units().get(index).hp());
        return sum;
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        HiveCombatController.tick(player);
        if (player.level().getGameTime() % 10 != 0) return;
        for (Equipped hive : active(player)) settle(player, hive.stack(), prepare(player, hive.stack(), true));
    }

    private HiveController() { }
}
