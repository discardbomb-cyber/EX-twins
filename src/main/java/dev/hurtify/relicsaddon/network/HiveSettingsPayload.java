package dev.hurtify.relicsaddon.network;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record HiveSettingsPayload(boolean charm, int slot, String identity, int healers) implements CustomPacketPayload {
    public static final Type<HiveSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "hive_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HiveSettingsPayload> STREAM_CODEC = new StreamCodec<>() {
        public void encode(RegistryFriendlyByteBuf buffer, HiveSettingsPayload value) {
            buffer.writeBoolean(value.charm).writeVarInt(value.slot).writeUtf(value.identity, 64).writeVarInt(value.healers);
        }
        public HiveSettingsPayload decode(RegistryFriendlyByteBuf buffer) {
            return new HiveSettingsPayload(buffer.readBoolean(), buffer.readVarInt(), buffer.readUtf(64), buffer.readVarInt());
        }
    };
    @Override public Type<HiveSettingsPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            boolean saved = HiveTaskController.configure(context.player(), payload.charm, payload.slot, payload.identity, payload.healers);
            context.player().displayClientMessage(Component.translatable(saved ? "message.relics_addon.hive_tasks_saved"
                    : "message.relics_addon.hive_tasks_rejected"), true);
        }));
    }
}
