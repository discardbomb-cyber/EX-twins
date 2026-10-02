package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import top.theillusivec4.curios.api.CuriosApi;

/** Operator test launch: prepares a charged hive and uses the normal server timeline. */
@EventBusSubscriber(modid = RelicsAddon.MOD_ID)
public final class ArmageddonCommand {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("armageddon").requires(source -> source.hasPermission(2));
        for (String type : new String[]{"twins", "mana", "rf"}) {
            root.then(Commands.literal(type)
                    .executes(ctx -> launch(ctx.getSource(), type, null))
                    .then(Commands.argument("target", Vec3Argument.vec3())
                            .executes(ctx -> launch(ctx.getSource(), type, Vec3Argument.getVec3(ctx, "target")))));
        }
        event.getDispatcher().register(Commands.literal("relics_addon").then(root));
    }

    private static int launch(CommandSourceStack source, String type, Vec3 requested)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        var handler = inventory == null ? null : inventory.getStacksHandler(RelicRole.EQUIPMENT_SLOT).orElse(null);
        if (handler == null || handler.getSlots() < 1) return 0;
        var slots = handler.getStacks();
        ItemStack previous = slots.getStackInSlot(0).copy();
        ItemStack hive = new ItemStack(switch (type) {
            case "mana" -> ModItems.MANA_HIVE.get();
            case "rf" -> ModItems.RF_HIVE.get();
            default -> ModItems.TWINS_HIVE.get();
        });
        AutonomousRelicItem.ensureState(hive);
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        hive.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(hive));
        inventory.setEquippedCurio(RelicRole.EQUIPMENT_SLOT, 0, hive);
        Vec3 target = requested == null ? player.pick(512, 0, false).getLocation() : requested;
        String refused = ArmageddonController.request(player, target);
        if (refused != null) {
            inventory.setEquippedCurio(RelicRole.EQUIPMENT_SLOT, 0, previous);
            source.sendFailure(Component.translatable(refused));
            return 0;
        }
        if (!previous.isEmpty() && !player.getInventory().add(previous)) player.drop(previous, false);
        source.sendSuccess(() -> Component.translatable("commands.relics_addon.armageddon.started", type), false);
        return 1;
    }
}
