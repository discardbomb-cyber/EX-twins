package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.network.OpenDevicePayload;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;

/** Client-side list of devices the console can address: Curios charm slots first, then the inventory. */
public final class DeviceTargets {
    public record Target(boolean charm, int slot) { }

    private static Target last;

    public static List<Target> collect(Player player) {
        List<Target> targets = new ArrayList<>();
        CuriosApi.getCuriosInventory(player).flatMap(handler -> handler.getStacksHandler(RelicRole.EQUIPMENT_SLOT)).ifPresent(handler -> {
            for (int slot = 0; slot < handler.getStacks().getSlots(); slot++) {
                if (isDevice(handler.getStacks().getStackInSlot(slot))) targets.add(new Target(true, slot));
            }
        });
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isDevice(player.getInventory().getItem(slot))) targets.add(new Target(false, slot));
        }
        return targets;
    }

    /** Opens the most recently used device if it is still present, otherwise the first one. */
    public static void openPreferred() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        List<Target> targets = collect(player);
        if (targets.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.relics_addon.no_devices"), true);
            return;
        }
        open(targets.contains(last) ? last : targets.getFirst());
    }

    /** Finds where a stack instance lives on the player: a Curios charm slot or the inventory. */
    public static Target locate(Player player, ItemStack stack) {
        var charm = CuriosApi.getCuriosInventory(player).flatMap(handler -> handler.getStacksHandler(RelicRole.EQUIPMENT_SLOT));
        if (charm.isPresent()) {
            var stacks = charm.get().getStacks();
            for (int slot = 0; slot < stacks.getSlots(); slot++) if (stacks.getStackInSlot(slot) == stack) return new Target(true, slot);
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot) == stack) return new Target(false, slot);
        }
        return null;
    }

    public static void open(Target target) {
        last = target;
        PacketDistributor.sendToServer(new OpenDevicePayload(target.charm(), target.slot()));
    }

    private static boolean isDevice(ItemStack stack) {
        return stack.getItem() instanceof AutonomousRelicItem item && item.role().available();
    }

    private DeviceTargets() {
    }
}
