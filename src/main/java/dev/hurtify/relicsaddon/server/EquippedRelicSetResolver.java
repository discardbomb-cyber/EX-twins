package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.Optional;
import java.util.function.Predicate;

public final class EquippedRelicSetResolver {
    public static Optional<ItemStack> findEquipped(Player player, RelicRole role) {
        return findEquipped(player, role, role.slot());
    }

    public static Optional<ItemStack> findEquipped(Player player, RelicRole role, String slot) {
        if (!isRealPlayer(player)) {
            return Optional.empty();
        }

        Predicate<ItemStack> predicate = stack -> isRole(stack, role);
        return CuriosApi.getCuriosInventory(player)
                .flatMap(handler -> handler.findFirstCurio(predicate, slot))
                .map(SlotResult::stack);
    }

    public static Optional<ItemStack> findFirstEquipped(Player player, String slot, RelicRole... roles) {
        if (!isRealPlayer(player)) {
            return Optional.empty();
        }

        for (RelicRole role : roles) {
            Optional<ItemStack> stack = findEquipped(player, role, slot);
            if (stack.isPresent()) {
                return stack;
            }
        }

        return Optional.empty();
    }

    public static boolean isRole(ItemStack stack, RelicRole role) {
        return stack.getItem() instanceof AutonomousRelicItem relic && relic.role() == role;
    }

    public static Optional<ItemStack> findFirstActive(Player player, String slot, RelicRole... roles) {
        if (!isRealPlayer(player) || !player.isAlive() || player.isSpectator()) {
            return Optional.empty();
        }
        return CuriosApi.getCuriosInventory(player).flatMap(handler -> {
            var slots = handler.getStacksHandler(slot);
            if (slots.isEmpty()) {
                return Optional.empty();
            }
            var stacks = slots.get().getStacks();
            for (RelicRole role : roles) {
                for (int index = 0; index < stacks.getSlots(); index++) {
                    ItemStack stack = stacks.getStackInSlot(index);
                    if (handler.isSlotActive(slot, index) && isRole(stack, role) && RelicRuntime.canOperate(player, stack)) {
                        return Optional.of(stack);
                    }
                }
            }
            return Optional.empty();
        });
    }

    public static boolean isRealPlayer(Player player) {
        return !(player instanceof FakePlayer);
    }

    private EquippedRelicSetResolver() {
    }
}
