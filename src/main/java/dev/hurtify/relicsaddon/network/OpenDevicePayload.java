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

/** Asks the server to open the device console for a shield or hive in a charm or inventory slot. */
public record OpenDevicePayload(boolean charm, int slot) implements CustomPacketPayload {
    public static final Type<OpenDevicePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "open_device"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDevicePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, OpenDevicePayload::charm,
            ByteBufCodecs.VAR_INT, OpenDevicePayload::slot,
            OpenDevicePayload::new);

    @Override public Type<OpenDevicePayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToServer(TYPE, STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) DeviceControlMenu.open(player, payload.charm(), payload.slot());
        }));
    }
}
