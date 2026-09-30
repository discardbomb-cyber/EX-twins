package dev.hurtify.relicsaddon.network;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * A console slider let go: give {@code count} drones to attack mode {@code target} (its ordinal), or make
 * {@code count} of them healers ({@link DeviceControlMenu#HEALERS}), in the hive whose console
 * {@code containerId} is open. A menu button id is a single byte, too small for a count of up to 2000.
 * The server checks the same rules as for the buttons and writes nothing it would not allow.
 */
public record HiveAllocationPayload(int containerId, int target, int count) implements CustomPacketPayload {
    public static final Type<HiveAllocationPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "hive_allocation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HiveAllocationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HiveAllocationPayload::containerId,
            ByteBufCodecs.VAR_INT, HiveAllocationPayload::target,
            ByteBufCodecs.VAR_INT, HiveAllocationPayload::count,
            HiveAllocationPayload::new);

    @Override public Type<HiveAllocationPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("4").playToServer(TYPE, STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof DeviceControlMenu menu
                    && menu.containerId == payload.containerId()) {
                menu.allocate(player, payload.target(), payload.count());
            }
        }));
    }
}
